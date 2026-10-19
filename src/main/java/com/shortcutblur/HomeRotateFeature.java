package com.shortcutblur;

import static com.shortcutblur.Symbols.*;

import android.graphics.Matrix;
import android.graphics.Rect;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

public final class HomeRotateFeature {

    public interface Host {
        void hookEx(String id, Executable target, XposedInterface.Hooker hooker);
    }

    private static final String TAG = "HOMEROT";

    static final float ROT_DEG = -90.0f;

    static final boolean ROTATE_OPEN = true;

    static final float ICON_HANDOVER_START = 0.9995f;

    static final int ICON_HOLD_BEFORE_FADE = 0;

    static final int ICON_HOLD_FRAMES = 4;

    static final float SETTLE_SIZE_EPS = 0.02f;
    static final float SETTLE_CENTER_EPS = 0.05f;
    static final float SETTLE_CENTER_EPS2 = SETTLE_CENTER_EPS * SETTLE_CENTER_EPS;

     static final int ICON_SETTLE_MAX_WAIT = 45;
 
     static final float UNWIND_ENTER_EPS = 0.2f;
 
     static final float UNWIND_STEP = 0.06f;

    static final String HANDOVER_METHOD = M_SHOW_ORIGINAL_ICON;

    static final long ICON_FOLLOW_WINDOW_MS = 600L;

    static final int LOG_FRAMES = 40;

    private static volatile boolean sResolved = false;

    private static volatile boolean sHooked = false;
    private static volatile boolean sWhyLogged = false;
    private static void why(String msg) {
        sWhyLogged = true;
        ModuleLog.d(TAG, "gated: " + msg);
    }

    private static Field F_ANIM_TYPE;
    private static Field F_SRC_RECT;
    private static Field F_REAL_RECT;
    private static Field F_TMP_MATRIX;
    private static Field F_CLIP_RECT;
    private static Field F_LEASH;
    private static Field F_ACTIVITY_TYPE;
    private static Field F_ICON_MATRIX;
    private static Field F_ICON_CROP;
    private static Field F_ICON_HOLDER;

    private static Method sGetProgress;
    private static Field F_PROGRESS;
    private static Method sGetIconAlpha;

    private static volatile Method sSetMatrix;
    private static volatile Method sSetAlpha;
    private static volatile Method sShowOriginal;

    private static volatile long sLastWindowRotateMs = 0L;

    private static boolean sOriginalShown = false;

    private static int sHoldFrames = 0;

    private static Object sLastTypeObj;
    private static String sLastTypeName = "";

    private static final float[] VALS = new float[9];

    private static final float[] PIVOT = new float[2];
    private static final float[] QUAD = new float[4];
    private static float sPrevCx, sPrevCy, sPrevW, sPrevH;
    private static boolean sHavePrev;
    private static int sLandedFrames;
    private static float sUnwind;
    private static boolean sUnwinding;
    private static Object sCacheHelper;
    private static Object sCacheType;
    private static int sCacheScene = -1;
    private static boolean sCacheLandscape;
    private static Object sCacheTarget;
    private static int sCacheAct = Integer.MIN_VALUE;
    private static Object sCacheLeash;
    private static boolean sCacheLeashValid;
    private static void clearFrameCache() {
        sCacheHelper = null;
        sCacheType = null;
        sCacheTarget = null;
        sCacheLeash = null;
        sCacheLeashValid = false;
        sCacheAct = Integer.MIN_VALUE;
        sCacheScene = -1;
    }
    private static final boolean PERF = true;
    private static long sPerfWinNs, sPerfIconNs;
    private static int sPerfWinCalls, sPerfIconCalls;
    private static final Matrix M_OUT = new Matrix();
    private static final Matrix M_ICON_OUT = new Matrix();
    private static final boolean ICON_UPRIGHT = false;

    private static int sFrame;

    private HomeRotateFeature() {
    }

