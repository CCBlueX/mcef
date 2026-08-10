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

package net.ccbluex.liquidbounce.mcef.cursor;

import com.mojang.blaze3d.platform.cursor.CursorType;
import org.cef.misc.CefCursorType;

import java.util.EnumMap;
import java.util.Map;

public class MCEFCursorHelper {

    private static final Map<CefCursorType, CursorType> CEF_TO_B3D_CURSORS = new EnumMap<>(CefCursorType.class);

    /**
     * Helper method to get a {@link CursorType} for the given {@link CefCursorType} cursor type
     */
    public static CursorType getCursorType(CefCursorType cursorType) {
        return CEF_TO_B3D_CURSORS.computeIfAbsent(cursorType, k -> k.glfwId == 0
                ? CursorType.DEFAULT
                : CursorType.createStandardCursor(k.glfwId, "CEF-CursorType-" + k.name(), CursorType.DEFAULT));
    }

}
