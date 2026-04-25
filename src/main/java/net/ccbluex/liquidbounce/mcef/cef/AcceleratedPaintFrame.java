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

import com.mojang.blaze3d.textures.GpuTexture;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
record AcceleratedPaintFrame(
        GpuTexture texture,
        boolean bgra,
        @Nullable Runnable releaseAction
) implements AutoCloseable {

    @Override
    public void close() {
        if (releaseAction != null) {
            releaseAction.run();
        }
    }

}
