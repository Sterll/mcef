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

import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

public class MCEFSettings {
    private static volatile Path path;

    private static Path getPath() {
        if (path == null) {
            path = Minecraft.getInstance().gameDirectory
                    .toPath()
                    .resolve("config")
                    .resolve("mcef")
                    .resolve("mcef.properties");
        }
        return path;
    }
    private static int deleteRetries = 0;

    private boolean skipDownload;
    private String downloadMirror;
    private String userAgent;
    private boolean useCache;
    private int windowlessFrameRate;
    private int browserPoolSize;

    public MCEFSettings() {
        skipDownload = false;
        downloadMirror = "https://mcef-download.cinemamod.com";
        userAgent = null;
        useCache = true;
        windowlessFrameRate = 60;
        browserPoolSize = 4;
    }

    public boolean isSkipDownload() {
        return skipDownload;
    }

    public void setSkipDownload(boolean skipDownload) {
        this.skipDownload = skipDownload;
        saveAsync();
    }

    public String getDownloadMirror() {
        return downloadMirror;
    }

    public void setDownloadMirror(String downloadMirror) {
        this.downloadMirror = downloadMirror;
        saveAsync();
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
        saveAsync();
    }

    public boolean isUsingCache() {
        return useCache;
    }

    public void setUseCache(boolean useCache) {
        this.useCache = useCache;
        saveAsync();
    }

    public int getWindowlessFrameRate() {
        return windowlessFrameRate;
    }

    public void setWindowlessFrameRate(int windowlessFrameRate) {
        this.windowlessFrameRate = Math.max(1, Math.min(60, windowlessFrameRate));
        saveAsync();
    }

    public int getBrowserPoolSize() {
        return browserPoolSize;
    }

    public void setBrowserPoolSize(int browserPoolSize) {
        this.browserPoolSize = Math.max(0, Math.min(8, browserPoolSize));
        saveAsync();
    }

    public void saveAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                save();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public void save() throws IOException {
        File file = getPath().toFile();

        file.getParentFile().mkdirs();

        if (!file.exists()) {
            file.createNewFile();
        }

        Properties properties = new Properties();
        properties.setProperty("skip-download", String.valueOf(skipDownload));
        properties.setProperty("download-mirror", String.valueOf(downloadMirror));
        if (userAgent != null) properties.setProperty("user-agent", userAgent);
        properties.setProperty("use-cache", String.valueOf(useCache));
        properties.setProperty("windowless-frame-rate", String.valueOf(windowlessFrameRate));
        properties.setProperty("browser-pool-size", String.valueOf(browserPoolSize));

        try (FileOutputStream output = new FileOutputStream(file)) {
            properties.store(output, null);
        }
    }

    public void load() throws IOException {
        File file = getPath().toFile();

        if (!file.exists()) {
            save();
        }

        Properties properties = new Properties();

        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
        }

        try {
            skipDownload = Boolean.parseBoolean(properties.getProperty("skip-download"));
            downloadMirror = properties.getProperty("download-mirror");
            String ua = properties.getProperty("user-agent");
            userAgent = (ua != null && !ua.equals("null")) ? ua : null;
            useCache = Boolean.parseBoolean(properties.getProperty("use-cache"));
            String frameRateStr = properties.getProperty("windowless-frame-rate");
            if (frameRateStr != null) windowlessFrameRate = Math.max(1, Math.min(60, Integer.parseInt(frameRateStr)));
            String poolSizeStr = properties.getProperty("browser-pool-size");
            if (poolSizeStr != null) browserPoolSize = Math.max(0, Math.min(8, Integer.parseInt(poolSizeStr)));
        } catch (Exception e) {
            // Delete and re-create the file if there was a parsing error
            if (deleteRetries++ > 20)
                return; // Stop gap
            file.delete();
            save();
        }
    }
}
