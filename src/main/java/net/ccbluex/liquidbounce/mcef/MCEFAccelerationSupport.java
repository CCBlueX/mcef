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

package net.ccbluex.liquidbounce.mcef;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Locale;

import net.ccbluex.liquidbounce.mcef.utils.EglUtils;
import org.lwjgl.egl.EGL14;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

/**
 * Check if the current platform supports GPU acceleration for CEF.
 */
public final class MCEFAccelerationSupport {

    public record Support(boolean isSupported, boolean isBeta) {
        public static final Support UNSUPPORTED = new Support(false, false);
    }

    private static volatile Support cachedSupport;

    private MCEFAccelerationSupport() {
    }

    /**
     * Checks and returns the acceleration support flags for the current platform.
     * Result is cached after the first successful computation.
     */
    public static Support getAccelerationSupport() {
        var support = cachedSupport;
        if (support != null) {
            return support;
        }

        cachedSupport = switch (MCEFPlatform.getPlatform()) {
            case WINDOWS_AMD64, WINDOWS_ARM64 -> checkWindowsSupport();
            case LINUX_AMD64, LINUX_ARM64 -> checkLinuxSupport();
            default -> Support.UNSUPPORTED;
        };

        if (!cachedSupport.isSupported()) {
            MCEF.INSTANCE.LOGGER.info("Falling back to software rendering for browser");
        }

        return cachedSupport;
    }

    private static Support checkWindowsSupport() {
        try {
            RenderSystem.assertOnRenderThread();

            var capabilities = GL.getCapabilities();
            var vendor = GL11.glGetString(GL11.GL_VENDOR);
            var renderer = GL11.glGetString(GL11.GL_RENDERER);

            var vendorString = vendor == null ? "" : vendor;
            var rendererString = renderer == null ? "" : renderer;

            MCEF.INSTANCE.LOGGER.info("GPU Vendor: {}", vendorString);
            MCEF.INSTANCE.LOGGER.info("GPU Renderer: {}", rendererString);

            var isNvidiaGpu = isNvidiaGpu(vendorString, rendererString);
            var isSupportedGpu = isNvidiaGpu || isAmdGpu(vendorString, rendererString);
            if (!isSupportedGpu) {
                MCEF.INSTANCE.LOGGER.warn("GPU acceleration only supported on NVIDIA and AMD GPUs");
                return Support.UNSUPPORTED;
            }

            if (!capabilities.GL_EXT_memory_object
                || !capabilities.GL_EXT_memory_object_win32
                || capabilities.glImportMemoryWin32HandleEXT == 0L) {
                MCEF.INSTANCE.LOGGER.warn("Required OpenGL extensions for GPU acceleration not supported");
                return Support.UNSUPPORTED;
            }

            return new Support(true, !isNvidiaGpu);
        } catch (Exception e) {
            MCEF.INSTANCE.LOGGER.warn("Failed to check GPU acceleration support: {}", e.getMessage());
            return Support.UNSUPPORTED;
        }
    }

    private static Support checkLinuxSupport() {
        try {
            RenderSystem.assertOnRenderThread();

            // Check if WEBKIT_DISABLE_DMABUF_RENDERER=1 is set.
            var webkitDisableDmabufRenderer = System.getenv("WEBKIT_DISABLE_DMABUF_RENDERER");
            if (webkitDisableDmabufRenderer != null && webkitDisableDmabufRenderer.equals("1")) {
                MCEF.INSTANCE.LOGGER.warn("WEBKIT_DISABLE_DMABUF_RENDERER=1 is set.");
                return Support.UNSUPPORTED;
            }

            var eglDisplay = EglUtils.getDisplay();
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                MCEF.INSTANCE.LOGGER.warn("EGL display is not available for accelerated paint");
                return Support.UNSUPPORTED;
            }

            if (EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) {
                MCEF.INSTANCE.LOGGER.warn("No EGL context available for accelerated paint. Install WayGL mod.");
                return Support.UNSUPPORTED;
            }

            var eglCapabilities = EglUtils.getCapabilities();
            var hasDmabufImport = eglCapabilities.EGL_EXT_image_dma_buf_import;
            var hasImageBase = eglCapabilities.EGL_KHR_image_base;

            MCEF.INSTANCE.LOGGER.info(
                "Checking EGL extensions for GPU acceleration support: EGL_EXT_image_dma_buf_import={}, EGL_KHR_image_base={}",
                hasDmabufImport,
                hasImageBase
            );
            if (!hasDmabufImport || !hasImageBase) {
                MCEF.INSTANCE.LOGGER.warn("Required EGL extensions for GPU acceleration not supported");
                return Support.UNSUPPORTED;
            }

            return new Support(true, false);
        } catch (Exception e) {
            MCEF.INSTANCE.LOGGER.warn("Failed to check Linux GPU acceleration support: {}", e.getMessage());
            return Support.UNSUPPORTED;
        }
    }

    private static boolean isNvidiaGpu(String vendor, String renderer) {
        var vendorLower = vendor.toLowerCase(Locale.ENGLISH);
        var rendererLower = renderer.toLowerCase(Locale.ENGLISH);
        return vendorLower.contains("nvidia")
            || rendererLower.contains("geforce")
            || rendererLower.contains("quadro");
    }

    private static boolean isAmdGpu(String vendor, String renderer) {
        var vendorLower = vendor.toLowerCase(Locale.ENGLISH);
        var rendererLower = renderer.toLowerCase(Locale.ENGLISH);
        return vendorLower.contains("amd")
            || rendererLower.contains("radeon");
    }
}
