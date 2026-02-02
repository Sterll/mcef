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

package com.cinemamod.mcef.mixins;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFWorldRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class CefWorldRenderMixin {
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void onRenderWorldBrowsers(PoseStack poseStack, float partialTick, long finishNanoTime,
                                       boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                       LightTexture lightTexture, Matrix4f projectionMatrix, CallbackInfo ci) {
        if (MCEF.isInitialized()) {
            MCEFWorldRenderer.renderAll(poseStack, projectionMatrix, partialTick, camera);
        }
    }
}
