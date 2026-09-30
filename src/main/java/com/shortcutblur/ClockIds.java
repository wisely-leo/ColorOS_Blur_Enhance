/*
 * ColorOS Blur Enhance —— ColorOS 16 桌面 / 多任务 / 时钟组件的动态模糊增强（LSPosed 模块）
 * Copyright (C) 2026 wisely-leo
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.shortcutblur;

public final class ClockIds {

    private ClockIds() {}

    public static final int HOUR     = 0x7f0a02cf;
    public static final int COLON    = 0x7f0a02c8;
    public static final int MINUTES  = 0x7f0a02d3;
    public static final int DATE     = 0x7f0a02ca;
    public static final int WEATHER  = 0x7f0a02da;
    public static final int WEEK     = 0x7f0a02db;
    public static final int WEATHER2 = 0x7f0a02d6;
    public static final int LUNAR    = 0x7f0a02cb;

    public static final int WEATHER_IMG1 = 0x7f0a02d9;
    public static final int WEATHER_IMG2 = 0x7f0a0276;

    public static final int TARGET_ROOT = 0x7f0a017b;

    public static final int CONTAINER = 0x7f0a02ab;

    public static final int[] TEXT_IDS = {
            HOUR, COLON, MINUTES, DATE, WEATHER, WEEK, WEATHER2, LUNAR
    };

    public static final int[] ICON_IDS = {
            WEATHER_IMG1, WEATHER_IMG2
    };
}
