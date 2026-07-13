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
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class CefTextureUploadMixin {
    @Inject(at = @At("HEAD"), method = "render")
    public void onRender(float partialTicks, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        if (MCEF.isInitialized()) {
            // Pump CEF message loop on the MC render thread (required by CEF thread
            // model). Goes through MCEF.pumpMessageLoop() which wraps the native
            // pump in a reentrancy barrier so browser lifecycle mutations triggered
            // from within a native callback are deferred until the pump returns
            // (prevents use-after-free crashes in N_DoMessageLoopWork).
            MCEF.pumpMessageLoop();
            // Process queued texture uploads from CEF paint callbacks
            MCEF.processRendererUploads();
        }
    }
}
