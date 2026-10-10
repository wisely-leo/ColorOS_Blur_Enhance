package com.shortcutblur;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.RenderEffect;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;
import static com.shortcutblur.BlurLib.*;
final class RecentsBlur {
    private RecentsBlur() {}
    public interface HookApi {
        void hook(String id, Executable target, XposedInterface.Hooker hooker);
    }
    private static HookApi API;
    private static volatile boolean sStateInOverview = false;
    private static volatile float RECENTS_BLUR_MAX = 64.0f;

    private static volatile float sScaleClampMin = 0.92f;
    private static volatile long sConfLastRead = 0L;
    private static final String CONF_PATH = "/sdcard/Download/ColorOSBlurEnhance.conf";
    private static volatile boolean sAnchorUseWorkspace = false;
    private static volatile boolean sConfErrorLogged = false;
    private static volatile float sRecentsLastRadius = -1.0f;
    private static volatile float sRecentsAnimRadius = 0.0f;

    private static volatile java.lang.ref.WeakReference<View> sRecentsBlurView = null;
    private static volatile float sRecentsTargetRadius = -1.0f;
    private static volatile int sRecentsPhase = 0;
    private static volatile boolean sRecentsBlurDoneForEntry = false;
    private static final long ENTER_FADE_MS = 180L;
    private static final long EXIT_FADE_MS = 120L;
    private static volatile ValueAnimator sRecentsEnterAnim = null;
    private static volatile ValueAnimator sRecentsExitAnim = null;
    private static volatile boolean sRecentsArmed = false;
    private static Runnable sPendingArm = null;
    private static final android.os.Handler sRecentsHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static Runnable sPendingClear = null;
    private static volatile Runnable sPendingEnter = null;

    private static volatile long sWzLastLogMs = 0L;

    private static volatile boolean sWallpaperJudgeEnabled = true;

    private static volatile long sLastWallpaperHandledMs = 0L;
    private static final long WALLPAPER_MAIN_WINDOW_MS = 400L;

    private static final long STATE_FALLBACK_DELAY_MS = 250L;
    private static volatile boolean sTintEnabled = false;
    private static volatile java.lang.ref.WeakReference<View> sLastAnchor = null;
    private static volatile boolean sDiagEnabled = false;
    private static volatile java.lang.ref.WeakReference<View> sCachedDragLayer = null;
    private static volatile Runnable sPendingExit = null;
    private static volatile boolean sSelfAlphaCall = false;
    private static volatile boolean sForceIconBlur = false;

    private static final java.util.List<java.lang.ref.WeakReference<View>> sBlurTargets =
            new java.util.ArrayList<java.lang.ref.WeakReference<View>>();

