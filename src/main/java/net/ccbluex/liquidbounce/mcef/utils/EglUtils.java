package net.ccbluex.liquidbounce.mcef.utils;

import net.ccbluex.liquidbounce.mcef.MCEF;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGL14;
import org.lwjgl.egl.EGLCapabilities;
import org.lwjgl.egl.KHRImageBase;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 *
 */
@NullMarked
public class EglUtils {

    private static @Nullable EGLCapabilities eglCapabilities = null;
    private static long eglDisplay = EGL14.EGL_NO_DISPLAY;

    public static long getDisplay() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            return eglDisplay;
        }

        long display = EGL14.eglGetCurrentDisplay();
        if (display == EGL14.EGL_NO_DISPLAY) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        }

        if (display == EGL14.EGL_NO_DISPLAY) {
            return EGL14.EGL_NO_DISPLAY;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer major = stack.mallocInt(1);
            IntBuffer minor = stack.mallocInt(1);
            if (!EGL14.eglInitialize(display, major, minor)) {
                MCEF.INSTANCE.LOGGER.error("eglInitialize failed for EGL display.");
                return EGL14.EGL_NO_DISPLAY;
            }
        }

        eglDisplay = display;

        try {
            EGL.getCapabilities();
        } catch (IllegalStateException ignored) {
            EGL.create();
        }
        eglCapabilities = EGL.createDisplayCapabilities(display);

        return eglDisplay;
    }

    public static EGLCapabilities getCapabilities() {
        if (eglCapabilities == null) {
            return EGL.getCapabilities();
        }
        return eglCapabilities;
    }

    /**
     * A copy of [{@link KHRImageBase#eglCreateImageKHR}] to bypass argument checks.
     */
    public static long eglCreateImageKHR(long display, long context, int target, long buffer, IntBuffer attribs) {
        long functionAddress = EGL.getCapabilities().eglCreateImageKHR;
        if (functionAddress == 0L) {
            MCEF.INSTANCE.LOGGER.error("eglCreateImageKHR is not available on this EGL implementation.");
            return 0L;
        }

        return JNI.callPPPPP(display, context, target, buffer, MemoryUtil.memAddress(attribs), functionAddress);
    }

}
