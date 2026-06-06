/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2025 CCBlueX
 * Copyright (C) 2023 CinemaMod Group
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 * USA
 */

package net.ccbluex.liquidbounce.mcef.cef;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.ccbluex.liquidbounce.mcef.MCEF;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.resources.Identifier;
import org.cef.handler.CefAcceleratedPaintInfo;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.awt.*;
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

import static net.ccbluex.liquidbounce.mcef.MCEF.mc;

@NullMarked
public class MCEFRenderer implements Closeable {
    private final boolean transparent;
    // CPU paint texture.
    private @Nullable GpuTexture texture = null;
    private @Nullable GpuTextureView textureView = null;
    private @Nullable GpuSampler sampler = null;
    private @Nullable TextureSetup textureSetup = null;
    // Stable accelerated display texture exposed to Minecraft.
    private @Nullable GpuTexture acceleratedTexture = null;
    private int textureWidth = 0;
    private int textureHeight = 0;

    // ResourceLocation for this renderer's texture
    private final Identifier identifier;
    private @Nullable MCEFDirectTexture directAcceleratedTexture;
    private boolean textureRegistered = false;
    private final List<AcceleratedPaintBackend> acceleratedPaintBackends = List.of(
            new WindowsAcceleratedPaintBackend(),
            new LinuxAcceleratedPaintBackend(),
            new MacAcceleratedPaintBackend()
    );

    private boolean isBGRA = false;
    private boolean unpainted = true;
    private boolean isAccelerated = false;

    protected MCEFRenderer(boolean transparent) {
        this.transparent = transparent;
        // Generate a unique ResourceLocation for this renderer
        String uniqueId = UUID.randomUUID().toString().toLowerCase().replace("-", "");
        this.identifier = Identifier.fromNamespaceAndPath("mcef", "browser_" + uniqueId);
    }

    /**
     * Initializes the renderer by generating a texture ID and setting up the texture parameters.
     */
    public void initialize() {
        mc.getTextureManager().register(identifier, new MCEFGpuTexture(this));
        textureRegistered = true;
    }

    /**
     * Returns the texture for the renderer.
     * If accelerated rendering is enabled, it returns the shared texture.
     * @return GpuTexture
     */
    public @Nullable GpuTexture getTexture() {
        if (isAccelerated) {
            return acceleratedTexture;
        } else {
            return texture;
        }
    }

    /**
     * Returns the texture view for the renderer.
     * If accelerated rendering is enabled, it returns the shared texture.
     * @return GpuTextureView
     */
    public @Nullable GpuTextureView getTextureView() {
        if (!isAccelerated) {
            return textureView;
        }

        return directAcceleratedTexture == null ? null : directAcceleratedTexture.getTextureView();
    }

    /**
     * Returns the sampler to be used for the renderer textures.
     * @return GpuSampler
     */
    public @Nullable GpuSampler getSampler() {
        if (!isAccelerated) {
            return sampler;
        }

        return directAcceleratedTexture == null ? null : directAcceleratedTexture.getSampler();
    }

    /**
     * Returns the texture setup for the renderer.
     * If accelerated rendering is enabled, it returns the shared texture setup.
     * @return TextureSetup
     */
    public @Nullable TextureSetup getTextureSetup() {
        if (!isAccelerated) {
            return textureSetup;
        }

        return directAcceleratedTexture == null ? null : directAcceleratedTexture.getTextureSetup();
    }

    /**
     * Gets the Identifier that can be used with GuiGraphics and other Minecraft rendering methods.
     * This Identifier is registered with the TextureManager and points to the browser's texture.
     */
    public Identifier getIdentifier() {
        return identifier;
    }

    /**
     * Check if the texture is ready for rendering with GuiGraphics
     */
    public boolean isTextureReady() {
        return isAccelerated ? acceleratedTexture != null : texture != null && textureSetup != null;
    }

    /**
     * Checks if the texture is unpainted. A texture is considered unpainted if it has not been painted yet,
     * which means no paint calls have been made since the last initialization or cleanup.
     */
    public boolean isUnpainted() {
        if (isAccelerated && acceleratedTexture == null) {
            return false;
        }

        if (texture == null) {
            return false;
        }

        return unpainted;
    }

    public int getTextureWidth() {
        return textureWidth;
    }

    public int getTextureHeight() {
        return textureHeight;
    }

