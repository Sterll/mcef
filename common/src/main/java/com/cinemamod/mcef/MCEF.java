/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package com.cinemamod.mcef;

import com.cinemamod.mcef.listeners.MCEFInitListener;
import net.minecraft.client.Minecraft;
import org.cef.browser.CefMessageRouter;
import org.cef.misc.CefCursorType;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An API to create Chromium web browsers in Minecraft. Uses
 * a modified version of java-cef (Java Chromium Embedded Framework).
 */
public final class MCEF {
    public static final Logger LOGGER = LoggerFactory.getLogger("MCEF");
    private static volatile MCEFSettings settings;
    private static volatile MCEFApp app;
    private static volatile MCEFClient client;
    private static volatile MCEFBrowserPool browserPool;
    private static volatile CefMessageRouter defaultMessageRouter;

    private static final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
    private static final CopyOnWriteArrayList<MCEFInitListener> awaitingInit = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<MCEFBrowser> activeBrowsers = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<MCEFBrowserWorld> worldBrowsers = new CopyOnWriteArrayList<>();

    // --- Message-loop pump reentrancy barrier --------------------------------
    // CEF runs a single-threaded message loop that is pumped every render frame
    // (see CefTextureUploadMixin -> pumpMessageLoop()). Native callbacks
    // (onPaint, cefQuery, load events) are dispatched DURING that pump. Mutating
    // browser lifecycle (loadURL/resize/close) or CEF handler lists while a pump
    // is in flight can free a native peer whose queued task is still pending,
    // producing a use-after-free that manifests as EXCEPTION_ACCESS_VIOLATION
    // inside N_DoMessageLoopWork. Such operations are serialized: if issued
    // while a pump is running they are deferred and executed (same thread) as
    // soon as the pump returns.
    private static volatile boolean pumping = false;
    private static final ConcurrentLinkedQueue<Runnable> deferredOps = new ConcurrentLinkedQueue<>();

    public static void scheduleForInit(MCEFInitListener task) {
        awaitingInit.add(task);
    }

    public static Logger getLogger() {
        return LOGGER;
    }

