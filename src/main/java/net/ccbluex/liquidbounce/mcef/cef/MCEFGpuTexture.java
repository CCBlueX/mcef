/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2025 CCBlueX
 * Copyright (C) 2023 CinemaMod Group
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 */

package net.ccbluex.liquidbounce.mcef.cef;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.jspecify.annotations.NullMarked;

/**
 * TextureManager adapter for the renderer-owned browser texture.
 */
@NullMarked
final class MCEFGpuTexture extends AbstractTexture {
    private final MCEFRenderer renderer;

    MCEFGpuTexture(MCEFRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public GpuTexture getTexture() {
        var texture = renderer.getTexture();
        if (texture == null) {
            throw new IllegalStateException("MCEF texture is not initialized");
        }

        return texture;
    }

    @Override
    public GpuTextureView getTextureView() {
        var textureView = renderer.getTextureView();
        if (textureView == null) {
            throw new IllegalStateException("MCEF texture view is not initialized");
        }

        return textureView;
    }

    @Override
    public GpuSampler getSampler() {
        var sampler = renderer.getSampler();
        if (sampler == null) {
            throw new IllegalStateException("MCEF sampler is not initialized");
        }

        return sampler;
    }

    @Override
    public void close() {
        // The renderer owns and closes the underlying GPU resources.
    }
}
