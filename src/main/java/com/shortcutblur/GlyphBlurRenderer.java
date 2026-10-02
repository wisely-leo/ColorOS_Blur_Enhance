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

    private static final long POLL_INTERVAL_ON_MS = 500L;

    private static final long POLL_INTERVAL_OFF_MS = 3000L;

    private static final long POLL_INTERVAL_HIDDEN_MS = 1500L;

    private static final long POLL_INTERVAL_MAX_MS = 2000L;

    private static final long POLL_INTERVAL_TICK_MS = 500L;

    private static final int  POLL_STABLE_THRESHOLD = 6;

    private static final float ICON_ALPHA = 0.30f;

    private static final int ICON_ALPHA_THRESHOLD = 40;

    private static final int ICON_SAMPLE_MAX_W = 96;

    private static final float BRIGHTEN_GAIN = 1.25f;

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

    // 天气图标缓存：按「时钟组件容器」分组，避免不同组件的图标尺寸差异导致复用错误。
    // 每项同时记录指纹与 iv 尺寸，复用前双重校验。
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
    private static volatile boolean sBrightenBuildFailed;
    private static volatile boolean sBrightenLogged;

    private static android.graphics.RenderEffect buildBrightenEffect() {
        if (sBrightenEffect != null) return sBrightenEffect;
        if (sBrightenBuildFailed) return null;

        final float gain = BRIGHTEN_GAIN;
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
            return sBrightenEffect;
        } catch (Throwable t) {
            sBrightenBuildFailed = true;
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

    static void applyClockBrighten(View container) {
        if (container == null) return;
        try {
            android.graphics.RenderEffect effect = buildBrightenEffect();
            if (effect == null) return;
            Method setRE = findSetRenderEffect(container);
            if (setRE == null) return;

            setRE.invoke(container, effect);
            if (!sBrightenLogged) { sBrightenLogged = true; ModuleLog.d("BRIGHT", "applied gain=" + BRIGHTEN_GAIN); }
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

        // updateAppWidget 意味着该组件的所有时钟元素都可能变化：广播给全部状态机
        //（与原事件驱动保持一致），同时刷新自身。
        notifyContentMaybeChangedAll();
    }

    private static void rebuildSnapshotsNow(View container) {
        try {
            Object blur = null;
            android.graphics.drawable.Drawable bg = container.getBackground();
            if (bg != null) blur = Reflect.call(bg, "getBlurDrawable");
            rebuildSnapshots(container);
            if (blur != null) {
                Method inv = Reflect.method(blur.getClass(), "invalidatePath", 0);
                if (inv != null) { inv.setAccessible(true); inv.invoke(blur); }
            }
            container.invalidate();
        } catch (Throwable t) { ModuleLog.e("GB", "rebuildSnapshotsNow fail", t); }
    }

    /** 将任意 Drawable 转为 Bitmap，覆盖 BitmapDrawable / VectorDrawable / AnimatedVectorDrawable /
     *  TransitionDrawable / ColorDrawable 等，避免"换了矢量/动画图标后模糊丢失"。
     *  优先走快路径：BitmapDrawable 直接取 bitmap（省一次绘制）。 */
    private static android.graphics.Bitmap drawableToBitmap(android.graphics.drawable.Drawable d, View host) {
        if (d == null) return null;
        try {
            // 快路径：BitmapDrawable（且 bitmap 未回收）。
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                android.graphics.Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                if (b != null && !b.isRecycled()) return b;
            }
            // TransitionDrawable：取当前活动层（避免拿到过渡中间态/空层）。
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
            // 通用路径：将 Drawable 绘制到 Bitmap。尺寸优先取 Drawable 自身固有尺寸，
            // 无固有尺寸时退回 ImageView 尺寸，最后兜底 1x1。
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
                if (v != null && Math.abs(v.getAlpha() - ICON_ALPHA) > 0.01f) {
                    v.setAlpha(ICON_ALPHA);
                    ModuleLog.d("GB", "iconAlpha id=0x" + Integer.toHexString(id)
                        + " cls=" + v.getClass().getSimpleName() + " alpha=" + ICON_ALPHA);
                }
            }
        } catch (Throwable t) { ModuleLog.e("GB", "applyIconAlpha fail", t); }
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
                // 仅当 Layout 已生成且缓存内容与当前文字一致时才使用它；
                // 否则（setText 后布局尚未完成）Layout 里仍是旧文字，
                // 直接采用会产生错误的 startX/baseline，导致模糊区域抖动/错位。
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

    /**
     * 时钟模糊「事件驱动状态机」。
     *
     * 设计目标：以低频/零轮询代替原来的 500ms 固定轮询，降低空闲功耗与掉帧。
     *
     * 状态：
     *   IDLE        —— 空闲，等待事件；仅保留一个「兜底校验」定时器（间隔较大）。
     *   DIRTY       —— 收到内容/布局变化事件，等待合并重建。
     *   REBUILDING  —— 正在重建快照（同一帧内的多次事件只重建一次）。
     *   PAUSED      —— 屏幕关闭或组件不可见；不消耗任何调度。
     *
     * 事件：
     *   onContentChanged() —— setText / updateAppWidget / 可见性变化
     *   onScreenStateChanged() —— 屏幕开关
     *   markDirty()        —— 合并重建（下一帧执行一次）
     *
     * 兜底：IDLE 状态下每 FALLBACK_CHECK_MS 做一次「只比对、不重建」的轻量校验，
     *       若发现文字/偏移变化，则转入 DIRTY，避免事件源漏报导致模糊过期。
     */
    private static final long FALLBACK_CHECK_MS = 2000L;
    /** 组件不可见时的可见性探测间隔（越短则恢复可见后越快更新，但会略增开销）。 */
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
        /** 兜底比对用的轻量指纹（文字 + 图标尺寸，无偏移）。与 lastStateSig 相互独立。 */
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
            // 首次进入：置脏并立即调度一次重建，取代原 poller 的 schedule(0)。
            transitionToDirty();
        }

        /** 事件入口：内容/布局可能变化。合并到下一帧执行一次重建。 */
        void onContentChanged() {
            if (!sScreenOn) return;
            // PAUSED 只表示"暂停重建"，不应成为永久门禁：事件到达即恢复。
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
            if (state == PAUSED && !sScreenOn) return; // 仅屏幕关闭时才是真正的暂停
            if (state == DIRTY || state == REBUILDING) return; // 已排队，合并即可
            state = DIRTY;
            sHandler.removeCallbacks(rebuildTask);
            sHandler.removeCallbacks(fallbackTask);
            // 节流：高频事件（拖动、快速刷新）场景下，保证两次重建之间至少间隔
            // MIN_REBUILD_INTERVAL_MS，避免每帧重建造成掉帧；若已超过间隔则立即执行。
            long now = android.os.SystemClock.uptimeMillis();
            long since = now - lastRebuildAt;
            long delay = (since >= MIN_REBUILD_INTERVAL_MS) ? 0L : (MIN_REBUILD_INTERVAL_MS - since);
            if (delay == 0L) {
                // 合并：下一帧执行，保证同一帧内的多个事件只触发一次重建。
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
                // 组件当前不可见（如拖动中）：跳过本次重建，但保持 IDLE 并用较短间隔探测，
                // 一旦重新可见即可尽快恢复更新，不进入永久 PAUSED。
                state = IDLE;
                scheduleFallbackSoon();
                return;
            }
            state = REBUILDING;
            // 【关键】在「预绘制」回调中重建：此时 TextView 的 Layout 一定已完成并与
            // 当前文字一致，避免 setText 与 layout 之间的时间差导致 startX/baseline
            // 取到旧值（表现为小时区域短暂错位/抖动）。
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
            // 重建完成。不再做无条件的二次重建（那是抖动的来源）；
            // 仅在一次轻微延迟后做一次「签名校验」，只有确实仍有变化时才补一次，
            // 从而既消除抖动，又不会漏掉 setText 与布局完成之间的时间差。
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

        /** 不可见时使用更短的探测间隔，保证重新可见后能尽快恢复更新。 */
        private void scheduleFallbackSoon() {
            sHandler.removeCallbacks(fallbackTask);
            sHandler.postDelayed(fallbackTask, FALLBACK_HIDDEN_PROBE_MS);
        }

        /** 兜底校验：仅比对轻量指纹（文字 + 图标尺寸），无变化则不重建。
         *  开销说明：不遍历父链、不计算偏移，单次仅 8~10 次 getText/getWidth，远低于全量签名。 */
        private synchronized void fallbackCheck() {
            if (!sScreenOn) { state = PAUSED; return; }
            // 不可见时：用较短间隔继续探测，一旦恢复可见可立即重建（避免恢复后长时间不更新）。
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

        /** 轻量指纹：仅文字 + 图标尺寸，不含偏移（避免兜底时反复爬父链）。
         *  位置变化必然伴随 layout/updateAppWidget 事件，由事件源负责，兜底无需重复覆盖。 */
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

        /** 采集文字 + 实时偏移签名。仅比对，不触发重建。 */
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
            // 天气图标尺寸也纳入签名（图标变化需重建）。
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
            lastStateSig = computeSignature();
            lastFingerprint = computeFallbackFingerprint(); // 同步更新轻量指纹，供兜底比对
            Method inv = Reflect.method(blurDrawable.getClass(), "invalidatePath", 0);
            if (inv != null) { try { inv.setAccessible(true); inv.invoke(blurDrawable); } catch (Throwable ignored) {} }
            container.invalidate();
        }
    }

}
