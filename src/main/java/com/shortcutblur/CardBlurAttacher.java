package com.shortcutblur;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.WeakHashMap;

/**
 * 卡片模糊附加器：给桌面 OPPO 私有卡片（{@code com.android.launcher3.card.*}，如「耗电看板」）
 * 加高斯模糊背景。
 *
 * <p><b>模糊目标</b>：卡片内「不透明 CSS 底色层」（快应用自绘的实心底色，诊断确认
 * {@code a.a.a.v110102.K40}，即 {@code bg=CSSBackgroundDrawable op=OPAQUE}）——
 * 它是真正挡住底层模糊的那一层，也是 {@code createBlurForView} 应当替换的目标。
 * <b>绝不退化到「挑一层凑合」</b>：否则会糊到 {@code OplusBubbleTextView} 标题文字上，
 * 表现为「只糊一条」。
 *
 * <p><b>稳定性闸门</b>（三道）：
 * <ol>
 *   <li>白名单 {@link #isCard(View)}：只处理 launcher card 包 + 网格跨度合规的卡片；</li>
 *   <li>尺寸闸门 {@link #isPlausibleTarget}：目标须 ≥ 卡片面积 {@value #TARGET_MIN_AREA_RATIO}
 *       且各边 ≥ {@value #TARGET_MIN_SIDE_PX}px，否则视为「内容未就绪」→ 退避重试；</li>
 *   <li>占位黑名单 {@link #isPlaceholderView}：{@code DefaultCardView} 永不作为目标。</li>
 * </ol>
 *
 * <p><b>触发与生命周期</b>：由 {@link CardBlurHook} hook 卡片 {@code onAttachedToWindow} /
 * {@code initCardAfterInflate} 驱动。同一张卡一旦「定论」（顺利糊上 / 重试耗尽而放弃）
 * 便不再重复尝试；仅当内容真正变化（{@code initCardAfterInflate} → {@link #forget(View)}）
 * 才解除定论、重新尝试。
 *
 * <p><b>零轮询</b>：卡片内容静态 —— 不注册快照、不参与自定义混色、无定时器；
 * 模糊完全由 OPPO 原生 {@code createBlurForView} 负责。
 *
 * <p><b>开关</b>：附属「时钟组件模糊」（{@link FeatureFlags#LAUNCHER_CARD_BLUR}），
 * GUI 里显示在「时钟组件模糊」开关下方。
 */
public final class CardBlurAttacher {

    private static final String TAG = "LAUNCHCARD";

    /** R$id.key_blur_view_radius —— 设置模糊背景圆角的 View tag key（值为 Float，px）。 */
    private static final int ID_KEY_BLUR_VIEW_RADIUS = 0x7f0a03d7;

    /** CardBackgroundBlurManager.createBlurForView 的 type 参数：TYPE_CARD。 */
    private static final int TYPE_CARD = 5;

    /** 默认圆角（dp）。 */
    private static final float DEFAULT_RADIUS_DP = 16f;

    /** 真正的卡片渲染宿主类（快应用 CardDelegator）。 */
    private static final String CLS_CARD_DELEGATOR = "org.hapjs.card.sdk.CardDelegator";

    /** 重试基础间隔（ms）。配合指数退避：200 → 400 → 800 → 1000（封顶 1s）。 */
    private static final long ATTACH_RETRY_MS = 200L;
    /**
     * 最大重试次数（配合指数退避，总窗口约 18s）。
     *
     * <p>性能：每次重试都要对卡片子树做一次 CardDelegator 定位 + 不透明层收集，
     * 属于中等开销。耗电看板类卡片（本模块唯一的模糊目标）实测在 attach 后
     * 1~3s 内即可就绪，故该窗口非常充裕；退避后每秒最多 1 次扫描，
     * 降低无效扫描次数，减少 CPU 占用与发热。
     */
    private static final int ATTACH_MAX_RETRY = 20;

