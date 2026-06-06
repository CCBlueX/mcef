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

package net.ccbluex.liquidbounce.mcef.cef;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.FrameBufferCache;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.jspecify.annotations.NullMarked;

/**
 * A more efficient texture implementation that directly wraps an existing OpenGL texture ID.
 * This bypasses the normal texture creation pipeline and allows us to use an existing texture
 * directly with Minecraft's rendering system.
 */
@NullMarked
public class MCEFDirectTexture extends AbstractTexture {
    public MCEFDirectTexture() {
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, false);
    }

    /**
     * Directly set the texture to an existing OpenGL texture ID.
     * This is more efficient than creating a new texture and copying data.
     * 
     * @param textureId The OpenGL texture ID to wrap
     * @param width The width of the texture
     * @param height The height of the texture
     */
    public void setDirectTextureId(int textureId, int width, int height) {
        this.setDirectTextureId(textureId, width, height, false);
    }

    public void setOwnedDirectTextureId(int textureId, int width, int height) {
        this.setDirectTextureId(textureId, width, height, true);
    }

    private void setDirectTextureId(int textureId, int width, int height, boolean ownsTexture) {
        this.close();

        if (textureId <= 0) {
            return;
        }

        this.texture = new DirectGlTexture(textureId, width, height, ownsTexture);
    }
    
    @Override
    public void close() {
        if (this.textureView != null) {
            this.textureView.close();
            this.textureView = null;
        }

        if (this.texture != null) {
            this.texture.close();
        }
        this.texture = null;
    }

    @Override
    public GlTexture getTexture() {
        return (GlTexture) super.getTexture();
    }

    @Override
    public GlTextureView getTextureView() {
        if (this.textureView == null) {
            this.textureView = RenderSystem.getDevice().createTextureView(this.getTexture());
        }

        return (GlTextureView) this.textureView;
    }

    public TextureSetup getTextureSetup() {
        return this.texture == null ? TextureSetup.noTexture() : TextureSetup.singleTexture(this.getTextureView(), this.sampler);
    }

    /**
     * Custom GlTexture implementation that wraps an existing OpenGL texture ID
     * without managing its lifecycle.
     */
    static class DirectGlTexture extends GlTexture {
        private static final FrameBufferCache FRAME_BUFFER_CACHE = new FrameBufferCache();

        private final boolean ownsTexture;

        protected DirectGlTexture(int textureId, int width, int height, boolean ownsTexture) {
            super(
                GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                "MCEF Direct Texture " + textureId + " (" + width + "x" + height + ")",
                GpuFormat.RGBA8_UNORM, width, height, 1, 1, textureId, FRAME_BUFFER_CACHE
            );
            this.ownsTexture = ownsTexture;
        }
        
        @Override
        public void close() {
            if (this.closed) {
                return;
            }

            if (this.ownsTexture) {
                super.close();
            } else {
                this.closed = true;
            }
        }
    }
}