    /**
     * Determines if the renderer is transparent.
     */
    public boolean isTransparent() {
        return transparent;
    }

    /**
     * Checks if the renderer is using accelerated rendering. This is true when CEF calls
     * [onAcceleratedPaint] with a valid {@link CefAcceleratedPaintInfo} object, instead of
     * [onPaint] with a ByteBuffer.
     * @return true if the renderer is using accelerated rendering, false otherwise.
     */
    public boolean isAccelerated() {
        return isAccelerated;
    }

    /**
     * Checks if consumers need to swap the sampled red and blue channels.
     * <p>
     * CEF software paint supplies BGRA bytes. The CPU paint path uploads those bytes through Blaze3D's
     * RGBA texture API without a CPU-side channel conversion, so consumers must use a BGRA-aware shader.
     * Accelerated paint may also require the same shader depending on the imported platform texture.
     *
     * @return true if the sampled texture needs red/blue channel swapping, false otherwise
     */
    public boolean isBGRA() {
        return isBGRA;
    }

    /**
     * Handles accelerated paint events from CEF.
     * <p>
     * @param info   The CefAcceleratedPaintInfo containing the shared texture handle and other information.
     * @param width  The width of the texture.
     * @param height The height of the texture.
     */
    protected void onAcceleratedPaint(CefAcceleratedPaintInfo info, int width, int height) {
        RenderSystem.assertOnRenderThread();

        if (transparent) {
            GlStateManager._enableBlend(0);
        }

        for (var backend : acceleratedPaintBackends) {
            if (backend.accepts(info)) {
                copyAcceleratedFrame(backend, info, width, height);
                return;
            }
        }

        MCEF.INSTANCE.LOGGER.warn("Unsupported CefAcceleratedPaintInfo type: {}", info.getClass().getName());
    }

    /**
     * Paints the texture with the provided ByteBuffer data.
     * This method is called when CEF provides a ByteBuffer for painting.
     *
     * @param buffer The ByteBuffer containing the pixel data to paint.
     * @param width  The width of the texture.
     * @param height The height of the texture.
     */
    protected void onPaint(ByteBuffer buffer, int width, int height) {
        RenderSystem.assertOnRenderThread();

        var texture = ensureTexture(width, height);

        RenderSystem.getDevice().createCommandEncoder().writeToTexture(
                texture,
                fixedByteBuffer(buffer, (long) width * height * GpuFormat.RGBA8_UNORM.blockSize()),
                0,
                0,
                0,
                0,
                width,
                height
        );

        isAccelerated = false;
        isBGRA = true;
        unpainted = false;
    }

    /**
     * Paints a sub-region of the texture with the provided ByteBuffer data.
     * This method is called when CEF provides a ByteBuffer for painting a specific area.
     *
     * @param buffer       The ByteBuffer containing the source pixel data.
     * @param sourceWidth  The width of the source pixel buffer.
     * @param sourceHeight The height of the source pixel buffer.
     * @param dirtyRects   The source rectangles to copy.
     * @param destOffsetX  The destination x offset added to every dirty rect.
     * @param destOffsetY  The destination y offset added to every dirty rect.
     */
    protected void onPaint(
            ByteBuffer buffer,
            int sourceWidth,
            int sourceHeight,
            Rectangle[] dirtyRects,
            int destOffsetX,
            int destOffsetY
    ) {
        RenderSystem.assertOnRenderThread();

        if (texture == null || dirtyRects.length == 0) {
            return;
        }

        var commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        var source = commandEncoder.transientMemory()
                .uploadStaging(fixedByteBuffer(buffer, (long) sourceWidth * sourceHeight * GpuFormat.RGBA8_UNORM.blockSize()),
                        1L, GpuBuffer.USAGE_COPY_SRC);

        for (var dirtyRect : dirtyRects) {
            commandEncoder.copyBufferToTexture(
                    source,
                    dirtyRect.x,
                    dirtyRect.y,
                    sourceWidth,
                    sourceHeight,
                    texture,
                    destOffsetX + dirtyRect.x,
                    destOffsetY + dirtyRect.y,
                    dirtyRect.width,
                    dirtyRect.height,
                    0,
                    0
            );
        }

        isAccelerated = false;
        isBGRA = true;
        unpainted = false;
    }

