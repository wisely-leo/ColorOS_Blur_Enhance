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
    private static volatile float sScaleClampMin = 0.96f;
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
    private static final long EXIT_DEBOUNCE_MS = 120L;
    private static final long ENTER_DEBOUNCE_MS = 80L;
    private static volatile Runnable sPendingEnter = null;
    private static volatile boolean sDescentEnterEnabled = false;
    private static volatile float sMinScaleSeen = 1.0f;
    private static volatile boolean sScaleExitEnabled = true;
    private static volatile boolean sTintEnabled = false;
    private static volatile boolean sGtsEnabled = true;
    private static volatile java.lang.ref.WeakReference<View> sLastAnchor = null;
    private static volatile boolean sDiagEnabled = false;
    private static volatile java.lang.ref.WeakReference<View> sCachedDragLayer = null;
    private static volatile boolean sVisExitEnabled = false;
    private static volatile boolean sScaleEnterEnabled = true;
    private static volatile float sEnterScale = 1.0f;
    private static volatile float sLastScale = 1.0f;
    private static volatile boolean sVisEnterEnabled = false;
    private static volatile Runnable sPendingExit = null;
    private static volatile float sLastAlphaIn = -1.0f;
    private static volatile float sMinAlphaIn = 1.0f;
    private static volatile boolean sSawAlphaDescent = false;
    private static volatile boolean sSelfAlphaCall = false;
    private static volatile boolean sAlphaExitEnabled = false;
    private static volatile String sLastVisKey = "";
    private static volatile String sLastFxKey = "";
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
        String[] sms = {"com.android.launcher3.statemanager.StateManager",
                        "com.android.launcher3.statemanager.StateManagerImpl"};
        for (String sn : sms) {
            try {
                Class<?> sm = Reflect.loadClass(sn, loader);
                if (sm == null) { ModuleLog.d("EARLY", "[miss] " + sn); continue; }
                int c = 0;
                for (java.lang.reflect.Method m : sm.getDeclaredMethods()) {
                    if (!m.getName().equals("goToState")) continue;
                    Reflect.setAccessible(m);
                    API.hook("early.goToState", (Executable) m, new XposedInterface.Hooker() {
                                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    try {
                                        Object[] a = chain.getArgs().toArray();
                                        Object st = (a.length > 0) ? a[0] : null;
                                        ModuleLog.d("EARLY", "goToState n=" + a.length + " to=" + stateName(st));
                                        if (sGtsEnabled && st != null) {
                                            Object gSelf = chain.getThisObject();
                                            if (isOverviewState(st)) {
                                                cancelPendingExit();
                                                enterOverviewFrom(gSelf, "gts:overview");
                                            } else if (isNormalState(st)) {
                                                exitOverviewFrom(gSelf, "gts:normal");
                                            }
                                        }
                                    } catch (Throwable ignore) {}
                                    return chain.proceed();
                                }
                            });
                    c++; n++;
                }
                ModuleLog.d("EARLY", "[hook] " + sn + ".goToState x" + c);
            } catch (Throwable t) {
                ModuleLog.e("EARLY", "hook " + sn + " failed", t);
            }
        }
        try {
            Class<?> anim = Reflect.loadClass("com.oplus.quickstep.anim.SwipeUpIconBlurAnim", loader);
            if (anim == null) {
                ModuleLog.d("EARLY", "[miss] SwipeUpIconBlurAnim");
            } else {
                int c = 0;
                for (java.lang.reflect.Constructor<?> ct : anim.getDeclaredConstructors()) {
                    Reflect.setAccessible(ct);
                    API.hook("early.swipeUpBlurAnim", (Executable) ct, new XposedInterface.Hooker() {
                                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    try { ModuleLog.d("EARLY", "SwipeUpIconBlurAnim ctor"); } catch (Throwable ignore) {}
                                    return chain.proceed();
                                }
                            });
                    c++; n++;
                }
                ModuleLog.d("EARLY", "[hook] SwipeUpIconBlurAnim ctors x" + c);
            }
        } catch (Throwable t) {
            ModuleLog.e("EARLY", "hook SwipeUpIconBlurAnim failed", t);
        }
        try {
            java.lang.reflect.Method va = Reflect.method(android.view.View.class, "onVisibilityAggregated", boolean.class);
            if (va != null) {
                Reflect.setAccessible(va);
                API.hook("early.visAgg", (Executable) va, new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                try {
                                    Object self = chain.getThisObject();
                                    if (self != null) {
                                        String cn = self.getClass().getName();
                                        if (cn.contains("Recents") || cn.contains("Overview")) {
                                            Object[] a = chain.getArgs().toArray();
                                            String v = (a.length > 0 && a[0] instanceof Boolean)
                                                    ? String.valueOf(a[0]) : "?";
                                            String key = cn + ":" + v;
                                            if (!key.equals(sLastVisKey)) {
                                                sLastVisKey = key;
                                                ModuleLog.d("EARLY", "visibilityAggregated " + cn + " visible=" + v);
                                            }
                                        }
                                    }
                                } catch (Throwable ignore) {}
                                return chain.proceed();
                            }
                        });
                n++;
                ModuleLog.d("EARLY", "[hook] View.onVisibilityAggregated (Recents/Overview filter)");
            } else {
                ModuleLog.d("EARLY", "[miss] onVisibilityAggregated");
            }
        } catch (Throwable t) {
            ModuleLog.e("EARLY", "hook visAgg failed", t);
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
        sMinAlphaIn = 1.0f;
        sSawAlphaDescent = false;
        sMinScaleSeen = 1.0f;
        ModuleLog.d("STATEBLUR", "ENTER overview (" + why + ") -> startEnterFadeIn anchor=" + anchorName(anchor));
        cancelPendingRecentsClear();
        sRecentsPhase = 1;
        startEnterFadeIn(anchor);
    }
    private static void scheduleEnterDebounced(final String why) {
        cancelPendingEnter();
        if (sStateInOverview) return;
        sPendingEnter = new Runnable() {
            @Override public void run() {
                sPendingEnter = null;
                if (sStateInOverview) return;
                ModuleLog.d("STATEBLUR", "enter debounce(" + ENTER_DEBOUNCE_MS + "ms) fired -> " + why);
                enterOverviewFrom(null, why);
            }
        };
        try { sRecentsHandler.postDelayed(sPendingEnter, ENTER_DEBOUNCE_MS); } catch (Throwable ignore) {}
    }
    private static void cancelPendingEnter() {
        Runnable r = sPendingEnter;
        if (r != null) {
            try { sRecentsHandler.removeCallbacks(r); } catch (Throwable ignore) {}
            sPendingEnter = null;
        }
    }
    private static void scheduleExitDebounced(final String why) {
        cancelPendingExit();
        if (!sStateInOverview) return;
        sPendingExit = new Runnable() {
            @Override public void run() {
                sPendingExit = null;
                if (!sStateInOverview) return;
                ModuleLog.d("STATEBLUR", "exit debounce(" + EXIT_DEBOUNCE_MS + "ms) fired -> " + why);
                exitOverviewFrom(null, why);
            }
        };
        try { sRecentsHandler.postDelayed(sPendingExit, EXIT_DEBOUNCE_MS); } catch (Throwable ignore) {}
    }
    private static void cancelPendingExit() {
        Runnable r = sPendingExit;
        if (r != null) {
            try { sRecentsHandler.removeCallbacks(r); } catch (Throwable ignore) {}
            sPendingExit = null;
        }
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
            java.lang.reflect.Method fx = Reflect.method(android.view.View.class, "setRenderEffect",
                    android.graphics.RenderEffect.class);
            if (fx != null) {
                Reflect.setAccessible(fx);
                API.hook("fx.setRenderEffect", (Executable) fx, new XposedInterface.Hooker() {
                            @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                try {
                                    Object self = chain.getThisObject();
                                    Object[] a = chain.getArgs().toArray();
                                    boolean isSet = (a.length > 0 && a[0] != null);
                                    String cn = (self == null) ? "?" : self.getClass().getSimpleName();
                                    String key = cn + (isSet ? ":set" : ":null");
                                    if (!key.equals(sLastFxKey)) {
                                        sLastFxKey = key;
                                        ModuleLog.d("FX", "setRenderEffect on=" + cn + " effect=" + (isSet ? "set" : "null"));
                                    }
                                } catch (Throwable ignore) {}
                                return chain.proceed();
                            }
                        });
                ModuleLog.d("FX", "[hook] View.setRenderEffect -> trace");
            } else {
                ModuleLog.d("FX", "[miss] View.setRenderEffect");
            }
        } catch (Throwable t) {
            ModuleLog.e("FX", "hook setRenderEffect failed", t);
        }
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
                                    float prevA = sLastAlphaIn;
                                    if (!sSelfAlphaCall) {
                                        sLastAlphaIn = a;
                                        if (sStateInOverview) {
                                            if (a < sMinAlphaIn) sMinAlphaIn = a;
                                            if (a < prevA - 0.02f) sSawAlphaDescent = true;
                                            if (sAlphaExitEnabled && sSawAlphaDescent && a > 0.25f && a > sMinAlphaIn + 0.15f) {
                                                ModuleLog.d("STATEBLUR", "alpha rising (min=" + sMinAlphaIn + " -> " + a + ") -> early exit");
                                                cancelPendingExit();
                                                exitOverviewFrom(null, "alphaRise");
                                            }
                                        }
                                    }
                                    if (!sSelfAlphaCall && !sStateInOverview && sDescentEnterEnabled
                                            && prevA >= 0.95f && a < 0.95f) {
                                        ModuleLog.d("STATEBLUR", "alpha falling (" + prevA + " -> " + a + ") -> early enter");
                                        cancelPendingExit();
                                        enterOverviewFrom(null, "alphaFall");
                                    }
                                    try {
                                        if (self instanceof View) {
                                            View v = (View) self;
                                            sCachedDragLayer = new java.lang.ref.WeakReference<>(v);
                                            float cur = v.getAlpha();
                                            ModuleLog.dv("DRAGALPHA", "setAlpha a=" + a + " cur=" + cur);
                                            if (a < 0.999f) {
                                                ModuleLog.dv("DRAGALPHA", "setAlpha descent -> clamp1.0 (keep DragLayer opaque)");
                                                args[0] = 1.0f;
                                                return chain.proceed(args);
                                            } else {
                                                ModuleLog.dv("DRAGALPHA", "alpha>=1 (log only)");
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
            Class<?> sh = Reflect.loadClass("com.android.quickstep.touch.SwipeToRecentAnimationHelper", loader);
            if (sh == null) {
                ModuleLog.d("ENTERHOOK", "[miss] SwipeToRecentAnimationHelper");
            } else {
                int c = 0;
                for (Method m : sh.getDeclaredMethods()) {
                    if (!m.getName().equals("goOverviewAnimation")) continue;
                    if (m.getParameterTypes().length != 0) continue;
                    Reflect.setAccessible(m);
                    API.hook("recents.enter.goOverview", (Executable) m, new XposedInterface.Hooker() {
                                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    try {
                                        Object self = chain.getThisObject();
                                        ModuleLog.d("ENTERHOOK", "goOverviewAnimation() called -> enter");
                                        if (self != null) {
                                            Field f = null;
                                            Class<?> cc = self.getClass();
                                            while (cc != null && f == null) {
                                                try { f = cc.getDeclaredField("mDragLayer"); }
                                                catch (Throwable ignore2) { cc = cc.getSuperclass(); }
                                            }
                                            if (f != null) {
                                                f.setAccessible(true);
                                                Object dl = f.get(self);
                                                if (dl instanceof View) {
                                                    enterOverviewFrom(dl, "goOverview");
                                                } else {
                                                    ModuleLog.d("ENTERHOOK", "mDragLayer not a View");
                                                }
                                            } else {
                                                ModuleLog.d("ENTERHOOK", "mDragLayer field not found");
                                            }
                                        }
                                    } catch (Throwable ignore) {}
                                    return chain.proceed();
                                }
                            });
                    c++; n++;
                }
                ModuleLog.d("ENTERHOOK", "[hook] SwipeToRecentAnimationHelper.goOverviewAnimation x" + c);
            }
        } catch (Throwable t) {
            ModuleLog.e("ENTERHOOK", "hook goOverviewAnimation failed", t);
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
                                            if (f < sMinScaleSeen) sMinScaleSeen = f;
                                            if (sScaleExitEnabled && sStateInOverview && sMinScaleSeen < 0.96f && f >= 0.988f) {
                                                ModuleLog.d("STATEBLUR", "scale rose to " + f + " (min seen " + sMinScaleSeen
                                                        + ") -> early exit (scaleRise)");
                                                exitOverviewFrom(self, "scaleRise");
                                            }
                                            float prevScale = sLastScale;
                                            sLastScale = f;
                                            if (sScaleEnterEnabled && !sStateInOverview
                                                    && prevScale >= sEnterScale && f < sEnterScale - 0.001f) {
                                                ModuleLog.d("STATEBLUR", "scale " + prevScale + " -> " + f
                                                        + " (crossed " + sEnterScale + ") -> enter (scaleFall, low-prio)");
                                                enterOverviewFrom(self, "scaleFall");
                                            }
                                            float clamped = sClampEnabled ? Math.max(f, sScaleClampMin) : f;
                                            if (clamped != f) {
                                                a[0] = Float.valueOf(clamped);
                                                ModuleLog.dv("SCALECLAMP", tag + " " + f + " -> " + clamped);
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
        for (String rn : new String[]{"resetGaussianAnimState", "resetViewsProperty"}) {
            try {
                Class<?> dl2 = Reflect.loadClass(CLS_OPLUS_DRAGLAYER, loader);
                if (dl2 == null) break;
                for (Method rm : dl2.getDeclaredMethods()) {
                    if (!rm.getName().equals(rn)) continue;
                    Reflect.setAccessible(rm);
                    final String rid = rn;
                    API.hook("recents.reset." + rn, (Executable) rm, new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object self = chain.getThisObject();
                                    ModuleLog.d("EXITPROBE", "reset(" + rid + ") radius=" + sRecentsLastRadius);
                                    if (self instanceof View) {
                                        ModuleLog.d("EXITPROBE", "reset(" + rid + ") [diag only]");
                                    }
                                    return chain.proceed();
                                }
                            });
                    n++;
                    ModuleLog.d("RECENTS", "[hook] OplusDragLayer." + rn + " -> clear");
                }
            } catch (Throwable t) {
                ModuleLog.e("RECENTS", "hook " + rn + " failed", t);
            }
        }
        try {
            Class<?> dl3 = Reflect.loadClass(CLS_OPLUS_DRAGLAYER, loader);
            if (dl3 != null) {
                for (Method vm : dl3.getDeclaredMethods()) {
                    String mn = vm.getName();
                    boolean isVis = mn.equals("onWindowVisibilityChanged");
                    boolean isAbtv = mn.equals("setAlphaByTaskView");
                    if (!isVis && !isAbtv) continue;
                    Class<?>[] pt = vm.getParameterTypes();
                    if (isVis && (pt.length != 1 || pt[0] != int.class)) continue;
                    if (isAbtv && (pt.length != 1 || pt[0] != float.class)) continue;
                    Reflect.setAccessible(vm);
                    final boolean visCall = isVis;
                    API.hook("recents.fallback." + mn, (Executable) vm, new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object self = chain.getThisObject();
                                    Object[] args = chain.getArgs().toArray();
                                    try {
                                        if (visCall) {
                                            int vis = (args.length > 0 && args[0] instanceof Number)
                                                    ? ((Number) args[0]).intValue() : -1;
                                            ModuleLog.d("EXITPROBE", "onWindowVisibilityChanged vis=" + vis + " radius=" + sRecentsLastRadius);
                                            if (vis == View.VISIBLE && self instanceof View && sRecentsLastRadius >= 0.0f) {
                                                ModuleLog.d("EXITPROBE", "windowVisible [diag only]");
                                            }
                                        } else {
                                            float a = (args.length > 0 && args[0] instanceof Number)
                                                    ? ((Number) args[0]).floatValue() : 1.0f;
                                            ModuleLog.d("EXITPROBE", "setAlphaByTaskView a=" + a + " radius=" + sRecentsLastRadius);
                                            if (a >= 0.999f && self instanceof View && sRecentsLastRadius >= 0.0f) {
                                                ModuleLog.d("EXITPROBE", "alphaByTaskView>=1 [diag only]");
                                            }
                                        }
                                    } catch (Throwable t) {
                                        ModuleLog.e("RECENTS", "fallback body failed", t);
                                    }
                                    return chain.proceed();
                                }
                            });
                    n++;
                    ModuleLog.d("RECENTS", "[hook] fallback " + mn);
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("RECENTS", "hook fallback failed", t);
        }
        } catch (Throwable t) {
            ModuleLog.e("RECENTS", "installRecentsIconBlurProbe failed", t);
        }
        try {
            Class<?> dlf = Reflect.loadClass(CLS_OPLUS_DRAGLAYER, loader);
            if (dlf != null) {
                for (Method fm : dlf.getDeclaredMethods()) {
                    if (!fm.getName().equals("startFadeInAnim")) continue;
                    if (fm.getParameterTypes().length != 0) continue;
                    Reflect.setAccessible(fm);
                    API.hook("recents.exit.fadeIn", (Executable) fm, new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object self = chain.getThisObject();
                                    ModuleLog.d("EXITPROBE", "startFadeInAnim (log only) radius=" + sRecentsLastRadius + " phase=" + sRecentsPhase);
                                    return chain.proceed();
                                }
                            });
                    n++;
                    ModuleLog.d("RECENTS", "[hook] startFadeInAnim -> exit clear");
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("EXITPROBE", "hook startFadeInAnim failed", t);
        }
        try {
            Class<?> lc = Reflect.loadClass(CLS_LAUNCHER, loader);
            if (lc != null) {
                for (Method rm : lc.getDeclaredMethods()) {
                    if (!rm.getName().equals("onResume")) continue;
                    if (rm.getParameterTypes().length != 0) continue;
                    Reflect.setAccessible(rm);
                    API.hook("recents.exit.launcherResume", (Executable) rm, new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object self = chain.getThisObject();
                                    ModuleLog.d("EXITPROBE", "Launcher.onResume (log only) radius=" + sRecentsLastRadius + " phase=" + sRecentsPhase);
                                    return chain.proceed();
                                }
                            });
                    n++;
                    ModuleLog.d("RECENTS", "[hook] Launcher.onResume -> exit clear");
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("EXITPROBE", "hook Launcher.onResume failed", t);
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
                                    cancelPendingExit();
                                    enterOverviewFrom(self, "stateStart");
                                } else if (toNormal) {
                                    cancelPendingExit();
                                    exitOverviewFrom(self, "stateStart:NORMAL");
                                }
                                return chain.proceed();
                            }
                        });
                n++;
                ModuleLog.d("STATEBLUR", "[hook] LRV.onStateTransitionStart");
            try {
                final Class<?> lrvCls = lrv;
                java.lang.reflect.Method va = Reflect.method(android.view.View.class,
                        "onVisibilityAggregated", boolean.class);
                if (va != null && lrvCls != null) {
                    Reflect.setAccessible(va);
                    API.hook("recents.visAgg", (Executable) va, new XposedInterface.Hooker() {
                                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    try {
                                        Object self = chain.getThisObject();
                                        if (self != null && lrvCls.isInstance(self)) {
                                            Object[] a = chain.getArgs().toArray();
                                            boolean vis = (a.length > 0 && a[0] instanceof Boolean)
                                                    && ((Boolean) a[0]).booleanValue();
                                            ModuleLog.d("STATEBLUR", "LRV.onVisibilityAggregated visible=" + vis);
                                            if (vis) {
                                                cancelPendingExit();
                                                if (sVisEnterEnabled) scheduleEnterDebounced("recentsVisible");
                                            } else {
                                                cancelPendingEnter();
                                                if (sVisExitEnabled) scheduleExitDebounced("recentsHidden");
                                            }
                                        }
                                    } catch (Throwable ignore) {}
                                    return chain.proceed();
                                }
                            });
                    n++;
                    ModuleLog.d("STATEBLUR", "[hook] LRV.onVisibilityAggregated -> enter trigger");
                } else {
                    ModuleLog.d("STATEBLUR", "[miss] onVisibilityAggregated / LRV");
                }
            } catch (Throwable t) {
                ModuleLog.e("STATEBLUR", "hook visAgg failed", t);
            }
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
                                        + stateName(finalState) + " toNormal=" + toNormal);
                                if (toNormal && sStateInOverview) {
                                    sStateInOverview = false;
                                    View anchor = resolveBlurAnchor(self);
                                    ModuleLog.d("STATEBLUR", "EXIT overview(complete-fallback) -> startExitFadeOut anchor=" + anchorName(anchor));
                                    cancelPendingRecentsClear();
                                    if (anchor != null) {
                                        startExitFadeOut(anchor, "stateComplete:NORMAL");
                                    }
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
                                    sStateInOverview = false;
                                    View anchor = resolveBlurAnchor(self);
                                    ModuleLog.d("STATEBLUR", "CANCEL -> startExitFadeOut anchor=" + anchorName(anchor));
                                    if (anchor != null) {
                                        startExitFadeOut(anchor, "stateCancel");
                                    }
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
            String ax = i.getStringExtra("alphaexit");
            if (ax != null) sAlphaExitEnabled = !ax.trim().equalsIgnoreCase("off");
            String de = i.getStringExtra("descententer");
            if (de != null) sDescentEnterEnabled = !de.trim().equalsIgnoreCase("off");
            String se = i.getStringExtra("scaleenter");
            if (se != null) sScaleEnterEnabled = !se.trim().equalsIgnoreCase("off");
            String esv = i.getStringExtra("enterscale");
            if (esv != null) { try { sEnterScale = Float.parseFloat(esv.trim()); } catch (Throwable ignore) {} }
            String sx = i.getStringExtra("scaleexit");
            if (sx != null) sScaleExitEnabled = !sx.trim().equalsIgnoreCase("off");
            String tn = i.getStringExtra("tint");
            if (tn != null) sTintEnabled = !tn.trim().equalsIgnoreCase("off");
            String gt = i.getStringExtra("gts");
            if (gt != null) sGtsEnabled = !gt.trim().equalsIgnoreCase("off");
            String vx = i.getStringExtra("visexit");
            if (vx != null) sVisExitEnabled = !vx.trim().equalsIgnoreCase("off");
            String ve = i.getStringExtra("visenter");
            if (ve != null) sVisEnterEnabled = !ve.trim().equalsIgnoreCase("off");
            String dg = i.getStringExtra("diag");
            if (dg != null) sDiagEnabled = !dg.trim().equalsIgnoreCase("off");
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
        return " | enter: scale=" + sScaleEnterEnabled + "(<" + sEnterScale + ")"
                + " vis=" + sVisEnterEnabled + " alphaFall=" + sDescentEnterEnabled + " state=on"
                + " | exit: state=on vis=" + sVisExitEnabled + " alphaRise=" + sAlphaExitEnabled + " gts=" + sGtsEnabled
                + " | tint=" + sTintEnabled + " scaleexit=" + sScaleExitEnabled + " iconblur=" + sForceIconBlur
                + " | verbose=" + ModuleLog.VERBOSE + " diag=" + sDiagEnabled
                + " | deb enter=" + ENTER_DEBOUNCE_MS + " exit=" + EXIT_DEBOUNCE_MS;
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
