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

import com.mojang.blaze3d.opengl.GlStateManager;
import net.ccbluex.liquidbounce.mcef.MCEF;
import org.cef.handler.CefAcceleratedPaintInfo;
import org.cef.handler.CefAcceleratedPaintInfoWin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.GL_HANDLE_TYPE_D3D11_IMAGE_EXT;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.GL11.*;

@NullMarked
final class WindowsAcceleratedPaintBackend implements AcceleratedPaintBackend {
    private static final long SHARED_TEXTURE_IMPORT_SIZE = 0L;

    @Override
    public boolean accepts(CefAcceleratedPaintInfo info) {
        return info instanceof CefAcceleratedPaintInfoWin;
    }

    @Override
    public @Nullable AcceleratedPaintFrame importFrame(CefAcceleratedPaintInfo info, int width, int height) {
        var winInfo = (CefAcceleratedPaintInfoWin) info;
        if (winInfo.shared_texture_handle == 0) {
            MCEF.INSTANCE.LOGGER.warn("Accelerated paint shared texture handle is invalid.");
            return null;
        }

        var importedTexture = importSharedTexture(winInfo.shared_texture_handle, width, height);
        if (importedTexture == null) {
            return null;
        }

        return new AcceleratedPaintFrame(importedTexture.getTexture(), true, importedTexture::close);
    }

    @Override
    public void close() {
    }

    private @Nullable MCEFDirectTexture importSharedTexture(long sharedTextureHandle, int width, int height) {
        var sharedTextureId = glGenTextures();

        var memoryObject = glCreateMemoryObjectsEXT();
        if (memoryObject == 0) {
            MCEF.INSTANCE.LOGGER.error("Failed to create memory object for shared texture.");
            glDeleteTextures(sharedTextureId);
            return null;
        }

        glImportMemoryWin32HandleEXT(
                memoryObject,
                SHARED_TEXTURE_IMPORT_SIZE,
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
        return directTexture;
    }

}
