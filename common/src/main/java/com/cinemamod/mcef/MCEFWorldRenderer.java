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

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

import java.util.List;

public class MCEFWorldRenderer {

    public static void renderAll(PoseStack poseStack, Matrix4f projectionMatrix, float partialTick, Camera camera) {
        List<MCEFBrowserWorld> browsers = MCEF.getWorldBrowsers();
        if (browsers.isEmpty()) return;

        for (MCEFBrowserWorld worldBrowser : browsers) {
            int textureID = worldBrowser.getBrowser().getRenderer().getTextureID();
            if (textureID == 0) continue;
            renderBrowser(worldBrowser, poseStack, camera);
        }
    }

    private static void renderBrowser(MCEFBrowserWorld worldBrowser, PoseStack poseStack, Camera camera) {
        poseStack.pushPose();

        // MC renders relative to the camera position
        double camX = camera.getPosition().x;
        double camY = camera.getPosition().y;
        double camZ = camera.getPosition().z;

        poseStack.translate(
                worldBrowser.getX() - camX,
                worldBrowser.getY() - camY,
                worldBrowser.getZ() - camZ
        );

        // Apply rotations
        poseStack.mulPose(Axis.YP.rotationDegrees(worldBrowser.getYaw()));
        poseStack.mulPose(Axis.XP.rotationDegrees(worldBrowser.getPitch()));
        poseStack.mulPose(Axis.ZP.rotationDegrees(worldBrowser.getRoll()));

        float halfW = worldBrowser.getWidth() / 2.0f;
        float halfH = worldBrowser.getHeight() / 2.0f;

        // Setup render state
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, worldBrowser.getBrowser().getRenderer().getTextureID());

        Matrix4f matrix = poseStack.last().pose();

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        // Front face (UV: 0,0 top-left to 1,1 bottom-right)
        buffer.vertex(matrix, -halfW, -halfH, 0).uv(0.0f, 1.0f).color(255, 255, 255, 255).endVertex();
        buffer.vertex(matrix,  halfW, -halfH, 0).uv(1.0f, 1.0f).color(255, 255, 255, 255).endVertex();
        buffer.vertex(matrix,  halfW,  halfH, 0).uv(1.0f, 0.0f).color(255, 255, 255, 255).endVertex();
        buffer.vertex(matrix, -halfW,  halfH, 0).uv(0.0f, 0.0f).color(255, 255, 255, 255).endVertex();

        tesselator.end();

        // Restore render state
        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        poseStack.popPose();
    }
}