    public static int install(Host host, ClassLoader cl) {
        if (host == null || cl == null) return 0;
        if (sHooked) {
            ModuleLog.d(TAG, "already hooked, skip");
            return 0;
        }
        if (!resolve(cl)) {
            ModuleLog.d(TAG, "resolve failed, skip (ROM signature mismatch?)");
            return 0;
        }
        int n = 0;
        if (hookWindow(host, cl)) n++;
        if (hookIcon(host, cl)) n++;
        if (n > 0) sHooked = true;
        ModuleLog.d(TAG, "installed hooks=" + n);
        return n;
    }

    private static boolean resolve(ClassLoader cl) {
        if (sResolved) return true;
        synchronized (HomeRotateFeature.class) {
            if (sResolved) return true;
            try {
                Class<?> helper = Class.forName(
                        CLS_RECT_HELPER, false, cl);
                Class<?> params = Class.forName(
                        CLS_TRANSFORM_PARAMS,
                        false, cl);
                Class<?> target = Class.forName(
                        CLS_REMOTE_TARGET, false, cl);
                Class<?> updater = Class.forName(
                        CLS_ICON_LAYER_UPDATER, false, cl);

                F_ANIM_TYPE = fieldOf(helper, "mAnimType");
                F_SRC_RECT = fieldOf(helper, "mSourceWindowRect");
                F_REAL_RECT = fieldOf(helper, "mRealWindowRect");
                F_TMP_MATRIX = fieldOf(helper, "mTmpMatrix");
                F_CLIP_RECT = fieldOf(helper, "mClipRect");
                F_LEASH = fieldOf(target, "leash");
                F_ACTIVITY_TYPE = fieldOf(target, "activityType");
                F_ICON_MATRIX = fieldOf(updater, "mMatrix");
                F_ICON_CROP = fieldOf(updater, "mIconLayerCrop");
                F_ICON_HOLDER = fieldOf(updater, "mIconLayerHolder");
                sGetProgress = methodOf(params, M_GET_PROGRESS, 0);
                F_PROGRESS = fieldOf(params, "progress");
                sGetIconAlpha = methodOf(updater, M_GET_ICON_ALPHA, 2);

                boolean ok = F_ANIM_TYPE != null && F_TMP_MATRIX != null && F_CLIP_RECT != null
                        && F_LEASH != null && sGetProgress != null;
                if (!ok) {
                    ModuleLog.d(TAG, "critical handle missing: animType=" + (F_ANIM_TYPE != null)
                            + " tmpMatrix=" + (F_TMP_MATRIX != null)
                            + " clip=" + (F_CLIP_RECT != null)
                            + " leash=" + (F_LEASH != null)
                            + " progress=" + (sGetProgress != null));
                    return false;
                }
                sResolved = true;
                return true;
            } catch (Throwable t) {
                ModuleLog.e(TAG, "resolve failed", t);
                return false;
            }
        }
    }