    protected void onPaint(
            ByteBuffer buffer,
            int sourceWidth,
            int sourceHeight,
            int sourceX,
            int sourceY,
            int destX,
            int destY,
            int width,
            int height
    ) {
        onPaint(buffer, sourceWidth, sourceHeight, new Rectangle[]{
                new Rectangle(sourceX, sourceY, width, height)
        }, destX - sourceX, destY - sourceY);
    }

    /**
     * Clears the texture by binding it and filling it with transparent pixels.
     */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();

        if (this.textureView != null) {
            this.textureView.close();
            this.textureView = null;
        }

        if (this.texture != null) {
            this.texture.close();
            this.texture = null;
        }

        this.textureSetup = null;

        for (var backend : acceleratedPaintBackends) {
            backend.close();
        }

        if (this.directAcceleratedTexture != null) {
            this.directAcceleratedTexture.close();
            this.directAcceleratedTexture = null;
        }

        if (this.acceleratedTexture != null) {
            this.acceleratedTexture.close();
            this.acceleratedTexture = null;
        }

        // Unregister from TextureManager
        if (textureRegistered) {
            mc.getTextureManager().release(identifier);
            textureRegistered = false;
        }

        isAccelerated = false;
    }

    private void copyAcceleratedFrame(
            AcceleratedPaintBackend backend,
            CefAcceleratedPaintInfo info,
            int width,
            int height
    ) {
        var frame = backend.importFrame(info, width, height);
        if (frame == null) {
            return;
        }

        try (frame) {
            copyAcceleratedFrame(frame, width, height);
        }
    }

    private void copyAcceleratedFrame(AcceleratedPaintFrame frame, int width, int height) {
        var targetTexture = ensureAcceleratedTargetTexture(width, height);

        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                frame.texture(),
                targetTexture,
                0,
                0,
                0,
                0,
                0,
                width,
                height
        );

        this.textureWidth = width;
        this.textureHeight = height;

        isAccelerated = true;
        unpainted = false;
        isBGRA = frame.bgra();
    }

    private GpuTexture ensureAcceleratedTargetTexture(int width, int height) {
        if (acceleratedTexture != null && textureWidth == width && textureHeight == height) {
            return acceleratedTexture;
        }

        if (directAcceleratedTexture != null) {
            directAcceleratedTexture.close();
            directAcceleratedTexture = null;
        }

        if (acceleratedTexture != null) {
            acceleratedTexture.close();
            acceleratedTexture = null;
        }

        var targetTexture = newRGBATexture("MCEF Accelerated Browser Texture " + width + "x" + height, width, height);

        MCEFDirectTexture directTexture = null;
        if (targetTexture instanceof GlTexture glTexture) {
            directTexture = new MCEFDirectTexture();
            directTexture.setDirectTextureId(glTexture.glId(), width, height);
        }

        this.directAcceleratedTexture = directTexture;
        this.acceleratedTexture = targetTexture;

        return this.acceleratedTexture;
    }

    private GpuTexture ensureTexture(int width, int height) {
        if (texture != null && textureWidth == width && textureHeight == height) {
            return texture;
        }

        if (textureView != null) {
            textureView.close();
            textureView = null;
        }

        if (texture != null) {
            texture.close();
            texture = null;
        }

        texture = newRGBATexture("MCEF Browser Texture " + width + "x" + height, width, height);
        textureView = RenderSystem.getDevice().createTextureView(texture);
        sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, false);
        textureSetup = TextureSetup.singleTexture(textureView, sampler);

        textureWidth = width;
        textureHeight = height;

        return texture;
    }

    private static ByteBuffer fixedByteBuffer(ByteBuffer buffer, long length) {
        if (length > buffer.capacity()) {
            throw new IllegalArgumentException("Buffer capacity " + buffer.capacity() + " is smaller than required " + length);
        }

        var duplicate = buffer.duplicate();
        duplicate.position(0);
        duplicate.limit(Math.toIntExact(length));
        return duplicate;
    }

    private static GpuTexture newRGBATexture(
        String label,
        int width,
        int height
    ) {
        return RenderSystem.getDevice().createTexture(label,
            GpuTexture.USAGE_TEXTURE_BINDING
                | GpuTexture.USAGE_RENDER_ATTACHMENT
                | GpuTexture.USAGE_COPY_SRC
                | GpuTexture.USAGE_COPY_DST,
            GpuFormat.RGBA8_UNORM,
            width,
            height,
            1,
            1
        );
    }

}
