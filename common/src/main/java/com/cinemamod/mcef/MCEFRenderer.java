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

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER;

public class MCEFRenderer {
    private final boolean transparent;
    private final int[] textureID = new int[1];

    // PBO for async texture upload
    private int pboId = 0;
    private boolean pboSupported = true;

    // Thread-safe paint event queue
    private final ConcurrentLinkedQueue<PaintEvent> paintQueue = new ConcurrentLinkedQueue<>();

    static class PaintEvent {
        final ByteBuffer buffer;
        final int x, y, width, height;
        final boolean fullUpdate;
        final int unpackRowLength;

        PaintEvent(ByteBuffer buffer, int x, int y, int width, int height, boolean fullUpdate, int unpackRowLength) {
            this.buffer = buffer;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.fullUpdate = fullUpdate;
            this.unpackRowLength = unpackRowLength;
        }
    }

    protected MCEFRenderer(boolean transparent) {
        this.transparent = transparent;
    }

    public void initialize() {
        textureID[0] = glGenTextures();
        RenderSystem.bindTexture(textureID[0]);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        RenderSystem.bindTexture(0);

        try {
            pboId = glGenBuffers();
            pboSupported = (pboId != 0);
        } catch (Exception e) {
            pboSupported = false;
        }
    }

    public int getTextureID() {
        return textureID[0];
    }

    public boolean isTransparent() {
        return transparent;
    }

    protected void cleanup() {
        // Drain remaining paint events and free their buffers
        PaintEvent event;
        while ((event = paintQueue.poll()) != null) {
            MemoryUtil.memFree(event.buffer);
        }

        if (textureID[0] != 0) {
            glDeleteTextures(textureID[0]);
            textureID[0] = 0;
        }
        if (pboId != 0) {
            glDeleteBuffers(pboId);
            pboId = 0;
        }
    }

    /**
     * Queue a full texture update. Called from the CEF paint callback thread.
     * The buffer is copied because the CEF buffer is only valid during the callback.
     */
    protected void queueFullPaint(ByteBuffer buffer, int width, int height) {
        ByteBuffer copy = MemoryUtil.memAlloc(buffer.remaining());
        copy.put(buffer.duplicate());
        copy.flip();
        paintQueue.add(new PaintEvent(copy, 0, 0, width, height, true, width));
    }

    /**
     * Queue a sub-region texture update. Called from the CEF paint callback thread.
     * The buffer is copied because the CEF buffer is only valid during the callback.
     */
    protected void queueSubPaint(ByteBuffer buffer, int x, int y, int width, int height, int unpackRowLength) {
        ByteBuffer copy = MemoryUtil.memAlloc(buffer.remaining());
        copy.put(buffer.duplicate());
        copy.flip();
        paintQueue.add(new PaintEvent(copy, x, y, width, height, false, unpackRowLength));
    }

    /**
     * Process all queued paint events on the render thread.
     * Uses a PBO as staging buffer for async GPU upload when available.
     */
    public void processUploads() {
        if (textureID[0] == 0) {
            // Drain and free if texture not ready
            PaintEvent event;
            while ((event = paintQueue.poll()) != null) {
                MemoryUtil.memFree(event.buffer);
            }
            return;
        }

        PaintEvent event;
        while ((event = paintQueue.poll()) != null) {
            try {
                if (transparent) RenderSystem.enableBlend();
                RenderSystem.bindTexture(textureID[0]);

                if (event.fullUpdate) {
                    uploadFullTexture(event);
                } else {
                    uploadSubTexture(event);
                }
            } finally {
                MemoryUtil.memFree(event.buffer);
                RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, 0);
                GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
                GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);
            }
        }
    }

    private void uploadFullTexture(PaintEvent event) {
        int dataSize = event.width * event.height * 4;

        if (pboSupported) {
            try {
                // Use PBO as staging buffer: write data then upload from same PBO.
                // The driver can DMA the data asynchronously after glUnmapBuffer.
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pboId);
                // Orphan the old buffer to avoid sync stalls
                glBufferData(GL_PIXEL_UNPACK_BUFFER, dataSize, GL_STREAM_DRAW);
                ByteBuffer mapped = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
                if (mapped != null) {
                    mapped.put(event.buffer);
                    glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);

                    // Upload from PBO (offset 0)
                    RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, event.width);
                    RenderSystem.pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
                    RenderSystem.pixelStore(GL_UNPACK_SKIP_ROWS, 0);
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, event.width, event.height, 0,
                            GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0L);

                    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
                    return;
                }
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            } catch (Exception e) {
                pboSupported = false;
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            }
        }

        // Fallback: synchronous upload
        RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, event.width);
        RenderSystem.pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
        RenderSystem.pixelStore(GL_UNPACK_SKIP_ROWS, 0);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, event.width, event.height, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, event.buffer);
    }

    private void uploadSubTexture(PaintEvent event) {
        int dataSize = event.buffer.remaining();

        if (pboSupported) {
            try {
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pboId);
                glBufferData(GL_PIXEL_UNPACK_BUFFER, dataSize, GL_STREAM_DRAW);
                ByteBuffer mapped = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
                if (mapped != null) {
                    mapped.put(event.buffer);
                    glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);

                    // Upload sub-region from PBO
                    RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, event.unpackRowLength);
                    GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, event.x);
                    GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, event.y);
                    glTexSubImage2D(GL_TEXTURE_2D, 0, event.x, event.y, event.width, event.height,
                            GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0L);

                    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
                    return;
                }
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            } catch (Exception e) {
                pboSupported = false;
                glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            }
        }

        // Fallback: synchronous upload
        RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, event.unpackRowLength);
        GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, event.x);
        GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, event.y);
        glTexSubImage2D(GL_TEXTURE_2D, 0, event.x, event.y, event.width, event.height,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, event.buffer);
    }

    // Legacy methods kept for backward compatibility
    protected void onPaint(ByteBuffer buffer, int width, int height) {
        if (textureID[0] == 0) return;
        if (transparent) RenderSystem.enableBlend();
        RenderSystem.bindTexture(textureID[0]);
        RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, width);
        RenderSystem.pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
        RenderSystem.pixelStore(GL_UNPACK_SKIP_ROWS, 0);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
    }

    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, GL_BGRA,
                GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
    }
}
