/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2026 CCBlueX
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
import org.cef.handler.CefAcceleratedPaintInfoMac;
import org.cef.handler.CefMacOsIOSurface;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_BGRA;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL12.GL_UNSIGNED_INT_8_8_8_8_REV;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.GL_TEXTURE_BINDING_RECTANGLE;
import static org.lwjgl.opengl.GL31.GL_TEXTURE_RECTANGLE;

@NullMarked
final class MacAcceleratedPaintBackend implements AcceleratedPaintBackend {
    private boolean disabled;
    private boolean missingNativeLogged;
    private boolean invalidHandleLogged;
    private boolean unsupportedFormatLogged;
    private boolean importFailureLogged;

    @Override
    public boolean accepts(CefAcceleratedPaintInfo info) {
        return info instanceof CefAcceleratedPaintInfoMac;
    }

    @Override
    public @Nullable AcceleratedPaintFrame importFrame(CefAcceleratedPaintInfo info, int width, int height) {
        if (disabled) {
            return null;
        }

        var macInfo = (CefAcceleratedPaintInfoMac) info;
        if (macInfo.shared_texture_io_surface == 0) {
            warnInvalidHandleOnce();
            return null;
        }

        if (macInfo.format != CefConstants.CEF_COLOR_TYPE_BGRA_8888) {
            warnUnsupportedFormatOnce(macInfo.format);
            return null;
        }

        try {
            return importIOSurface(macInfo.shared_texture_io_surface, width, height);
        } catch (UnsatisfiedLinkError e) {
            disabled = true;
            if (!missingNativeLogged) {
                missingNativeLogged = true;
                MCEF.INSTANCE.LOGGER.warn("macOS IOSurface JNI helper is unavailable; disabling accelerated paint.", e);
            }
            return null;
        } catch (RuntimeException e) {
            warnImportFailureOnce("Unexpected macOS IOSurface import failure", e);
            return null;
        }
    }

    @Override
    public void close() {
    }

    private @Nullable AcceleratedPaintFrame importIOSurface(long ioSurface, int width, int height) {
        var previousRectangleTexture = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        var previousTexture2D = glGetInteger(GL_TEXTURE_BINDING_2D);
        var previousReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        var previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        var previousReadBuffer = glGetInteger(GL_READ_BUFFER);
        var previousDrawBuffer = glGetInteger(GL_DRAW_BUFFER);

        var rectangleTextureId = glGenTextures();
        var texture2DId = glGenTextures();
        var readFramebuffer = glGenFramebuffers();
        var drawFramebuffer = glGenFramebuffers();

        try {
            glBindTexture(GL_TEXTURE_RECTANGLE, rectangleTextureId);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

            var cglError = CefMacOsIOSurface.bindToCurrentTexture(ioSurface, width, height);
            if (cglError != 0) {
                warnImportFailureOnce("CGLTexImageIOSurface2D failed with CGLError=" + cglError, null);
                return null;
            }

            GlStateManager._bindTexture(texture2DId);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGBA8,
                    width,
                    height,
                    0,
                    GL_BGRA,
                    GL_UNSIGNED_INT_8_8_8_8_REV,
                    (ByteBuffer) null
            );

            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
            glFramebufferTexture2D(
                    GL_READ_FRAMEBUFFER,
                    GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_RECTANGLE,
                    rectangleTextureId,
                    0
            );
            var readStatus = glCheckFramebufferStatus(GL_READ_FRAMEBUFFER);
            if (readStatus != GL_FRAMEBUFFER_COMPLETE) {
                warnImportFailureOnce("macOS IOSurface read framebuffer incomplete: " + readStatus, null);
                return null;
            }

            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            glFramebufferTexture2D(
                    GL_DRAW_FRAMEBUFFER,
                    GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_2D,
                    texture2DId,
                    0
            );
            var drawStatus = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
            if (drawStatus != GL_FRAMEBUFFER_COMPLETE) {
                warnImportFailureOnce("macOS IOSurface draw framebuffer incomplete: " + drawStatus, null);
                return null;
            }

            glReadBuffer(GL_COLOR_ATTACHMENT0);
            glDrawBuffer(GL_COLOR_ATTACHMENT0);
            glBlitFramebuffer(
                    0,
                    0,
                    width,
                    height,
                    0,
                    0,
                    width,
                    height,
                    GL_COLOR_BUFFER_BIT,
                    GL_NEAREST
            );

            var error = glGetError();
            if (error != GL_NO_ERROR) {
                warnImportFailureOnce("macOS IOSurface blit failed with GL error: " + error, null);
                return null;
            }

            var directTexture = new MCEFDirectTexture();
            directTexture.setOwnedDirectTextureId(texture2DId, width, height);
            texture2DId = 0;
            return new AcceleratedPaintFrame(directTexture.getTexture(), false, directTexture::close);
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            glReadBuffer(previousReadBuffer);
            glDrawBuffer(previousDrawBuffer);
            glBindTexture(GL_TEXTURE_RECTANGLE, previousRectangleTexture);
            GlStateManager._bindTexture(previousTexture2D);

            if (readFramebuffer != 0) {
                glDeleteFramebuffers(readFramebuffer);
            }
            if (drawFramebuffer != 0) {
                glDeleteFramebuffers(drawFramebuffer);
            }
            if (rectangleTextureId != 0) {
                glDeleteTextures(rectangleTextureId);
            }
            if (texture2DId != 0) {
                glDeleteTextures(texture2DId);
            }
        }
    }

    private void warnInvalidHandleOnce() {
        if (!invalidHandleLogged) {
            invalidHandleLogged = true;
            MCEF.INSTANCE.LOGGER.warn("Accelerated paint IOSurface handle is invalid on macOS.");
        }
    }

    private void warnUnsupportedFormatOnce(int format) {
        if (!unsupportedFormatLogged) {
            unsupportedFormatLogged = true;
            MCEF.INSTANCE.LOGGER.warn("Unsupported macOS accelerated paint format: {}", format);
        }
    }

    private void warnImportFailureOnce(String message, @Nullable Throwable throwable) {
        if (importFailureLogged) {
            return;
        }

        importFailureLogged = true;
        if (throwable == null) {
            MCEF.INSTANCE.LOGGER.warn(message);
        } else {
            MCEF.INSTANCE.LOGGER.warn(message, throwable);
        }
    }
}
