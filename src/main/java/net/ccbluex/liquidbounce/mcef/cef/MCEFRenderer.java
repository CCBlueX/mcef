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
import net.ccbluex.liquidbounce.mcef.MCEFPlatform;
import org.cef.handler.CefAcceleratedPaintInfo;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

// Use only Compatibility Profile imports to avoid conflicts
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.*;
import static org.lwjgl.opengl.GL45.glTextureParameteri;

public class MCEFRenderer {

    private final boolean transparent;
    private final int[] textureID = new int[1];
    private final int[] memoryObjectID = new int[1];
    private boolean unpainted = true;
    private boolean isAccelerated = false;
    private final boolean isWindows = MCEFPlatform.getPlatform().isWindows();

    protected MCEFRenderer(boolean transparent) {
        this.transparent = transparent;
    }

    public void initialize() {
        RenderSystem.assertOnRenderThreadOrInit();

        textureID[0] = GL11.glGenTextures();
        RenderSystem.bindTexture(textureID[0]);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        RenderSystem.bindTexture(0);

        memoryObjectID[0] = 0;

        if (isWindows) {
            boolean hasMemoryObjectExt = GL.getCapabilities().GL_EXT_memory_object;
            boolean hasMemoryObjectWin32Ext = GL.getCapabilities().GL_EXT_memory_object_win32;

            if (!hasMemoryObjectExt || !hasMemoryObjectWin32Ext) {
                System.err.println("Warning: Required OpenGL extensions for shared texture are not available:");
                System.err.println("  GL_EXT_memory_object: " + hasMemoryObjectExt);
                System.err.println("  GL_EXT_memory_object_win32: " + hasMemoryObjectWin32Ext);
                System.err.println("  Shared texture acceleration will be disabled.");
            } else {
                System.out.println("OpenGL extensions for shared texture are available - acceleration enabled");
            }
        }

        unpainted = true;
    }

    public int getTextureID() {
        return textureID[0];
    }

    public boolean isUnpainted() {
        return unpainted;
    }

    public boolean isTransparent() {
        return transparent;
    }

    public boolean isAccelerated() {
        return isAccelerated;
    }

    protected void onAcceleratedPaint(CefAcceleratedPaintInfo info, int width, int height) {
        if (textureID[0] == 0) {
            return;
        }

        RenderSystem.assertOnRenderThread();

        if (transparent) {
            RenderSystem.enableBlend();
        }

        var d3d11Handle = info.shared_texture_handle;

        System.out.println("Creating new shared texture mapping: handle=0x" + Long.toHexString(d3d11Handle) +
                " size=" + width + "x" + height);

        // Clean up previous memory object if it exists
        if (memoryObjectID[0] != 0) {
            glDeleteMemoryObjectsEXT(memoryObjectID[0]);
            memoryObjectID[0] = 0;
        }

        if (textureID[0] != 0) {
            RenderSystem.deleteTexture(textureID[0]);
        }

        textureID[0] = GL11.glGenTextures();
        memoryObjectID[0] = glCreateMemoryObjectsEXT();

        if (memoryObjectID[0] == 0) {
            System.err.println("Failed to create OpenGL memory object");
            int error = glGetError();
            System.err.println("OpenGL error after glCreateMemoryObjectsEXT: 0x" + Integer.toHexString(error));
            return;
        }

        var estimatedSize = (long) width * height * 4;

        System.out.println("OpenGL estimated size: " + estimatedSize);
        glImportMemoryWin32HandleEXT(memoryObjectID[0],
                estimatedSize,
                GL_HANDLE_TYPE_D3D11_IMAGE_EXT,
                d3d11Handle
        );

        int error = glGetError();
        if (error != GL_NO_ERROR) {
            System.err.println("OpenGL error after glImportMemoryWin32HandleEXT: 0x" + Integer.toHexString(error));
            return;
        }

        RenderSystem.bindTexture(textureID[0]);
//        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
//        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
//        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
//        RenderSystem.texParameter(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        glTexStorageMem2DEXT(
                GL_TEXTURE_2D,      // Target (not texture ID)
                1,                  // Mip levels
                GL_RGBA8,           // Internal format
                width,
                height,
                memoryObjectID[0],
                0                   // Offset
        );

        error = glGetError();
        if (error != GL_NO_ERROR) {
            System.err.println("OpenGL error after glTexStorageMem2DEXT: 0x" + Integer.toHexString(error));
            return;
        }

        RenderSystem.bindTexture(0);

        isAccelerated = true;
        unpainted = false;
    }



    protected void cleanup() {
        RenderSystem.assertOnRenderThread();

        if (memoryObjectID[0] != 0) {
            glDeleteMemoryObjectsEXT(memoryObjectID[0]);
            memoryObjectID[0] = 0;
        }

        if (textureID[0] != 0) {
            RenderSystem.deleteTexture(textureID[0]);
            textureID[0] = 0;
        }

        isAccelerated = false;
    }

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

    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        RenderSystem.assertOnRenderThread();

        GL11.glTexSubImage2D(GL_TEXTURE_2D, 0, x, y, width, height, GL_BGRA,
                GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        unpainted = false;
    }
}
