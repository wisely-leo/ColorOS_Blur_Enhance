package com.shortcutblur;
import android.graphics.Path;
import android.text.TextPaint;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
public class GlyphBlurRenderer {
    private static final int MAX_RETRY = 12;
    private static final float GLYPH_DY = 0.0f;
    private static final long RETRY_DELAY_MS = 120L;
    private static final int ICON_ALPHA_THRESHOLD = 40;
    private static final int ICON_SAMPLE_MAX_W = 96;
    /** 天气图标透明度：读可调参数（默认0.30）*/
    private static float iconAlpha() {
        try {
            // 自定义混色启用时：时钟透明度强制为 0（由混色接管）
            if (FeatureFlags.CLOCK_GLASS) return 0f;
            return FeatureFlags.CLOCK_ICON_ALPHA;
        } catch (Throwable t) { return 0.30f; }
    }
    /** 提亮增益：读可调参数（默认1.25）*/
    private static float brightenGain() {
        try {
            // 自定义混色启用时：提亮增益强制为 1.0（不变亮，由混色接管）
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
    private static final java.util.WeakHashMap<View, ClockBlurStateMachine> sRunners = new java.util.WeakHashMap<View, ClockBlurStateMachine>();
    /** 记录所有时钟 blurDrawable（供 setBlurParamsInternal hook 做身份判断）*/
    private static final java.util.ArrayList<Object> sClockBlurDrawables = new java.util.ArrayList<Object>();
    public static void registerBlurDrawable(Object bd) {
        if (bd == null) return;
        synchronized (sClockBlurDrawables) {
            for (Object o : sClockBlurDrawables) { if (o == bd) return; }
            sClockBlurDrawables.add(bd);
        }
    }
    public static boolean isClockBlurDrawable(Object bd) {
        if (bd == null) return false;
        synchronized (sClockBlurDrawables) {
            for (Object o : sClockBlurDrawables) { if (o == bd) return true; }
        }
        return false;
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
        if (!changed) return;
        try {
            synchronized (sRunners) {
                for (ClockBlurStateMachine pr : new java.util.ArrayList<ClockBlurStateMachine>(sRunners.values())) pr.onScreenStateChanged();
            }
        } catch (Throwable t) { ModuleLog.e("GB", "screenStateChanged fail", t); }
    }
    public static void notifyContentMaybeChangedAll() {
        try {
            java.util.List<ClockBlurStateMachine> list;
            synchronized (sRunners) { list = new java.util.ArrayList<ClockBlurStateMachine>(sRunners.values()); }
            for (ClockBlurStateMachine pr : list) pr.onContentChanged();
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
            Class<?> iface = Class.forName("com.oplus.posteffect.path.BlurDrawablePathProvider", false, cl);
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
    /**
     * 彩色玻璃：给模糊 drawable 上色。
     * 调用链：BlurParam.setMaterialParams(blendMode, blendColorA, blendColorB)
     *         -> ContinuousBlurDrawable.setBlurParams(BlurParam)
     */
    static void applyGlassColor(Object blurDrawable, ClassLoader cl) {
        try {
            if (blurDrawable == null) return;
            Class<?> paramCls = Class.forName("com.oplus.posteffect.BlurParam", false, cl);
            // 1) 读当前参数（保留 blurRadius/blurType 等）
            Method getBP = Reflect.method(blurDrawable.getClass(), "getBlurParam", 0);
            if (getBP == null) { ModuleLog.e("GLASS", "getBlurParam not found", null); return; }
            getBP.setAccessible(true);
            Object oldParam = getBP.invoke(blurDrawable);
            if (oldParam == null) { ModuleLog.e("GLASS", "oldParam null", null); return; }
            // 2) 新建并复制旧参数
            Object param = paramCls.newInstance();
            Method copyFrom = Reflect.method(paramCls, "copyFrom", 1);
            if (copyFrom == null) { ModuleLog.e("GLASS", "copyFrom not found", null); return; }
            copyFrom.setAccessible(true);
            copyFrom.invoke(param, oldParam);
            // 3) 开关关闭 → 不上色（保持原样）
            if (!FeatureFlags.CLOCK_GLASS) {
                return;
            }
            // 4) 用 setMaterialParams(mode, A, B) 染色（颜色从 FeatureFlags 读）
            boolean dark = isUiDarkMode();
            int blendMode = dark ? 4 : 3;
            int blendA = FeatureFlags.CLOCK_GLASS_BLEND;
            int blendB = FeatureFlags.CLOCK_GLASS_MIX;
            // 无色 → 跳过
            if (blendA == 0 && blendB == 0) { return; }
            Method setMP = Reflect.method(paramCls, "setMaterialParams", 3);
            if (setMP != null) { setMP.setAccessible(true); setMP.invoke(param, blendMode, blendA, blendB); }
            // 4) 写回
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
    /** 判断当前是否深色模式（用于彩色玻璃 blendMode 自适应）。 */
    private static boolean isUiDarkMode() {
        try {
            Class<?> appCls = Class.forName("android.app.ActivityThread", false, null);
            Object app = appCls.getMethod("currentApplication").invoke(null);
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
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return out;
        } catch (Throwable t) {
            ModuleLog.e("GB", "drawableToBitmap fail cls=" + (d == null ? "null" : d.getClass().getName()), t);
            return null;
        }
    }
    private static void appendWeatherIconSnapshots(View container, View base, java.util.ArrayList<GlyphSnapshot> list) {
        int[] iconIds = ClockIds.ICON_IDS;
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
                small.getPixels(px, 0, bw, 0, 0, bw, bh);
                android.graphics.Region region = new android.graphics.Region();
                int alphaThreshold = ICON_ALPHA_THRESHOLD;
                for (int y = 0; y < bh; y++) {
                    int runStart = -1;
                    for (int x = 0; x < bw; x++) {
                        int a = (px[y * bw + x] >>> 24);
                        if (a > alphaThreshold) {
                            if (runStart < 0) runStart = x;
                        } else {
                            if (runStart >= 0) { region.op(new android.graphics.Rect(runStart, y, x, y + 1), android.graphics.Region.Op.UNION); runStart = -1; }
                        }
                    }
                    if (runStart >= 0) region.op(new android.graphics.Rect(runStart, y, bw, y + 1), android.graphics.Region.Op.UNION);
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
    /** 实时刷新时钟文字 alpha：遍历 TEXT_IDS，改已存在 TextView 文字色的 alpha */
    static void applyTextAlpha(View container) {
        try {
            int a = 0x4D;
            try {
                // 自定义混色启用时：文字透明度强制为 0（与 ClockTextAlphaHook 保持一致）
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
            if (sRunners.containsKey(container)) { return; }
            pr = new ClockBlurStateMachine(container, blurDrawable);
            sRunners.put(container, pr);
        }
        pr.start();
    }
    private static final long FALLBACK_CHECK_MS = 2000L;
    private static final long FALLBACK_HIDDEN_PROBE_MS = 250L;
    private static final long REBUILD_SETTLE_MS = 50L;
    private static final class ClockBlurStateMachine {
        private static final int IDLE = 0;
        private static final int DIRTY = 1;
        private static final int REBUILDING = 2;
        private static final int PAUSED = 3;
        private final View container;
        private final Object blurDrawable;
        private volatile int state = IDLE;
        private String lastStateSig = null;
        private String lastFingerprint = null;
        private final StringBuilder sb = new StringBuilder(128);
        private final Runnable rebuildTask = new Runnable() {
            @Override public void run() { performRebuild(); }
        };
        private final Runnable fallbackTask = new Runnable() {
            @Override public void run() { fallbackCheck(); }
        };
        ClockBlurStateMachine(View container, Object blurDrawable) {
            this.container = container;
            this.blurDrawable = blurDrawable;
        }
        void start() {
            transitionToDirty();
        }
        void onContentChanged() {
            if (!sScreenOn) return;
            transitionToDirty();
        }
        synchronized void onScreenStateChanged() {
            if (sScreenOn) {
                if (state == PAUSED) transitionToDirty();
            } else {
                state = PAUSED;
                sHandler.removeCallbacks(rebuildTask);
                sHandler.removeCallbacks(fallbackTask);
            }
        }
        private long lastRebuildAt = 0L;
        private static final long MIN_REBUILD_INTERVAL_MS = 100L;
        private synchronized void transitionToDirty() {
            if (state == PAUSED && !sScreenOn) return;
            if (state == DIRTY || state == REBUILDING) return;
            state = DIRTY;
            sHandler.removeCallbacks(rebuildTask);
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
            sHandler.removeCallbacks(rebuildTask);
            if (state == PAUSED && !sScreenOn) return;
            if (!sScreenOn) { state = PAUSED; return; }
            if (!ViewUtils.isReallyVisible(container)) {
                state = IDLE;
                scheduleFallbackSoon();
                return;
            }
            state = REBUILDING;
            final android.view.ViewTreeObserver vto = container.getViewTreeObserver();
            if (vto != null && vto.isAlive()) {
                vto.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        final android.view.ViewTreeObserver cur = container.getViewTreeObserver();
                        if (cur != null && cur.isAlive()) cur.removeOnPreDrawListener(this);
                        synchronized (ClockBlurStateMachine.this) {
                            if (state == PAUSED) return true;
                            try {
                                doRebuild();
                                applyClockBrighten(container);
                            } catch (Throwable t) {
                                ModuleLog.e("GB", "sm predraw rebuild fail", t);
                            }
                        }
                        return true;
                    }
                });
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
        private synchronized void fallbackCheck() {
            if (!sScreenOn) { state = PAUSED; return; }
            if (!ViewUtils.isReallyVisible(container)) { scheduleFallbackSoon(); return; }
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
        private void doRebuild() {
            rebuildSnapshots(container);
            try { applyGlassColor(blurDrawable, container.getContext().getClassLoader()); } catch (Throwable ignored) {}
            lastStateSig = computeSignature();
            lastFingerprint = computeFallbackFingerprint();
            Method inv = Reflect.method(blurDrawable.getClass(), "invalidatePath", 0);
            if (inv != null) { try { inv.setAccessible(true); inv.invoke(blurDrawable); } catch (Throwable ignored) {} }
            container.invalidate();
        }
    }
}
