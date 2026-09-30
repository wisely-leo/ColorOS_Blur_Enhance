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

import android.widget.RemoteViews;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedModule;

public final class ClockTextAlphaHook {

    private static final int TARGET_ALPHA = 0x4D;

    private static volatile boolean sInstalled = false;

    private ClockTextAlphaHook() {}

    private static boolean isClockTextId(int id) {
        for (int tid : ClockIds.TEXT_IDS) {
            if (tid == id) return true;
        }
        return false;
    }

    public static void install(XposedModule mod, ClassLoader cl) {
        if (sInstalled) return;
        synchronized (ClockTextAlphaHook.class) {
            if (sInstalled) return;
            sInstalled = true;
        }
        try {
            Class<?> rv = Class.forName("android.widget.RemoteViews", false, cl);
            Method m = rv.getDeclaredMethod("setTextColor", int.class, int.class);
            mod.hook(m).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(new HookColor());
            ModuleLog.d("CTCH", "hooked RemoteViews.setTextColor");
        } catch (Throwable t) {
            sInstalled = false;
            ModuleLog.e("CTCH", "hook fail", t);
        }
    }

    private static final class HookColor implements io.github.libxposed.api.XposedInterface.Hooker {
        @Override
        public Object intercept(Chain chain) throws Throwable {
            try {
                java.util.List<Object> args = chain.getArgs();
                if (args != null && args.size() >= 2) {
                    Object a0 = args.get(0);
                    Object a1 = args.get(1);
                    if (a0 instanceof Integer && a1 instanceof Integer) {
                        int id = (Integer) a0;
                        int color = (Integer) a1;
                        if (isClockTextId(id)) {
                            int newColor = (color & 0x00FFFFFF) | (TARGET_ALPHA << 24);
                            try { GlyphBlurRenderer.notifyContentMaybeChangedAll(); } catch (Throwable ignored) {}
                            ModuleLog.d("CTCH", "setTextColor id=0x" + Integer.toHexString(id)
                                + " color=0x" + Integer.toHexString(color)
                                + " -> 0x" + Integer.toHexString(newColor));

                            Object[] newArgs = new Object[args.size()];
                            for (int i = 0; i < args.size(); i++) newArgs[i] = args.get(i);
                            newArgs[1] = Integer.valueOf(newColor);
                            return chain.proceed(newArgs);
                        }
                    }
                }
            } catch (Throwable t) {
                ModuleLog.e("CTCH", "intercept fail", t);
            }
            return chain.proceed();
        }
    }
}
