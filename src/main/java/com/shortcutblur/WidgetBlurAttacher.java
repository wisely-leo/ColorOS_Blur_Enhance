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

import android.view.View;

import java.lang.reflect.Method;

public class WidgetBlurAttacher {

    private static final int TYPE_WIDGET = 7;

    private static final long ATTACH_RETRY_MS = 120L;
    private static final int ATTACH_MAX_RETRY = 8;

    private static final java.util.WeakHashMap<View, Boolean> sDone = new java.util.WeakHashMap<View, Boolean>();

    private static final java.util.WeakHashMap<View, View> sDoneHost = new java.util.WeakHashMap<View, View>();

    private static final java.util.WeakHashMap<View, Boolean> sGaveUp = new java.util.WeakHashMap<View, Boolean>();

    private static final java.util.WeakHashMap<View, Boolean> sEverHit = new java.util.WeakHashMap<View, Boolean>();

    public static void attach(final String tag, final View root, final ClassLoader cl) {
        attach(tag, root, cl, 0);
    }

    private static void attach(final String tag, final View root, final ClassLoader cl, final int attempt) {

        if (root == null) return;
        try {
            View host = ViewUtils.ancestorOfType(root, "AppWidgetHostView");
            View containerEarly = ViewUtils.findByViewId(root, ClockIds.TARGET_ROOT);
            boolean hasT = (containerEarly != null);
            if (hasT) {
                synchronized (sEverHit) { sEverHit.put(root, Boolean.TRUE); }
                synchronized (sGaveUp) { sGaveUp.remove(root); }
            } else {
                if (Boolean.TRUE.equals(sGaveUp.get(root))) return;
                if (attempt >= ATTACH_MAX_RETRY && !Boolean.TRUE.equals(sEverHit.get(root))) {
                    synchronized (sGaveUp) { sGaveUp.put(root, Boolean.TRUE); }
                    return;
                }
            }
            if (host == null) { ModuleLog.e("BW", tag + " host not found", null); return; }
            Object launcher = ViewUtils.contextOfType(host.getContext(), "com.android.launcher.Launcher");
            if (launcher == null) { ModuleLog.e("BW", "no launcher", null); return; }

            View container = containerEarly;
            if (container == null) { container = ViewUtils.findByViewId(root, ClockIds.CONTAINER); }
            if (container == null) {
                if (attempt < ATTACH_MAX_RETRY && root != null) {
                    root.postDelayed(new Runnable() {
                        @Override public void run() { attach(tag, root, cl, attempt + 1); }
                    }, ATTACH_RETRY_MS);
                }
                return;
            }
            synchronized (sDone) {
                View bound = sDoneHost.get(host);
                if (bound == container || Boolean.TRUE.equals(sDone.get(container))) {
                    return;
                }
                sDone.put(container, Boolean.TRUE);
                sDoneHost.put(host, container);
            }
            ModuleLog.d("BW", "container=" + container.getClass().getName() + " " + container.getWidth() + "x" + container.getHeight());
            try { container.setTag("OplusBlurBg"); } catch (Throwable t) { ModuleLog.e("BW", "setTag fail", t); }
            boolean ok = tryCardBlurManager(launcher, container);
            if (!ok) ok = tryCreateViaHost(host, container);
            ModuleLog.d("BW", "final ok=" + ok);
            GlyphBlurRenderer.attachGlyphBlur(container, container.getContext().getClassLoader());
        } catch (Throwable t) {
            ModuleLog.e("BW", "attach fail", t);
        }
    }

    private static boolean tryCardBlurManager(Object launcher, View target) {
        try {
            Object mgr = Reflect.call(launcher, "getCardBlurManager");
            if (mgr == null) { ModuleLog.e("BW", "cardBlurManager null", null); return false; }
            Method m = Reflect.method(mgr.getClass(), "createBlurForView", 3);
            if (m == null) { ModuleLog.e("BW", "createBlurForView not found", null); return false; }
            Object r = m.invoke(mgr, target, null, TYPE_WIDGET);
            ModuleLog.d("BW", "createBlurForView => " + r);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            ModuleLog.e("BW", "cardBlur fail", t);
            return false;
        }
    }

    private static boolean tryCreateViaHost(View host, View target) {
        try {
            Method m = Reflect.method(host.getClass(), "createBlurForView", 1);
            if (m == null) return false;
            m.invoke(host, target);
            ModuleLog.d("BW", "host.createBlurForView ok");
            return true;
        } catch (Throwable t) {
            ModuleLog.e("BW", "hostCreate fail", t);
            return false;
        }
    }

}
