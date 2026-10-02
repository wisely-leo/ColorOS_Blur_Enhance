package com.shortcutblur;

import android.view.View;
import android.view.ViewGroup;

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
            if (host == null) { ModuleLog.e("BW", tag + " host not found", null); return; }

            // 【性能优化】已处理过的 host 直接返回，避免拖动组件时高频 updateAppWidget
            // 触发重复的全树扫描（findByViewId / findBestContentContainer）导致掉帧卡顿。
            synchronized (sDoneHost) {
                View bound = sDoneHost.get(host);
                if (bound != null && Boolean.TRUE.equals(sDone.get(bound))) return;
            }

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
                // 【自适应兜底】部分布局（如系统时钟"月日/周 + 天气/温度 + 时 + 分"四行竖排样式）
                // 不含 TARGET_ROOT / CONTAINER 固定 id。此时自动挑选"包含最多时钟文字元素"
                // 且尺寸合理的容器，保证模糊层可正常覆盖。
                container = findBestContentContainer(root, host);
                if (container != null) {
                    ModuleLog.d("BW", tag + " adaptive container=" + container.getClass().getName()
                            + " 0x" + Integer.toHexString(container.getId())
                            + " " + container.getWidth() + "x" + container.getHeight());
                }
            }
            if (container == null) {
                if (attempt < ATTACH_MAX_RETRY && root != null) {
                    root.postDelayed(new Runnable() {
                        @Override public void run() { attach(tag, root, cl, attempt + 1); }
                    }, ATTACH_RETRY_MS);
                }
                return;
            }
            // 【安全校验】模糊容器不能是 AppWidgetHostView 自身，也不能是未布局（0x0）的视图，
            // 否则对其做 blur 会导致桌面崩溃。
            if (container == host) {
                ModuleLog.e("BW", tag + " container==host, reject (would crash)", null);
                return;
            }
            if (container.getWidth() <= 0 || container.getHeight() <= 0) {
                // 尚未完成布局，稍后重试而不是直接用它。
                if (attempt < ATTACH_MAX_RETRY && root != null) {
                    root.postDelayed(new Runnable() {
                        @Override public void run() { attach(tag, root, cl, attempt + 1); }
                    }, ATTACH_RETRY_MS);
                }
                return;
            }
            // 【修复】若选中的 container 未覆盖全部文字元素（例如竖向时钟只选了内层"时间"容器），
            // 则向上提升到能覆盖更多文字元素的祖先容器，避免上下文字（星期/月日）落在模糊范围之外。
            View promoted = promoteContainerIfNeeded(container, host);
            if (promoted != null) {
                ModuleLog.d("BW", "container promoted: "
                        + container.getClass().getSimpleName() + " " + container.getWidth() + "x" + container.getHeight()
                        + " -> " + promoted.getClass().getSimpleName() + " " + promoted.getWidth() + "x" + promoted.getHeight());
                container = promoted;
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
    private static int countTextIdsIn(View v) {
        if (v == null) return 0;
        int n = 0;
        for (int id : ClockIds.TEXT_IDS) {
            try { if (v.findViewById(id) != null) n++; } catch (Throwable ignored) {}
        }
        return n;
    }

    /**
     * 自适应查找"内容容器"：遍历 AppWidgetHostView 子树，挑出包含时钟文字元素最多、
     * 且尺寸覆盖较完整的 ViewGroup 作为模糊容器。
     * 用于没有 TARGET_ROOT / CONTAINER 固定 id 的布局样式。
     */
    private static View findBestContentContainer(View root, View host) {
        try {
            View scanRoot = (host != null) ? host : root;
            if (!(scanRoot instanceof ViewGroup)) return null;
            final int total = ClockIds.TEXT_IDS.length;
            final View[] best = new View[]{null};
            final int[] bestScore = new int[]{0};
            findBestRec((ViewGroup) scanRoot, 0, total, best, bestScore, scanRoot);
            return best[0];
        } catch (Throwable t) {
            ModuleLog.e("BW", "findBestContentContainer fail", t);
            return null;
        }
    }

    private static void findBestRec(ViewGroup g, int depth, int total,
                                    View[] best, int[] bestScore, View excludeRoot) {
        if (g == null || depth > 12) return;
        // 绝不能让 AppWidgetHostView 本身（即扫描根）充当模糊容器：对它做 blur 会崩溃。
        if (g != excludeRoot) {
            int c = countTextIdsIn(g);
            boolean sized = g.getWidth() > 0 && g.getHeight() > 0;
            // 仅接受已完成布局（尺寸 > 0）、且覆盖至少 2 个文字元素的容器。
            if (c >= 2 && sized) {
                int score = c * 100000 + (g.getWidth() * g.getHeight() / 1000);
                if (score > bestScore[0]) {
                    bestScore[0] = score;
                    best[0] = g;
                }
            }
            // 若某容器已覆盖全部文字元素，无需继续下沉（避免选到过小的子容器）。
            if (c >= total) return;
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            View ch = g.getChildAt(i);
            if (ch instanceof ViewGroup) findBestRec((ViewGroup) ch, depth + 1, total, best, bestScore, excludeRoot);
        }
    }


    /**
     * 若当前 container 覆盖的文字元素明显少于其祖先（例如竖向时钟只选中了内层"时间"容器，
     * 导致星期/月日落在模糊范围之外），则向上提升到第一个"覆盖更多文字元素"的祖先容器。
     * 仅在祖先仍位于 AppWidgetHostView（host）之内时才提升。
     */
    private static View promoteContainerIfNeeded(View container, View host) {
        try {
            if (container == null) return null;
            int baseCount = countTextIdsIn(container);
            int total = ClockIds.TEXT_IDS.length;
            if (baseCount >= total) return null; // 已覆盖全部，无需提升

            View best = null;
            int bestCount = baseCount;
            android.view.ViewParent p = container.getParent();
            int guard = 0;
            while ((p instanceof View) && guard++ < 16) {
                View pv = (View) p;
                if (host != null && !ViewUtils.isDescendantOrSelf(host, pv)) break;
                int c = countTextIdsIn(pv);
                if (c > bestCount) { bestCount = c; best = pv; }
                if (c >= total) break;
                p = pv.getParent();
            }
            return best;
        } catch (Throwable t) {
            ModuleLog.e("BW", "promoteContainerIfNeeded fail", t);
            return null;
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