    /**
     * 已「定论」的卡片：顺利糊上，或重试耗尽被放弃。定论后不再重复尝试，
     * 直到 {@link #forget(View)}（内容真正变化）解除。
     */
    private static final WeakHashMap<View, Boolean> sSettled = new WeakHashMap<View, Boolean>();
    /** 正在重试中的卡片（避免 onAttachedToWindow + initCardAfterInflate 双重触发导致并发 retry 链）。 */
    private static final WeakHashMap<View, Boolean> sPending = new WeakHashMap<View, Boolean>();
    // 两张表都以 View 为弱键、值为 Boolean（不反向引用键）→ 卡片被回收即自动移除，无滞留。

    /**
     * 最近一次「被拒绝的目标」原因（仅用于日志可观测，不参与逻辑）。
     * 区分「没找到不透明层」与「找到了但太小」，便于定位卡片内容未就绪的情况。
     */
    private static final ThreadLocal<String> sLastRejectedTarget = new ThreadLocal<String>();

    private CardBlurAttacher() {}

    /** 对外入口：尝试给一张卡片（TitleCardView 等）挂模糊背景。 */
    public static void attach(final View card, final ClassLoader cl) {
        attach(card, cl, 0);
    }

    /** 解除卡片的「定论」，允许内容变化后重新尝试（如 initCardAfterInflate）。 */
    public static void forget(final View card) {
        if (card == null) return;
        synchronized (sSettled) { sSettled.remove(card); }
        synchronized (sPending) { sPending.remove(card); }
    }

