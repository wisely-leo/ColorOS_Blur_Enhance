package com.shortcutblur;
import android.graphics.Path;
import android.text.TextPaint;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import static com.shortcutblur.Symbols.*;
public class GlyphBlurRenderer {
    private static final int MAX_RETRY = 12;
    private static final float GLYPH_DY = 0.0f;
    private static final long RETRY_DELAY_MS = 120L;
    private static final int ICON_ALPHA_THRESHOLD = 40;
    private static final int ICON_SAMPLE_MAX_W = 96;

    private static float iconAlpha() {
        try {

            if (FeatureFlags.CLOCK_GLASS) return 0f;
            return FeatureFlags.CLOCK_ICON_ALPHA;
        } catch (Throwable t) { return 0.30f; }
    }

    private static float brightenGain() {
        try {

            if (FeatureFlags.CLOCK_GLASS) return 1.0f;
            return FeatureFlags.CLOCK_BRIGHTEN;
        } catch (Throwable t) { return 1.25f; }
    }
    private static final class GlyphSnapshot {
        final Path localPath;
        final float offX, offY;
        GlyphSnapshot(Path localPath, float offX, float offY) {
            this.localPath = localPath; this.offX = offX; this.offY = offY;
        }
    }
    private static final java.util.WeakHashMap<View, GlyphSnapshot[]> sSnapsMap = new java.util.WeakHashMap<View, GlyphSnapshot[]>();
    private static volatile boolean sScreenOn = true;
    // key 弱引用 View，value 也须弱引用：StateMachine 强引用 container，
// 否则 entry 永远无法回收（值是 key 的持有者）。
    private static final java.util.WeakHashMap<View, java.lang.ref.WeakReference<ClockBlurStateMachine>> sRunners =
            new java.util.WeakHashMap<View, java.lang.ref.WeakReference<ClockBlurStateMachine>>();

    private static java.util.ArrayList<ClockBlurStateMachine> runnersSnapshot() {
        java.util.ArrayList<ClockBlurStateMachine> list = new java.util.ArrayList<>();
        synchronized (sRunners) {
            for (java.lang.ref.WeakReference<ClockBlurStateMachine> ref : sRunners.values()) {
                ClockBlurStateMachine sm = ref == null ? null : ref.get();
                if (sm != null) list.add(sm);
            }
        }
        return list;
    }

