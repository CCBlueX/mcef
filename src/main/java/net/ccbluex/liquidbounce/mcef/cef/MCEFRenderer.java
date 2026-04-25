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

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.ccbluex.liquidbounce.mcef.MCEF;
import net.ccbluex.liquidbounce.mcef.utils.EglUtils;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.resources.Identifier;
import org.cef.handler.CefAcceleratedPaintInfo;
import org.cef.handler.CefAcceleratedPaintInfoLinux;
import org.cef.handler.CefAcceleratedPaintInfoWin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.egl.EGL14;
import org.lwjgl.egl.EXTImageDMABufImport;
import org.lwjgl.egl.KHRImageBase;
import org.lwjgl.opengl.EXTEGLImageStorage;
import org.lwjgl.system.MemoryStack;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.UUID;

import static net.ccbluex.liquidbounce.mcef.MCEF.mc;
import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D11_IMAGE_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV;

@NullMarked
public class MCEFRenderer implements Closeable {
    private static final int WINDOWS_SHARED_TEXTURE_CACHE_LIMIT = 4;
    private static final long WINDOWS_SHARED_TEXTURE_IMPORT_SIZE = 0L;

    private final boolean transparent;
    private @Nullable GpuTexture texture = null;
    private @Nullable GpuTexture sharedTexture = null;
    private int textureWidth = 0;
    private int textureHeight = 0;

