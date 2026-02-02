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

    private static final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);
    private static final CopyOnWriteArrayList<MCEFInitListener> awaitingInit = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<MCEFBrowser> activeBrowsers = new CopyOnWriteArrayList<>();

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
     * Will assert that MCEF has been initialized; throws a {@link RuntimeException} if not.
     * Creates a new Chromium web browser with some starting URL. Can set it to be transparent rendering.
     * @return the {@link MCEFBrowser} web browser instance
     */
    public static MCEFBrowser createBrowser(String url, boolean transparent) {
        assertInitialized();
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
        MCEFBrowser browser = new MCEFBrowser(client, url, transparent);
        browser.setCloseAllowed();
        browser.createImmediately();
        browser.resize(width, height);
        return browser;
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
