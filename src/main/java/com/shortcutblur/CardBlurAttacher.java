package com.shortcutblur;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.WeakHashMap;

public final class CardBlurAttacher {

    private static final String TAG = "LAUNCHCARD";

    private static final int ID_KEY_BLUR_VIEW_RADIUS = 0x7f0a03d7;

    private static final int TYPE_CARD = 5;

    private static final float DEFAULT_RADIUS_DP = 16f;

    private static final String CLS_CARD_DELEGATOR = "org.hapjs.card.sdk.CardDelegator";

    private static final long ATTACH_RETRY_MS = 200L;
    private static final int ATTACH_MAX_RETRY = 20;

    private static final WeakHashMap<View, Boolean> sSettled = new WeakHashMap<View, Boolean>();
    private static final WeakHashMap<View, Boolean> sPending = new WeakHashMap<View, Boolean>();

    private static final ThreadLocal<String> sLastRejectedTarget = new ThreadLocal<String>();

    private CardBlurAttacher() {}

    public static void attach(final View card, final ClassLoader cl) {
        attach(card, cl, 0);
    }

    public static void forget(final View card) {
        if (card == null) return;
        synchronized (sSettled) { sSettled.remove(card); }
        synchronized (sPending) { sPending.remove(card); }
    }

    private static boolean applyBlurToTarget(View card, Object launcher, View target) {
        float density = densityOf(target);
        float radiusPx = DEFAULT_RADIUS_DP * density;
        try { target.setTag(ID_KEY_BLUR_VIEW_RADIUS, Float.valueOf(radiusPx)); }
        catch (Throwable t) { ModuleLog.swallow("cardblur.radius", t); }

        int cleared = 0;

        if (isBlurBackground(target.getBackground())) {
            if (ModuleLog.enabled()) {
                ModuleLog.d(TAG, "skip already blurred: " + target.getClass().getSimpleName()
                        + " " + target.getWidth() + "x" + target.getHeight());
            }
            synchronized (sSettled) { sSettled.put(card, Boolean.TRUE); }
            return true;
        }

        Drawable originalBg = target.getBackground();

        boolean ok = createBlurViaManager(launcher, target);
        if (!ok) return false;

        boolean bgReplaced = isBlurBackground(target.getBackground());
        if (!bgReplaced) {
            try {
                if (originalBg != null && target.getBackground() != originalBg) {
                    target.setBackground(originalBg);
                    cleared = 2;
                }
            } catch (Throwable t) { ModuleLog.swallow("cardblur.restoreBg", t); }
            try { target.invalidate(); } catch (Throwable ignored) { ModuleLog.swallow("CardBlurAttacher", ignored); }
            if (ModuleLog.enabled()) {
                ModuleLog.d(TAG, "L40/GUARD createBlurForView not replaced, bg restored (rollback=" + cleared
                        + ", target=" + target.getClass().getSimpleName()
                        + " " + target.getWidth() + "x" + target.getHeight() + ")");
            }
            return false;
        }

        try { target.invalidate(); } catch (Throwable ignored) { ModuleLog.swallow("CardBlurAttacher", ignored); }

        synchronized (sSettled) { sSettled.put(card, Boolean.TRUE); }
        if (ModuleLog.enabled()) {
            ModuleLog.d(TAG, "card blur attached: outer=" + card.getClass().getSimpleName()
                    + " " + card.getWidth() + "x" + card.getHeight()
                    + " | target=" + target.getClass().getSimpleName()
                    + " " + target.getWidth() + "x" + target.getHeight()
                    + " radius=" + radiusPx
                    + " clearedBg=" + cleared + " bgReplaced=" + bgReplaced);
        }
        return true;
    }
    private static void attach(final View card, final ClassLoader cl, final int attempt) {
        if (card == null) return;
        if (!FeatureFlags.LAUNCHER_CARD_BLUR) return;
        synchronized (sSettled) {
            if (Boolean.TRUE.equals(sSettled.get(card))) return;
        }
        if (attempt == 0 && !isCard(card)) return;
        boolean keepPending = false;
        try {
            if (attempt == 0) {
                synchronized (sPending) {
                    if (Boolean.TRUE.equals(sPending.get(card))) { keepPending = true; return; }
                    sPending.put(card, Boolean.TRUE);
                }
            }
            if (!card.isAttachedToWindow() || card.getWidth() <= 0 || card.getHeight() <= 0) {
                keepPending = retry(card, cl, attempt, "not ready");
                return;
            }
            if (!(card instanceof ViewGroup)) {
                ModuleLog.e(TAG, "card not ViewGroup: " + card.getClass().getName(), null);
                return;
            }

            Object launcher = BlurLib.getLauncherQuietly(card);
            if (launcher == null) {
                keepPending = retry(card, cl, attempt, "launcher null");
                return;
            }

            View target = findOpaqueBgLayer(card);
            if (target == null) {
                String rej = sLastRejectedTarget.get();
                sLastRejectedTarget.remove();
                keepPending = retry(card, cl, attempt,
                        "await opaque css layer" + (rej != null ? " [" + rej + "]" : ""));
                return;
            }
            if (isPlaceholderView(target)) {
                keepPending = retry(card, cl, attempt, "target is placeholder");
                return;
            }

            if (!applyBlurToTarget(card, launcher, target)) {
                keepPending = retry(card, cl, attempt, "blur apply failed");
                return;
            }
        } catch (Throwable t) {
            ModuleLog.e(TAG, "attach fail", t);
        } finally {
            if (!keepPending) {
                synchronized (sPending) { sPending.remove(card); }
            }
        }
    }

