package com.shortcutblur;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import static com.shortcutblur.Symbols.*;
public class WidgetBlurAttacher {
    private static final int TYPE_WIDGET = 7;
    private static final long ATTACH_RETRY_MS = 200L;
    // 发热优化：16 → 8。原 16 次 × 200ms = 3.2s 重试窗口，首次失败时在 3.2s 内
    // 排队 16 个 postDelayed（CPU 反复唤醒）。8 次（1.6s）足以覆盖"launcher 冷启动
    // 后容器尚未测量完成"的常见时序；仍失败则由 sGaveUp 记录，不再重试。
    private static final int ATTACH_MAX_RETRY = 8;
    private static final java.util.WeakHashMap<View, Boolean> sDone = new java.util.WeakHashMap<View, Boolean>();
    // host -> container：container 是 host 的子孙，持有 parent 引用会反向强引用 host，
    // 因此 value 也必须用弱引用，否则 WeakHashMap 的 entry 永远无法回收（View 树泄漏）。
    private static final java.util.WeakHashMap<View, java.lang.ref.WeakReference<View>> sDoneHost =
            new java.util.WeakHashMap<View, java.lang.ref.WeakReference<View>>();
    private static final java.util.WeakHashMap<View, Boolean> sGaveUp = new java.util.WeakHashMap<View, Boolean>();
    private static final java.util.WeakHashMap<View, Boolean> sEverHit = new java.util.WeakHashMap<View, Boolean>();

    private static View doneHostOf(View host) {
        java.lang.ref.WeakReference<View> ref;
        synchronized (sDoneHost) { ref = sDoneHost.get(host); }
        return ref == null ? null : ref.get();
    }

    private static void putDoneHost(View host, View container) {
        synchronized (sDoneHost) { sDoneHost.put(host, new java.lang.ref.WeakReference<>(container)); }
    }

    public static void attach(final String tag, final View root, final ClassLoader cl) {
        attach(tag, root, cl, 0);
    }

    public static void resetGiveUp(final View root) {
        if (root == null) return;
        try {

            synchronized (sGaveUp) { sGaveUp.remove(root); }
            View host = ViewUtils.ancestorOfType(root, "AppWidgetHostView");
            if (host != null) {
                synchronized (sGaveUp) { sGaveUp.remove(host); }
            }
        } catch (Throwable ignored) {}
    }

    private static void attach(final String tag, final View root, final ClassLoader cl, final int attempt) {
        if (root == null) return;
        try {
            View host = ViewUtils.ancestorOfType(root, "AppWidgetHostView");
            if (host == null) { ModuleLog.e("BW", tag + " host not found", null); return; }
            // 重试场景下若 root 已脱离窗口，重试无意义（避免持有 View 到超时）。
            if (attempt > 0) {
                try { if (!root.isAttachedToWindow()) return; } catch (Throwable ignore) {}
            }
            // 已绑定过同一 host 且仍有效 → 直接跳过（避免重复挂载）。
            View bound = doneHostOf(host);
            if (bound != null) {
                boolean already;
                synchronized (sDone) { already = Boolean.TRUE.equals(sDone.get(bound)); }
                if (already) return;
            }
            View containerEarly = ViewUtils.findByViewId(root, ClockIds.TARGET_ROOT);
            // 真我 (realme) 时钟：TARGET_ROOT(0x7f0a017b) 在真我上是 date_week_text（非容器），
            // 真正的模糊背景容器是 RM_CONTAINER(0x7f0a04b2，bg=widget_top_bg)。优先用它。
            View rmContainer = ViewUtils.findByViewId(root, ClockIds.RM_CONTAINER);
            if (rmContainer == null) {
                // 真我 (realme) 天气/日历横向小组件：根容器 weather_widget_layout
                rmContainer = ViewUtils.findByViewId(root, ClockIds.RM_W_LAYOUT);
                if (rmContainer != null) {
                    ModuleLog.d("BW", tag + " realme weather_widget_layout found");
                }
            }
            if (rmContainer != null) {
                containerEarly = rmContainer;
                ModuleLog.d("BW", tag + " realme container=0x"
                        + Integer.toHexString(containerEarly.getId()));
            }
            boolean hasT = (containerEarly != null);
            if (hasT) {
                synchronized (sEverHit) { sEverHit.put(root, Boolean.TRUE); }
                synchronized (sGaveUp) { sGaveUp.remove(root); }
            } else {
                boolean gaveUp;
                synchronized (sGaveUp) { gaveUp = Boolean.TRUE.equals(sGaveUp.get(root)); }
                if (gaveUp) {
                    return;
                }
                boolean everHit;
                synchronized (sEverHit) { everHit = Boolean.TRUE.equals(sEverHit.get(root)); }
                if (attempt >= ATTACH_MAX_RETRY && !everHit) {
                    synchronized (sGaveUp) { sGaveUp.put(root, Boolean.TRUE); }
                    return;
                }
            }
            Object launcher = ViewUtils.contextOfType(host.getContext(), CLS_LAUNCHER);
            if (launcher == null) { ModuleLog.e("BW", "no launcher", null); return; }
            View container = containerEarly;
            if (container == null) { container = ViewUtils.findByViewId(root, ClockIds.CONTAINER); }
            if (container == null) {
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
            if (container == host) {
                ModuleLog.e("BW", tag + " container==host, reject (would crash)", null);
                return;
            }
            if (container.getWidth() <= 0 || container.getHeight() <= 0) {
                if (attempt < ATTACH_MAX_RETRY && root != null) {
                    root.postDelayed(new Runnable() {
                        @Override public void run() { attach(tag, root, cl, attempt + 1); }
                    }, ATTACH_RETRY_MS);
                }
                return;
            }
            View promoted = promoteContainerIfNeeded(container, host);
            if (promoted != null) {
                ModuleLog.d("BW", "container promoted: "
                        + container.getClass().getSimpleName() + " " + container.getWidth() + "x" + container.getHeight()
                        + " -> " + promoted.getClass().getSimpleName() + " " + promoted.getWidth() + "x" + promoted.getHeight());
                container = promoted;
            }
            // 统一"先 sDoneHost 后 sDone"的加锁顺序，避免与 doneHostOf 形成反向嵌套死锁。
            View boundNow = doneHostOf(host);   // 内部只用 sDoneHost 锁
            synchronized (sDone) {
                if (boundNow == container || Boolean.TRUE.equals(sDone.get(container))) {
                    return;
                }
                sDone.put(container, Boolean.TRUE);
            }
            putDoneHost(host, container);       // 内部只用 sDoneHost 锁
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
        if (g != excludeRoot) {
            int c = countTextIdsIn(g);
            boolean sized = g.getWidth() > 0 && g.getHeight() > 0;
            if (c >= 2 && sized) {
                int score = c * 100000 + (g.getWidth() * g.getHeight() / 1000);
                if (score > bestScore[0]) {
                    bestScore[0] = score;
                    best[0] = g;
                }
            }
            if (c >= total) return;
        }
        for (int i = 0; i < g.getChildCount(); i++) {
            View ch = g.getChildAt(i);
            if (ch instanceof ViewGroup) findBestRec((ViewGroup) ch, depth + 1, total, best, bestScore, excludeRoot);
        }
    }
    private static View promoteContainerIfNeeded(View container, View host) {
        try {
            if (container == null) return null;
            int baseCount = countTextIdsIn(container);
            int total = ClockIds.TEXT_IDS.length;
            if (baseCount >= total) return null;
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