    private static boolean hookWindow(Host host, ClassLoader cl) {
        try {
            Class<?> helper = Class.forName(
                    CLS_RECT_HELPER, false, cl);
            Method lambda = null;
            for (Class<?> k = helper; k != null && lambda == null; k = k.getSuperclass()) {
                Method[] all = k.getDeclaredMethods();
                for (int i = 0; i < all.length; i++) {
                    Method m = all[i];
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length == 8
                            && ps[1].getName().endsWith("RectTransformHelper")
                            && CLS_SURFACE_TX.equals(ps[2].getName())
                            && ps[3].getName().endsWith("RectTransformHelper$TransformParams")
                            && M_APPLY_SURFACE_LAMBDA.equals(m.getName())) {
                        lambda = m;
                        break;
                    }
                }
            }
            if (lambda == null) {
                ModuleLog.d(TAG, "window lambda not found");
                return false;
            }
            lambda.setAccessible(true);
            host.hookEx("homeRotate.window", lambda, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    try {
                        timedWindow(chain.getArg(1), chain.getArg(0),
                                chain.getArg(2), chain.getArg(3));
                    } catch (Throwable ignored) {
                    }
                    return r;
                }
            });
            return true;
        } catch (Throwable t) {
            ModuleLog.e(TAG, "hook window failed", t);
            return false;
        }
    }

    private static boolean hookIcon(Host host, ClassLoader cl) {
        try {
            Class<?> updater = Class.forName(
                    CLS_ICON_LAYER_UPDATER, false, cl);
            Method target = null;
            Method[] all = updater.getDeclaredMethods();
            for (int i = 0; i < all.length; i++) {
                Method m = all[i];
                if (M_UPDATE_ICON_LAYER.equals(m.getName()) && m.getParameterTypes().length == 7) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                ModuleLog.d(TAG, "updateIconLayer not found");
                return false;
            }
            target.setAccessible(true);
            host.hookEx("homeRotate.icon", target, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object r = chain.proceed();
                    try {
                        timedIcon(chain.getThisObject(),
                                chain.getArg(0),
                                chain.getArg(1),
                                chain.getArg(3),
                                chain.getArg(4));
                    } catch (Throwable ignored) {
                    }
                    return r;
                }
            });
            return true;
        } catch (Throwable t) {
            ModuleLog.e(TAG, "hook icon failed", t);
            return false;
        }
    }

    private static void timedWindow(Object helper, Object target, Object tx, Object params) {
        if (!PERF) {
            try { onWindowFrame(helper, target, tx, params); } catch (Throwable ignored) { }
            return;
        }
        long t0 = System.nanoTime();
        try {
            onWindowFrame(helper, target, tx, params);
        } catch (Throwable ignored) {
        } finally {
            sPerfWinNs += System.nanoTime() - t0;
            sPerfWinCalls++;
            perfLog();
        }
    }
    private static void timedIcon(Object self, Object params, Object enabled,
                                  Object iconSc, Object tx) {
        if (!PERF) {
            try { onIconFrame(self, params, enabled, iconSc, tx); } catch (Throwable ignored) { }
            return;
        }
        long t0 = System.nanoTime();
        try {
            onIconFrame(self, params, enabled, iconSc, tx);
        } catch (Throwable ignored) {
        } finally {
            sPerfIconNs += System.nanoTime() - t0;
            sPerfIconCalls++;
            perfLog();
        }
    }
    private static void perfLog() {
        int total = sPerfWinCalls + sPerfIconCalls;
        if (total == 0 || (total & 0x7f) != 0) return;
        if (!FeatureFlags.LOG_ENABLED) return;
        int wc = sPerfWinCalls, ic = sPerfIconCalls;
        long wn = sPerfWinNs, in = sPerfIconNs;
        ModuleLog.d(TAG, "PERF wc=" + wc + " wAvgNs=" + (wc == 0 ? 0 : wn / wc)
                + " ic=" + ic + " iAvgNs=" + (ic == 0 ? 0 : in / ic));
    }

    private static void onWindowFrame(Object helper, Object target, Object tx, Object params)
            throws Throwable {
        if (!FeatureFlags.HOME_ROTATE) {
            clearFrameCache();
            if (!sWhyLogged) why("flag off");
            return;
        }
        Object type = F_ANIM_TYPE.get(helper);
        int scene;
        boolean landscape;
        if (helper == sCacheHelper && type == sCacheType && sCacheScene >= 0) {
            scene = sCacheScene;
            landscape = sCacheLandscape;
        } else {
            scene = sceneKind(typeName(type));
            boolean land = false;
            if (scene != 0) {
                Rect judge = rectOf(scene == 2 ? F_REAL_RECT.get(helper) : F_SRC_RECT.get(helper));
                land = judge != null && judge.width() > judge.height();
            }
            sCacheHelper = helper;
            sCacheType = type;
            sCacheScene = scene;
            sCacheLandscape = land;
            landscape = land;
        }
        if (scene == 0) {
            if (!sWhyLogged) why("scene=0 type=" + sLastTypeName);
            return;
        }
        if (!landscape) {
            if (!sWhyLogged) why("judge portrait scene=" + scene);
            return;
        }
        int act = activityTypeOf(target);
        if (act >= 0 && act != 1) {
            if (!sWhyLogged) why("activityType=" + act + " scene=" + scene);
            return;
        }

        float p = progress(params);
        if (p <= 0.001f || p >= 1.0f) {
            clearFrameCache();
            return;
        }

        Object leash = leashOf(target);
        if (leash == null) return;
        Object mObj = F_TMP_MATRIX.get(helper);
        if (!(mObj instanceof Matrix)) return;

        float angle = ROT_DEG * (scene == 2 ? -1f : 1f) * p;

        Matrix out = M_OUT;
        out.set((Matrix) mObj);
        Rect clip = rectOf(F_CLIP_RECT.get(helper));
        float px = 0f, py = 0f;
        if (clip != null) {
            PIVOT[0] = clip.centerX();
            PIVOT[1] = clip.centerY();
            out.mapPoints(PIVOT);
            px = PIVOT[0];
            py = PIVOT[1];
        }
        out.postRotate(angle, px, py);

        Object sp = forSurface(tx, leash);
        if (sp == null) return;
        if (!applyMatrix(sp, out)) return;

        sLastWindowRotateMs = android.os.SystemClock.elapsedRealtime();

        if (FeatureFlags.LOG_ENABLED && ++sFrame <= LOG_FRAMES) {
            Rect judge = rectOf(scene == 2 ? F_REAL_RECT.get(helper) : F_SRC_RECT.get(helper));
            ModuleLog.d(TAG, "W#" + sFrame + " p=" + p + " deg=" + angle
                    + " pivot=(" + px + "," + py + ")"
                    + " judge=" + (judge == null ? "null" : judge.width() + "x" + judge.height())
                    + " scene=" + (scene == 2 ? "OPEN" : "CLOSE"));
        }
    }

    private static void onIconFrame(Object self, Object params, Object enabled,
                                    Object iconSc, Object tx)
            throws Throwable {
        if (!FeatureFlags.HOME_ROTATE) {
            clearFrameCache();
            return;
        }

        long delta = android.os.SystemClock.elapsedRealtime() - sLastWindowRotateMs;
        if (delta > ICON_FOLLOW_WINDOW_MS) return;

        float p = progress(params);
        if (ICON_UPRIGHT) {
            if (p <= 0.001f) {
                sOriginalShown = false;
                return;
            }
            if (p >= ICON_HANDOVER_START && !sOriginalShown) {
                sOriginalShown = true;
                showOriginalIcon(self);
            }
            return;
        }
        if (p <= 0.001f) {
            sHavePrev = false;
            clearFrameCache();
            return;
        }
        Object mObj = F_ICON_MATRIX.get(self);
        if (!(mObj instanceof Matrix)) return;
        Matrix out = M_ICON_OUT;
        out.set((Matrix) mObj);
        Rect crop = rectOf(F_ICON_CROP.get(self));
        if (crop != null) {
            QUAD[0] = crop.left; QUAD[1] = crop.top;
            QUAD[2] = crop.right; QUAD[3] = crop.bottom;
        } else {
            QUAD[0] = 0f; QUAD[1] = 0f; QUAD[2] = 0f; QUAD[3] = 0f;
        }
        out.mapPoints(QUAD);
        float cx = (QUAD[0] + QUAD[2]) * 0.5f;
        float cy = (QUAD[1] + QUAD[3]) * 0.5f;
        float wpx = Math.abs(QUAD[2] - QUAD[0]);
        float hpx = Math.abs(QUAD[3] - QUAD[1]);
        float dcx = cx - sPrevCx;
        float dcy = cy - sPrevCy;
        boolean settled = sHavePrev
                && (dcx * dcx + dcy * dcy) < SETTLE_CENTER_EPS2
                && Math.abs(wpx - sPrevW) < SETTLE_SIZE_EPS
                && Math.abs(hpx - sPrevH) < SETTLE_SIZE_EPS;
        if (p >= ICON_HANDOVER_START && !sUnwinding
                && (settled || (sHavePrev && Math.abs(wpx - sPrevW) < UNWIND_ENTER_EPS))) {
            sUnwinding = true;
        }
        if (sUnwinding) {
            sUnwind += UNWIND_STEP;
            if (sUnwind > 1f) sUnwind = 1f;
        }
        sPrevCx = cx; sPrevCy = cy; sPrevW = wpx; sPrevH = hpx; sHavePrev = true;
        float alphaOut = -1f;
        float fadeU = -1f;
        if (p >= ICON_HANDOVER_START) {
            sLandedFrames++;
            if (sOriginalShown || settled || sLandedFrames >= ICON_SETTLE_MAX_WAIT) {
                if (!sOriginalShown) {
                    sOriginalShown = true;
                    showOriginalIcon(self);
                }
                sHoldFrames++;
                if (sHoldFrames <= ICON_HOLD_BEFORE_FADE) {
                    alphaOut = 1f;
                } else {
                    float u = (sHoldFrames - ICON_HOLD_BEFORE_FADE) / (float) ICON_HOLD_FRAMES;
                    if (u > 1f) u = 1f;
                    float s = u * u * (3f - 2f * u);
                    alphaOut = 1f - s;
                    fadeU = u;
                }
            }
        } else {
            if (sHoldFrames > 0 && FeatureFlags.LOG_ENABLED) {
                ModuleLog.d(TAG, "IH tail=" + sHoldFrames + " settled=" + settled);
            }
            sHoldFrames = 0;
            sLandedFrames = 0;
            sUnwind = 0f;
            sUnwinding = false;
            sOriginalShown = false;
        }
        float prog = sUnwind;
        if (fadeU >= 0f) {
            float s = fadeU * fadeU * (3f - 2f * fadeU);
            if (s > prog) prog = s;
        }
        float ang = ROT_DEG * p * (1f - prog);
        out.postRotate(ang, cx, cy);

        Object sp = forSurface(tx, iconSc);
        if (sp == null) return;
        if (!applyMatrix(sp, out)) return;
        if (alphaOut >= 0f) {
            float sysA = iconAlphaOf(self, params, enabled);
            if (sysA < 0f) sysA = 1f;
            applyAlpha(sp, sysA * alphaOut);
        }

        if (FeatureFlags.LOG_ENABLED && sFrame <= LOG_FRAMES) {
            ModuleLog.d(TAG, "I p=" + p + " alpha=" + alphaOut + " ang=" + ang
                    + " pivot=(" + cx + "," + cy + ")"
                    + " settled=" + settled + " w=" + wpx);
        }
    }

    private static int activityTypeOf(Object target) {
        Field f = F_ACTIVITY_TYPE;
        if (f == null || target == null) return -1;
        if (target != sCacheTarget) {
            sCacheTarget = target;
            sCacheAct = Integer.MIN_VALUE;
            sCacheLeash = null;
            sCacheLeashValid = false;
        }
        if (sCacheAct != Integer.MIN_VALUE) return sCacheAct;
        int v;
        try {
            v = f.getInt(target);
        } catch (Throwable t) {
            try {
                Object o = f.get(target);
                v = (o instanceof Integer) ? ((Integer) o).intValue() : -1;
            } catch (Throwable t2) {
                v = -1;
            }
        }
        sCacheAct = v;
        return v;
    }
    private static Object leashOf(Object target) {
        Field f = F_LEASH;
        if (f == null || target == null) return null;
        if (target != sCacheTarget) {
            sCacheTarget = target;
            sCacheAct = Integer.MIN_VALUE;
            sCacheLeash = null;
            sCacheLeashValid = false;
        }
        if (sCacheLeashValid) return sCacheLeash;
        Object v;
        try {
            v = f.get(target);
        } catch (Throwable t) {
            v = null;
        }
        sCacheLeash = v;
        sCacheLeashValid = true;
        return v;
    }
    private static Rect rectOf(Object o) {
        return (o instanceof Rect) ? (Rect) o : null;
    }

    private static float iconAlphaOf(Object self, Object params, Object enabled) {
        Method m = sGetIconAlpha;
        if (m == null || self == null || params == null) return -1f;
        try {
            Object o = m.invoke(self, params, enabled);
            return (o instanceof Number) ? ((Number) o).floatValue() : -1f;
        } catch (Throwable t) {
            return -1f;
        }
    }
    private static float progress(Object params) {
        if (params == null) return 0f;
        Field f = F_PROGRESS;
        if (f != null) {
            try {
                return f.getFloat(params);
            } catch (Throwable ignored) {
            }
        }
        try {
            Object o = sGetProgress.invoke(params);
            return (o instanceof Number) ? ((Number) o).floatValue() : 0f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static volatile Method sForSurface;

    private static Object forSurface(Object tx, Object sc) {
        if (tx == null || sc == null) return null;
        try {
            Method m = sForSurface;
            if (m == null) {
                m = tx.getClass().getMethod(M_FOR_SURFACE, sc.getClass());
                m.setAccessible(true);
                sForSurface = m;
            }
            return m.invoke(tx, sc);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean applyMatrix(Object sp, Matrix m) {
        try {
            Method setter = sSetMatrix;
            if (setter == null) {
                setter = methodOf(sp.getClass(), M_SET_MATRIX, 1, Matrix.class);
                if (setter == null) return false;
                sSetMatrix = setter;
            }
            setter.invoke(sp, m);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void applyAlpha(Object sp, float a) {
        try {
            Method setter = sSetAlpha;
            if (setter == null) {
                setter = methodOf(sp.getClass(), M_SET_ALPHA, 1, float.class);
                if (setter == null) return;
                sSetAlpha = setter;
            }
            setter.invoke(sp, a);
        } catch (Throwable t) {
        }
    }

    private static void showOriginalIcon(Object updater) {
        try {
            Object holder = F_ICON_HOLDER == null ? null : F_ICON_HOLDER.get(updater);
            if (holder == null) return;
            Method m = sShowOriginal;
            if (m == null) {
                m = holder.getClass().getMethod(HANDOVER_METHOD);
                m.setAccessible(true);
                sShowOriginal = m;
            }
            m.invoke(holder);
        } catch (Throwable t) {
            ModuleLog.d(TAG, "showOriginalIcon failed: " + t);
        }
    }

    private static String typeName(Object type) {
        if (type == null) return "";
        if (type == sLastTypeObj) return sLastTypeName;
        String n;
        try {
            n = type.toString();
        } catch (Throwable t) {
            n = "";
        }
        sLastTypeObj = type;
        sLastTypeName = n;
        return n;
    }

    private static int sceneKind(String tn) {
        if (tn == null || tn.isEmpty()) return 0;
        if (ROTATE_OPEN && tn.contains("OPEN_FROM_HOME")) return 2;
        if (tn.contains("CLOSE_TO_HOME")
                || tn.contains("SWIPE_TO_HOME")
                || tn.contains("SWIPE_TO_BACK")) {
            return 1;
        }
        return 0;
    }

    private static Field fieldOf(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Method methodOf(Class<?> c, String name, int argc) {
        return methodOf(c, name, argc, null);
    }

    private static Method methodOf(Class<?> c, String name, int argc, Class<?> onlyArg) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            Method[] all = k.getDeclaredMethods();
            for (int i = 0; i < all.length; i++) {
                Method m = all[i];
                if (!name.equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != argc) continue;
                if (onlyArg != null && (ps.length != 1 || ps[0] != onlyArg)) continue;
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }
}