    private static volatile java.lang.ref.WeakReference<Object> sRecentsViewObj = null;
    private static volatile String sBlurMode = "draglayer";
    private static volatile boolean sClampEnabled = true;
    private static void armBlurTargets(View anchor) {
        sBlurTargets.clear();
        if (anchor == null) return;
        String mode = sBlurMode;
        if ("workspace".equals(mode) || "draglayer".equals(mode) || !(anchor instanceof ViewGroup)) {
            sBlurTargets.add(new java.lang.ref.WeakReference<View>(anchor));
        } else {
            ViewGroup g = (ViewGroup) anchor;
            View rec = findRecentsChildOf(g);
            if (rec == null) {
                sBlurTargets.add(new java.lang.ref.WeakReference<View>(anchor));
            } else {
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c == null || c == rec) continue;
                    sBlurTargets.add(new java.lang.ref.WeakReference<View>(c));
                }
            }
        }
        ModuleLog.d("BLURTARGET", "mode=" + mode + " count=" + sBlurTargets.size()
                + " names=" + blurTargetNames());
    }
    private static View findRecentsChildOf(ViewGroup dragLayer) {
        Object rv = sRecentsViewObj == null ? null : sRecentsViewObj.get();
        if (!(rv instanceof View)) return null;
        View v = (View) rv;
        Object p = v.getParent();
        int guard = 0;
        while (p != null && guard++ < 32) {
            if (p == dragLayer) return v;
            if (!(p instanceof View)) return null;
            v = (View) p;
            p = v.getParent();
        }
        return null;
    }
    private static String blurTargetNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sBlurTargets.size(); i++) {
            java.lang.ref.WeakReference<View> ref = sBlurTargets.get(i);
            View t = ref == null ? null : ref.get();
            sb.append(t == null ? "null" : t.getClass().getSimpleName()).append(' ');
        }
        return sb.toString();
    }

    private static boolean hasLiveBlurTarget() {
        for (int i = 0; i < sBlurTargets.size(); i++) {
            java.lang.ref.WeakReference<View> ref = sBlurTargets.get(i);
            if (ref != null && ref.get() != null) return true;
        }
        return false;
    }
    private static void dumpDragLayerTree(View anchor) {
        try {
            if (!(anchor instanceof ViewGroup)) {
                ModuleLog.d("TREE", "anchor not ViewGroup: " + anchor.getClass().getSimpleName());
                return;
            }
            ViewGroup g = (ViewGroup) anchor;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                sb.append('[').append(i).append(']');
                if (c == null) { sb.append("null "); continue; }
                sb.append(c.getClass().getSimpleName())
                  .append("{a=").append(c.getAlpha())
                  .append(",v=").append(c.getVisibility()).append("} ");
            }
            ModuleLog.d("TREE", "anchor=" + anchor.getClass().getSimpleName()
                    + " children=" + g.getChildCount() + " :: " + sb);
        } catch (Throwable t) {
            ModuleLog.e("TREE", "dump failed", t);
        }
    }
    private static void startDrawProbe(final View anchor) {
        if (anchor == null) return;
        try {
            final android.view.ViewTreeObserver vto = anchor.getViewTreeObserver();
            final int[] cnt = new int[1];
            final android.view.ViewTreeObserver.OnPreDrawListener l =
                    new android.view.ViewTreeObserver.OnPreDrawListener() {
                        @Override public boolean onPreDraw() { cnt[0]++; return true; }
                    };
            vto.addOnPreDrawListener(l);
            sRecentsHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    try { vto.removeOnPreDrawListener(l); } catch (Throwable ignore) {}
                    ModuleLog.d("DRAW", "preDraw within 700ms = " + cnt[0]
                            + (cnt[0] == 0 ? " (SNAPSHOT? desktop not redrawn)" : " (live)"));
                }
            }, 700L);
        } catch (Throwable t) {
            ModuleLog.e("DRAW", "probe failed", t);
        }
    }
    private static void startPixelProbe(final View anchor, final String tag) {
        if (anchor == null) return;
        final long[] delays = {40L, 100L, 200L, 350L, 600L, 1200L};
        for (int i = 0; i < delays.length; i++) {
            final long d = delays[i];
            sRecentsHandler.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        int ww = anchor.getWidth(), hh = anchor.getHeight();
                        if (ww < 80 || hh < 80) return;
                        int cw = Math.min(420, ww);
                        int ch = Math.min((int) (hh * 0.32f), hh - 10);
                        int x0 = (ww - cw) / 2;
                        int y0 = (int) (hh * 0.25f);
                        if (y0 + ch > hh) y0 = hh - ch;
                        android.view.Window win = resolveWindow(anchor);
                        if (win == null) { ModuleLog.d("PIXEL", tag + " +" + d + "ms no window"); return; }
                        int[] loc = new int[2];
                        anchor.getLocationInWindow(loc);
                        final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                                cw, ch, android.graphics.Bitmap.Config.ARGB_8888);
                        android.view.PixelCopy.request(win,
                                new android.graphics.Rect(loc[0] + x0, loc[1] + y0, loc[0] + x0 + cw, loc[1] + y0 + ch), bmp,
                                new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                                    @Override public void onPixelCopyFinished(int result) {
                                        try {
                                            if (result != android.view.PixelCopy.SUCCESS) {
                                                ModuleLog.d("PIXEL", tag + " +" + d + "ms copyResult=" + result);
                                                bmp.recycle();
                                                return;
                                            }
                                            int w2 = bmp.getWidth(), h2 = bmp.getHeight();
                                            int[] pix = new int[w2 * h2];
                                            bmp.getPixels(pix, 0, w2, 0, 0, w2, h2);
                                            bmp.recycle();
                                            long sum = 0; int cnt = 0; int strong = 0; int mx = 0;
                                            for (int y = 0; y < h2; y += 2) {
                                                int prev = 0;
                                                for (int x = 0; x < w2; x += 2) {
                                                    int p = pix[y * w2 + x];
                                                    int g = ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF);
                                                    if (x > 0) {
                                                        int dd = Math.abs(g - prev);
                                                        sum += dd; cnt++;
                                                        if (dd > 45) strong++;
                                                        if (dd > mx) mx = dd;
                                                    }
                                                    prev = g;
                                                }
                                            }
                                            ModuleLog.d("PIXEL", tag + " +" + d + "ms mean="
                                                    + (cnt > 0 ? (sum / cnt) : -1)
                                                    + " strongEdges=" + strong + " max=" + mx + " ov=" + sStateInOverview);
                                        } catch (Throwable t) {
                                            ModuleLog.e("PIXEL", "calc failed", t);
                                        }
                                    }
                                }, sRecentsHandler);
                    } catch (Throwable t) {
                        ModuleLog.e("PIXEL", "request failed", t);
                    }
                }
            }, d);
        }
    }
    private static int installEarlySignalProbes(ClassLoader loader) {
        int n = 0;

        try {
            Class<?> odc = Reflect.loadClass(
                    "com.android.launcher3.uioverrides.states.OplusDepthController", loader);
            if (odc == null) {
                ModuleLog.d("WZOOM", "[miss] OplusDepthController");
            } else {
                int c = 0;
                for (java.lang.reflect.Method m : odc.getDeclaredMethods()) {
                    if (!m.getName().equals("startWallpaperAnimation")) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length != 2 || pt[0] != boolean.class
                            || pt[1] != String.class) continue;
                    Reflect.setAccessible(m);
                    API.hook("probe.wallpaperAnim", (Executable) m, new XposedInterface.Hooker() {
                                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    try {
                                        Object[] a = chain.getArgs().toArray();
                                        String type = (a.length > 1 && a[1] != null)
                                                ? String.valueOf(a[1]) : "?";

                                        if (sWallpaperJudgeEnabled) {
                                            boolean enter = "app_recent_enter".equals(type);
                                            boolean exit = !enter && "app_recent_exit".equals(type);
                                            if (enter || exit) {
                                                long now = android.os.SystemClock.uptimeMillis();
                                                ModuleLog.d("WZOOM", "startWallpaperAnim type=" + type
                                                        + " inOverview=" + sStateInOverview
                                                        + " dt=" + (now - sWzLastLogMs) + "ms");
                                                sWzLastLogMs = now;
                                                sLastWallpaperHandledMs = now;
                                                if (enter) {
                                                    cancelPendingExit();
                                                    enterOverviewFrom(chain.getThisObject(), "wzoom:enter");
                                                } else {
                                                    cancelPendingEnter();
                                                    exitOverviewFrom(chain.getThisObject(), "wzoom:exit");
                                                }
                                            }
                                        }
                                    } catch (Throwable ignore) {}
                                    return chain.proceed();
                                }
                            });
                    c++; n++;
                }
                ModuleLog.d("WZOOM", "[hook] OplusDepthController.startWallpaperAnimation(Z,String) x" + c);
            }
        } catch (Throwable t) {
            ModuleLog.e("WZOOM", "hook startWallpaperAnimation failed", t);
        }
        return n;
    }
    private static void exitOverviewFrom(Object lrvSelf, String why) {
        if (!sStateInOverview) return;
        sStateInOverview = false;
        cancelPendingEnter();
        View anchor = resolveBlurAnchor(lrvSelf);
        ModuleLog.d("STATEBLUR", "EXIT overview (" + why + ") -> startExitFadeOut anchor=" + anchorName(anchor));
        cancelPendingRecentsClear();
        if (anchor != null) {
            startExitFadeOut(anchor, why);
        }
    }
    private static void enterOverviewFrom(Object lrvSelf, String why) {
        if (sStateInOverview) return;
        View anchor = resolveBlurAnchor(lrvSelf);
        if (anchor == null) {
            ModuleLog.d("STATEBLUR", "ENTER skipped (anchor null) why=" + why);
            return;
        }
        sStateInOverview = true;
        ModuleLog.d("STATEBLUR", "ENTER overview (" + why + ") -> startEnterFadeIn anchor=" + anchorName(anchor));
        cancelPendingRecentsClear();
        sRecentsPhase = 1;
        startEnterFadeIn(anchor);
    }
    private static void cancelPendingEnter() {
        Runnable r = sPendingEnter;
        if (r != null) {
            try { sRecentsHandler.removeCallbacks(r); } catch (Throwable ignore) {}
            sPendingEnter = null;
        }
    }
    private static void cancelPendingExit() {
        Runnable r = sPendingExit;
        if (r != null) {
            try { sRecentsHandler.removeCallbacks(r); } catch (Throwable ignore) {}
            sPendingExit = null;
        }
    }

    private static void stateFallbackEnter(Object lrvSelf) {
        if (sStateInOverview) return;
        final java.lang.ref.WeakReference<Object> ref = new java.lang.ref.WeakReference<>(lrvSelf);
        long since = android.os.SystemClock.uptimeMillis() - sLastWallpaperHandledMs;
        if (since < WALLPAPER_MAIN_WINDOW_MS) {
            ModuleLog.d("STATEBLUR", "state-enter skipped (wallpaper handled " + since + "ms ago)");
            return;
        }
        cancelPendingEnter();
        Runnable r = new Runnable() {
            @Override public void run() {
                sPendingEnter = null;
                if (sStateInOverview) return;
                long s2 = android.os.SystemClock.uptimeMillis() - sLastWallpaperHandledMs;
                if (s2 < WALLPAPER_MAIN_WINDOW_MS) {
                    ModuleLog.d("STATEBLUR", "state-enter fallback skipped (wallpaper took over)");
                    return;
                }
                ModuleLog.d("STATEBLUR", "state-enter fallback fired");
                enterOverviewFrom(ref.get(), "state-fallback:enter");
            }
        };
        sPendingEnter = r;
        try { sRecentsHandler.postDelayed(r, STATE_FALLBACK_DELAY_MS); } catch (Throwable ignore) {}
    }
    private static void stateFallbackExit(Object lrvSelf, final String why) {
        if (!sStateInOverview) return;
        final java.lang.ref.WeakReference<Object> ref = new java.lang.ref.WeakReference<>(lrvSelf);
        long since = android.os.SystemClock.uptimeMillis() - sLastWallpaperHandledMs;
        if (since < WALLPAPER_MAIN_WINDOW_MS) {
            ModuleLog.d("STATEBLUR", "state-exit skipped (wallpaper handled " + since + "ms ago) why=" + why);
            return;
        }
        cancelPendingExit();
        Runnable r = new Runnable() {
            @Override public void run() {
                sPendingExit = null;
                if (!sStateInOverview) return;
                long s2 = android.os.SystemClock.uptimeMillis() - sLastWallpaperHandledMs;
                if (s2 < WALLPAPER_MAIN_WINDOW_MS) {
                    ModuleLog.d("STATEBLUR", "state-exit fallback skipped (wallpaper took over) why=" + why);
                    return;
                }
                ModuleLog.d("STATEBLUR", "state-exit fallback fired why=" + why);
                exitOverviewFrom(ref.get(), "state-fallback:" + why);
            }
        };
        sPendingExit = r;
        try { sRecentsHandler.postDelayed(r, STATE_FALLBACK_DELAY_MS); } catch (Throwable ignore) {}
    }
    private static void cancelPendingRecentsClear() {
        Runnable r = sPendingClear;
        if (r != null) {
            try { sRecentsHandler.removeCallbacks(r); } catch (Throwable ignore) {}
            sPendingClear = null;
        }
    }
    private static void startEnterFadeIn(final View v) {
        ValueAnimator old = sRecentsEnterAnim;
        if (old != null) { try { old.cancel(); } catch (Throwable ignore) {} sRecentsEnterAnim = null; }
        ValueAnimator oldX = sRecentsExitAnim;
        if (oldX != null) { try { oldX.cancel(); } catch (Throwable ignore) {} sRecentsExitAnim = null; }
        sRecentsBlurView = new java.lang.ref.WeakReference<>(v);
        armBlurTargets(v);
        if (sDiagEnabled) {
            dumpDragLayerTree(v);
            startDrawProbe(v);
            startPixelProbe(v, "enter");
        }
        sRecentsAnimRadius = 0.0f;
        sRecentsLastRadius = 0.0f;
        final ValueAnimator va = ValueAnimator.ofFloat(0.0f, RECENTS_BLUR_MAX);
        va.setDuration(ENTER_FADE_MS);
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float r = ((Number) a.getAnimatedValue()).floatValue();
                sRecentsAnimRadius = r;
                sRecentsLastRadius = r;
                applySelfBlur(v, r);
            }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                        if (!sStateInOverview) {
                            ModuleLog.d("DRAGALPHA", "enter anim end skipped (exit already started)");
                            return;
                        }
                        sRecentsAnimRadius = RECENTS_BLUR_MAX;
                        sRecentsLastRadius = RECENTS_BLUR_MAX;
                        applySelfBlur(v, RECENTS_BLUR_MAX);
                        if (sRecentsPhase == 1) {
                            sRecentsPhase = 2;
                            ModuleLog.d("DRAGALPHA", "phase ENTERING -> IN_RECENTS (fade-in done)");
                        }
                    }
        });
        sRecentsEnterAnim = va;
        try { va.start(); } catch (Throwable t) { ModuleLog.e("DRAGALPHA", "enter anim start failed", t); }
        ModuleLog.d("DRAGALPHA", "enter fade-in started (0 -> " + RECENTS_BLUR_MAX + ")");
    }
    private static void clearPendingArm() {
        if (sPendingArm != null) {
            try { sRecentsHandler.removeCallbacks(sPendingArm); } catch (Throwable ignore) {}
            sPendingArm = null;
        }
    }
    private static void startExitFadeOut(final View v, final String why) {
        final float from = Math.max(0.0f, sRecentsLastRadius);
        ValueAnimator oldE = sRecentsEnterAnim;
        if (oldE != null) { try { oldE.cancel(); } catch (Throwable ignore) {} sRecentsEnterAnim = null; }
        ValueAnimator oldX = sRecentsExitAnim;
        if (oldX != null) { try { oldX.cancel(); } catch (Throwable ignore) {} sRecentsExitAnim = null; }
        sRecentsPhase = 3;
        final ValueAnimator va = ValueAnimator.ofFloat(from, 0.0f);
        va.setDuration(EXIT_FADE_MS);
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float r = ((Number) a.getAnimatedValue()).floatValue();
                sRecentsAnimRadius = r;
                sRecentsLastRadius = r;
                applySelfBlur(v, r);
            }
        });
        va.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (sStateInOverview) {
                    ModuleLog.d("DRAGALPHA", "exit anim end skipped (enter already started)");
                    return;
                }
                sRecentsExitAnim = null;
                sRecentsAnimRadius = 0.0f;
                sRecentsLastRadius = -1.0f;
                sRecentsBlurView = null;
                sRecentsTargetRadius = -1.0f;
                sRecentsPhase = 0;
                sRecentsBlurDoneForEntry = false;
                sRecentsArmed = false;
                clearPendingArm();

                sBlurTargets.clear();
                if (v != null) { try { v.setRenderEffect(null); } catch (Throwable ignore) {} }
                ModuleLog.d("DRAGALPHA", why + " -> exit fade-out done (phase=IDLE)");
            }
        });
        sRecentsExitAnim = va;
        try { va.start(); } catch (Throwable t) { ModuleLog.e("DRAGALPHA", "exit anim start failed", t); }
        ModuleLog.d("DRAGALPHA", "exit fade-out started (" + from + " -> 0, why=" + why + ")");
        if (sDiagEnabled) startPixelProbe(v, "exit");
    }
    private static void applySelfBlur(View v, float r) {
        sRecentsTargetRadius = r;
        if (!hasLiveBlurTarget()) {
            blurOne(v, r);
        } else {
            for (int i = 0; i < sBlurTargets.size(); i++) {
                java.lang.ref.WeakReference<View> ref = sBlurTargets.get(i);
                View t = ref == null ? null : ref.get();
                if (t != null) blurOne(t, r);
            }
        }
    }
    private static void blurOne(View v, float r) {
        if (v == null) return;
        sRecentsBlurView = new java.lang.ref.WeakReference<>(v);
        sRecentsTargetRadius = r;
        if (sAnchorUseWorkspace && r > 0.5f) {
            sSelfAlphaCall = true;
            try { v.setAlpha(1.0f); } catch (Throwable ignore) {}
            sSelfAlphaCall = false;
        }
        float rApplied = r;
        try {
            if (rApplied > 0.5f && sTintEnabled) {
                RenderEffect fx = RenderEffect.createColorFilterEffect(
                        new android.graphics.BlendModeColorFilter(
                                0xB0FF0000, android.graphics.BlendMode.SRC_ATOP));
                ModuleLog.d("TINT", "colorFilter on=" + v.getClass().getSimpleName());
                v.setRenderEffect(fx);
            } else {
                BlurLib.setBlurRadius(v, rApplied);
            }
            ModuleLog.dv("BLURAPPLY", "r=" + rApplied + " on=" + v.getClass().getSimpleName()
                    + " setOk=true");
        } catch (Throwable t) {
            ModuleLog.e("BLURAPPLY", "setRenderEffect failed r=" + rApplied, t);
        }
    }

    private static volatile boolean sProbesInstalled = false;
    private static int installRecentsIconBlurProbe(ClassLoader loader) {
        if (sProbesInstalled) return 0;
        sProbesInstalled = true;
        int n = 0;
        installEarlySignalProbes(loader);
        try {
            Class<?> cls = Reflect.loadClass(CLS_WORKSPACE_SCRIM, loader);
            if (cls == null) {
                ModuleLog.d("RECENTS", "[miss] " + CLS_WORKSPACE_SCRIM);
                return 0;
            }
            for (Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals("supportIconBlur")) continue;
                if (m.getParameterTypes().length != 0) continue;
                if (m.getReturnType() != boolean.class) continue;
                Reflect.setAccessible(m);
                API.hook("recents.supportIconBlur", (Executable) m, new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object raw = chain.proceed();
                                boolean orig = (raw instanceof Boolean) && ((Boolean) raw).booleanValue();
                                if (sForceIconBlur) {
                                    ModuleLog.d("RECENTS", "supportIconBlur() orig=" + orig + " -> FORCE true");
                                    return Boolean.TRUE;
                                }
                                ModuleLog.d("RECENTS", "supportIconBlur() orig=" + orig + " -> pass-through (iconblur=off)");
                                return raw;
                            }
                        });
                n++;
                ModuleLog.d("RECENTS", "[hook] supportIconBlur -> switchable (default force true)");
            }
            if (n == 0) ModuleLog.d("RECENTS", "[miss] supportIconBlur() not found");
        try {
            Class<?> dl = Reflect.loadClass(CLS_OPLUS_DRAGLAYER, loader);
            if (dl == null) {
                ModuleLog.d("RECENTS", "[miss] " + CLS_OPLUS_DRAGLAYER);
            } else {
                for (Method mm : dl.getDeclaredMethods()) {
                    if (!mm.getName().equals("setAlpha")) continue;
                    Class<?>[] pt = mm.getParameterTypes();
                    if (pt.length != 1 || pt[0] != float.class) continue;
                    Reflect.setAccessible(mm);
                    API.hook("recents.dragAlpha", (Executable) mm, new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object self = chain.getThisObject();
                                    Object[] args = chain.getArgs().toArray();
                                    float a = (args.length > 0 && args[0] instanceof Number)
                                            ? ((Number) args[0]).floatValue() : 1.0f;
                                    try {
                                        if (self instanceof View) {
                                            View v = (View) self;
                                            sCachedDragLayer = new java.lang.ref.WeakReference<>(v);
                                            if (a < 0.999f) {

                                                if (ModuleLog.VERBOSE) {
                                                    ModuleLog.dv("DRAGALPHA", "setAlpha a=" + a
                                                            + " cur=" + v.getAlpha()
                                                            + " -> clamp1.0 (keep DragLayer opaque)");
                                                }
                                                args[0] = 1.0f;
                                                return chain.proceed(args);
                                            }
                                        }
                                    } catch (Throwable t) {
                                        ModuleLog.e("DRAGALPHA", "intercept failed", t);
                                    }
                                    return chain.proceed();
                                }
                            });
                    n++;
                    ModuleLog.d("RECENTS", "[hook] OplusDragLayer.setAlpha -> intercept");
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("RECENTS", "hook dragLayer alpha failed", t);
        }
        try {
            Class<?> dls = Reflect.loadClass(CLS_OPLUS_DRAGLAYER, loader);
            if (dls != null) {
                for (String sn : new String[]{"setScaleX", "setScaleY"}) {
                    for (Method sm : dls.getDeclaredMethods()) {
                        if (!sm.getName().equals(sn)) continue;
                        Class<?>[] pt = sm.getParameterTypes();
                        if (pt.length != 1 || pt[0] != float.class) continue;
                        Reflect.setAccessible(sm);
                        final String tag = sn;
                        API.hook("recents.scaleclamp." + sn, (Executable) sm, new XposedInterface.Hooker() {
                                    @Override
                                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                        Object self = chain.getThisObject();
                                        if (self instanceof View) sCachedDragLayer = new java.lang.ref.WeakReference<>((View) self);
                                        Object[] a = chain.getArgs().toArray();
                                        if (a.length > 0 && a[0] instanceof Number) {
                                            float f = ((Number) a[0]).floatValue();
                                            reloadConfigIfStale();
                                            float clamped = sClampEnabled ? Math.max(f, sScaleClampMin) : f;
                                            if (clamped != f) {

                                                if (ModuleLog.VERBOSE) {
                                                    ModuleLog.dv("SCALECLAMP", tag + " " + f + " -> " + clamped);
                                                }
                                                a[0] = Float.valueOf(clamped);
                                            }
                                            return chain.proceed(a);
                                        }
                                        return chain.proceed();
                                    }
                                });
                        n++;
                        ModuleLog.d("RECENTS", "[hook] scale clamp " + sn);
                    }
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("RECENTS", "hook scale clamp failed", t);
        }
        } catch (Throwable t) {
            ModuleLog.e("RECENTS", "installRecentsIconBlurProbe failed", t);
        }
        return n;
    }

    private static volatile boolean sStateHooksInstalled = false;
    private static int installRecentsStateBlurHooks(ClassLoader loader) {
        if (sStateHooksInstalled) return 0;
        sStateHooksInstalled = true;
        int n = 0;
        try {
            Class<?> lrv = Reflect.loadClass(CLS_LAUNCHER_RECENTS_VIEW, loader);
            Class<?> ls = Reflect.loadClass(CLS_LAUNCHER_STATE, loader);
            if (lrv == null || ls == null) {
                ModuleLog.d("STATEBLUR", "[miss] LRV or LauncherState");
                return 0;
            }
            for (Method m : lrv.getDeclaredMethods()) {
                if (!m.getName().equals(M_ON_STATE_TRANSITION_START)) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 1 || pt[0] != ls) continue;
                Reflect.setAccessible(m);
                API.hook("recents.state.start", (Executable) m, new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object self = chain.getThisObject();
                                Object[] a = chain.getArgs().toArray();
                                Object toState = (a.length > 0) ? a[0] : null;
                                sRecentsViewObj = new java.lang.ref.WeakReference<>(self);
                                boolean toOverview = isOverviewState(toState);
                                boolean toNormal = isNormalState(toState);
                                ModuleLog.d("STATEBLUR", "onStateTransitionStart toState="
                                        + stateName(toState) + " toOverview=" + toOverview
                                        + " toNormal=" + toNormal);

                                if (toOverview) {
                                    stateFallbackEnter(self);
                                } else if (toNormal) {
                                    stateFallbackExit(self, "stateStart:NORMAL");
                                }
                                return chain.proceed();
                            }
                        });
                n++;
                ModuleLog.d("STATEBLUR", "[hook] LRV.onStateTransitionStart");
            }
            for (Method m : lrv.getDeclaredMethods()) {
                if (!m.getName().equals(M_ON_STATE_TRANSITION_COMPLETE)) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 1 || pt[0] != ls) continue;
                Reflect.setAccessible(m);
                API.hook("recents.state.complete", (Executable) m, new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object self = chain.getThisObject();
                                Object[] a = chain.getArgs().toArray();
                                Object finalState = (a.length > 0) ? a[0] : null;
                                boolean toNormal = isNormalState(finalState);
                                ModuleLog.d("STATEBLUR", "onStateTransitionComplete finalState="
                                        + stateName(finalState) + " toNormal=" + toNormal
                                        + " inOverview=" + sStateInOverview);

                                if (toNormal && sStateInOverview) {
                                    stateFallbackExit(self, "stateComplete:NORMAL");
                                }
                                return chain.proceed();
                            }
                        });
                n++;
                ModuleLog.d("STATEBLUR", "[hook] LRV.onStateTransitionComplete");
            }
            for (Method m : lrv.getDeclaredMethods()) {
                if (!m.getName().equals(M_ON_STATE_TRANSITION_CANCEL)) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 1) continue;
                Reflect.setAccessible(m);
                API.hook("recents.state.cancel", (Executable) m, new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object self = chain.getThisObject();
                                ModuleLog.d("STATEBLUR", "onStateTransitionCancel inOverview=" + sStateInOverview);

                                if (sStateInOverview) {
                                    stateFallbackExit(self, "stateCancel");
                                }
                                return chain.proceed();
                            }
                        });
                n++;
                ModuleLog.d("STATEBLUR", "[hook] LRV.onStateTransitionCancel");
            }
        } catch (Throwable t) {
            ModuleLog.e("STATEBLUR", "installRecentsStateBlurHooks failed", t);
        }
        return n;
    }
    private static View useAnchor(View v) {
        if (v != null) sLastAnchor = new java.lang.ref.WeakReference<>(v);
        return v;
    }
    private static View resolveBlurAnchor(Object lrvSelf) {
        try {
            View cached = sRecentsBlurView == null ? null : sRecentsBlurView.get();
            if (cached != null) return useAnchor(cached);
            View last = anchoredOf(sLastAnchor);
            if (last != null) {
                try {
                    if (last.isAttachedToWindow()) return last;
                } catch (Throwable ignore) {}
            }
            Object launcher = null;
            try {
                if (lrvSelf instanceof View) launcher = getLauncherQuietly((View) lrvSelf);
            } catch (Throwable ignore) {}
            if (launcher != null && sAnchorUseWorkspace) {
            Object ws = Reflect.call(launcher, "getWorkspace", 0);
            if (ws instanceof View) {
                ModuleLog.d("ANCHOR", "use Workspace (anchorMode=workspace) " + anchorName((View) ws));
                return useAnchor((View) ws);
            }
            ModuleLog.d("ANCHOR", "anchorMode=workspace but getWorkspace() null -> fallback DragLayer");
        }
            if (launcher != null) {
                Object dl = Reflect.call(launcher, "getDragLayer", 0);
                if (dl instanceof View) return useAnchor((View) dl);
            }
            if (lrvSelf instanceof View) {
                View v = (View) lrvSelf;
                if (v.getParent() instanceof View) return useAnchor((View) v.getParent());
                return useAnchor(v);
            }
            View cdl = anchoredOf(sCachedDragLayer);
            if (cdl != null) {
                try { if (cdl.isAttachedToWindow()) return useAnchor(cdl); } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
        ModuleLog.d("ANCHOR", "[v21] resolveBlurAnchor FAILED lrvSelf="
                + (lrvSelf == null ? "null" : lrvSelf.getClass().getSimpleName())
                + " lastAnchor=" + (anchoredOf(sLastAnchor) == null ? "null" : "detached"));
        return null;
    }

    private static View anchoredOf(java.lang.ref.WeakReference<View> ref) {
        return ref == null ? null : ref.get();
    }
    static int installProbes(ClassLoader loader, HookApi api) {
        API = api;
        return installRecentsIconBlurProbe(loader);
    }
    static int installStateHooks(ClassLoader loader, HookApi api) {
        API = api;
        return installRecentsStateBlurHooks(loader);
    }
    static void applyConf(Intent i) {
        if (i == null) return;
        try {
            boolean anchorChanged = false;
            String sm = i.getStringExtra("scale_min");
            if (sm != null) { float v = Float.parseFloat(sm.trim()); if (v > 0.5f && v <= 1.0f) sScaleClampMin = v; }
            String an = i.getStringExtra("anchor");
            if (an != null) { boolean ws = an.trim().equalsIgnoreCase("workspace"); if (ws != sAnchorUseWorkspace) anchorChanged = true; sAnchorUseWorkspace = ws; }
            String bm = i.getStringExtra("blur_max");
            if (bm != null) { float v = Float.parseFloat(bm.trim()); if (v >= 0f && v <= 200f) RECENTS_BLUR_MAX = v; }
            String ib = i.getStringExtra("iconblur");
            if (ib != null) sForceIconBlur = !ib.trim().equalsIgnoreCase("off");
            String md = i.getStringExtra("blur_mode");
            if (md != null && md.trim().length() > 0) { sBlurMode = md.trim(); anchorChanged = true; }
            String clv = i.getStringExtra("clamp");
            if (clv != null) sClampEnabled = !clv.trim().equalsIgnoreCase("off");
            String tn = i.getStringExtra("tint");
            if (tn != null) sTintEnabled = !tn.trim().equalsIgnoreCase("off");
            String dg = i.getStringExtra("diag");
            if (dg != null) sDiagEnabled = !dg.trim().equalsIgnoreCase("off");

            String wj = i.getStringExtra("walljudge");
            if (wj != null) sWallpaperJudgeEnabled = !wj.trim().equalsIgnoreCase("off");
            if (anchorChanged) {
                View old = sRecentsBlurView == null ? null : sRecentsBlurView.get();
                sRecentsBlurView = null;
                if (old != null) { try { applySelfBlur(old, 0f); } catch (Throwable ignore) {} }
            }
            ModuleLog.d("CONF", "LIVE scaleMin=" + sScaleClampMin + " anchorWS=" + sAnchorUseWorkspace
                    + " blurMax=" + RECENTS_BLUR_MAX + " mode=" + sBlurMode + " clamp=" + sClampEnabled
                    + " forceIconBlur=" + sForceIconBlur + (anchorChanged ? " (cache cleared)" : ""));
        } catch (Throwable t) {
            ModuleLog.e("CONF", "applyConf failed", t);
        }
    }
    static String describe() {
        return " | judge: walljudge=" + sWallpaperJudgeEnabled
                + " (main) state-fallback (delay=" + STATE_FALLBACK_DELAY_MS + "ms)"
                + " | clamp=" + sClampEnabled + "(>=" + sScaleClampMin + ")"
                + " | tint=" + sTintEnabled + " iconblur=" + sForceIconBlur
                + " | verbose=" + ModuleLog.VERBOSE + " diag=" + sDiagEnabled;
    }
    private static void reloadConfigIfStale() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sConfLastRead < 2000L) return;
        sConfLastRead = now;
        try {
            android.content.Context ctx = currentAppContext();
            if (ctx != null) {
                String v = android.provider.Settings.System.getString(ctx.getContentResolver(), "coloros_blur_scale_min");
                if (v != null) {
                    float f = Float.parseFloat(v.trim());
                    if (f > 0.5f && f < 1.0f) sScaleClampMin = f;
                }
                String m = android.provider.Settings.System.getString(ctx.getContentResolver(), "coloros_blur_anchor");
                if (m != null) sAnchorUseWorkspace = m.trim().equalsIgnoreCase("workspace");
            }
        } catch (Throwable ignore) {}
        try {
            java.io.File f = new java.io.File(CONF_PATH);
            if (!f.exists()) return;
            java.io.BufferedReader br = null;
            try {
                br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("scaleMin")) {
                        int i = line.indexOf('=');
                        if (i > 0) {
                            float v2 = Float.parseFloat(line.substring(i + 1).trim());
                            if (v2 > 0.5f && v2 < 1.0f) sScaleClampMin = v2;
                        }
                    } else if (line.startsWith("anchorMode")) {
                        int i = line.indexOf('=');
                        if (i > 0) sAnchorUseWorkspace = line.substring(i + 1).trim().equalsIgnoreCase("workspace");
                    }
                }
                sConfErrorLogged = false;
            } finally {

                if (br != null) { try { br.close(); } catch (Throwable ignored) {} }
            }
        } catch (Throwable t) {
            if (!sConfErrorLogged) {
                sConfErrorLogged = true;
                ModuleLog.e("CONF", "file conf unreadable (EACCES?) - fallback settings/default", t);
            }
        }
    }
}