    // 时钟模糊 drawable 登记表：仅用于识别「是不是时钟的模糊 drawable」。
    // 必须用弱引用，否则会随每次时钟重建累积并拖住其回调的 View 树，造成内存泄漏。
    private static final java.util.WeakHashMap<Object, Boolean> sClockBlurDrawables =
            new java.util.WeakHashMap<Object, Boolean>();
    public static void registerBlurDrawable(Object bd) {
        if (bd == null) return;
        synchronized (sClockBlurDrawables) {
            sClockBlurDrawables.put(bd, Boolean.TRUE);
        }
    }
    public static boolean isClockBlurDrawable(Object bd) {
        if (bd == null) return false;
        synchronized (sClockBlurDrawables) {
            return sClockBlurDrawables.containsKey(bd);
        }
    }
    private static final class IconCacheEntry {
        final int fp;
        final int ivW, ivH;
        final Path path;
        IconCacheEntry(int fp, int ivW, int ivH, Path path) {
            this.fp = fp; this.ivW = ivW; this.ivH = ivH; this.path = path;
        }
    }
    private static final java.util.WeakHashMap<View, java.util.HashMap<Integer, IconCacheEntry>> sIconCache =
            new java.util.WeakHashMap<View, java.util.HashMap<Integer, IconCacheEntry>>();
    private static int iconFingerprint(android.graphics.Bitmap b) {
        if (b == null || b.isRecycled()) return 0;
        int w = b.getWidth(), h = b.getHeight();
        if (w <= 0 || h <= 0) return 0;
        int hash = 17;
        hash = hash * 31 + w;
        hash = hash * 31 + h;
        for (int iy = 0; iy < 4; iy++) {
            int y = h * iy / 4;
            for (int ix = 0; ix < 4; ix++) {
                hash = hash * 31 + b.getPixel(w * ix / 4, y);
            }
        }
        return hash;
    }
    public static void setScreenOn(boolean on) {
        boolean changed = (sScreenOn != on);
        sScreenOn = on;
        // 亮屏事件（SCREEN_ON / USER_PRESENT）必须每次都推进一次重建：
        // 不能因 sScreenOn 值未变（进程在灭屏时被拉起、初值与真实一致）而短路，
        // 否则状态机可能停在「无任务」死角，导致长时间待机后亮屏不刷新。
        if (!changed && !on) return;
        try {
            for (ClockBlurStateMachine pr : runnersSnapshot()) pr.onScreenStateChanged();
        } catch (Throwable t) { ModuleLog.e("GB", "screenStateChanged fail", t); }
    }
    public static void notifyContentMaybeChangedAll() {
        // 高频入口（RemoteViews.apply/reapply 等每帧级回调）：只做读锁快照，
        // 不持有 sRunners 锁调用回调（避免与 View/Handler 的锁形成顺序反转）。
        try {
            for (ClockBlurStateMachine pr : runnersSnapshot()) pr.onContentChanged();
        } catch (Throwable t) { ModuleLog.e("GB", "kickAll fail", t); }
    }
    public static void attachGlyphBlur(final View container, final ClassLoader cl) {
        if (container == null) return;
        try { tryAttachOnce(container, cl, 0); }
        catch (Throwable t) { ModuleLog.e("GB", "apply fail", t); }
    }
    private static void tryAttachOnce(final View container, final ClassLoader cl, final int attempt) {
        try {
            android.graphics.drawable.Drawable bg = container.getBackground();
            if (bg == null) { retry(container, cl, attempt, "bg null"); return; }
            String bgName = bg.getClass().getName();
            Object blurDrawable = Reflect.call(bg, "getBlurDrawable");
            if (blurDrawable == null) { retry(container, cl, attempt, "bg=" + bgName + " no getBlurDrawable"); return; }
            ModuleLog.d("GB", "bg=" + bgName + " blur=" + blurDrawable.getClass().getName());
            registerBlurDrawable(blurDrawable);
            rebuildSnapshots(container);
            GlyphSnapshot[] snaps = sSnapsMap.get(container);
            if (snaps == null || snaps.length == 0) { return; }
            ModuleLog.d("GB", "snapshot n=" + snaps.length);
            Class<?> iface = Class.forName(CLS_BLUR_PATH_PROVIDER, false, cl);
            Object provider = Proxy.newProxyInstance(cl, new Class<?>[]{ iface }, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    try {
                        if ("getPath".equals(method.getName()) && args != null && args.length >= 2) {
                            Path out = (Path) args[1];
                            if (out != null) out.set(snapshotsToPath(sSnapsMap.get(container)));
                        }
                    } catch (Throwable t) { ModuleLog.e("GB", "proxy getPath fail", t); }
                    return null;
                }
            });
            Method setPP = Reflect.method(blurDrawable.getClass(), "setPathProvider", 1);
            if (setPP == null) { ModuleLog.e("GB", "setPathProvider not found", null); return; }
            setPP.invoke(blurDrawable, provider);
            ModuleLog.d("GB", "setPathProvider ok");
            applyGlassColor(blurDrawable, cl);
            installRefreshPoller(container, blurDrawable);
            applyClockBrighten(container);
            Method inv = Reflect.method(blurDrawable.getClass(), "invalidatePath", 0);
            if (inv != null) { inv.invoke(blurDrawable); ModuleLog.d("GB", "invalidatePath ok"); }
            container.invalidate();
            ModuleLog.d("GB", "done container");
        } catch (Throwable t) {
            ModuleLog.e("GB", "tryAttachOnce fail attempt=" + attempt, t);
        }
    }
    private static volatile Method sSetRenderEffectMethod;
    private static volatile android.graphics.RenderEffect sBrightenEffect;
    private static volatile float sBrightenEffectGain = Float.NaN;
    private static volatile boolean sBrightenBuildFailed;
    private static volatile boolean sBrightenLogged;
    private static android.graphics.RenderEffect buildBrightenEffect() {
        final float gain = brightenGain();
        android.graphics.RenderEffect cached = sBrightenEffect;
        if (cached != null && Math.abs(sBrightenEffectGain - gain) < 0.001f) return cached;
        float[] m = new float[] {
                gain, 0f,   0f,   0f, 0f,
                0f,   gain, 0f,   0f, 0f,
                0f,   0f,   gain, 0f, 0f,
                0f,   0f,   0f,   1f, 0f
        };
        try {
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(m);
            android.graphics.ColorFilter cf = new android.graphics.ColorMatrixColorFilter(cm);
            Method cmf = android.graphics.RenderEffect.class.getMethod(
                    "createColorFilterEffect", android.graphics.ColorFilter.class);
            sBrightenEffect = (android.graphics.RenderEffect) cmf.invoke(null, cf);
            sBrightenEffectGain = gain;
            return sBrightenEffect;
        } catch (Throwable t) {
            ModuleLog.e("BRIGHT", "buildBrightenEffect fail", t);
            return null;
        }
    }
    private static Method findSetRenderEffect(View container) {
        Method cached = sSetRenderEffectMethod;
        if (cached != null) return cached;
        Class<?> target = android.view.View.class;
        try {
            cached = target.getMethod("setRenderEffect", android.graphics.RenderEffect.class);
            cached.setAccessible(true);
            sSetRenderEffectMethod = cached;
            return cached;
        } catch (Throwable t) {
            ModuleLog.e("BRIGHT", "View.setRenderEffect NOT FOUND", t);
            return null;
        }
    }

    static void applyGlassColor(Object blurDrawable, ClassLoader cl) {
        try {
            if (blurDrawable == null) return;
            Class<?> paramCls = Class.forName(CLS_BLUR_PARAM, false, cl);

            Method getBP = Reflect.method(blurDrawable.getClass(), "getBlurParam", 0);
            if (getBP == null) { ModuleLog.e("GLASS", "getBlurParam not found", null); return; }
            getBP.setAccessible(true);
            Object oldParam = getBP.invoke(blurDrawable);
            if (oldParam == null) { ModuleLog.e("GLASS", "oldParam null", null); return; }

            Object param = paramCls.newInstance();
            Method copyFrom = Reflect.method(paramCls, "copyFrom", 1);
            if (copyFrom == null) { ModuleLog.e("GLASS", "copyFrom not found", null); return; }
            copyFrom.setAccessible(true);
            copyFrom.invoke(param, oldParam);

            if (!FeatureFlags.CLOCK_GLASS) {
                return;
            }

            boolean dark = isUiDarkMode();
            int blendMode = dark ? 4 : 3;
            int blendA = FeatureFlags.CLOCK_GLASS_BLEND;
            int blendB = FeatureFlags.CLOCK_GLASS_MIX;

            if (blendA == 0 && blendB == 0) { return; }
            Method setMP = Reflect.method(paramCls, "setMaterialParams", 3);
            if (setMP != null) { setMP.setAccessible(true); setMP.invoke(param, blendMode, blendA, blendB); }

            Method setBP = Reflect.method(blurDrawable.getClass(), "setBlurParams", 1);
            if (setBP == null) { ModuleLog.e("GLASS", "setBlurParams not found", null); return; }
            setBP.setAccessible(true);
            setBP.invoke(blurDrawable, param);
            ModuleLog.d("GLASS", "applied mode=" + blendMode + " A=0x" + Integer.toHexString(blendA)
                    + " B=0x" + Integer.toHexString(blendB));
        } catch (Throwable t) {
            ModuleLog.e("GLASS", "applyGlassColor fail", t);
        }
    }

    private static boolean isUiDarkMode() {
        try {
            Object app = BlurLib.currentAppContext();
            if (app instanceof android.app.Application) {
                android.content.res.Resources res = ((android.app.Application) app).getResources();
                if (res != null) {
                    android.content.res.Configuration cfg = res.getConfiguration();
                    return (cfg.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static void applyClockBrighten(View container) {
        if (container == null) return;
        try {
            android.graphics.RenderEffect effect = buildBrightenEffect();
            if (effect == null) return;
            Method setRE = findSetRenderEffect(container);
            if (setRE == null) return;
            setRE.invoke(container, effect);
            if (!sBrightenLogged) { sBrightenLogged = true; ModuleLog.d("BRIGHT", "applied gain=" + brightenGain()); }
        } catch (Throwable t) {
            ModuleLog.e("BRIGHT", "applyClockBrighten fail", t);
        }
    }
    private static void retry(final View container, final ClassLoader cl, final int attempt, final String why) {
        if (attempt >= MAX_RETRY) { ModuleLog.e("GB", "give up: " + why, null); return; }
        // 已脱离窗口说明该 View 不会再展示，重试无意义，直接停。
        try { if (!container.isAttachedToWindow()) return; } catch (Throwable ignore) {}
        if (attempt == 0 || attempt == MAX_RETRY - 1) ModuleLog.d("GB", "retry(" + attempt + "): " + why);
        container.postDelayed(new Runnable() {
            @Override public void run() { tryAttachOnce(container, cl, attempt + 1); }
        }, RETRY_DELAY_MS);
    }
    static void onWidgetUpdated(final View container) {
        if (container == null) return;
        notifyContentMaybeChangedAll();
    }
    private static android.graphics.Bitmap drawableToBitmap(android.graphics.drawable.Drawable d, View host) {
        if (d == null) return null;
        try {
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                android.graphics.Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                if (b != null && !b.isRecycled()) return b;
            }
            if (d instanceof android.graphics.drawable.TransitionDrawable) {
                android.graphics.drawable.TransitionDrawable td =
                        (android.graphics.drawable.TransitionDrawable) d;
                int n = td.getNumberOfLayers();
                for (int i = n - 1; i >= 0; i--) {
                    android.graphics.drawable.Drawable layer = td.getDrawable(i);
                    android.graphics.Bitmap lb = drawableToBitmap(layer, host);
                    if (lb != null) return lb;
                }
            }
            int w = d.getIntrinsicWidth();
            int h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = (host != null) ? host.getWidth() : 0;
                h = (host != null) ? host.getHeight() : 0;
            }
            if (w <= 0) w = 1;
            if (h <= 0) h = 1;
            // 仅在确实需要时创建位图；若尺寸过大，先按比例缩到上限，避免全尺寸 ARGB_8888 分配。
            final int MAX_DIM = 192;
            int cw = w, ch = h;
            if (w > MAX_DIM || h > MAX_DIM) {
                if (w >= h) { cw = MAX_DIM; ch = Math.max(1, h * MAX_DIM / w); }
                else { ch = MAX_DIM; cw = Math.max(1, w * MAX_DIM / h); }
            }
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                    cw, ch, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            // 保存/恢复 bounds：d 是 ImageView 自己的 drawable，
            // 直接改 bounds 会破坏它的后续绘制（图标错位/拉伸）。
            android.graphics.Rect oldBounds = d.copyBounds();
            try {
                d.setBounds(0, 0, cw, ch);
                d.draw(c);
            } finally {
                d.setBounds(oldBounds);
            }
            return out;
        } catch (Throwable t) {
            ModuleLog.e("GB", "drawableToBitmap fail cls=" + (d == null ? "null" : d.getClass().getName()), t);
            return null;
        }
    }
    private static void appendWeatherIconSnapshots(View container, View base, java.util.ArrayList<GlyphSnapshot> list) {
        int[] iconIds = ClockIds.ICON_IDS;
        // 复用单个 Rect 与 Region：避免在像素扫描的双重循环里反复 new（原实现每行 new Rect）。
        final android.graphics.Rect reuseRect = new android.graphics.Rect();
        for (int id : iconIds) {
            try {
                View v = container.findViewById(id);
                if (!(v instanceof android.widget.ImageView)) {
                    View root = ViewUtils.descendantJustBelow(container, "AppWidgetHostView");
                    if (root != null) v = root.findViewById(id);
                }
                if (!(v instanceof android.widget.ImageView)) continue;
                android.widget.ImageView iv = (android.widget.ImageView) v;
                android.graphics.drawable.Drawable d = iv.getDrawable();
                if (d == null) continue;
                android.graphics.Bitmap bmp = drawableToBitmap(d, iv);
                if (bmp == null || bmp.isRecycled()) continue;
                if (bmp.getWidth() <= 0 || bmp.getHeight() <= 0) continue;
                int fp = iconFingerprint(bmp);
                int ivW = iv.getWidth(), ivH = iv.getHeight();
                java.util.HashMap<Integer, IconCacheEntry> cache = sIconCache.get(container);
                IconCacheEntry ce = (cache == null) ? null : cache.get(Integer.valueOf(id));
                if (ce != null && ce.fp == fp && ce.ivW == ivW && ce.ivH == ivH && ce.path != null) {
                    list.add(new GlyphSnapshot(ce.path, localOffsetX(v, base), localOffsetY(v, base)));
                    continue;
                }
                int bw = Math.min(bmp.getWidth(), ICON_SAMPLE_MAX_W);
                int bh = Math.max(1, bmp.getHeight() * bw / bmp.getWidth());
                android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(bmp, bw, bh, true);
                int[] px = new int[bw * bh];
                try {
                    small.getPixels(px, 0, bw, 0, 0, bw, bh);
                } finally {
                    // small 一定是本方法新建的（createScaledBitmap），用完立刻回收，降低 GC 压力。
                    // 注意：bmp 可能是 ImageView 自己的 BitmapDrawable 位图，绝不能 recycle。
                    try { small.recycle(); } catch (Throwable ignore) {}
                }
                android.graphics.Region region = new android.graphics.Region();
                int alphaThreshold = ICON_ALPHA_THRESHOLD;
                for (int y = 0; y < bh; y++) {
                    int runStart = -1;
                    for (int x = 0; x < bw; x++) {
                        int a = (px[y * bw + x] >>> 24);
                        if (a > alphaThreshold) {
                            if (runStart < 0) runStart = x;
                        } else {
                            if (runStart >= 0) {
                                reuseRect.set(runStart, y, x, y + 1);
                                region.op(reuseRect, android.graphics.Region.Op.UNION);
                                runStart = -1;
                            }
                        }
                    }
                    if (runStart >= 0) {
                        reuseRect.set(runStart, y, bw, y + 1);
                        region.op(reuseRect, android.graphics.Region.Op.UNION);
                    }
                }
                if (region.isEmpty()) continue;
                Path iconPath = new Path();
                android.graphics.RegionIterator it = new android.graphics.RegionIterator(region);
                android.graphics.Rect r = new android.graphics.Rect();
                while (it.next(r)) iconPath.addRect(r.left, r.top, r.right, r.bottom, Path.Direction.CW);
                float sx = (float) iv.getWidth() / bw;
                float sy = (float) iv.getHeight() / bh;
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setScale(sx, sy);
                iconPath.transform(m);
                float dx = localOffsetX(v, base);
                float dy = localOffsetY(v, base);
                list.add(new GlyphSnapshot(iconPath, dx, dy));
                if (cache == null) { cache = new java.util.HashMap<Integer, IconCacheEntry>(); sIconCache.put(container, cache); }
                cache.put(Integer.valueOf(id), new IconCacheEntry(fp, ivW, ivH, iconPath));
            } catch (Throwable t) { ModuleLog.e("GB", "iconPath fail id=0x" + Integer.toHexString(id), t); }
        }
    }
    static float localOffsetX(View v, View container) {
        return localOffset(v, container, true);
    }
    static float localOffsetY(View v, View container) {
        return localOffset(v, container, false);
    }
    private static float localOffset(View v, View container, boolean horizontal) {
        if (v == null) return 0f;
        if (v == container) return 0f;
        float acc = 0f;
        View cur = v;
        int guard = 0;
        while (cur != null && cur != container && guard++ < 64) {
            acc += horizontal ? cur.getLeft() : cur.getTop();
            android.view.ViewParent p = cur.getParent();
            if (!(p instanceof View)) break;
            View pv = (View) p;
            acc -= horizontal ? pv.getScrollX() : pv.getScrollY();
            cur = pv;
        }
        return acc;
    }
    static void applyIconAlpha(View container) {
        try {
            int[] iconIds = ClockIds.ICON_IDS;
            for (int id : iconIds) {
                View v = container.findViewById(id);
                if (v == null) {
                    View root = ViewUtils.descendantJustBelow(container, "AppWidgetHostView");
                    if (root != null) v = root.findViewById(id);
                }
                if (v != null && Math.abs(v.getAlpha() - iconAlpha()) > 0.01f) {
                    v.setAlpha(iconAlpha());
                    ModuleLog.d("GB", "iconAlpha id=0x" + Integer.toHexString(id)
                        + " cls=" + v.getClass().getSimpleName() + " alpha=" + iconAlpha());
                }
            }
        } catch (Throwable t) { ModuleLog.e("GB", "applyIconAlpha fail", t); }
    }

    static void applyTextAlpha(View container) {
        try {
            int a = 0x4D;
            try {

                if (FeatureFlags.CLOCK_GLASS) {
                    a = 0;
                } else {
                    float f = FeatureFlags.CLOCK_TEXT_ALPHA;
                    if (f < 0f) f = 0f; if (f > 1f) f = 1f;
                    a = (int) (f * 255f + 0.5f);
                }
            } catch (Throwable ignore) {}
            int[] ids = ClockIds.TEXT_IDS;
            for (int id : ids) {
                View v = container.findViewById(id);
                if (!(v instanceof TextView)) {
                    View root = ViewUtils.descendantJustBelow(container, "AppWidgetHostView");
                    if (root != null) v = root.findViewById(id);
                }
                if (!(v instanceof TextView)) continue;
                TextView tv = (TextView) v;
                if (ClockIds.isRealmeLockId(id)) {
                    // 真我 (realme) 时钟：文字用 View 级 alpha（与天气图标同机制）。
                    // 原因：时钟 App 会通过 RemoteViews.setTextColor 反复推送"实心白"，
                    // 单纯改 setTextColor 会被其覆盖（闪成实心白）；
                    // 而 View.setAlpha 是整层透明度，任何 setTextColor 都无法把它拉回不透明，
                    // 从而稳定露出容器上的模糊层。
                    float fa = a / 255f;
                    if (Math.abs(tv.getAlpha() - fa) > 0.01f) {
                        tv.setAlpha(fa);
                        // 真我文字透明度来源唯一（视图级），记录以便排查
                        ModuleLog.dv("TALPHA", "id=0x" + Integer.toHexString(id)
                                + " cls=" + tv.getClass().getSimpleName()
                                + " viewAlpha=" + fa);
                    }
                    continue;
                }
                android.content.res.ColorStateList csl = tv.getTextColors();
                if (csl == null) continue;
                int cur = csl.getDefaultColor();
                int newColor = (cur & 0x00FFFFFF) | (a << 24);
                if (cur != newColor) tv.setTextColor(newColor);
            }
        } catch (Throwable t) { ModuleLog.e("GB", "applyTextAlpha fail", t); }
    }
    static void rebuildSnapshots(View container) {
        try {
            final View base = container;
            int[] ids = ClockIds.TEXT_IDS;
            java.util.ArrayList<GlyphSnapshot> list = new java.util.ArrayList<GlyphSnapshot>();
            for (int id : ids) {
                View v = container.findViewById(id);
                if (!(v instanceof TextView)) {
                    View root = ViewUtils.descendantJustBelow(container, "AppWidgetHostView");
                    if (root != null) v = root.findViewById(id);
                }
                if (!(v instanceof TextView)) continue;
                TextView tv = (TextView) v;
                CharSequence cs = tv.getText();
                if (cs == null || cs.length() == 0) continue;
                String text = cs.toString();
                TextPaint tp = tv.getPaint();
                if (tp == null) continue;
                float baseline;
                float startX;
                android.text.Layout lay = tv.getLayout();
                boolean layFresh = false;
                if (lay != null && lay.getLineCount() > 0) {
                    CharSequence ls = lay.getText();
                    layFresh = (ls != null) && ls.toString().equals(text);
                }
                if (layFresh) {
                    int line = 0;
                    baseline = tv.getTotalPaddingTop() + lay.getLineBaseline(line) + GLYPH_DY;
                    startX = tv.getTotalPaddingLeft() + lay.getLineLeft(line);
                } else {
                    android.graphics.Paint.FontMetrics fm = tp.getFontMetrics();
                    baseline = tv.getHeight() * 0.5f - (fm.ascent + fm.descent) * 0.5f + GLYPH_DY;
                    float w = tp.measureText(text);
                    startX = (tv.getWidth() - w) * 0.5f;
                }
                float dx = localOffsetX(v, base);
                float dy = localOffsetY(v, base);
                Path localPath = new Path();
                tp.getTextPath(text, 0, text.length(), startX, baseline, localPath);
                list.add(new GlyphSnapshot(localPath, dx, dy));
            }
            appendWeatherIconSnapshots(container, base, list);
            // 注意：不能在此 reset/recycle 旧快照的 Path —— sIconCache 中的图标 Path 是
            // 跨次复用共享的（缓存命中时被直接放进新快照），reset 会破坏缓存导致图标错误。
            // 旧 Path 交给 GC（NativeAllocationRegistry）回收即可。
            sSnapsMap.put(container, list.isEmpty() ? null : list.toArray(new GlyphSnapshot[0]));
            applyIconAlpha(container);
            applyTextAlpha(container);
        } catch (Throwable t) {
            ModuleLog.e("GB", "rebuildSnapshots fail", t);
        }
    }
    private static final ThreadLocal<android.graphics.Matrix> TL_MATRIX =
            new ThreadLocal<android.graphics.Matrix>() {
                @Override protected android.graphics.Matrix initialValue() {
                    return new android.graphics.Matrix();
                }
            };
    private static final ThreadLocal<Path> TL_SCRATCH =
            new ThreadLocal<Path>() {
                @Override protected Path initialValue() { return new Path(); }
            };
    static Path snapshotsToPath(GlyphSnapshot[] snaps) {
        Path out = new Path();
        if (snaps == null) return out;
        Path scratch = TL_SCRATCH.get();
        android.graphics.Matrix mtx = TL_MATRIX.get();
        for (GlyphSnapshot s : snaps) {
            if (s.localPath == null) continue;
            if (s.offX == 0f && s.offY == 0f) {
                out.addPath(s.localPath);
                continue;
            }
            scratch.set(s.localPath);
            mtx.setTranslate(s.offX, s.offY);
            scratch.transform(mtx);
            out.addPath(scratch);
        }
        return out;
    }
    private static final android.os.Handler sHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static void installRefreshPoller(final View container, final Object blurDrawable) {
        ClockBlurStateMachine pr;
        synchronized (sRunners) {
            java.lang.ref.WeakReference<ClockBlurStateMachine> ref = sRunners.get(container);
            if (ref != null && ref.get() != null) { return; }
            pr = new ClockBlurStateMachine(container, blurDrawable);
            sRunners.put(container, new java.lang.ref.WeakReference<>(pr));
        }
        pr.start();
    }
    private static final long FALLBACK_CHECK_MS = 2000L;
    private static final long FALLBACK_SCREENOFF_MS = 8000L;
    // 可见性待恢复时的探测间隔。
    // 发热优化⑧：250ms（4Hz）→ 800ms。原注释担心「不可见期间时间变动延迟刷新」，
    // 但实际上不可见时用户看不到内容，稍晚刷新无感；反而 4Hz 常驻唤醒在
    // 「被遮挡但未 detach」的场景下会持续耗电发热（卡在 tick 状态）。
    // 可见性恢复有两条更快的路径兜底：① OnPreDraw（可见即首帧触发）
    // ② onContentChanged（内容变更驱动），800ms 仅作为「广播丢失」的最终保险。
    private static final long FALLBACK_HIDDEN_PROBE_MS = 800L;
    private static final long REBUILD_SETTLE_MS = 50L;
    private static final class ClockBlurStateMachine {
        private static final int IDLE = 0;
        private static final int DIRTY = 1;
        private static final int REBUILDING = 2;
        private static final int PAUSED = 3;
        private final View container;
        private final Object blurDrawable;
        // state 仅在本类 synchronized 方法内读写，无需 volatile（避免与 synchronized 混用造成语义混乱）。
        private int state = IDLE;
        private String lastStateSig = null;
        private String lastFingerprint = null;
        private final StringBuilder sb = new StringBuilder(128);
        private final StringBuilder sb2 = new StringBuilder(128);
        private final Runnable rebuildTask = new Runnable() {
            @Override public void run() { performRebuild(); }
        };
        private final Runnable fallbackTask = new Runnable() {
            @Override public void run() { fallbackCheck(); }
        };
        // 重建前等待 preDraw 的监听器：必须保留引用，以便 View 脱离窗口时主动移除。
        // 否则若 invalidate() 后 View 立即 detach，onPreDraw 永不触发，
        // listener 会滞留在 ViewTreeObserver 上并强引用 container（泄漏）。
        private android.view.ViewTreeObserver.OnPreDrawListener pendingPreDrawListener = null;
        ClockBlurStateMachine(View container, Object blurDrawable) {
            this.container = container;
            this.blurDrawable = blurDrawable;
        }
        void start() {
            transitionToDirty();
        }
        synchronized void onContentChanged() {
            // 灭屏期间的更新由亮屏时的无条件重建统一兑现（见 onScreenStateChanged），此处直接丢弃即可。
            if (!sScreenOn) return;
            transitionToDirty();
        }
        synchronized void onScreenStateChanged() {
            if (sScreenOn) {
                // 亮屏后强制推进一次重建（覆盖 IDLE/DIRTY 死角，或灭屏期间被丢弃的更新）。
                // 注意：统一走 transitionToDirty()，不要直接 postOnAnimation，
                // 否则会绕过 MIN_REBUILD_INTERVAL_MS 节流并造成重建堆积（发热）。
                // 先把状态复位到可推进态（DIRTY/REBUILDING 会被 transitionToDirty 去重拦截）。
                if (state == DIRTY || state == REBUILDING) {
                    // 已有重建在路上：置回 IDLE 以便重新触发（旧任务会被 rebuildTask 内的状态校验丢弃）
                    state = IDLE;
                }
                transitionToDirty();
            } else {
                state = PAUSED;
                container.removeCallbacks(rebuildTask);
                // 不再移除 fallbackTask：让低频探测在灭屏期间续排，
                // 保证亮屏后即使广播丢失也能自行恢复（见 fallbackCheck）。
                // ★ 先 removeCallbacks 再 postDelayed：避免重复的 SCREEN_OFF 广播导致任务堆积
                //   （与 scheduleFallback/scheduleScreenOffProbe 保持一致）。
                sHandler.removeCallbacks(fallbackTask);
                sHandler.postDelayed(fallbackTask, FALLBACK_SCREENOFF_MS);
            }
        }
        private long lastRebuildAt = 0L;
        // 「不可见期间收到内容变更」的标记：一旦可见性恢复，立即重建（不依赖探测周期）。
        private volatile boolean pendingRebuild = false;
        private static final long MIN_REBUILD_INTERVAL_MS = 100L;
        private synchronized void transitionToDirty() {
            if (state == PAUSED && !sScreenOn) return;
            if (state == DIRTY || state == REBUILDING) return;
            state = DIRTY;
            // rebuildTask 排在 View 的 Handler 上，取消也必须用同一个 Handler（container），
            // 否则 removeCallbacks 对另一个 Handler 无效，会造成重建任务堆积（发热）。
            container.removeCallbacks(rebuildTask);
            sHandler.removeCallbacks(fallbackTask);
            long now = android.os.SystemClock.uptimeMillis();
            long since = now - lastRebuildAt;
            long delay = (since >= MIN_REBUILD_INTERVAL_MS) ? 0L : (MIN_REBUILD_INTERVAL_MS - since);
            if (delay == 0L) {
                container.postOnAnimation(rebuildTask);
            } else {
                container.postDelayed(rebuildTask, delay);
            }
        }
        private synchronized void performRebuild() {
            container.removeCallbacks(rebuildTask);
            if (state == PAUSED && !sScreenOn) return;
            if (!sScreenOn) {
                // 灭屏否决重建时必须保留低频探测，否则状态机将停在「无任务」状态，
                // 亮屏后若没有新的屏事件就永远不会重建。
                state = PAUSED;
                scheduleScreenOffProbe();
                return;
            }
            if (!ViewUtils.isReallyVisible(container)) {
                // 不可见期间到达的内容变更不丢弃：置标记，待可见性恢复时立即重建。
                pendingRebuild = true;
                state = IDLE;
                scheduleFallbackSoon();
                return;
            }
            state = REBUILDING;
            final android.view.ViewTreeObserver vto = container.getViewTreeObserver();
            if (vto != null && vto.isAlive()) {
                final android.view.ViewTreeObserver.OnPreDrawListener l =
                        new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        final android.view.ViewTreeObserver cur = container.getViewTreeObserver();
                        if (cur != null && cur.isAlive()) cur.removeOnPreDrawListener(this);
                        pendingPreDrawListener = null;
                        synchronized (ClockBlurStateMachine.this) {
                            // 仅在本次重建仍有效（REBUILDING）时执行，
                            // 避免亮屏重排/状态切换后旧监听器造成重复重建。
                            if (state != REBUILDING) return true;
                            try {
                                doRebuild();
                                applyClockBrighten(container);
                            } catch (Throwable t) {
                                ModuleLog.e("GB", "sm predraw rebuild fail", t);
                            }
                        }
                        return true;
                    }
                };
                pendingPreDrawListener = l;
                vto.addOnPreDrawListener(l);
                container.invalidate();
            } else {
                try {
                    doRebuild();
                    applyClockBrighten(container);
                } catch (Throwable t) {
                    ModuleLog.e("GB", "sm rebuild fail", t);
                }
            }
            container.postDelayed(new Runnable() {
                @Override public void run() {
                    synchronized (ClockBlurStateMachine.this) {
                        if (state == PAUSED) return;
                        try {
                            String cur = computeSignature();
                            if (lastStateSig == null || !lastStateSig.equals(cur)) {
                                doRebuild();
                                applyClockBrighten(container);
                            }
                        } catch (Throwable t) {
                            ModuleLog.e("GB", "sm settle fail", t);
                        }
                        lastRebuildAt = android.os.SystemClock.uptimeMillis();
                        state = IDLE;
                        scheduleFallback();
                    }
                }
            }, REBUILD_SETTLE_MS);
        }
        private void scheduleFallback() {
            sHandler.removeCallbacks(fallbackTask);
            sHandler.postDelayed(fallbackTask, FALLBACK_CHECK_MS);
        }
        private void scheduleFallbackSoon() {
            sHandler.removeCallbacks(fallbackTask);
            sHandler.postDelayed(fallbackTask, FALLBACK_HIDDEN_PROBE_MS);
        }
        private void scheduleScreenOffProbe() {
            sHandler.removeCallbacks(fallbackTask);
            sHandler.postDelayed(fallbackTask, FALLBACK_SCREENOFF_MS);
        }
        private synchronized void fallbackCheck() {
            // 容器已脱离窗口（时钟被移除/重建）时终止轮询，
            // 否则静态 Handler 会一直持有本状态机（进而持有 View）造成泄漏。
            if (!container.isAttachedToWindow()) {
                state = IDLE;
                container.removeCallbacks(rebuildTask);
                sHandler.removeCallbacks(fallbackTask);
                // 移除可能残留的 preDraw 监听器（若 invalidate 后 View 立即 detach，
                // onPreDraw 可能永不触发，listener 会滞留在 ViewTreeObserver 上造成泄漏）。
                if (pendingPreDrawListener != null) {
                    try {
                        android.view.ViewTreeObserver vo = container.getViewTreeObserver();
                        if (vo != null && vo.isAlive()) vo.removeOnPreDrawListener(pendingPreDrawListener);
                    } catch (Throwable ignored) {}
                    pendingPreDrawListener = null;
                }
                synchronized (sRunners) { sRunners.remove(container); }
                // 显式清理该容器的快照与图标缓存，避免 WeakHashMap 中旧条目滞留。
                try { sSnapsMap.remove(container); } catch (Throwable ignored) {}
                try { sIconCache.remove(container); } catch (Throwable ignored) {}
                return;
            }
            if (!sScreenOn) {
                // 灭屏不再让轮询自终止（原实现 return 后无人再排任务，
                // 一旦 SCREEN_ON 广播丢失/延迟就会永久停摆）。改为低频续排。
                state = PAUSED;
                scheduleScreenOffProbe();
                return;
            }
            if (!ViewUtils.isReallyVisible(container)) { scheduleFallbackSoon(); return; }
            // 可见性已恢复：若不可见期间有内容变更，立即重建（无需等指纹比对，响应≈0）。
            if (pendingRebuild) {
                pendingRebuild = false;
                transitionToDirty();
                return;
            }
            try {
                String cur = computeFallbackFingerprint();
                if (lastFingerprint == null || !lastFingerprint.equals(cur)) {
                    transitionToDirty();
                } else {
                    scheduleFallback();
                }
            } catch (Throwable t) {
                ModuleLog.e("GB", "sm fallback fail", t);
                scheduleFallback();
            }
        }
        private String computeFallbackFingerprint() {
            sb.setLength(0);
            int[] ids = ClockIds.TEXT_IDS;
            for (int id : ids) {
                View v = container.findViewById(id);
                if (!(v instanceof TextView)) {
                    View root = ViewUtils.descendantJustBelow(container, "AppWidgetHostView");
                    if (root != null) v = root.findViewById(id);
                }
                if (!(v instanceof TextView)) { sb.append('-').append('|'); continue; }
                CharSequence cs = ((TextView) v).getText();
                sb.append(cs == null ? "" : cs.toString());
                sb.append('|');
            }
            for (int id : ClockIds.ICON_IDS) {
                View v = container.findViewById(id);
                if (!(v instanceof View)) {
                    View root = ViewUtils.descendantJustBelow(container, "AppWidgetHostView");
                    if (root != null) v = root.findViewById(id);
                }
                sb.append(v == null ? '-' : (v.getWidth() + "x" + v.getHeight()));
                sb.append('|');
            }
            return sb.toString();
        }
        private String computeSignature() {
            sb.setLength(0);
            int[] ids = ClockIds.TEXT_IDS;
            for (int id : ids) {
                View v = container.findViewById(id);
                if (!(v instanceof TextView)) {
                    View root = ViewUtils.descendantJustBelow(container, "AppWidgetHostView");
                    if (root != null) v = root.findViewById(id);
                }
                if (!(v instanceof TextView)) { sb.append('-').append('|'); continue; }
                CharSequence cs = ((TextView) v).getText();
                sb.append(cs == null ? "" : cs.toString());
                sb.append('@').append((int) localOffsetX(v, container))
                  .append(',').append((int) localOffsetY(v, container));
                sb.append('|');
            }
            for (int id : ClockIds.ICON_IDS) {
                View v = container.findViewById(id);
                if (!(v instanceof View)) {
                    View root = ViewUtils.descendantJustBelow(container, "AppWidgetHostView");
                    if (root != null) v = root.findViewById(id);
                }
                sb.append(v == null ? '-' : (v.getWidth() + "x" + v.getHeight()));
                sb.append('|');
            }
            return sb.toString();
        }
        // 一次遍历同时产出 signature 与 fingerprint，避免 doRebuild 里重复整树遍历。
        private void computeBoth(StringBuilder sigOut, StringBuilder fpOut) {
            sigOut.setLength(0);
            fpOut.setLength(0);
            int[] ids = ClockIds.TEXT_IDS;
            for (int id : ids) {
                View v = container.findViewById(id);
                if (!(v instanceof TextView)) {
                    View root = ViewUtils.descendantJustBelow(container, "AppWidgetHostView");
                    if (root != null) v = root.findViewById(id);
                }
                if (!(v instanceof TextView)) { sigOut.append('-').append('|'); fpOut.append('-').append('|'); continue; }
                CharSequence cs = ((TextView) v).getText();
                String t = (cs == null ? "" : cs.toString());
                fpOut.append(t).append('|');
                sigOut.append(t).append('@').append((int) localOffsetX(v, container))
                      .append(',').append((int) localOffsetY(v, container)).append('|');
            }
            for (int id : ClockIds.ICON_IDS) {
                View v = container.findViewById(id);
                if (!(v instanceof View)) {
                    View root = ViewUtils.descendantJustBelow(container, "AppWidgetHostView");
                    if (root != null) v = root.findViewById(id);
                }
                String sz = (v == null ? "-" : (v.getWidth() + "x" + v.getHeight()));
                sigOut.append(sz).append('|');
                fpOut.append(sz).append('|');
            }
        }
        private void doRebuild() {
            rebuildSnapshots(container);
            // 重建已完成：清除「待重建」标记（本次已消费不可见期间的变更）。
            pendingRebuild = false;
            try { applyGlassColor(blurDrawable, container.getContext().getClassLoader()); } catch (Throwable ignored) {}
            // 一次遍历同时得到 signature 与 fingerprint（原实现分两次整树遍历）。
            computeBoth(sb, sb2);
            lastStateSig = sb.toString();
            lastFingerprint = sb2.toString();
            Method inv = Reflect.method(blurDrawable.getClass(), "invalidatePath", 0);
            if (inv != null) { try { inv.setAccessible(true); inv.invoke(blurDrawable); } catch (Throwable ignored) {} }
            container.invalidate();
        }
    }
}
