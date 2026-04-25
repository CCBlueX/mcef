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
import net.ccbluex.liquidbounce.mcef.utils.EglUtils;
import org.cef.handler.CefAcceleratedPaintInfo;
import org.cef.handler.CefAcceleratedPaintInfoLinux;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.egl.EGL14;
import org.lwjgl.egl.EXTImageDMABufImport;
import org.lwjgl.egl.KHRImageBase;
import org.lwjgl.opengl.EXTEGLImageStorage;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.util.Arrays;

import static org.lwjgl.opengl.GL11.*;

@NullMarked
final class LinuxAcceleratedPaintBackend implements AcceleratedPaintBackend {

    @Override
    public boolean accepts(CefAcceleratedPaintInfo info) {
        return info instanceof CefAcceleratedPaintInfoLinux;
    }

    @Override
    public @Nullable AcceleratedPaintFrame importFrame(CefAcceleratedPaintInfo info, int width, int height) {
        var linuxInfo = (CefAcceleratedPaintInfoLinux) info;
        if (!linuxInfo.hasDmaBufPlanes()) {
            MCEF.INSTANCE.LOGGER.warn("Accelerated paint info has no dmabuf planes on Linux.");
            return null;
        }

        var display = EglUtils.getDisplay();
        if (display == EGL14.EGL_NO_DISPLAY) {
            MCEF.INSTANCE.LOGGER.error("EGL display is not available for dmabuf import.");
            return null;
        }

        if (EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) {
            MCEF.INSTANCE.LOGGER.warn("No current EGL context available for dmabuf import.");
            return null;
        }

        var drmFormat = switch (linuxInfo.format) {
            case CefConstants.CEF_COLOR_TYPE_RGBA_8888 -> CefConstants.DRM_FORMAT_ABGR8888;
            case CefConstants.CEF_COLOR_TYPE_BGRA_8888 -> CefConstants.DRM_FORMAT_ARGB8888;
            default -> 0;
        };

        if (drmFormat == 0) {
            MCEF.INSTANCE.LOGGER.error("Unsupported accelerated paint format: {}", linuxInfo.format);
            return null;
        }

        var planeCount = Math.min(linuxInfo.plane_count, CefConstants.DMA_BUF_PLANE_FD_ATTRS.length);
        planeCount = Math.min(planeCount, linuxInfo.plane_fds.length);
        planeCount = Math.min(planeCount, linuxInfo.plane_strides.length);
        planeCount = Math.min(planeCount, linuxInfo.plane_offsets.length);
        if (planeCount <= 0) {
            MCEF.INSTANCE.LOGGER.warn("No dmabuf planes available for accelerated paint.");
            return null;
        }

        var eglCapabilities = EglUtils.getCapabilities();
        var useModifiers = eglCapabilities.EGL_EXT_image_dma_buf_import_modifiers;
        var modifier = linuxInfo.modifier;

        var planeAttribInts = useModifiers ? 10 : 6;
        var attribCapacity = 6 + (planeCount * planeAttribInts) + 1;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var attribs = stack.mallocInt(attribCapacity);
            attribs.put(EGL14.EGL_WIDTH).put(width);
            attribs.put(EGL14.EGL_HEIGHT).put(height);
            attribs.put(EXTImageDMABufImport.EGL_LINUX_DRM_FOURCC_EXT).put(drmFormat);

            for (int i = 0; i < planeCount; i++) {
                long offset = linuxInfo.plane_offsets[i];
                if (offset > Integer.MAX_VALUE) {
                    MCEF.INSTANCE.LOGGER.error("dmabuf plane offset too large for EGL attributes: {}", offset);
                    return null;
                }

                attribs.put(CefConstants.DMA_BUF_PLANE_FD_ATTRS[i]).put(linuxInfo.plane_fds[i]);
                attribs.put(CefConstants.DMA_BUF_PLANE_OFFSET_ATTRS[i]).put((int) offset);
                attribs.put(CefConstants.DMA_BUF_PLANE_PITCH_ATTRS[i]).put(linuxInfo.plane_strides[i]);

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
            logDmaBufImport(linuxInfo, planeCount, display, drmFormat, width, height, attribSnapshot);

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
                MCEF.INSTANCE.LOGGER.error("dmabuf attribs at failure: {}", Arrays.toString(attribSnapshot));
                return null;
            }

            var sharedTextureId = glGenTextures();
            GlStateManager._bindTexture(sharedTextureId);
            EXTEGLImageStorage.glEGLImageTargetTexStorageEXT(GL_TEXTURE_2D, eglImage, (IntBuffer) null);
            KHRImageBase.eglDestroyImageKHR(display, eglImage);

            var error = glGetError();
            if (error != GL_NO_ERROR) {
                MCEF.INSTANCE.LOGGER.error("glEGLImageTargetTexture2DOES failed with error: {}", error);
                glDeleteTextures(sharedTextureId);
                return null;
            }

            GlStateManager._bindTexture(0);

            var directTexture = new MCEFDirectTexture();
            directTexture.setOwnedDirectTextureId(sharedTextureId, width, height);
            var bgra = linuxInfo.format != CefConstants.CEF_COLOR_TYPE_BGRA_8888;
            return new AcceleratedPaintFrame(directTexture.getTexture(), bgra, directTexture::close);
        }
    }

    @Override
    public void close() {
    }

    private void logDmaBufImport(
            CefAcceleratedPaintInfoLinux info,
            int planeCount,
            long display,
            int drmFormat,
            int width,
            int height,
            int[] attribSnapshot
    ) {
        MCEF.INSTANCE.LOGGER.debug(
                "dmabuf planes: count={}, fds={}, strides={}, offsets={}, modifier=0x{}",
                planeCount,
                Arrays.toString(info.plane_fds),
                Arrays.toString(info.plane_strides),
                Arrays.toString(info.plane_offsets),
                Long.toHexString(info.modifier)
        );
        MCEF.INSTANCE.LOGGER.debug("EGL display: display=0x{}", Long.toHexString(display));
        MCEF.INSTANCE.LOGGER.debug(
                "dmabuf format: drmFormat=0x{}, size={}x{}",
                Integer.toHexString(drmFormat),
                width,
                height
        );
        MCEF.INSTANCE.LOGGER.debug("eglCreateImageKHR dmabuf attribs: {}", Arrays.toString(attribSnapshot));
    }

}