    // ResourceLocation for this renderer's texture
    private final Identifier identifier;
    private @Nullable MCEFDirectTexture directTexture;
    private @Nullable MCEFDirectTexture directSharedTexture;
    private boolean textureRegistered = false;
    private final Long2ObjectLinkedOpenHashMap<WindowsSharedTextureEntry> windowsSharedTextureCache =
            new Long2ObjectLinkedOpenHashMap<>(WINDOWS_SHARED_TEXTURE_CACHE_LIMIT);

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
        // Create and register the direct texture wrapper with Minecraft's TextureManager
        directTexture = new MCEFDirectTexture();
        mc.getTextureManager().register(identifier, directTexture);
        textureRegistered = true;
    }

    /**
     * Returns the texture ID for the renderer.
     * If accelerated rendering is enabled, it returns the shared texture ID.
     * @return OpenGL texture ID
     */
    @Deprecated(since = "1.21.5")
    public int getTextureId() {
        var texture = getTexture();
        return !(texture instanceof GlTexture) ? 0 : ((GlTexture) texture).glId();
    }

    /**
     * Returns the texture for the renderer.
     * If accelerated rendering is enabled, it returns the shared texture.
     * @return GpuTexture
     */
    public @Nullable GpuTexture getTexture() {
        if (isAccelerated) {
            return sharedTexture;
        } else {
            return texture;
        }
    }

    private @Nullable MCEFDirectTexture getDirectTexture() {
        return isAccelerated ? directSharedTexture : directTexture;
    }

    /**
     * Returns the texture view for the renderer.
     * If accelerated rendering is enabled, it returns the shared texture.
     * @return GpuTextureView
     */
    public @Nullable GpuTextureView getTextureView() {
        var directTexture = this.getDirectTexture();
        return directTexture == null ? null : directTexture.getTextureView();
    }

    /**
     * Returns the sampler to be used for the renderer textures.
     * @return GpuSampler
     */
    public @Nullable GpuSampler getSampler() {
        var directTexture = this.getDirectTexture();
        return directTexture == null ? null : directTexture.getSampler();
    }

    /**
     * Returns the texture setup for the renderer.
     * If accelerated rendering is enabled, it returns the shared texture setup.
     * @return TextureSetup
     */
    public @Nullable TextureSetup getTextureSetup() {
        var directTexture = this.getDirectTexture();
        return directTexture == null ? null : directTexture.getTextureSetup();
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
        return isAccelerated ? sharedTexture != null : texture != null && textureRegistered && directTexture != null;
    }

    /**
     * Checks if the texture is unpainted. A texture is considered unpainted if it has not been painted yet,
     * which means no paint calls have been made since the last initialization or cleanup.
     */
    public boolean isUnpainted() {
        if (isAccelerated && sharedTexture == null) {
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
     * Checks if the texture format is BGRA. This is the case when we use [onAcceleratedPaint] with
     * {@link CefAcceleratedPaintInfo} as it uses the BGRA format for shared textures.
     *
     * @return true if the texture format is BGRA, false otherwise
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

        switch (info) {
            case CefAcceleratedPaintInfoWin winInfo -> onAcceleratedPaintWindows(winInfo, width, height);
            case CefAcceleratedPaintInfoLinux linuxInfo -> onAcceleratedPaintLinux(linuxInfo, width, height);
            default -> MCEF.INSTANCE.LOGGER.warn("Unsupported CefAcceleratedPaintInfo type: {}", info.getClass().getName());
        }
    }

    /**
     * Called when CEF provides D3D11 texture handle.
     * Requires conversion from D3D11 texture to OpenGL texture using glImportMemoryWin32HandleEXT.
     */
    private void onAcceleratedPaintWindows(CefAcceleratedPaintInfoWin info, int width, int height) {
        if (info.shared_texture_handle == 0) {
            MCEF.INSTANCE.LOGGER.warn("Accelerated paint shared texture handle is invalid.");
            return;
        }

        if (transparent) {
            GlStateManager._enableBlend();
        }

        var cachedTexture = windowsSharedTextureCache.getAndMoveToLast(info.shared_texture_handle);
        if (cachedTexture != null) {
            if (cachedTexture.matches(width, height)) {
                copyWindowsSharedTexture(cachedTexture.directTexture, width, height);
                return;
            }

            windowsSharedTextureCache.remove(info.shared_texture_handle);
            cachedTexture.close();
        }

        var importedTexture = importWindowsSharedTexture(info.shared_texture_handle, width, height);
        if (importedTexture == null) {
            return;
        }

        windowsSharedTextureCache.putAndMoveToLast(info.shared_texture_handle, importedTexture);
        trimWindowsSharedTextureCache();
        copyWindowsSharedTexture(importedTexture.directTexture, width, height);
    }

    /**
     * Called when CEF provides dmabuf planes on Linux. Imported using OpenGL EGL.
     */
    private void onAcceleratedPaintLinux(CefAcceleratedPaintInfoLinux info, int width, int height) {
        if (!info.hasDmaBufPlanes()) {
            MCEF.INSTANCE.LOGGER.warn("Accelerated paint info has no dmabuf planes on Linux.");
            return;
        }

        var display = EglUtils.getDisplay();
        if (display == EGL14.EGL_NO_DISPLAY) {
            MCEF.INSTANCE.LOGGER.error("EGL display is not available for dmabuf import.");
            return;
        }

        if (EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) {
            MCEF.INSTANCE.LOGGER.warn("No current EGL context available for dmabuf import.");
            return;
        }

        var drmFormat = switch (info.format) {
            case CefConstants.CEF_COLOR_TYPE_RGBA_8888 -> CefConstants.DRM_FORMAT_ABGR8888;
            case CefConstants.CEF_COLOR_TYPE_BGRA_8888 -> CefConstants.DRM_FORMAT_ARGB8888;
            default -> 0;
        };

        if (drmFormat == 0) {
            MCEF.INSTANCE.LOGGER.error("Unsupported accelerated paint format: {}", info.format);
            return;
        }

        var planeCount = Math.min(info.plane_count, CefConstants.DMA_BUF_PLANE_FD_ATTRS.length);
        planeCount = Math.min(planeCount, info.plane_fds.length);
        planeCount = Math.min(planeCount, info.plane_strides.length);
        planeCount = Math.min(planeCount, info.plane_offsets.length);
        if (planeCount <= 0) {
            MCEF.INSTANCE.LOGGER.warn("No dmabuf planes available for accelerated paint.");
            return;
        }

        var eglCapabilities = EglUtils.getCapabilities();
        var useModifiers = eglCapabilities.EGL_EXT_image_dma_buf_import_modifiers;
        var modifier = info.modifier;

        var planeAttribInts = useModifiers ? 10 : 6;
        var attribCapacity = 6 + (planeCount * planeAttribInts) + 1;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            MCEF.INSTANCE.LOGGER.debug(
                    "dmabuf planes: count={}, fds={}, strides={}, offsets={}, modifier=0x{}",
                    planeCount,
                    Arrays.toString(info.plane_fds),
                    Arrays.toString(info.plane_strides),
                    Arrays.toString(info.plane_offsets),
                    Long.toHexString(modifier)
            );
            MCEF.INSTANCE.LOGGER.debug(
                    "EGL display: display=0x{}",
                    Long.toHexString(display)
            );
            MCEF.INSTANCE.LOGGER.debug(
                    "dmabuf format: drmFormat=0x{}, size={}x{}",
                    Integer.toHexString(drmFormat),
                    width,
                    height
            );

            var attribs = stack.mallocInt(attribCapacity);
            attribs.put(EGL14.EGL_WIDTH).put(width);
            attribs.put(EGL14.EGL_HEIGHT).put(height);
            attribs.put(EXTImageDMABufImport.EGL_LINUX_DRM_FOURCC_EXT).put(drmFormat);

            for (int i = 0; i < planeCount; i++) {
                long offset = info.plane_offsets[i];
                if (offset > Integer.MAX_VALUE) {
                    MCEF.INSTANCE.LOGGER.error("dmabuf plane offset too large for EGL attributes: {}", offset);
                    return;
                }

                attribs.put(CefConstants.DMA_BUF_PLANE_FD_ATTRS[i]).put(info.plane_fds[i]);
                attribs.put(CefConstants.DMA_BUF_PLANE_OFFSET_ATTRS[i]).put((int) offset);
                attribs.put(CefConstants.DMA_BUF_PLANE_PITCH_ATTRS[i]).put(info.plane_strides[i]);

                if (useModifiers) {
                    int modifierLo = (int) (modifier & 0xffffffffL);
                    int modifierHi = (int) ((modifier >>> 32) & 0xffffffffL);
                    attribs.put(CefConstants.DMA_BUF_PLANE_MODIFIER_LO_ATTRS[i]).put(modifierLo);
                    attribs.put(CefConstants.DMA_BUF_PLANE_MODIFIER_HI_ATTRS[i]).put(modifierHi);
                }
            }

            attribs.put(EGL14.EGL_NONE);
            attribs.flip();

            var attribSnapshot = new int[attribs.remaining()];
            attribs.get(attribSnapshot);
            attribs.rewind();
            MCEF.INSTANCE.LOGGER.debug(
                    "eglCreateImageKHR dmabuf attribs: {}",
                    Arrays.toString(attribSnapshot)
            );

            var eglImage = EglUtils.eglCreateImageKHR(
                    display,
                    EGL14.EGL_NO_CONTEXT,
                    EXTImageDMABufImport.EGL_LINUX_DMA_BUF_EXT,
                    0L,
                    attribs
            );

            if (eglImage == 0) {
                var eglError = EGL14.eglGetError();
                MCEF.INSTANCE.LOGGER.error(
                        "eglCreateImageKHR failed for dmabuf import. eglGetError=0x{}",
                        Integer.toHexString(eglError)
                );
                MCEF.INSTANCE.LOGGER.error(
                        "dmabuf attribs at failure: {}",
                        Arrays.toString(attribSnapshot)
                );
                return;
            }

            if (transparent) {
                GlStateManager._enableBlend();
            }

            var sharedTextureId = glGenTextures();
            GlStateManager._bindTexture(sharedTextureId);
            EXTEGLImageStorage.glEGLImageTargetTexStorageEXT(GL_TEXTURE_2D, eglImage, (IntBuffer) null);
            KHRImageBase.eglDestroyImageKHR(display, eglImage);

            var error = glGetError();
            if (error != GL_NO_ERROR) {
                MCEF.INSTANCE.LOGGER.error("glEGLImageTargetTexture2DOES failed with error: {}", error);
                glDeleteTextures(sharedTextureId);
                return;
            }

            var previousDirectSharedTexture = this.directSharedTexture;
            var directSharedTexture = new MCEFDirectTexture();
            directSharedTexture.setOwnedDirectTextureId(sharedTextureId, width, height);
            this.directSharedTexture = directSharedTexture;
            this.sharedTexture = directSharedTexture.getTexture();
            if (previousDirectSharedTexture != null) {
                previousDirectSharedTexture.close();
            }
            this.textureWidth = width;
            this.textureHeight = height;

            isAccelerated = true;
            unpainted = false;
            isBGRA = info.format != CefConstants.CEF_COLOR_TYPE_BGRA_8888;

            GlStateManager._bindTexture(0);
        }
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

        // Create or recreate texture if size changed
        if (texture == null || textureWidth != width || textureHeight != height) {
            if (texture != null) {
                texture.close();
            }

            // Create new GpuTexture using the device
            String label = "MCEF Browser Texture " + width + "x" + height;
            texture = RenderSystem.getDevice().createTexture(
                    label,
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                    TextureFormat.RGBA8,
                    width,
                    height,
                    1, // depthOrLayers
                    1  // mipLevels
            );

            textureWidth = width;
            textureHeight = height;

            // Update the direct texture wrapper to point to our new texture
            if (directTexture != null && texture instanceof GlTexture glTexture) {
                directTexture.setDirectTextureId(glTexture.glId(), width, height);
            }
        }

        if (transparent) {
            GlStateManager._enableBlend();
        }

        if (texture instanceof GlTexture glTexture) {
            // Bind the texture directly using its GL ID
            GlStateManager._bindTexture(glTexture.glId());
            GlStateManager._pixelStore(GL_UNPACK_ROW_LENGTH, width);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._pixelStore(GL_UNPACK_SKIP_ROWS, 0);

            // Upload the full texture
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);

            isBGRA = false;
            unpainted = false;
        }
    }

    /**
     * Paints a sub-region of the texture with the provided ByteBuffer data.
     * This method is called when CEF provides a ByteBuffer for painting a specific area.
     *
     * @param buffer The ByteBuffer containing the pixel data to paint.
     * @param x      The x-coordinate of the sub-region to paint.
     * @param y      The y-coordinate of the sub-region to paint.
     * @param width  The width of the sub-region to paint.
     * @param height The height of the sub-region to paint.
     */
    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        RenderSystem.assertOnRenderThread();

        if (texture instanceof GlTexture glTexture) {
            // Bind and update sub-region
            GlStateManager._bindTexture(glTexture.glId());
            glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, GL_BGRA,
                    GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        }
    }

    /**
     * Clears the texture by binding it and filling it with transparent pixels.
     */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();

        if (this.directTexture != null) {
            this.directTexture.close();
            this.directTexture = null;
        }

        if (this.texture != null) {
            this.texture.close();
            this.texture = null;
        }

        clearWindowsSharedTextureCache();

        if (this.directSharedTexture != null) {
            this.directSharedTexture.close();
            this.directSharedTexture = null;
        }

        if (this.sharedTexture != null) {
            this.sharedTexture.close();
            this.sharedTexture = null;
        }

        // Unregister from TextureManager
        if (textureRegistered) {
            mc.getTextureManager().release(identifier);
            textureRegistered = false;
        }

        isAccelerated = false;
    }

    private @Nullable WindowsSharedTextureEntry importWindowsSharedTexture(long sharedTextureHandle, int width, int height) {
        var sharedTextureId = glGenTextures();

        var memoryObject = glCreateMemoryObjectsEXT();
        if (memoryObject == 0) {
            MCEF.INSTANCE.LOGGER.error("Failed to create memory object for shared texture.");
            glDeleteTextures(sharedTextureId);
            return null;
        }

        glImportMemoryWin32HandleEXT(
                memoryObject,
                WINDOWS_SHARED_TEXTURE_IMPORT_SIZE,
                GL_HANDLE_TYPE_D3D11_IMAGE_EXT,
                sharedTextureHandle
        );

        var error = glGetError();
        if (error != GL_NO_ERROR) {
            MCEF.INSTANCE.LOGGER.error("glImportMemoryWin32HandleEXT failed with error: {}", error);
            glDeleteTextures(sharedTextureId);
            glDeleteMemoryObjectsEXT(memoryObject);
            return null;
        }

        GlStateManager._bindTexture(sharedTextureId);
        glTexStorageMem2DEXT(
                GL_TEXTURE_2D,
                1,
                GL_RGBA8,
                width,
                height,
                memoryObject,
                0
        );
        glFinish();
        glDeleteMemoryObjectsEXT(memoryObject);

        error = glGetError();
        if (error != GL_NO_ERROR) {
            MCEF.INSTANCE.LOGGER.error("glTexStorageMem2DEXT failed with error: {}", error);
            glDeleteTextures(sharedTextureId);
            GlStateManager._bindTexture(0);
            return null;
        }

        GlStateManager._bindTexture(0);

        var directTexture = new MCEFDirectTexture();
        directTexture.setOwnedDirectTextureId(sharedTextureId, width, height);
        return new WindowsSharedTextureEntry(width, height, directTexture);
    }

    private void copyWindowsSharedTexture(MCEFDirectTexture sourceTexture, int width, int height) {
        var targetTexture = ensureWindowsAcceleratedTargetTexture(width, height);
        if (targetTexture == null) {
            return;
        }

        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                sourceTexture.getTexture(),
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
        isBGRA = true;
    }

    private @Nullable GpuTexture ensureWindowsAcceleratedTargetTexture(int width, int height) {
        if (sharedTexture != null && textureWidth == width && textureHeight == height) {
            return sharedTexture;
        }

        if (directSharedTexture != null) {
            directSharedTexture.close();
            directSharedTexture = null;
        }

        if (sharedTexture != null) {
            sharedTexture.close();
            sharedTexture = null;
        }

        var targetTextureId = glGenTextures();
        GlStateManager._bindTexture(targetTextureId);
        glTexImage2D(
                GL_TEXTURE_2D,
                0,
                GL_RGBA8,
                width,
                height,
                0,
                GL_RGBA,
                GL_UNSIGNED_BYTE,
                (ByteBuffer) null
        );

        var error = glGetError();
        if (error != GL_NO_ERROR) {
            MCEF.INSTANCE.LOGGER.error("Failed to allocate accelerated paint target texture: {}", error);
            glDeleteTextures(targetTextureId);
            GlStateManager._bindTexture(0);
            return null;
        }
        GlStateManager._bindTexture(0);

        var directTexture = new MCEFDirectTexture();
        directTexture.setOwnedDirectTextureId(targetTextureId, width, height);

        this.directSharedTexture = directTexture;
        this.sharedTexture = directTexture.getTexture();

        return this.sharedTexture;
    }

    private void trimWindowsSharedTextureCache() {
        while (windowsSharedTextureCache.size() > WINDOWS_SHARED_TEXTURE_CACHE_LIMIT) {
            long eldestHandle = windowsSharedTextureCache.firstLongKey();
            var evictedEntry = windowsSharedTextureCache.get(eldestHandle);
            if (evictedEntry == null) {
                return;
            }

            if (evictedEntry.directTexture == this.directSharedTexture) {
                windowsSharedTextureCache.getAndMoveToLast(eldestHandle);
                continue;
            }

            windowsSharedTextureCache.remove(eldestHandle);
            evictedEntry.close();
        }
    }

    private void clearWindowsSharedTextureCache() {
        if (windowsSharedTextureCache.isEmpty()) {
            return;
        }

        for (var entry : windowsSharedTextureCache.values()) {
            entry.close();
        }
        windowsSharedTextureCache.clear();
    }

    private record WindowsSharedTextureEntry(
            int width,
            int height,
            MCEFDirectTexture directTexture
    ) implements Closeable {

        private boolean matches(int width, int height) {
            return this.width == width && this.height == height;
        }

        @Override
        public void close() {
            this.directTexture.close();
        }
    }

}