    private static void attach(final View card, final ClassLoader cl, final int attempt) {
        if (card == null) return;
        // 开关关闭时不做（附属「时钟组件模糊」）。
        if (!FeatureFlags.LAUNCHER_CARD_BLUR) return;
        // ① 已「定论」的卡片最先短路：这是最常见的重复触发路径（切页 / 布局变化会让
        //    View 反复 attach），因此放在最前 —— 只需一次 map 查表，不做任何反射与遍历。
        //    成功者无需重复处理；放弃者不再重跑约 18s 重试链（否则每次切页都会全树扫描）。
        //    解除定论只发生在内容真正变化时（initCardAfterInflate → forget()）。
        synchronized (sSettled) {
            if (Boolean.TRUE.equals(sSettled.get(card))) return;
        }
        // ② 白名单闸门：仅处理已知的桌面卡片类；即使 hook 被误挂到其它 View 也不误伤。
        //    只在首次（attempt==0）评估 —— 重试链是同一张卡，无需重复反射读 span。
        if (attempt == 0 && !isCard(card)) return;
        // 是否把「处理中」标记保留到下一轮（retry 链未结束）。默认 false：
        // 本调用正常结束（成功 / 放弃 / 异常 / 非同类）时，必须在 finally 清理 sPending，
        // 否则该卡片会被幂等检查永久挡住。
        boolean keepPending = false;
        try {
            // 幂等：同一张卡已在处理（首次或 retry 链）→ 忽略本次重复触发，
            // 并保留其 pending 标记（另一条链仍在跑）。
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
                return;   // keepPending=false → finally 清理
            }

            Object launcher = BlurLib.getLauncherQuietly(card);
            if (launcher == null) {
                keepPending = retry(card, cl, attempt, "launcher null");
                return;
            }

            // ★ 模糊目标 = 卡片内「不透明 CSSBackgroundDrawable」的那一层（快应用卡片底色，
            //   尺寸 = 真实展示区）。这是唯一正确的目标：找不到就重试、等真内容出现，
            //   绝不退化到「挑一层凑合」（否则会糊到 OplusBubbleTextView 标题文字上）。
            View target = findOpaqueBgLayer(card);
            if (target == null) {
                // 没有「可信的不透明底色层」→ 内容尚未就绪（快应用卡片加载中）。
                // 不退化、不瞎选，直接延迟重试，等大尺寸底色层出现。
                String rej = sLastRejectedTarget.get();
                sLastRejectedTarget.remove();
                keepPending = retry(card, cl, attempt,
                        "await opaque css layer" + (rej != null ? " [" + rej + "]" : ""));
                return;
            }
            // 兜底防御：目标若是占位视图，重试（真内容会覆盖它）。
            if (isPlaceholderView(target)) {
                keepPending = retry(card, cl, attempt, "target is placeholder");
                return;   // retry 内部处理「耗尽即放弃」；未耗尽则保留 pending
            }

            // ① 设圆角 tag（设到目标上）
            float density = densityOf(target);
            float radiusPx = DEFAULT_RADIUS_DP * density;
            try { target.setTag(ID_KEY_BLUR_VIEW_RADIUS, Float.valueOf(radiusPx)); }
            catch (Throwable t) { ModuleLog.swallow("cardblur.radius", t); }

            // ② 目标是「不透明 CSS 底色层」本身：createBlurForView 会 setBackground 覆盖它，
            //    无需预先剥离其它层（卡片内容 / 装饰保持不动）。
            int cleared = 0;

            // ③ 调 createBlurForView(target, null, TYPE_CARD)
            boolean ok = createBlurViaManager(launcher, target);
            if (!ok) {
                keepPending = retry(card, cl, attempt, "createBlurForView false");
                return;
            }

            // ③.5 事后校验：createBlurForView 是否真的把目标背景替换成了「模糊 Drawable」？
            //   正常（K40 / CSSBackgroundDrawable）→ 被替换为 BlurDrawable → 可见 ✅。
            //   万一未被替换且原背景仍不透明，模糊会被盖住 → 手动清掉原背景兜底。
            boolean bgReplaced = isBlurBackground(target.getBackground());
            if (!bgReplaced && bgLooksOpaque(target.getBackground())) {
                // 未替换且仍不透明 → 手动清掉原背景，让底层模糊透出。
                try { target.setBackground(null); cleared = 1; }
                catch (Throwable t) { ModuleLog.swallow("cardblur.clearRepl", t); }
            }

            // ④ 触发一次重绘让模糊立即呈现。卡片模糊完全由 createBlurForView（OPPO 原生）负责：
            //    不登记 blurDrawable（避免被 GlassColorHook 认作时钟 drawable 而套用自定义混色）、
            //    不注册快照 / 轮询（内容静态 → 零轮询）。
            try { target.invalidate(); } catch (Throwable ignored) {}

            synchronized (sSettled) { sSettled.put(card, Boolean.TRUE); }
            // keepPending 保持 false → finally 清理 sPending
            if (ModuleLog.enabled()) {
                ModuleLog.d(TAG, "card blur attached: outer=" + card.getClass().getSimpleName()
                        + " " + card.getWidth() + "x" + card.getHeight()
                        + " | target=" + target.getClass().getSimpleName()
                        + " " + target.getWidth() + "x" + target.getHeight()
                        + " radius=" + radiusPx
                        + " clearedBg=" + cleared + " bgReplaced=" + bgReplaced);
            }
        } catch (Throwable t) {
            ModuleLog.e(TAG, "attach fail", t);
            // 异常时链已断，keepPending 保持 false → finally 清理
        } finally {
            // 唯一清理点：所有出口（成功 / 放弃 / 异常 / 非同类）都会走到这里。
            // retry 成功排队时 keepPending=true，标记留给下一轮，不清理。
            if (!keepPending) {
                synchronized (sPending) { sPending.remove(card); }
            }
        }
    }

    // ---------------------------------------------------------------- target

    /**
     * 在卡片内部找「不透明 CSS 背景层」——即快应用卡片的实心底色层
     * （诊断确认：{@code a.a.a.v110102.K40}，bg=CSSBackgroundDrawable op=OPAQUE）。
     * 这是真正挡住底层模糊的那一层，也是模糊应该替换的目标。
     *
     * <p>筛选规则：
     * <ol>
     *   <li>在 CardDelegator 子树内（快应用卡片内容宿主）；</li>
     *   <li>背景 {@code getOpacity()==OPAQUE}，或 ColorDrawable alpha>=250；</li>
     *   <li>尺寸 > 0 且尽量大（选面积最大的那层，避开小色块）；</li>
     *   <li>类名含 {@code CSSBackgroundDrawable} 优先。</li>
     * </ol>
     */
    private static View findOpaqueBgLayer(View card) {
        try {
            View root = findFirstByClass(card, CLS_CARD_DELEGATOR);
            // 轻量前置探测：CardDelegator 存在但还没内容（无子视图）→ 直接返回，跳过全树遍历。
            if (root instanceof ViewGroup && ((ViewGroup) root).getChildCount() == 0) {
                return null;
            }
            OpaquePick pick = new OpaquePick();
            pick.maxArea = -1;
            collectOpaqueLayers(root, 0, pick);
            // ★ 稳定性关键：目标必须「足够大」（≥ 卡片面积的 50%），否则视为内容未就绪 →
            //   返回 null 触发 retry，避免误伤内层小碎片（文字条 / 图标容器）。
            //   卡片内容渲染前顶层不透明底尚未生成，此时只会捞到 408x105 / 430x32
            //   之类的小层（表现为「只糊一条」），一律拒绝。
            if (pick.view != null) {
                if (isPlausibleTarget(card, pick.view)) return pick.view;
                // 记下被拒绝的目标，便于日志区分「没找到层」与「层太小」。
                // 纯日志用途：日志关闭时连字符串都不拼（避免重试期的无谓分配）。
                if (ModuleLog.enabled()) {
                    sLastRejectedTarget.set("too small: " + pick.view.getWidth() + "x"
                            + pick.view.getHeight() + " (card " + card.getWidth()
                            + "x" + card.getHeight() + ")");
                }
            } else {
                sLastRejectedTarget.remove();
            }
        } catch (Throwable t) {
            ModuleLog.swallow("cardblur.findOpaque", t);
        }
        return null;
    }

    private static final class OpaquePick { View view; long maxArea; boolean cssPreferred; int depth = -1; }

    /** 目标尺寸下限：目标面积至少为卡片面积的该比例，否则视为「内容未就绪」。 */
    private static final float TARGET_MIN_AREA_RATIO = 0.5f;
    /** 目标最小绝对尺寸（px）：宽或高小于此值一律视为碎片，防止极端情况。 */
    private static final int TARGET_MIN_SIDE_PX = 64;

    /**
     * 判定选中的「不透明层」是否是一个可信的模糊目标。
     *
     * <p>这是本功能<b>稳定性的核心闸门</b>：卡片内容（快应用自绘）渲染有先后，
     * 顶层大不透明底可能晚于 {@code onAttachedToWindow} 出现。若不加约束，
     * 遍历会退而求其次命中小尺寸内层（如 408x105 的文字条），表现为「只糊了一条」。
     *
     * <p>因此：目标面积必须 ≥ 卡片面积 {@value #TARGET_MIN_AREA_RATIO}，且任一边
     * ≥ {@value #TARGET_MIN_SIDE_PX}px；否则返回 false（调用方会转为 retry，等真内容出现）。
     */
    private static boolean isPlausibleTarget(View card, View target) {
        try {
            int cw = card.getWidth(), ch = card.getHeight();
            int tw = target.getWidth(), th = target.getHeight();
            if (tw <= 0 || th <= 0) return false;
            if (tw < TARGET_MIN_SIDE_PX || th < TARGET_MIN_SIDE_PX) return false;
            long cardArea = (long) cw * (long) ch;
            if (cardArea <= 0) return true; // 卡片尺寸未知时不阻拦（交给后续 retry 兜底）
            long targetArea = (long) tw * (long) th;
            return targetArea >= (long) (cardArea * TARGET_MIN_AREA_RATIO);
        } catch (Throwable t) {
            return true; // 判定失败时不阻拦，保持原行为
        }
    }

    /** 占位视图类名黑名单：这些永远不该作为模糊目标（卡片未就绪时的默认壳）。 */
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
            // ★ 跳过占位视图（DefaultCardView 等）：自动触发时常命中它，但真内容随后会覆盖。
            if (isPlaceholderView(v)) {
                // 仍要继续遍历其子树（真内容可能挂在它下面），只是它自身不参与挑选
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
                        try { opaque = (bg.getOpacity() == android.graphics.PixelFormat.OPAQUE); }
                        catch (Throwable ignored) {}
                    }
                }
                if (opaque && v.getWidth() > 0 && v.getHeight() > 0) {
                    long area = (long) v.getWidth() * (long) v.getHeight();
                    // 优先 CSS 背景；同优先下取面积最大
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
        } catch (Throwable ignored) {}
        return null;
    }

    // ------------------------------------------------------- background

    /** 背景是否为「模糊 Drawable」（createBlurForView 成功替换后的标志）。 */
    private static boolean isBlurBackground(Drawable d) {
        if (d == null) return false;
        try {
            String cn = d.getClass().getName().toLowerCase();
            return cn.contains("blur");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 背景是否为「不透明」（用于判断「模糊是否被原背景盖住」）。 */
    private static boolean bgLooksOpaque(Drawable d) {
        if (d == null) return false;
        try {
            if (d instanceof ColorDrawable) return ((ColorDrawable) d).getAlpha() >= 250;
            return d.getOpacity() == android.graphics.PixelFormat.OPAQUE;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------- helpers

    /**
     * 排队下一轮重试。
     *
     * @return true 表示已排入（sPending 标记须保留给下一轮）；
     *         false 表示链已结束（放弃 / View 已 detach），由调用方 finally 清理标记。
     */
    private static boolean retry(final View card, final ClassLoader cl, final int attempt, final String why) {
        if (attempt >= ATTACH_MAX_RETRY) {
            // 重试耗尽 → 记为「定论」（放弃）：后续 attach 直接短路，等 forget() 解除。
            synchronized (sSettled) { sSettled.put(card, Boolean.TRUE); }
            ModuleLog.d(TAG, "give up after " + attempt + " retries: " + why);
            return false;
        }
        if (!card.isAttachedToWindow()) return false;   // View 已移除，链终止

        // 指数退避：200 → 400 → 800 → 1000（封顶 1s）。
        // 前几次密（快速抓住刚就绪的 K40），之后 1s 一次慢扫。
        // 总计不超过 ATTACH_MAX_RETRY 次（约 18s 窗口），既降低扫描密度
        // （省 CPU / 减少发热），又覆盖慢加载卡片。
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
            ModuleLog.d(TAG, "createBlurForView => " + r);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            ModuleLog.e(TAG, "createBlurViaManager fail", t);
            return false;
        }
    }

    private static float densityOf(View v) {
        try { return v.getResources().getDisplayMetrics().density; } catch (Throwable t) { return 3.5f; }
    }

    /**
     * 判定一个 View 是否是「可以模糊的卡片」（在遍历中筛选目标）。
     *
     * <p>集合卡（{@code GroupCardView}）不在内：其容器内没有尺寸达标的不透明 CSS 底色层，
     * 扫描只会得到“无层可用”而放弃。它仍被 {@link CardBlurHook} 钩住，用于卡片标题阴影。
     */
    public static boolean isCard(View v) {
        if (v == null) return false;
        try {
            String cn = v.getClass().getName();
            // ① OPPO launcher 原生卡片（launcher card 包，以 CardView 结尾的卡片视图）。
            boolean isLauncherCard = cn.startsWith("com.android.launcher3.card.")
                    && (cn.endsWith("TitleCardView") || cn.endsWith("LauncherCardView")
                        || cn.endsWith("EngineCardView") || cn.endsWith("CommonCardView"));
            if (isLauncherCard) {
                // 规格闸门：只认「正确的大卡片」（2x1 / 2x2 / 4x2 ...）。
                // 依据 LauncherCardInfo.getSpanX/getSpanY（扫描确认桌面卡片 spanRange 均为 2-4 x 2-4）。
                // 拿不到 span 时（信息尚未就绪）不阻拦，交给后续逻辑处理。
                return hasValidSpan(v);
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
    /** 最小网格跨度：spanX >= 2 且 spanY >= 1（即 2x1 / 2x2 / 4x2 等正确规格）。 */
    private static final int MIN_SPAN_X = 2;
    private static final int MIN_SPAN_Y = 1;

    /**
     * 读卡片自身的网格跨度（{@code LauncherCardView#getItemInfo() → LauncherCardInfo}），
     * 判定是否为「应该模糊的大卡片」。
     *
     * <p>这是比「像素面积比例」更权威的规格判据：卡片自带的 {@code spanX/spanY}
     * 直接对应桌面网格（2x1 / 2x2 / 4x2 ...），不受渲染时机、字体缩放影响。
     *
     * <p>拿不到信息（info 为 null / 方法缺失）时返回 true，不阻拦 —— 保持宽容，
     * 真正的碎片过滤由目标尺寸闸门 {@link #isPlausibleTarget} 兜底。
     */
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
