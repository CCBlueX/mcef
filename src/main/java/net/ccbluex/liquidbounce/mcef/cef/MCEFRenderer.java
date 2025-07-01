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

import com.mojang.blaze3d.systems.RenderSystem;
import net.ccbluex.liquidbounce.mcef.MCEF;
import org.cef.handler.CefAcceleratedPaintInfo;
import org.lwjgl.opengl.GL11;

import java.io.Closeable;
import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.*;

public class MCEFRenderer implements Closeable {

    private final boolean transparent;
    private final int[] textureID = new int[1];
    private final int[] sharedTextureID = new int[1];
    private final int[] memoryObjectID = new int[1];
    private boolean unpainted = true;
    private boolean isAccelerated = false;

    protected MCEFRenderer(boolean transparent) {
        this.transparent = transparent;
    }

    /**
     * Initializes the renderer by generating a texture ID and setting up the texture parameters.
     */
    public void initialize() {
        RenderSystem.assertOnRenderThreadOrInit();

        textureID[0] = GL11.glGenTextures();
        RenderSystem.bindTexture(textureID[0]);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        RenderSystem.bindTexture(0);

        sharedTextureID[0] = 0;
        memoryObjectID[0] = 0;
    }

    /**
     * Returns the texture ID for the renderer. If accelerated rendering is enabled, it returns the shared texture ID.
     * @return OpenGL texture ID
     */
    public int getTextureID() {
        if (isAccelerated) {
            return sharedTextureID[0];
        } else {
            return textureID[0];
        }
    }

    /**
     * Checks if the texture is unpainted. A texture is considered unpainted if it has not been painted yet,
     * which means no paint calls have been made since the last initialization or cleanup.
     */
    public boolean isUnpainted() {
        if (isAccelerated && sharedTextureID[0] == 0) {
            return false;
        }

        if (textureID[0] == 0) {
            return false;
        }

        return unpainted;
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
        return isAccelerated;
    }

    /**
     * Handles accelerated paint events from CEF. This method is called when CEF provides a shared texture
     * for accelerated rendering. On Windows, this texture is a D3D11 shared texture handle.
     * <p>
     * TODO: For other platforms, we have no support yet.
     *
     * @param info   The CefAcceleratedPaintInfo containing the shared texture handle and other information.
     * @param width  The width of the texture.
     * @param height The height of the texture.
     */
    protected void onAcceleratedPaint(CefAcceleratedPaintInfo info, int width, int height) {
        RenderSystem.assertOnRenderThread();

        if (transparent) {
            RenderSystem.enableBlend();
        }

        var d3d11Handle = info.shared_texture_handle;
        if (memoryObjectID[0] != 0) {
            glDeleteMemoryObjectsEXT(memoryObjectID[0]);
            memoryObjectID[0] = 0;
        }

        if (sharedTextureID[0] != 0) {
            RenderSystem.deleteTexture(sharedTextureID[0]);
        }

        sharedTextureID[0] = GL11.glGenTextures();
        memoryObjectID[0] = glCreateMemoryObjectsEXT();

        if (memoryObjectID[0] == 0) {
            MCEF.INSTANCE.LOGGER.error("Failed to create memory object for shared texture.");
            return;
        }

        var estimatedSize = (long) width * height * 4 * 2; // 4 bytes per pixel, 2 planes for BGRA
        glImportMemoryWin32HandleEXT(memoryObjectID[0],
                estimatedSize,
                GL_HANDLE_TYPE_D3D11_IMAGE_EXT,
                d3d11Handle
        );

        RenderSystem.bindTexture(sharedTextureID[0]);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        glTexStorageMem2DEXT(
                GL_TEXTURE_2D,      // Target (not texture ID)
                1,                  // Mip levels
                GL_RGBA8,           // Internal format
                width,
                height,
                memoryObjectID[0],
                0                   // Offset
        );

        RenderSystem.bindTexture(0);

        isAccelerated = true;
        unpainted = false;
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
        if (textureID[0] == 0) {
            return;
        }

        RenderSystem.assertOnRenderThread();

        if (transparent) {
            RenderSystem.enableBlend();
        }

        RenderSystem.bindTexture(textureID[0]);
        RenderSystem.pixelStore(GL_UNPACK_ROW_LENGTH, width);
        RenderSystem.pixelStore(GL_UNPACK_SKIP_PIXELS, 0);
        RenderSystem.pixelStore(GL_UNPACK_SKIP_ROWS, 0);

        GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        unpainted = false;
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

        GL11.glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, GL_BGRA,
                GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        unpainted = false;
    }

    /**
     * Clears the texture by binding it and filling it with transparent pixels.
     */
    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();

        if (memoryObjectID[0] != 0) {
            glDeleteMemoryObjectsEXT(memoryObjectID[0]);
            memoryObjectID[0] = 0;
        }

        if (textureID[0] != 0) {
            RenderSystem.deleteTexture(textureID[0]);
            textureID[0] = 0;
        }

        if (sharedTextureID[0] != 0) {
            RenderSystem.deleteTexture(sharedTextureID[0]);
            sharedTextureID[0] = 0;
        }

        isAccelerated = false;
    }

}
