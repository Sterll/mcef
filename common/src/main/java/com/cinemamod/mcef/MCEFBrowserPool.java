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

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A pool of reusable {@link MCEFBrowser} instances to avoid the cost of
 * spawning and destroying Chromium processes on every UI open/close.
 * <p>
 * Browsers returned to the pool are navigated to {@code about:blank} and
 * kept alive for future reuse. The pool is bounded by {@code maxPoolSize};
 * any excess browsers are closed normally.
 */
public class MCEFBrowserPool {
    private final ConcurrentLinkedQueue<MCEFBrowser> availableBrowsers = new ConcurrentLinkedQueue<>();
    private volatile int maxPoolSize;

    public MCEFBrowserPool(int maxPoolSize) {
        this.maxPoolSize = Math.max(0, Math.min(8, maxPoolSize));
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = Math.max(0, Math.min(8, maxPoolSize));
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    /**
     * Acquire a browser from the pool or create a new one if the pool is empty.
     *
     * @param url         the URL to navigate to
     * @param transparent whether the browser uses transparent rendering
     * @return a ready-to-use {@link MCEFBrowser}
     */
    public MCEFBrowser acquire(String url, boolean transparent) {
        if (maxPoolSize > 0) {
            MCEFBrowser browser = pollMatchingBrowser(transparent);
            if (browser != null) {
                // Defer navigation out of an in-flight message-loop pump.
                MCEF.runSafely(() -> browser.loadURL(url));
                return browser;
            }
        }
        return MCEF.createBrowser(url, transparent);
    }

    /**
     * Acquire a browser from the pool or create a new one, with explicit dimensions.
     *
     * @param url         the URL to navigate to
     * @param transparent whether the browser uses transparent rendering
     * @param width       desired width in pixels
     * @param height      desired height in pixels
     * @return a ready-to-use {@link MCEFBrowser}
     */
    public MCEFBrowser acquire(String url, boolean transparent, int width, int height) {
        if (maxPoolSize > 0) {
            MCEFBrowser browser = pollMatchingBrowser(transparent);
            if (browser != null) {
                // Defer navigation + resize out of an in-flight message-loop pump.
                MCEF.runSafely(() -> {
                    browser.loadURL(url);
                    browser.resize(width, height);
                });
                return browser;
            }
        }
        return MCEF.createBrowser(url, transparent, width, height);
    }

    /**
     * Return a browser to the pool for future reuse. If the pool is full,
     * the browser is closed instead.
     *
     * @param browser the browser to release
     */
    public void release(MCEFBrowser browser) {
        if (browser == null) return;

        // Reset browser state
        browser.cancelDrag();
        browser.setZoomLevel(0);
        browser.setFocus(false);

        if (maxPoolSize > 0 && availableBrowsers.size() < maxPoolSize) {
            // Defer navigation out of an in-flight message-loop pump.
            MCEF.runSafely(() -> browser.loadURL("about:blank"));
            availableBrowsers.add(browser);
        } else {
            // Defer native destruction out of an in-flight message-loop pump so
            // no queued task references the freed native peer (UAF guard).
            MCEF.runSafely(browser::close);
        }
    }

    /**
     * Pre-create browsers in the pool to warm up the Chromium subprocess.
     * The first browser creation starts the subprocess (slow); subsequent
     * creations reuse it (fast). Call this right after CEF initialization.
     */
    public void warmUp(MCEFClient client, int count) {
        if (maxPoolSize <= 0 || count <= 0) return;
        int toCreate = Math.min(count, maxPoolSize);
        MCEF.getLogger().info("Pre-warming " + toCreate + " browser(s) in pool...");
        for (int i = 0; i < toCreate; i++) {
            MCEFBrowser browser = new MCEFBrowser(client, "about:blank", true);
            browser.setCloseAllowed();
            browser.createImmediately();
            availableBrowsers.add(browser);
        }
        MCEF.getLogger().info("Browser pool warm-up complete");
    }

    /**
     * Shut down the pool. Just clear the queue — CefUtil.shutdown() handles
     * native resource cleanup. Calling browser.close() here would trigger
     * GL/GLFW calls from the shutdown thread, which crashes.
     */
    public void shutdown() {
        availableBrowsers.clear();
    }

    MCEFBrowser pollMatchingBrowser(boolean transparent) {
        // Iterate to find a browser with matching transparency
        int size = availableBrowsers.size();
        for (int i = 0; i < size; i++) {
            MCEFBrowser browser = availableBrowsers.poll();
            if (browser == null) break;
            if (browser.getRenderer().isTransparent() == transparent) {
                return browser;
            }
            // Not matching, put it back
            availableBrowsers.add(browser);
        }
        return null;
    }
}
