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

import org.cef.handler.CefAcceleratedPaintInfo;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
sealed interface AcceleratedPaintBackend extends AutoCloseable
        permits LinuxAcceleratedPaintBackend, MacAcceleratedPaintBackend, WindowsAcceleratedPaintBackend {

    boolean accepts(CefAcceleratedPaintInfo info);

    /**
     * Imports a platform frame as a temporary copy source. The renderer owns the final display texture.
     */
    @Nullable AcceleratedPaintFrame importFrame(CefAcceleratedPaintInfo info, int width, int height);

    @Override
    void close();

}