    private static View findOpaqueBgLayer(View card) {
        sLastRejectedTarget.remove();
        try {
            View root = findFirstByClass(card, CLS_CARD_DELEGATOR);
            if (root instanceof ViewGroup && ((ViewGroup) root).getChildCount() == 0) {
                return null;
            }
            OpaquePick pick = new OpaquePick();
            pick.maxArea = -1;
            collectOpaqueLayers(root, 0, pick);
            if (pick.view != null) {
                if (isPlausibleTarget(card, pick.view)) return pick.view;
                if (ModuleLog.enabled()) {
                    sLastRejectedTarget.set("too small: " + pick.view.getWidth() + "x"
                            + pick.view.getHeight() + " (card " + card.getWidth()
                            + "x" + card.getHeight() + ")");
                }
            }
        } catch (Throwable t) {
            ModuleLog.swallow("cardblur.findOpaque", t);
        }
        return null;
    }

    private static final class OpaquePick { View view; long maxArea; boolean cssPreferred; }

    private static final float TARGET_MIN_AREA_RATIO = 0.5f;
    private static final int TARGET_MIN_SIDE_PX = 64;

    private static boolean isPlausibleTarget(View card, View target) {
        try {
            int cw = card.getWidth(), ch = card.getHeight();
            int tw = target.getWidth(), th = target.getHeight();
            if (tw <= 0 || th <= 0) return false;
            if (tw < TARGET_MIN_SIDE_PX || th < TARGET_MIN_SIDE_PX) return false;
            long cardArea = (long) cw * (long) ch;
            if (cardArea <= 0) return true;
            long targetArea = (long) tw * (long) th;
            return targetArea >= (long) (cardArea * TARGET_MIN_AREA_RATIO);
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean isPlaceholderView(View v) {
        try {
            String cn = v.getClass().getName();
            return cn.contains("DefaultCardView") || cn.endsWith(".PlaceholderView");
        } catch (Throwable t) {
            return false;
        }
    }

    private static void collectOpaqueLayers(View v, int depth, OpaquePick pick) {
        if (v == null || depth > 20) return;
        try {
            if (isPlaceholderView(v)) {
            } else {
                Drawable bg = v.getBackground();
                boolean opaque = false;
                boolean css = false;
                if (bg != null) {
                    String cn = bg.getClass().getName();
                    css = cn.contains("CSSBackgroundDrawable");
                    if (bg instanceof ColorDrawable) {
                        opaque = ((ColorDrawable) bg).getAlpha() >= 250;
                    } else {
                        try {
                            int op = bg.getOpacity();
                            opaque = (op == android.graphics.PixelFormat.OPAQUE)
                                    || (css && (op == android.graphics.PixelFormat.TRANSPARENT
                                             || op == android.graphics.PixelFormat.TRANSLUCENT));
                        } catch (Throwable ignored) { ModuleLog.swallow("CardBlurAttacher", ignored); }
                    }
                }
                if (opaque && v.getWidth() > 0 && v.getHeight() > 0) {
                    long area = (long) v.getWidth() * (long) v.getHeight();
                    boolean better = (css && !pick.cssPreferred)
                            || ((css == pick.cssPreferred) && (area > pick.maxArea));
                    if (better) {
                        pick.view = v;
                        pick.maxArea = area;
                        pick.cssPreferred = css;
                    }
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                int n;
                try { n = g.getChildCount(); } catch (Throwable t) { return; }
                for (int i = 0; i < n; i++) {
                    View c;
                    try { c = g.getChildAt(i); } catch (Throwable t) { continue; }
                    collectOpaqueLayers(c, depth + 1, pick);
                }
            }
        } catch (Throwable t) {
            ModuleLog.swallow("cardblur.collectOpaque", t);
        }
    }
    private static View findFirstByClass(View root, String className) {
        return findFirstByClass(root, className, 0);
    }

    private static View findFirstByClass(View root, String className, int depth) {
        if (root == null || depth > 24) return null;
        try {
            if (className.equals(root.getClass().getName())) return root;
            if (root instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) root;
                int n;
                try { n = g.getChildCount(); } catch (Throwable t) { return null; }
                for (int i = 0; i < n; i++) {
                    View r = findFirstByClass(g.getChildAt(i), className, depth + 1);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) { ModuleLog.swallow("CardBlurAttacher", ignored); }
        return null;
    }

    private static boolean isBlurBackground(Drawable d) {
        if (d == null) return false;
        try {
            String cn = d.getClass().getName();
            return cn.contains("Blur") || cn.contains("blur");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean retry(final View card, final ClassLoader cl, final int attempt, final String why) {
        if (attempt >= ATTACH_MAX_RETRY) {
            synchronized (sSettled) { sSettled.put(card, Boolean.TRUE); }
            ModuleLog.d(TAG, "give up after " + attempt + " retries: " + why);
            return false;
        }
        if (!card.isAttachedToWindow()) return false;

        long delay = Math.min(ATTACH_RETRY_MS << Math.min(attempt, 3), 1000L);
        card.postDelayed(new Runnable() {
            @Override public void run() { attach(card, cl, attempt + 1); }
        }, delay);
        return true;
    }

    private static boolean createBlurViaManager(Object launcher, View target) {
        try {
            Object mgr = Reflect.call(launcher, "getCardBlurManager");
            if (mgr == null) { ModuleLog.e(TAG, "cardBlurManager null", null); return false; }
            Method m = Reflect.method(mgr.getClass(), "createBlurForView", 3);
            if (m == null) { ModuleLog.e(TAG, "createBlurForView/3 not found", null); return false; }
            m.setAccessible(true);
            Object r = m.invoke(mgr, target, null, TYPE_CARD);
            if (ModuleLog.enabled()) ModuleLog.d(TAG, "createBlurForView => " + r);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "createBlurViaManager fail", t);
            return false;
        }
    }

    private static float densityOf(View v) {
        try { return v.getResources().getDisplayMetrics().density; } catch (Throwable t) { return 3.5f; }
    }

    public static boolean isCard(View v) {
        if (v == null) return false;
        try {
            String cn = v.getClass().getName();
            if (!cn.startsWith(Symbols.PKG_CARD)) return false;
            boolean isLauncherCard = false;
            for (String suffix : Symbols.CARD_BLUR_SUFFIXES) {
                if (cn.endsWith(suffix)) { isLauncherCard = true; break; }
            }
            if (isLauncherCard) {
                return hasValidSpan(v);
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
    private static final int MIN_SPAN_X = 2;
    private static final int MIN_SPAN_Y = 1;

    private static boolean hasValidSpan(View card) {
        try {
            Object info = Reflect.call(card, "getItemInfo");
            if (info == null) return true;
            Object sx = Reflect.call(info, "getSpanX");
            Object sy = Reflect.call(info, "getSpanY");
            if (!(sx instanceof Integer) || !(sy instanceof Integer)) return true;
            return ((Integer) sx) >= MIN_SPAN_X && ((Integer) sy) >= MIN_SPAN_Y;
        } catch (Throwable t) {
            return true;
        }
    }

}