    /**
     * Get access to various settings for MCEF.
     * @return Returns the existing {@link MCEFSettings} or creates a new {@link MCEFSettings} and loads from disk (blocking)
     */
    public static MCEFSettings getSettings() {
        if (settings == null) {
            settings = new MCEFSettings();
            try {
                settings.load();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return settings;
    }

    /**
     * This gets called by {@link com.cinemamod.mcef.mixins.CefInitMixin}.
     * This should not be called by anything else.
     */
    public static boolean initialize() {
        MCEF.getLogger().info("Initializing CEF on " + MCEFPlatform.getPlatform().getNormalizedName() + "...");
        if (CefUtil.init()) {
            app = new MCEFApp(CefUtil.getCefApp());
            client = new MCEFClient(CefUtil.getCefClient());
            // Register a default CefMessageRouter config on the CefClient BEFORE
            // creating any browsers. This ensures the router config is in
            // BrowserProcessHandler's static set, so when pooled browsers are created,
            // the config is passed via extra_info to the render process, and
            // window.cefQuery JS bindings are injected into V8 contexts from the start.
            // Without this, pooled browsers would never have cefQuery available because
            // they were created before any mod registers its own router.
            defaultMessageRouter = CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig());
            client.getHandle().addMessageRouter(defaultMessageRouter);

            browserPool = new MCEFBrowserPool(getSettings().getBrowserPoolSize());

            // Pre-warm the browser pool asynchronously to start the Chromium subprocess early
            Thread warmUpThread = new Thread(() -> {
                browserPool.warmUp(client, getSettings().getBrowserPoolSize());
            }, "MCEF-WarmUp");
            warmUpThread.setDaemon(true);
            warmUpThread.start();

            awaitingInit.forEach(t -> t.onInit(true));
            awaitingInit.clear();
            MCEF.getLogger().info("Chromium Embedded Framework initialized");

            app.getHandle().registerSchemeHandlerFactory(
                    "mod", "",
                    (browser, frame, url, request) -> new ModScheme(request.getURL())
            );

            // Handle shutdown events, macOS is special
            // These are important; the jcef process will linger around if not done
            MCEFPlatform platform = MCEFPlatform.getPlatform();
            if (platform.isLinux() || platform.isWindows()) {
                Runtime.getRuntime().addShutdownHook(new Thread(MCEF::shutdown, "MCEF-Shutdown"));
            } else if (platform.isMacOS()) {
                CefUtil.getCefApp().macOSTerminationRequestRunnable = () -> {
                    shutdown();
                    Minecraft.getInstance().stop();
                };
            }

            return true;
        }
        awaitingInit.forEach(t -> t.onInit(false));
        awaitingInit.clear();
        MCEF.getLogger().error("Could not initialize Chromium Embedded Framework");
        shutdown();
        return false;
    }

    /**
     * Will assert that MCEF has been initialized; throws a {@link RuntimeException} if not.
     * @return the {@link MCEFApp} instance
     */
    public static MCEFApp getApp() {
        assertInitialized();
        return app;
    }

    /**
     * Will assert that MCEF has been initialized; throws a {@link RuntimeException} if not.
     * @return the {@link MCEFClient} instance
     */
    public static MCEFClient getClient() {
        assertInitialized();
        return client;
    }

    /**
     * Get the default {@link CefMessageRouter} that is registered on the CefClient
     * at initialization. Mods should add/remove their handlers on this router
     * rather than creating separate routers, to avoid conflicts with pooled browsers.
     * @return the default CefMessageRouter
     */
    public static CefMessageRouter getMessageRouter() {
        assertInitialized();
        return defaultMessageRouter;
    }

    /**
     * Will assert that MCEF has been initialized; throws a {@link RuntimeException} if not.
     * Creates a new Chromium web browser with some starting URL. Can set it to be transparent rendering.
     * @return the {@link MCEFBrowser} web browser instance
     */
    public static MCEFBrowser createBrowser(String url, boolean transparent) {
        assertInitialized();
        if (browserPool != null) {
            MCEFBrowser pooled = browserPool.pollMatchingBrowser(transparent);
            if (pooled != null) {
                pooled.loadURL(url);
                return pooled;
            }
        }
        MCEFBrowser browser = new MCEFBrowser(client, url, transparent);
        browser.setCloseAllowed();
        browser.createImmediately();
        return browser;
    }

    /**
     * Will assert that MCEF has been initialized; throws a {@link RuntimeException} if not.
     * Creates a new Chromium web browser with some starting URL, width, and height.
     * Can set it to be transparent rendering.
     * @return the {@link MCEFBrowser} web browser instance
     */
    public static MCEFBrowser createBrowser(String url, boolean transparent, int width, int height) {
        assertInitialized();
        if (browserPool != null) {
            MCEFBrowser pooled = browserPool.pollMatchingBrowser(transparent);
            if (pooled != null) {
                pooled.loadURL(url);
                pooled.resize(width, height);
                return pooled;
            }
        }
        MCEFBrowser browser = new MCEFBrowser(client, url, transparent);
        browser.setCloseAllowed();
        browser.createImmediately();
        browser.resize(width, height);
        return browser;
    }

    /**
     * Acquire a reusable browser from the pool, or create a new one if the pool is empty.
     * Use {@link #releaseBrowser(MCEFBrowser)} to return it to the pool instead of closing it.
     * @param url the starting URL
     * @param transparent whether the browser uses transparent rendering
     * @return the {@link MCEFBrowser} web browser instance
     */
    public static MCEFBrowser acquireBrowser(String url, boolean transparent) {
        assertInitialized();
        return browserPool.acquire(url, transparent);
    }

    /**
     * Acquire a reusable browser from the pool with explicit dimensions.
     * Use {@link #releaseBrowser(MCEFBrowser)} to return it to the pool instead of closing it.
     * @param url the starting URL
     * @param transparent whether the browser uses transparent rendering
     * @param width desired width in pixels
     * @param height desired height in pixels
     * @return the {@link MCEFBrowser} web browser instance
     */
    public static MCEFBrowser acquireBrowser(String url, boolean transparent, int width, int height) {
        assertInitialized();
        return browserPool.acquire(url, transparent, width, height);
    }

    /**
     * Return a browser to the pool for future reuse. The browser will be navigated
     * to {@code about:blank} and its state reset. If the pool is full, the browser
     * is closed normally instead.
     * @param browser the browser to release back to the pool
     */
    public static void releaseBrowser(MCEFBrowser browser) {
        assertInitialized();
        runSafely(() -> browserPool.release(browser));
    }

    /**
     * @return {@code true} while a CEF message-loop pump is in progress. Native
     * callbacks (onPaint / cefQuery / load events) run during this window.
     */
    public static boolean isPumping() {
        return pumping;
    }

    /**
     * Run an operation that mutates CEF/browser native state safely with respect
     * to the message-loop pump. If a pump is currently in flight the operation is
     * deferred and executed as soon as the pump finishes (on the same, render
     * thread); otherwise it runs immediately. Prevents use-after-free crashes in
     * {@code N_DoMessageLoopWork} caused by tearing down or re-navigating a
     * browser (or mutating a CEF handler list) mid-pump.
     */
    public static void runSafely(Runnable op) {
        if (op == null) return;
        if (pumping) {
            deferredOps.add(op);
        } else {
            op.run();
        }
    }

    /**
     * Pump the CEF message loop with a reentrancy barrier. Called every render
     * frame by {@code CefTextureUploadMixin}. Any lifecycle mutation issued from
     * within a native callback during the pump is deferred (see
     * {@link #runSafely(Runnable)}) and drained here once the pump returns, so no
     * native peer is freed while a queued task still references it.
     */
    public static void pumpMessageLoop() {
        if (!isInitialized()) return;
        pumping = true;
        try {
            app.getHandle().pumpMessageLoop();
        } finally {
            pumping = false;
        }
        // Drain deferred lifecycle ops now that no pump is in flight.
        Runnable op;
        while ((op = deferredOps.poll()) != null) {
            try {
                op.run();
            } catch (Throwable t) {
                LOGGER.error("Deferred MCEF op failed", t);
            }
        }
    }

    /**
     * Check if MCEF is initialized.
     * @return true if MCEF is initialized correctly, false if not
     */
    public static boolean isInitialized() {
        return client != null;
    }

    /**
     * Request a shutdown of MCEF/CEF. Nothing will happen if not initialized.
     */
    public static void shutdown() {
        if (isInitialized() && shutdownInProgress.compareAndSet(false, true)) {
            try {
                if (browserPool != null) {
                    browserPool.shutdown();
                    browserPool = null;
                }
                if (defaultMessageRouter != null) {
                    if (client != null) {
                        try { client.getHandle().removeMessageRouter(defaultMessageRouter); } catch (Exception ignored) {}
                    }
                    defaultMessageRouter.dispose();
                    defaultMessageRouter = null;
                }
                CefUtil.shutdown();
            } finally {
                client = null;
                app = null;
            }
        }
    }

    static void registerBrowser(MCEFBrowser browser) {
        activeBrowsers.add(browser);
    }

    static void unregisterBrowser(MCEFBrowser browser) {
        activeBrowsers.remove(browser);
    }

    public static MCEFBrowserWorld createWorldBrowser(String url, boolean transparent, double x, double y, double z, float width, float height) {
        MCEFBrowser browser = createBrowser(url, transparent);
        MCEFBrowserWorld worldBrowser = new MCEFBrowserWorld(browser, x, y, z, width, height);
        worldBrowsers.add(worldBrowser);
        return worldBrowser;
    }

    public static void registerWorldBrowser(MCEFBrowserWorld browser) {
        worldBrowsers.add(browser);
    }

    public static void unregisterWorldBrowser(MCEFBrowserWorld browser) {
        worldBrowsers.remove(browser);
    }

    public static List<MCEFBrowserWorld> getWorldBrowsers() {
        return Collections.unmodifiableList(worldBrowsers);
    }

    public static void processRendererUploads() {
        for (MCEFBrowser browser : activeBrowsers) {
            browser.getRenderer().processUploads();
        }
    }

    /**
     * Check if MCEF has been initialized, throws a {@link RuntimeException} if not.
     */
    private static void assertInitialized() {
        if (!isInitialized())
            throw new RuntimeException("Chromium Embedded Framework was never initialized.");
    }

    /**
     * Get the git commit hash of the java-cef code (either from MANIFEST.MF or from the git repo on-disk if in a
     * development environment). Used for downloading the java-cef release.
     * @return The git commit hash of java-cef
     * @throws IOException
     */
    public static String getJavaCefCommit() throws IOException {
        // First check system property
        if (System.getProperty("mcef.java.cef.commit") != null) {
            return System.getProperty("mcef.java.cef.commit");
        }

        // Try to get from resources (if loading from a jar)
        Enumeration<URL> resources = MCEF.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
        Map<String, String> commits = new HashMap<>(1);
        resources.asIterator().forEachRemaining(resource -> {
            Properties properties = new Properties();
            try {
                properties.load(resource.openStream());
                if (properties.containsKey("java-cef-commit")) {
                    commits.put(resource.getFile(), properties.getProperty("java-cef-commit"));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        if (!commits.isEmpty()) {
            return commits.get(commits.keySet().stream().toList().get(0));
        }

        // Try to get from the git submodule (if loading from development environment)
        ProcessBuilder processBuilder = new ProcessBuilder("git", "submodule", "status", "common/java-cef");
        processBuilder.directory(new File("../../"));
        Process process = processBuilder.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            String[] parts = line.trim().split(" ");
            return parts[0].replace("+", "");
        }

        return null;
    }

    /**
     * Helper method to get a GLFW cursor handle for the given {@link CefCursorType} cursor type
     */
    static long getGLFWCursorHandle(CefCursorType cursorType) {
        return CEF_TO_GLFW_CURSORS.computeIfAbsent(cursorType, type -> GLFW.glfwCreateStandardCursor(type.glfwId));
    }
    private static final ConcurrentHashMap<CefCursorType, Long> CEF_TO_GLFW_CURSORS = new ConcurrentHashMap<>();
}
