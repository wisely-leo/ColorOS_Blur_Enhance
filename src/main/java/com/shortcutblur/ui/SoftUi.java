package com.shortcutblur.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * SoftUi —— 轻量 UI 库（纯代码，零 XML，零图片）
 *
 * 设计原则（可扩展性）：
 *   1. 令牌集中：颜色/尺寸/圆角只在下方 Token 区
 *   2. 控件独立：每个控件是独立 static class，互不依赖
 *   3. 工厂统一：外部只用工厂方法，不碰内部实现
 *   4. 参数不写死：尺寸走 dp()，颜色走常量，数值靠传入
 *
 * 复用示例：
 *   SoftUi.card(ctx,
 *       SoftUi.toggle(ctx, "启用模块", true, cb),
 *       SoftUi.slider(ctx, "模糊强度", 64, 0, 100, cb));
 */
public final class SoftUi {

    private SoftUi() {}

    // ============================================================
    // ① Token 区（改主题只动这里）
    // ============================================================

    /** 画布/分组背景 */
    public static int CANVAS         = 0xFFF2F2F7;
    /** 卡片表面 */
    public static int SURFACE        = 0xFFFFFFFF;
    /**
     * 卡片背景色。默认 = SURFACE（不透明，无背景图时）。
     * 设置了背景图后，SettingsActivity 会把它改成一个半透明色，
     * 好让背景图透出来（第 2 步会换成真正的毛玻璃）。
     */
    public static int CARD_FILL      = SURFACE;
    /** 强调色（开关开启/滑杆激活） */
    public static int ACCENT         = 0xFF007AFF;
    /** 主文字 */
    public static int TEXT_PRIMARY   = 0xFF000000;
    /** 次文字 */
    public static int TEXT_SECONDARY = 0x993C3C43;
    /** 分隔线 */
    public static int DIVIDER        = 0x5C3C3C43;
    /** 开关关闭态 */
    public static int SWITCH_OFF     = 0xFFE9E9EA;
    /** 滑杆轨道 */
    public static int TRACK          = 0xFFE9E9EA;
    /** 滑杆/开关的白色滑块 */
    public static int KNOB           = 0xFFFFFFFF;

    // 尺寸（dp）
    public static float RADIUS      = 22f;   // 卡片圆角（加大）
    public static float ROW_H       = 48f;   // 行高
    public static float PAD         = 16f;   // 卡片内边距
    public static float GAP         = 10f;   // 卡片间距
    public static float SIDE        = 16f;   // 页面左右边距
    public static float TITLE_TOP   = 18f;   // 标题上边距
    public static float TITLE_BOTTOM= 10f;   // 标题下边距

    // 字号（sp）
    public static float TITLE_SIZE = 28f;
    public static float BODY_SIZE  = 16f;
    public static float SUB_SIZE   = 13f;

    // 控件细节
    public static float SWITCH_W   = 51f;    // 开关宽
    public static float SWITCH_H   = 31f;    // 开关高
    public static float KNOB_PAD   = 2f;     // 滑块与轨道间距
    public static float SLIDER_W   = 160f;   // 滑杆宽
    public static float TRACK_H    = 4f;     // 轨道高
    public static float THUMB_R    = 11f;    // 滑杆圆点半径

    // —— 模糊/毛玻璃（效果区 token）——
    /** 默认模糊半径（dp） */
    public static float BLUR_RADIUS   = 30f;
    /** 毛玻璃底色（半透明白，叠加在模糊层上提亮） */
    public static int   GLASS_TINT    = 0x33FFFFFF;   // 白纱：20% 不透明度白（全站统一）
    /** 毛玻璃高光边（顶部亮线） */
    public static int   GLASS_EDGE    = 0x33FFFFFF;
    /** 顶栏默认模糊半径 */
    public static float HEADER_BLUR   = 30f;
    /** 顶栏高度（固定，含状态栏视觉重心） */
    public static float HEADER_H      = 76f;
    /** 顶栏与内容首项的呼吸间距 */
    public static float HEADER_GAP    = 12f;
    /** 顶栏叠加色（半透明白，iOS 风） */
    public static int   HEADER_TINT   = 0x33FFFFFF;   // 白纱：20% 不透明度白（与 GLASS_TINT 统一）
    /** 顶栏底部分割线：颜色（半透明）与高度（dp，0 表示不画） */
    public static int   HEADER_LINE_COLOR = 0x1F8E8E93;   // 12% 中性灰（iOS systemGray）
    public static float HEADER_LINE_H     = 1f;

    // —— 底栏（底部标签栏，与顶栏同一套毛玻璃）——
    /** 底栏标签区高度（dp，不含手势条） */
    public static float FOOTER_H   = 64f;
    /** 底栏标签字号（sp） */
    public static float TAB_SIZE   = 15f;
    /** 底栏选中文字色（选中态只高亮文字，不做胶囊底） */
    public static int   TAB_SEL    = 0xFF007AFF;

    // ============================================================
    // ② 工具
    // ============================================================

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** 状态栏高度（px）。优先读 WindowInsets，取不到则回退资源 id。 */
    public static int statusBarH(Context c) {
        try {
            if (c instanceof android.app.Activity) {
                android.view.WindowInsets wi = ((android.app.Activity) c).getWindow()
                        .getDecorView().getRootWindowInsets();
                if (wi != null) {
                    int h = wi.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                    if (h > 0) return h;
                }
            }
        } catch (Throwable ignored) {}
        try {
            int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) return c.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        return dp(c, 28f);
    }

    /** 导航栏（手势条）高度（px）。底栏要避开它，否则标签被手势条压住。 */
    public static int navBarH(Context c) {
        try {
            if (c instanceof android.app.Activity) {
                android.view.WindowInsets wi = ((android.app.Activity) c).getWindow()
                        .getDecorView().getRootWindowInsets();
                if (wi != null) {
                    int h = wi.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom;
                    if (h > 0) return h;
                }
            }
        } catch (Throwable ignored) {}
        try {
            int id = c.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) return c.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        return dp(c, 24f);
    }

    private static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private static GradientDrawable roundRect(int fill, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    // ------------------------------------------------------------
    //  ③ 基础控件区（Switch / Slider / Card / Row —— 原子 UI 零件）
    //     本区【只做 UI】，不含模糊算法；需要毛玻璃时调用下面的 ④ 能力区。
    // ============================================================

    /** 开关：自绘，无图片。支持平滑切换动画 + 跟手左右拖动。 */
    public static class Switch extends View {
        public interface OnChange { void onChange(boolean value); }
        /** 开关回调（供"带附属行"的 toggle 使用，同时可驱动外部状态）。 */
        public interface OnToggle { void onToggle(boolean value); }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private boolean checked;
        private float anim = 0f; // 0..1 动画位（0=关，1=开）
        private OnChange cb;

        /** 切换动画器（点击 / setChecked 时平滑过渡）。 */
        private ValueAnimator animator;
        /** 拖动状态。 */
        private boolean dragging = false;
        private float downX = 0f;
        /** 触摸拖动阈值（px）。 */
        private float touchSlop;

        public Switch(Context c) {
            super(c);
            setClickable(true);
            touchSlop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        }

        /** 外部设置状态（带动画）。 */
        public void setChecked(boolean v) {
            if (checked == v) return;
            checked = v;
            animateTo(v ? 1f : 0f);
            if (cb != null) cb.onChange(checked);   // 任何来源的变化都通知（状态驱动）
        }

        /**
         * 初始化状态：即时生效、不播放动画、不回调。
         * 用于界面构建时设定初值，避免重建/切页时整屏开关一起做切换动画。
         */
        public void setCheckedImmediate(boolean v) {
            if (animator != null) { animator.cancel(); animator = null; }
            checked = v;
            anim = v ? 1f : 0f;
            invalidate();
        }

        public boolean isChecked() { return checked; }

        public Switch setOnChange(OnChange c) { this.cb = c; return this; }

        /** 平滑动画到目标位置（不触发回调）。 */
        private void animateTo(float target) {
            if (animator != null) animator.cancel();
            animator = ValueAnimator.ofFloat(anim, target);
            animator.setDuration(180L);
            animator.addUpdateListener(a -> {
                anim = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        /** 立即吸附到目标（拖动松手用，避免与动画器冲突）。 */
        private void snapTo(float target) {
            if (animator != null) { animator.cancel(); animator = null; }
            anim = target;
            invalidate();
        }

        /** 由 anim 位置推导最终状态并通知（若变化）。 */
        private void commitFromAnim() {
            boolean target = anim >= 0.5f;
            snapTo(target ? 1f : 0f);
            if (target != checked) {
                checked = target;
                if (cb != null) cb.onChange(checked);
            }
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), SWITCH_W), dp(getContext(), SWITCH_H));
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float r = h / 2f;

            // 轨道（颜色随动画在 关→开 之间过渡）
            int off = SWITCH_OFF, on = ACCENT;
            int trackColor = blend(off, on, anim);
            paint.setColor(trackColor);
            rect.set(0, 0, w, h);
            cv.drawRoundRect(rect, r, r, paint);

            // 滑块
            float pad = dp(getContext(), KNOB_PAD);
            float kr = r - pad;
            float cx = pad + kr + (w - 2 * (pad + kr)) * anim;
            float cy = h / 2f;
            paint.setColor(KNOB);
            cv.drawCircle(cx, cy, kr, paint);
        }

        /** 滑块中心在 anim=0 时的 X（左端最小中心位）。 */
        private float knobMinX() {
            float h = getHeight();
            float kr = h / 2f - dp(getContext(), KNOB_PAD);
            return dp(getContext(), KNOB_PAD) + kr;
        }

        /** 滑块中心可变行程（用于把手指 X 映射成 anim）。 */
        private float knobTravel() {
            float w = getWidth(), h = getHeight();
            float r = h / 2f;
            float pad = dp(getContext(), KNOB_PAD);
            float kr = r - pad;
            return w - 2 * (pad + kr);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (animator != null) { animator.cancel(); animator = null; }
                    dragging = false;
                    downX = e.getX();
                    // 请求父容器（Row）不要拦截本次手势，保证拖动连续
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - downX;
                    if (!dragging && Math.abs(dx) > touchSlop) {
                        dragging = true;               // 进入拖动模式
                    }
                    if (dragging) {
                        float travel = knobTravel();
                        if (travel > 0f) {
                            // 把「手指 X 相对轨道起点」映射为 anim（跟手）
                            float raw = (e.getX() - knobMinX()) / travel;
                            anim = Math.max(0f, Math.min(1f, raw));
                            invalidate();
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    if (dragging) {
                        commitFromAnim();              // 拖动松手：吸附 + 通知
                    } else {
                        // 未拖动（轻点）：直接切换（带平滑动画）
                        checked = !checked;
                        animateTo(checked ? 1f : 0f);
                        if (cb != null) cb.onChange(checked);
                    }
                    dragging = false;
                    return true;
                }
            }
            return true;
        }
    }

    /** 滑杆：自绘 */
    public static class Slider extends View {
        public interface OnChange { void onChange(float value); }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX, downY;
        private boolean dragging = false;
        private float touchSlop = -1f;

        private final RectF track = new RectF();
        private float min, max, value;
        private OnChange cb;

        public Slider(Context c, float value, float min, float max) {
            super(c);
            this.min = min; this.max = max; this.value = value;
            setClickable(true);
            // 系统标准触摸阈值（超过才判定为滑动），用于区分横/竖方向
            touchSlop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        }

        public void setValue(float v) {
            value = Math.max(min, Math.min(max, v));
            invalidate();
        }
        public float getValue() { return value; }
        public Slider setOnChange(OnChange c) { this.cb = c; return this; }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), SLIDER_W), dp(getContext(), SWITCH_H));
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float trackH = dp(getContext(), TRACK_H);
            float th = dp(getContext(), THUMB_R);
            float cy = h / 2f;
            float left = th, right = w - th;
            float frac = (max > min) ? (value - min) / (max - min) : 0f;

            // 轨道底
            paint.setColor(TRACK);
            track.set(left, cy - trackH / 2f, right, cy + trackH / 2f);
            cv.drawRoundRect(track, trackH / 2f, trackH / 2f, paint);

            // 已选部分
            float cx = left + (right - left) * frac;
            paint.setColor(ACCENT);
            track.set(left, cy - trackH / 2f, cx, cy + trackH / 2f);
            cv.drawRoundRect(track, trackH / 2f, trackH / 2f, paint);

            // 圆点
            paint.setColor(KNOB);
            cv.drawCircle(cx, cy, th, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(getContext(), 0.5f));
            paint.setColor(0x1F000000);
            cv.drawCircle(cx, cy, th, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // 按下时先记录起点，暂不改值（��区分方向后再定）
                    downX = e.getX(); downY = e.getY();
                    dragging = false;
                    // 先不让父容器拦截，保证我们能收到后续 MOVE
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) {
                        float dx = Math.abs(e.getX() - downX);
                        float dy = Math.abs(e.getY() - downY);
                        if (dx < touchSlop && dy < touchSlop) return true;   // 未超阈值，继续观察
                        if (dy > dx) {
                            // 判定为竖向滑动：放行给父容器（页面滚动），本滑块不再接管
                            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                            dragging = false;
                            return false;   // 不再处理，让 ScrollView 滚动
                        }
                        dragging = true;    // 判定为横向拖动滑块
                    }
                    applyX(e.getX());
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    if (dragging) {
                        applyX(e.getX());
                        if (cb != null) cb.onChange(value);
                    }
                    dragging = false;
                    return true;
            }
            return true;
        }

        /** 根据 X 坐标更新值并通知。 */
        private void applyX(float x) {
            float th = dp(getContext(), THUMB_R);
            float left = th, right = getWidth() - th;
            float frac = (right > left) ? (x - left) / (right - left) : 0f;
            frac = Math.max(0f, Math.min(1f, frac));
            value = min + (max - min) * frac;
            invalidate();
            if (cb != null) cb.onChange(value);
        }
    }

/** 卡片：白底圆角容器 */
    public static class Card extends android.widget.FrameLayout {
        private final LinearLayout inner;   // 真正放内容
        private StaticGlass glass;

        public Card(Context c) {
            super(c);
            setBackground(roundRect(CARD_FILL, RADIUS, c));

            inner = new LinearLayout(c);
            inner.setOrientation(LinearLayout.VERTICAL);
            addView(inner, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            inner.setPadding(dp(c, PAD), dp(c, PAD / 2), dp(c, PAD), dp(c, PAD / 2));
        }

        /** 往卡片里面加行（重定向到 inner，保持 addView(card, row) 写法可用）。 */
        @Override public void addView(View child, int index, ViewGroup.LayoutParams params) {
            if (inner != null && child != inner) { inner.addView(child, params); return; }
            super.addView(child, index, params);
        }
        @Override public void addView(View child) {
            if (inner != null && child != inner) { inner.addView(child); return; }
            super.addView(child);
        }

        /**
         * 启用真·毛玻璃。必须在卡片入窗后调用（需要屏幕绝对坐标）。
         * 毛玻璃层插在最底，MATCH_PARENT 铺满卡片，不参与内容布局。
         */
        public void enableGlass(float radiusDp, int tintColor) {
            if (glass != null) return;
            setBackground(null);
            // 插到 index 0 = 最底层。内容(inner)永远在最上面，不会被糊掉。
            glass = new StaticGlass(getContext(), radiusDp, tintColor, RADIUS);
            super.addView(glass, 0, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            // 接入滚动刷新总线（自动等 attach；视图树重建后自动重挂）。
            glass.start();
        }
    }

    /** 行：左标题 + 右控件 + 可选底部细分隔线 */
    public static class Row extends LinearLayout {
        public Row(Context c, String title, View right, boolean divider) {
            super(c);
            setOrientation(VERTICAL);

            LinearLayout line = new LinearLayout(c);
            line.setOrientation(HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setMinimumHeight(dp(c, ROW_H));

            TextView tv = text(c, title, BODY_SIZE, TEXT_PRIMARY);
            line.addView(tv, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (right != null) line.addView(right);
            addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            if (divider) {
                View d = new View(c);
                d.setBackgroundColor(DIVIDER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f)));
                d.setLayoutParams(lp);
                addView(d);
            }
        }

        /**
         * 让整行可点击以切换指定 Switch（点行任意处 = 切换该开关）。
         * 不改动 Switch 自身的 onChange，仅通过 setChecked 触发已有回调。
         */
        public Row setRowClickSwitch(final Switch sw) {
            if (sw == null) return this;
            setClickable(true);
            setFocusable(true);
            setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
            return this;
        }

        /** 把整行做成可点击开关（点行任意处切换右侧 Switch）。 */
        /** 把整行做成可点击开关（点行任意处或开关本体均可切换）。 */
        public Row setOnToggle(final Switch.OnToggle cb) {
            final View right = findSwitch(this);
            if (right instanceof Switch) {
                // 开关本体：值变化即回调（状态驱动，任何来源都生效）
                ((Switch) right).setOnChange(value -> {
                    if (cb != null) cb.onToggle(value);
                });
            }
            // 点行任意位置也可切换（经 Switch 触发同一回调）
            setClickable(true);
            setFocusable(true);
            setOnClickListener(v -> {
                if (right instanceof Switch) {
                    Switch sw = (Switch) right;
                    sw.setChecked(!sw.isChecked());   // 触发 onChange -> cb
                }
            });
            return this;
        }

        /** 在本行【下方】附加一个容器（用于折叠附属项）。 */
        public void attachBelow(View below) {
            if (below == null) return;
            addView(below, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        private static View findSwitch(ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof Switch) return c;
                if (c instanceof ViewGroup) {
                    View r = findSwitch((ViewGroup) c);
                    if (r != null) return r;
                }
            }
            return null;
        }

        /** 子项缩进：给标题加左侧内边距（用于表示层次关系）。 */
        public Row indent(Context c, float dpExtra) {
            View v = getChildAt(0);
            if (v instanceof LinearLayout) {
                LinearLayout line = (LinearLayout) v;
                if (line.getChildCount() > 0) {
                    View title = line.getChildAt(0);
                    title.setPadding(dp(c, dpExtra), title.getPaddingTop(),
                            title.getPaddingRight(), title.getPaddingBottom());
                }
            }
            return this;
        }
    }

    // ============================================================
    //  ④ 毛玻璃能力区（零自实现模糊 —— 全部交给系统 RenderEffect）
    //
    //  每个玻璃层（从下到上）：
    //    [0] Painter  模糊源：只负责“画”（壁纸 / 壁纸+背后内容）
    //                 → View.setRenderEffect(createBlurEffect) 由系统 GPU 实时高斯
    //    [1] Skin     白纱 + 高光边：叠在模糊之上，本身不参与模糊
    //
    //  为什么这样最好：
    //    · 系统模糊作用在【屏幕像素】上 —— 与壁纸分辨率、centerCrop 缩放比无关，
    //      彻底没有“半径换算(sc) / 矩阵左乘顺序”这类坑（这两个都真出过 bug）
    //    · 没有 GPU→CPU 回读、没有预模糊缓存、没有 CPU 盒式模糊
    //    · 圆角用 clipToOutline 裁剪（背景+内容+子View 一起裁），模糊不会糊出圆角，
    //      也就不需要 saveLayer / clipPath / DST_IN
    //
    //    Backdrop      背景源     壁纸 + centerCrop 映射（只登记，不做任何模糊）
    //    BackdropView  背景层     自绘清晰壁纸，同时作为取景坐标基准
    //    GlassView     玻璃容器   圆角裁剪 + 模糊源子View + 白纱/高光边
    //    StaticGlass   静态玻璃   卡片：模糊源 = 壁纸取景
    //    LiveGlass     实时玻璃   顶栏：模糊源 = 壁纸 + 滚到它背后的内容
    //    GlassSync     刷新总线   1 个滚动监听驱动 N 个玻璃层
    // ============================================================

    // -------------------- 对外效果 API --------------------

    /** 给任意 View 加高斯模糊（系统原生，模糊自身渲染内容）。 */
    public static boolean blur(View v, float radiusDp) {
        if (v == null) return false;
        return Effects.selfBlur(v, radiusDp);
    }

    /** 给任意 View 加高斯模糊（默认半径）。 */
    public static boolean blur(View v) { return blur(v, BLUR_RADIUS); }

    /**
     * 让 target 透视 sample 的内容并模糊（真·透过被遮盖物）。
     *
     * 模糊源 = 壁纸 + 滚到它背后的内容，整体交给系统 GPU 模糊。
     *
     * @param target   要显示毛玻璃背景的 View（如顶栏）
     * @param sample   被采样的内容（通常是滚动容器）
     * @param radiusDp 模糊半径（dp，屏幕像素尺度 —— 与壁纸分辨率无关）
     * @param sync     是否跟随滚动自动刷新
     */
    public static void blurBehind(View target, View sample, float radiusDp, boolean sync) {
        if (!(target instanceof ViewGroup) || sample == null) return;
        ViewGroup tg = (ViewGroup) target;
        for (int i = tg.getChildCount() - 1; i >= 0; i--) {      // 幂等：清掉旧玻璃层
            if (tg.getChildAt(i) instanceof GlassView) tg.removeViewAt(i);
        }
        LiveGlass g = new LiveGlass(target.getContext(), sample, radiusDp, HEADER_TINT, 0f);
        tg.addView(g, 0, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (sync) g.start();
    }

    /** blurBehind 简化版：默认半径 + 自动跟随滚动。 */
    public static void blurBehind(View target, View sample) {
        blurBehind(target, sample, BLUR_RADIUS, true);
    }

    /** 毛玻璃质感：圆角半透明底 + 高光边（作用于 View 自身背景）。 */
    public static void glass(View v, float radiusDp) {
        if (v == null) return;
        v.setBackground(new GlassDrawable(v.getContext(), radiusDp, GLASS_TINT));
    }

    /** 撤销该 View 上的 SoftUi 模糊效果。 */
    public static void clearEffects(View v) {
        if (v == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try { v.setRenderEffect(null); } catch (Throwable ignored) {}
        }
    }

    // -------------------- 能力实现 --------------------

    /**
     * 背景源：壁纸 + centerCrop 映射。
     *
     * 【注意】这里不再做任何“预模糊” —— 模糊只是渲染时的事，
     * 由每个玻璃层的系统 RenderEffect 负责，所以换壁纸不需要失效任何缓存。
     */
    static final class Backdrop {

        /** 壁纸原图（null = 无背景）。 */
        static android.graphics.Bitmap src;
        /** 取景坐标基准（背景层 View）。 */
        static View anchor;
        /** centerCrop 映射：{scale, offsetX, offsetY}（壁纸像素 -> 背景层坐标）。 */
        static final float[] MAP = new float[3];

        private Backdrop() {}

        /** 登记壁纸（纯赋值：没有缓存需要失效）。 */
        static void setSource(android.graphics.Bitmap b, View anchorView) {
            if (src != b) {
                Effects.logDiag("壁纸登记: " + (b == null ? "null"
                        : b.getWidth() + "x" + b.getHeight()
                          + " id=" + Integer.toHexString(System.identityHashCode(b))));
            }
            src = b;
            if (anchorView != null) anchor = anchorView;
        }

        static boolean ready() {
            return src != null && !src.isRecycled();
        }

        /** 计算 centerCrop 映射（写入 MAP）。 */
        static boolean mapping() {
            View a = anchor;
            if (a == null || !ready()) return false;
            int aw = a.getWidth(), ah = a.getHeight();
            if (aw <= 0 || ah <= 0) return false;
            int bw = src.getWidth(), bh = src.getHeight();
            if (bw <= 0 || bh <= 0) return false;
            float sc = Math.max((float) aw / bw, (float) ah / bh);
            MAP[0] = sc;
            MAP[1] = (aw - bw * sc) / 2f;
            MAP[2] = (ah - bh * sc) / 2f;
            return true;
        }

        /**
         * 把壁纸画进 canvas，使其在【本层局部坐标】中位置正确。
         * relX/relY = 本层相对背景层的偏移（0/0 表示本层就是背景层）。
         */
        static void drawInto(Canvas cv, int relX, int relY, Paint p) {
            if (!mapping()) return;
            cv.save();
            cv.translate(MAP[1] - relX, MAP[2] - relY);
            cv.scale(MAP[0], MAP[0]);
            cv.drawBitmap(src, 0, 0, p);
            cv.restore();
        }
    }

    /** 背景层：自绘壁纸（清晰，centerCrop），同时作为取景的坐标基准。 */
    public static class BackdropView extends View {

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        public BackdropView(Context c) {
            super(c);
            p.setDither(true);
        }

        /** 设置壁纸（null = 纯色背景）。 */
        public void setBitmap(android.graphics.Bitmap b) {
            Backdrop.setSource(b, this);
            invalidate();
        }

        public android.graphics.Bitmap getBitmap() { return Backdrop.src; }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            if (!Backdrop.ready()) return;
            Backdrop.drawInto(cv, 0, 0, p);
        }
    }

    /** 清空背景（恢复纯色）：清掉静态壁纸引用，避免"清除后还糊着旧图"。 */
    public static void clearBackdrop() {
        Backdrop.setSource(null, null);
    }

    /**
     * 毛玻璃容器（FrameLayout）。
     *
     * 结构（从下到上）：
     *   [0] Painter  模糊源：只负责“画”（壁纸 / 壁纸+背后内容），
     *                由系统 RenderEffect.createBlurEffect 在 GPU 上实时高斯模糊。
     *   [1] Skin     白纱 + 高光边：在模糊之上单独绘制，不参与模糊。
     *
     * 圆角：setClipToOutline(true) + roundRect Outline
     *   → 背景、内容、子 View 全部被裁到圆角内，模糊不会“糊出”边角。
     *
     * 子类只需实现 drawSource(Canvas)：画什么由子类决定；怎么糊由系统决定。
     */
    public static abstract class GlassView extends android.widget.FrameLayout {

        final float radiusDp;
        final int tint;
        final float cornerDp;

        /** 模糊源（唯一的绘制子 View）。 */
        final Painter painter;

        /** 本层相对背景层的偏移（由 GlassSync 缓存，避免 onDraw 里遍历视图树）。 */
        int relX = Integer.MIN_VALUE, relY = Integer.MIN_VALUE;

        /** 画位图用的画笔（FILTER_BITMAP 保证缩放质量）。 */
        final Paint srcPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        private boolean started;

        GlassView(Context c, float radiusDp, int tint, float cornerDp) {
            super(c);
            this.radiusDp = radiusDp;
            this.tint = tint;
            this.cornerDp = cornerDp;
            srcPaint.setDither(true);

            // 圆角裁剪：背景 + 内容 + 子 View 一起裁（模糊不会溢出圆角）
            setClipToOutline(true);
            setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    int w = v.getWidth(), h = v.getHeight();
                    if (w <= 0 || h <= 0) { o.setEmpty(); return; }
                    o.setRoundRect(0, 0, w, h,
                            Math.max(0f, dp(v.getContext(), GlassView.this.cornerDp)));
                }
            });

            // ① 模糊源子 View（系统原生模糊作用在它身上）
            painter = new Painter(this);
            addView(painter, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // ② 白纱 + 高光边（叠在模糊之上，本身不糊）
            if (((tint >>> 24) != 0) || ((GLASS_EDGE >>> 24) != 0)) {
                View skin = new View(c);
                skin.setClickable(false);
                skin.setBackground(new GlassDrawable(c, cornerDp, tint));
                addView(skin, new LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }

            applyNativeBlur();
        }

        /** 把“模糊源”的渲染交给系统 GPU 高斯（Android 12+；更低版本降级为不模糊）。 */
        private void applyNativeBlur() {
            if (android.os.Build.VERSION.SDK_INT < 31) {
                Effects.logDiag("API<31：系统无 RenderEffect，玻璃层降级为不模糊");
                return;
            }
            float px = Math.max(0.1f, dp(getContext(), radiusDp));
            try {
                painter.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        px, px, android.graphics.Shader.TileMode.CLAMP));
            } catch (Throwable t) {
                Effects.logDiag("setRenderEffect 失败: " + t);
            }
        }

        /** 接入滚动刷新（自动等 attach；视图树重建后自动重挂）。 */
        public void start() {
            if (started) return;
            started = true;
            Effects.logDiag("玻璃层启动: " + getClass().getSimpleName()
                    + " r=" + radiusDp + "dp corner=" + cornerDp
                    + " 原生模糊=" + (android.os.Build.VERSION.SDK_INT >= 31));
            final Runnable bind = () -> {
                View sc = scrollSource();
                GlassSync.register(this);
                GlassSync.attach(sc != null ? sc : this);
                cacheLocation();
                doTick();
            };
            if (isAttachedToWindow()) {
                bind.run();
            } else {
                addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) { bind.run(); }
                    @Override public void onViewDetachedFromWindow(View v) { }
                });
            }
            addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> doTick());
        }

        /** 驱动本层的滚动源。默认：向上找 ScrollView；LiveGlass 覆写为采样源。 */
        View scrollSource() {
            android.view.ViewParent p = getParent();
            while (p != null) {
                if (p instanceof android.widget.ScrollView) return (View) p;
                p = p.getParent();
            }
            return null;
        }

        /** 缓存“本层相对背景层”的偏移。 */
        final void cacheLocation() {
            try {
                View bd = Backdrop.anchor;
                if (bd == null) return;
                getLocationInWindow(GlassSync.LOC2);
                bd.getLocationInWindow(GlassSync.LOC1);
                relX = GlassSync.LOC2[0] - GlassSync.LOC1[0];
                relY = GlassSync.LOC2[1] - GlassSync.LOC1[1];
            } catch (Throwable ignored) {}
        }

        /**
         * 刷新本层。
         *
         * ★ 必须 invalidate【painter】而不是本容器：
         *   滚动时系统的做法是“平移复用子 View 的显示列表”，
         *   只重画容器不会重新记录模糊源的取景位置 → 图案会跟着卡片一起走
         *   （视觉上就是“模糊固定住了 / 静态贴图”）。
         */
        final void doTick() {
            cacheLocation();
            painter.invalidate();
        }

        /** 滚动 / 布局变化时调用（子类可覆写，但务必 invalidate painter）。 */
        void onTick() { doTick(); }

        /**
         * 子类实现：往 canvas 上画“模糊源”（本层局部坐标，0,0 = 本层左上角）。
         * 画完由系统 RenderEffect 统一模糊 —— 这里【不要】做任何模糊。
         */
        abstract void drawSource(Canvas cv);

        /** 只负责“画”的模糊源 View；模糊由系统 RenderEffect 完成。 */
        static final class Painter extends View {
            private final GlassView owner;
            Painter(GlassView owner) {
                super(owner.getContext());
                this.owner = owner;
                setWillNotDraw(false);
            }
            @Override protected void onDraw(Canvas cv) { owner.drawSource(cv); }
        }
    }

    /** 静态玻璃（卡片）：模糊源 = 从壁纸取景的一小块。 */
    public static class StaticGlass extends GlassView {

        public StaticGlass(Context c, float radiusDp, int tint, float cornerDp) {
            super(c, radiusDp, tint, cornerDp);
        }

        @Override void drawSource(Canvas cv) {
            if (relX == Integer.MIN_VALUE) cacheLocation();
            Backdrop.drawInto(cv, relX, relY, srcPaint);
        }
    }

    /**
     * 实时玻璃（顶栏 / 底栏）。
     *
     * 模糊源 =「壁纸 + 滚到它背后的内容」，整体交给系统 RenderEffect 模糊。
     *
     * ★★ 采样必须走【软件画布】，两个原因（都是实测踩出来的）：
     *   ① ScrollView 的滚动偏移不在它自己的显示列表里 ——
     *      mScrollX/mScrollY 是【父容器画它的时候】才 translate 的，
     *      所以直接 content.draw(canvas) 拿到的是 scrollY=0 的画面
     *      （＝内容顶部那段留白）→ 视觉上就是“只糊到壁纸，卡片没糊到”。
     *      必须自己补 -scrollY。
     *   ② 硬件画布里，系统对本帧已经画过的 View 走的是“复用显示列表”的快路径，
     *      直接 draw 一个已挂树的 View 拿不到内容；软件画布走直接绘制路径，一定画得出来。
     *   本层只做【合成】（画壁纸 + 画内容），模糊仍然 100% 由系统 RenderEffect 完成。
     */
    public static class LiveGlass extends GlassView {

        /** 合成缓冲缩放：模糊对分辨率不敏感，1/4 够用且软件绘制面积只有 1/16。 */
        private static final int SCALE = 4;

        private final View content;            // 被采样的内容（滚动容器）
        private final int[] locA = new int[2]; // 内容的窗口坐标
        private final int[] locB = new int[2]; // 本层的窗口坐标
        private final android.graphics.Rect dst = new android.graphics.Rect();
        private android.graphics.Bitmap buf;

        public LiveGlass(Context c, View content, float radiusDp, int tint, float cornerDp) {
            super(c, radiusDp, tint, cornerDp);
            this.content = content;
        }

        @Override View scrollSource() { return content; }

        @Override void drawSource(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (relX == Integer.MIN_VALUE) cacheLocation();

            // ---------- ① 在软件画布上合成“本层背后的画面” ----------
            int sw = Math.max(1, w / SCALE), sh = Math.max(1, h / SCALE);
            if (buf == null || buf.isRecycled()
                    || buf.getWidth() != sw || buf.getHeight() != sh) {
                if (buf != null) buf.recycle();
                buf = android.graphics.Bitmap.createBitmap(
                        sw, sh, android.graphics.Bitmap.Config.ARGB_8888);
            }
            buf.eraseColor(CANVAS);
            Canvas cc = new Canvas(buf);
            cc.scale(1f / SCALE, 1f / SCALE);

            // 壁纸垫底（按本层在屏幕上的位置取景）
            if (Backdrop.ready()) Backdrop.drawInto(cc, relX, relY, srcPaint);

            // 叠上“滚到本层背后的内容”
            if (content != null) {
                content.getLocationInWindow(locA);
                getLocationInWindow(locB);
                int tx = locA[0] - locB[0];
                int ty = locA[1] - locB[1];
                View target = content;
                if (content instanceof android.widget.ScrollView) {
                    android.widget.ScrollView sv = (android.widget.ScrollView) content;
                    // ★ 关键：滚动偏移要自己补，它不在 ScrollView 自己的绘制里
                    tx -= sv.getScrollX();
                    ty -= sv.getScrollY();
                    if (sv.getChildCount() > 0) target = sv.getChildAt(0);
                }
                cc.save();
                cc.translate(tx, ty);
                target.draw(cc);
                cc.restore();
            }
            cc.setBitmap(null);

            // ---------- ② 画进本层：painter 上的系统 RenderEffect 会糊掉整层 ----------
            dst.set(0, 0, w, h);
            cv.drawBitmap(buf, null, dst, srcPaint);
        }
    }

    /**
     * 刷新总线：一个滚动监听驱动全部玻璃层。
     *
     * ★ 关键：ViewTreeObserver 是【整个窗口共用】的对象（Activity recreate 后仍是同一个），
     *   换容器时必须先卸掉旧监听 —— 否则旧监听会把自己的 tick post 到【已 detach 的旧视图】上，
     *   那个 post 永远不会执行 → pending 闩卡死 → 之后所有滚动刷新静默失效
     *   （现象：换壁纸后就“固定”了，连顶栏的实时模糊也没了）。
     */
    static final class GlassSync {

        static final int[] LOC1 = new int[2], LOC2 = new int[2];

        private static final java.util.ArrayList<java.lang.ref.WeakReference<GlassView>> VIEWS =
                new java.util.ArrayList<>();
        private static final java.util.WeakHashMap<View, Boolean> WATCHED =
                new java.util.WeakHashMap<>();
        private static android.view.ViewTreeObserver hookedTvo;
        private static View hooked;
        private static android.view.ViewTreeObserver.OnScrollChangedListener hookListener;
        private static boolean pending;
        private static long pendingAt;
        private static int ticks;

        private GlassSync() {}

        static void register(GlassView v) {
            prune();
            for (java.lang.ref.WeakReference<GlassView> r : VIEWS) {
                if (r.get() == v) return;
            }
            VIEWS.add(new java.lang.ref.WeakReference<>(v));
        }

        /** 把滚动容器接入总线（同一个 observer 只挂一次；换了就重挂）。 */
        static void attach(final View sc) {
            if (sc == null) return;
            hook(sc);
            if (WATCHED.put(sc, Boolean.TRUE) == null) {
                sc.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) { hook(v); }
                    @Override public void onViewDetachedFromWindow(View v) { }
                });
            }
        }

        private static void hook(final View sc) {
            android.view.ViewTreeObserver tvo = sc.getViewTreeObserver();
            if (hooked == sc && hookedTvo == tvo) return;
            if (hookedTvo != null && hookListener != null) {
                try { hookedTvo.removeOnScrollChangedListener(hookListener); } catch (Throwable ignored) {}
            }
            pending = false;
            hookListener = null;
            hooked = sc;
            hookedTvo = tvo;
            hookListener = () -> {
                // 合并同帧重复事件；96ms 超时自愈（防止闩被意外卡死）
                long now = android.os.SystemClock.uptimeMillis();
                if (pending && now - pendingAt < 96L) return;
                pending = true;
                pendingAt = now;
                sc.post(() -> { pending = false; tick(); });
            };
            tvo.addOnScrollChangedListener(hookListener);
            Effects.logDiag("滚动监听挂载: sv@" + Integer.toHexString(System.identityHashCode(sc))
                    + " tvo@" + Integer.toHexString(System.identityHashCode(tvo)));
        }

        private static void tick() {
            prune();
            ticks++;
            int alive = 0;
            for (int i = 0; i < VIEWS.size(); i++) {
                GlassView v = VIEWS.get(i).get();
                if (v == null || !v.isAttachedToWindow()) continue;
                v.onTick();
                alive++;
            }
            if (ticks <= 3 || ticks % 120 == 0) {
                Effects.logDiag("tick#" + ticks + " 层数=" + alive + " scrollY="
                        + (hooked instanceof android.widget.ScrollView
                            ? ((android.widget.ScrollView) hooked).getScrollY() : -1));
            }
        }

        /** 手动刷新全部玻璃层（切页 / 布局突变后调用）。 */
        static void refresh() {
            prune();
            for (int i = 0; i < VIEWS.size(); i++) {
                GlassView v = VIEWS.get(i).get();
                if (v == null || !v.isAttachedToWindow()) continue;
                v.doTick();
            }
        }

        private static void prune() {
            for (int i = VIEWS.size() - 1; i >= 0; i--) {
                if (VIEWS.get(i).get() == null) VIEWS.remove(i);
            }
        }
    }

    /** 毛玻璃皮肤：圆角白纱 + 高光边（只画形状/颜色，不涉及模糊）。 */
    static final class GlassDrawable extends android.graphics.drawable.Drawable {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rr = new RectF();
        private final float radius;

        GlassDrawable(Context c, float cornerDp, int tint) {
            radius = dp(c, cornerDp);
            fill.setColor(tint);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1f, dp(c, 0.5f)));
            edge.setColor(GLASS_EDGE);
        }

        @Override public void draw(Canvas cv) {
            android.graphics.Rect b = getBounds();
            rr.set(b.left, b.top, b.right, b.bottom);
            cv.drawRoundRect(rr, radius, radius, fill);
            cv.drawRoundRect(rr, radius, radius, edge);
        }

        @Override public void setAlpha(int a) { fill.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { fill.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** 系统原生模糊 + 诊断日志。 */
    static final class Effects {

        /** 给 View 自身渲染内容加高斯模糊（系统原生 API 31+）。 */
        static boolean selfBlur(View v, float r) {
            if (android.os.Build.VERSION.SDK_INT < 31) return false;
            try {
                float px = Math.max(0.1f, dp(v.getContext(), r));
                v.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        px, px, android.graphics.Shader.TileMode.CLAMP));
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * 文件日志开关（默认关闭）。
         *
         * logDiag 每次调用都要 open/write/close 一个文件；滚动时一秒能触发上百次，
         * 是 debug 包卡顿的元凶之一。需要抓日志时置 true（否则只走 Log.i）。
         */
        static boolean FILE_LOGGING = false;  // 滚动时写文件会卡顿，默认关；抓日志时置 true
        private static final java.util.concurrent.atomic.AtomicBoolean BUSY =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        static void logDiag(String s) {
            android.util.Log.i("SoftUi", s);
            if (!FILE_LOGGING) return;
            if (!BUSY.compareAndSet(false, true)) return;
            try {
                for (String d : new String[]{
                        "/storage/emulated/0/Download",
                        "/sdcard/Download",
                        "/storage/emulated/0"}) {
                    java.io.File dir = new java.io.File(d);
                    if (!dir.exists() || !dir.canWrite()) continue;
                    java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(
                                    new java.io.File(dir, "UiStartup.log"), true),
                            "UTF-8");
                    w.write("[fx] " + s + "\n");
                    w.flush();
                    w.close();
                    return;
                }
            } catch (Throwable ignored) {
            } finally {
                BUSY.set(false);
            }
        }
    }
    // ============================================================
    //  ⑤ 工厂 API（外部只用这些）
    // ============================================================

    /** 页面根：灰底 + 大标题 + 若干卡片 */
    public static LinearLayout screen(Context c, String title) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CANVAS);
        root.setPadding(dp(c, SIDE), dp(c, TITLE_TOP), dp(c, SIDE), dp(c, SIDE));

        if (title != null && !title.isEmpty()) {
            TextView t = text(c, title, TITLE_SIZE, TEXT_PRIMARY);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(c, TITLE_BOTTOM);
            root.addView(t, lp);
        }
        return root;
    }

    /** 把若干卡片/视图依次加进根，自动加间距 */
    public static void stack(LinearLayout root, View... children) {
        Context c = root.getContext();
        for (int i = 0; i < children.length; i++) {
            View v = children[i];
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(c, GAP);
            root.addView(v, lp);
        }
    }

    /** 卡片容器 */
    public static Card card(Context c, View... children) {
        Card card = new Card(c);
        for (View v : children) card.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    /** 开关行 */
    public static View toggle(Context c, String title, boolean checked,
                              Switch.OnChange cb) {
        return toggle(c, title, checked, cb, 0f);
    }

    /**
     * 开关行 + 附属行折叠：
     *   - 开启时：dependents 依次显示（带淡入）
     *   - 关闭时：dependents 收起（带淡出），避免选项堆叠
     *
     * 用法：
     *   View clockRow = toggle(c, "时钟模糊", ...);
     *   View widgetRow = toggleWithDependents(c, "小组件模糊", checked, cb, clockRow);
     */
    /**
     * 开关行 + 附属折叠（兄弟级：holder 与开关行【平级】，由调用方放进同一卡片）。
     *
     * 用法：
     *   View[] pair = toggleWithDependents(c, "小组件模糊", checked, cb, clockRow);
     *   card(pair[0], pair[1]);     // 开关行 + 折叠容器 平级入卡片
     *
     * 返回 [开关行, 折叠容器]，调用方自行决定两者在卡片中的位置。
     */
    public static View[] toggleWithDependents(Context c, String title, boolean checked,
                                              Switch.OnToggle cb, final View... dependents) {
        final LinearLayout holder = new LinearLayout(c);
        holder.setOrientation(LinearLayout.VERTICAL);
        for (View d : dependents) {
            holder.addView(d, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        applyFold(holder, checked, false);

        Row row = (Row) toggle(c, title, checked, null);
        row.setOnToggle(v -> {
            applyFold(holder, v, true);
            if (cb != null) cb.onToggle(v);
        });
        return new View[]{ row, holder };
    }

    /**
     * 折叠/展开一个容器（带高度动画，让父容器白底跟着生长/收缩，而不是突兀跳变）。
     *
     * 采用“先测量目标高 -> 从 0/目标高 插值”的方式，兼容 WRAP_CONTENT 卡片。
     */
    public static void applyFold(final View holder, boolean visible, boolean animate) {
        if (holder == null) return;
        cancelFoldAnim(holder);        // ★ 取消上一次未完成的折叠动画（避免多个动画竞争）
        final ViewGroup.LayoutParams lp = holder.getLayoutParams();
        if (lp == null) {
            // 无布局参数，退化为纯可见性
            holder.setVisibility(visible ? View.VISIBLE : View.GONE);
            return;
        }
        if (!animate) {
            lp.height = visible ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
            holder.setLayoutParams(lp);
            holder.setVisibility(visible ? View.VISIBLE : View.GONE);
            holder.setAlpha(1f);
            return;
        }
        final int targetH = measureContentHeight(holder);   // 内容目标高
        if (visible) {
            // 展开：0 -> targetH，然后交回 WRAP_CONTENT
            holder.setVisibility(View.VISIBLE);
            final int fromH = Math.max(0, holder.getHeight());
            ValueAnimator va = ValueAnimator.ofInt(fromH, targetH);
            va.setDuration(200);
            va.setInterpolator(new android.view.animation.DecelerateInterpolator());
            va.addUpdateListener(a -> {
                lp.height = (int) a.getAnimatedValue();
                holder.setLayoutParams(lp);
            });
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    holder.setLayoutParams(lp);
                }
            });
            holder.setTag(FOLD_ANIM_TAG, va);
            va.start();
        } else {
            // 收起：当前高 -> 0，然后 GONE
            int fromH = holder.getHeight() > 0 ? holder.getHeight() : measureContentHeight(holder);
            ValueAnimator va = ValueAnimator.ofInt(fromH, 0);
            va.setDuration(180);
            va.setInterpolator(new android.view.animation.AccelerateInterpolator());
            va.addUpdateListener(a -> {
                lp.height = (int) a.getAnimatedValue();
                holder.setLayoutParams(lp);
            });
            va.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    lp.height = 0;
                    holder.setLayoutParams(lp);
                    holder.setVisibility(View.GONE);
                }
            });
            holder.setTag(FOLD_ANIM_TAG, va);
            va.start();
        }
    }

    /** 折叠动画 Tag（存放当前正在跑的 ValueAnimator，供快速切换时取消）。 */
    private static final int FOLD_ANIM_TAG = "softui_fold_anim".hashCode();

    // ==================== ⑥ 自绘确认弹窗 ====================

    /** 确认弹窗回调。 */
    public interface ConfirmCb {
        void onConfirm();
    }

    /**
     * 自绘确认弹窗（**底部弹出** + **毛玻璃卡片**，与顶栏/底栏同一套模糊实现）。
     *
     * @param anchor     挂载锚点（推荐传页面根 FrameLayout，如 scrollingScreen 的 shell）
     * @param title      标题（可为 null）
     * @param message    正文
     * @param okText     确定按钮文字（null = “确定”）
     * @param cancelText 取消按钮文字（null = “取消”）
     * @param onConfirm  点“确定”后回调（在 UI 线程）
     */
    public static void confirm(View anchor, String title, String message,
                               String okText, String cancelText,
                               final ConfirmCb onConfirm) {
        try {
            final ViewGroup host = resolveHost(anchor);
            if (host == null) {
                if (onConfirm != null) onConfirm.onConfirm();
                return;
            }
            final Context ctx = host.getContext();

            // 模糊采样源：宿主里的滚动容器（与顶栏/底栏一致）
            final android.widget.ScrollView sample = scrollerOf(host);

            // —— 遮罩层（全屏，压暗主界面）——
            final android.widget.FrameLayout overlay = new android.widget.FrameLayout(ctx);
            overlay.setBackgroundColor(0x4D000000);         // 30% 黑（压暗一点，不刺眼）
            overlay.setClickable(true);
            overlay.setFocusable(true);

            // —— 面板（FrameLayout：底层放玻璃，上层放内容）——
            final android.widget.FrameLayout panel = new android.widget.FrameLayout(ctx);
            panel.setClickable(true);                       // 拦截点击，防穿透
            float corner = 25f;                      // 弹窗圆角（较大，更圆润）
            // 圆角裁剪，让玻璃层跟着圆角（与 GlassView 一致）
            panel.setClipToOutline(true);
            final float cornerFinal = corner;
            panel.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    int w = v.getWidth(), h = v.getHeight();
                    if (w <= 0 || h <= 0) { o.setEmpty(); return; }
                    o.setRoundRect(0, 0, w, h, dp(v.getContext(), cornerFinal));
                }
            });

            // 内容容器（叠在玻璃之上）
            LinearLayout inner = new LinearLayout(ctx);
            inner.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(ctx, PAD + 6f);
            inner.setPadding(pad, pad, pad, pad);

            // 标题（居中）
            if (title != null && title.length() > 0) {
                TextView tvT = text(ctx, title, BODY_SIZE + 2f, TEXT_PRIMARY);
                tvT.setTypeface(tvT.getTypeface(), android.graphics.Typeface.BOLD);
                tvT.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.bottomMargin = dp(ctx, 12f);
                inner.addView(tvT, tlp);
            }

            // 正文（居中）
            if (message != null && message.length() > 0) {
                TextView tvM = text(ctx, message, SUB_SIZE + 2f, TEXT_SECONDARY);
                tvM.setGravity(Gravity.CENTER);
                tvM.setLineSpacing(dp(ctx, 5f), 1f);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                mlp.bottomMargin = dp(ctx, 16f);
                inner.addView(tvM, mlp);
            }

            // —— 按钮行（取消 | 确定），等宽撑满 ——
            LinearLayout btnRow = new LinearLayout(ctx);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.BOTTOM);

            final String cancelLabel = (cancelText == null ? "取消" : cancelText);
            final String okLabel = (okText == null ? "确定" : okText);

            // 收场动画：面板下滑 + 遮罩淡出，结束后移除
            final Runnable[] dismiss = new Runnable[1];
            dismiss[0] = () -> {
                if (overlay.getParent() == null) return;
                panel.animate().translationY(dp(ctx, 80f)).setDuration(200)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .start();
                overlay.animate().alpha(0f).setDuration(200)
                        .setListener(new android.animation.AnimatorListenerAdapter() {
                            @Override public void onAnimationEnd(android.animation.Animator a) {
                                host.removeView(overlay);
                            }
                        }).start();
            };

            TextView btnCancel = dialogButton(ctx, cancelLabel, TEXT_SECONDARY, false, true);
            btnCancel.setOnClickListener(v -> dismiss[0].run());
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, dp(ctx, 46f), 1f);   // 等宽
            clp.rightMargin = dp(ctx, 8f);
            btnRow.addView(btnCancel, clp);

            TextView btnOk = dialogButton(ctx, okLabel, ACCENT, true, true);
            btnOk.setOnClickListener(v -> {
                dismiss[0].run();
                if (onConfirm != null) onConfirm.onConfirm();
            });
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                    0, dp(ctx, 46f), 1f);   // 等宽
            olp.leftMargin = dp(ctx, 8f);
            btnRow.addView(btnOk, olp);

            inner.addView(btnRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f));      // 撑满剩余高度

            // 内容铺满面板（高度撑满，内部自动分布）
            panel.addView(inner, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // 面板放到底部（bottom sheet）：固定高度 = 屏高 1/4，底部留间距
            int screenH = ctx.getResources().getDisplayMetrics().heightPixels;
            int panelH = Math.round(screenH * 0.25f);           // 占屏高 1/4
            android.widget.FrameLayout.LayoutParams plp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            panelH);
            plp.gravity = Gravity.BOTTOM;
            plp.leftMargin = dp(ctx, 12f);
            plp.rightMargin = dp(ctx, 12f);
            plp.bottomMargin = dp(ctx, 40f);                    // 与屏幕底拉开间距
            overlay.addView(panel, plp);

            // 点遮罩空白处取消
            overlay.setOnClickListener(v -> dismiss[0].run());

            // 剪掉底部窗口 inset（避免被手势条遮挡）
            host.addView(overlay, new android.view.ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // —— 毛玻璃：内容装好后，把玻璃层插到面板最底（与顶栏同一套）——
            if (sample != null) {
                try {
                    LiveGlass g = new LiveGlass(ctx, sample, BLUR_RADIUS, GLASS_TINT, cornerFinal);
                    g.setClickable(false);
                    panel.addView(g, 0, new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
                    g.start();
                } catch (Throwable t2) {
                    com.shortcutblur.ModuleLog.e("SoftUi", "confirm glass failed", t2);
                    // 退化：纯白圆角底
                    panel.setBackground(roundRect(CARD_FILL, cornerFinal, ctx));
                }
            } else {
                // 无采样源（如未设壁纸）：半透明白底，仍有圆角
                panel.setBackground(roundRect(0xF2FFFFFF, cornerFinal, ctx));
            }

            // 进入动画：遮罩淡入 + 面板自底滑入
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(180).start();
            panel.setTranslationY(dp(ctx, 80f));
            panel.animate().translationY(0f).setDuration(240)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } catch (Throwable t) {
            com.shortcutblur.ModuleLog.e("SoftUi", "confirm dialog failed", t);
            if (onConfirm != null) onConfirm.onConfirm();
        }
    }

    /** 解析弹窗宿主：优先用 anchor 自身（若是 ViewGroup），否则向上找 DecorView。 */
    private static ViewGroup resolveHost(View anchor) {
        if (anchor instanceof ViewGroup) return (ViewGroup) anchor;
        if (anchor != null) {
            android.view.ViewParent p = anchor.getParent();
            while (p != null) {
                if (p instanceof ViewGroup) return (ViewGroup) p;
                p = p.getParent();
            }
        }
        return null;
    }
/**
     * 弹窗按钮（自绘胶囊按钮：圆角底 + 文字 + 按压态）。
     *
     * @param filled  true = 实心强调色底 + 白字；false = 浅灰底 + 彩色字
     * @param stretch true = 等宽撑满模式（水平 padding 收紧，交给 weight 分配宽度）
     */
    private static TextView dialogButton(Context c, String label, int color,
                                         boolean filled, boolean stretch) {
        TextView t = text(c, label, BODY_SIZE, filled ? 0xFFFFFFFF : color);
        t.setGravity(Gravity.CENTER);
        final float radius = dp(c, 24f);                 // 胶囊圆角（略大，配合 46dp 高）
        int ph = stretch ? dp(c, 8f) : dp(c, 22f), pv = dp(c, 10f);
        t.setPadding(ph, pv, ph, pv);

        final int baseColor = filled ? ACCENT : 0x14000000;   // 实心强调 / 浅灰
        final int pressColor = filled ? darken(ACCENT, 0.85f) : 0x28000000;

        final android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(radius);
        bg.setColor(baseColor);
        t.setBackground(bg);


        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    bg.setColor(pressColor);
                    v.invalidate();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    bg.setColor(baseColor);
                    v.invalidate();
                    break;
            }
            return false;   // 不吞事件，让点击照常触发
        });
        return t;
    }

    /** 颜色变暗（k<1 变暗）。 */
    private static int darken(int color, float k) {
        int a = (color >>> 24) & 0xFF;
        int r = (int) (((color >> 16) & 0xFF) * k);
        int g = (int) (((color >> 8) & 0xFF) * k);
        int b = (int) ((color & 0xFF) * k);
        return (a << 24) | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
    }
    private static int clamp8(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

    /** 取消 holder 上正在跑的折叠动画（若存在）。 */
    private static void cancelFoldAnim(View holder) {
        try {
            Object t = holder.getTag(FOLD_ANIM_TAG);
            if (t instanceof ValueAnimator) {
                ((ValueAnimator) t).cancel();
            }
            holder.setTag(FOLD_ANIM_TAG, null);
        } catch (Throwable ignored) {}
    }

    /** 测量一个 View 在 WRAP_CONTENT 下的内容高度。 */
    public static int measureContentHeight(View v) {
        try {
            int w = v.getWidth();
            if (w <= 0) {
                android.view.ViewParent p = v.getParent();
                w = (p instanceof View) ? ((View) p).getWidth() : 0;
            }
            if (w <= 0) {
                w = v.getContext().getResources().getDisplayMetrics().widthPixels;
            }
            int wSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST);
            int hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            v.measure(wSpec, hSpec);
            int h = v.getMeasuredHeight();
            return h > 0 ? h : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** toggle 带子项缩进（extraDp > 0 时标题右移，表示层次）。整行可点。 */
    public static Row toggle(Context c, String title, boolean checked,
                             Switch.OnChange cb, float extraDp) {
        return toggle(c, title, checked, cb, extraDp, true);
    }

    /**
     * toggle 全参重载。
     * @param rowClickable true = 点击整行任意处可切换开关（默认）；false = 仅开关本体可点（如日志行）。
     */
    public static Row toggle(Context c, String title, boolean checked,
                             Switch.OnChange cb, float extraDp, boolean rowClickable) {
        Switch sw = new Switch(c);
        sw.setCheckedImmediate(checked);   // ★ 初始化不播动画（避免重建时整屏开关一起动）
        if (cb != null) sw.setOnChange(cb);
        Row r = new Row(c, title, sw, false);
        if (extraDp > 0f) r.indent(c, extraDp);
        if (rowClickable) r.setRowClickSwitch(sw);   // ★ 整行可点切换（不覆盖 onChange）
        return r;
    }

    /** 分组标题（卡片外，小写灰色标题 + 上下呼吸距）。 */
    public static TextView group(Context c, String title) {
        TextView t = text(c, title, SUB_SIZE, TEXT_SECONDARY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(c, SIDE + 4);
        lp.topMargin = dp(c, 14);
        lp.bottomMargin = dp(c, 6);
        t.setLayoutParams(lp);
        return t;
    }

    /** 可点击链接行（右侧箭头），点击回调。 */
    public static Row link(Context c, String title, final OnClick cb) {
        TextView arrow = text(c, "›", BODY_SIZE + 4, TEXT_SECONDARY);
        Row r = new Row(c, title, arrow, false);
        r.setOnClickListener(v -> { if (cb != null) cb.onClick(); });
        return r;
    }

    /** 行右侧的状态文字（可后续 setText 更新）。 */
    public static TextView statusText(Context c, String s) {
        return text(c, s == null ? "" : s, SUB_SIZE, TEXT_SECONDARY);
    }

    /** 可点击行：标题 + 右侧自定义 View（如状态文字），点击触发回调。 */
    public static Row action(Context c, String title, View right, final OnClick cb) {
        Row r = new Row(c, title, right, false);
        r.setOnClickListener(v -> { if (cb != null) cb.onClick(); });
        return r;
    }

    /**
     * 可点击行（带箭头，双行）：上行标题 + 下行副标题，右侧 ”›” 箭头。
     * 用于“主名 + 包名”这类可能放不下一行的条目：副标题可自动换行。
     */
    public static View actionArrowBlock(Context c, String title, String subtitle, final OnClick cb) {
        LinearLayout line = new LinearLayout(c);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = text(c, title == null ? "" : title, BODY_SIZE, TEXT_PRIMARY);
        t1.setSingleLine(true);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        if (subtitle != null && subtitle.length() > 0) {
            TextView t2 = text(c, subtitle, SUB_SIZE, TEXT_SECONDARY);
            t2.setSingleLine(false);          // 副标题可换行
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp2.topMargin = dp(c, 2f);
            texts.addView(t2, lp2);
        }
        line.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = text(c, "›", BODY_SIZE + 4, TEXT_SECONDARY);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(c, 6f);
        line.addView(arrow, alp);

        LinearLayout outer = new LinearLayout(c);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setMinimumHeight(dp(c, ROW_H));
        outer.setGravity(Gravity.CENTER_VERTICAL);
        outer.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        outer.setOnClickListener(v -> { if (cb != null) cb.onClick(); });
        return outer;
    }

    /**
     * 可点击行（带箭头）：标题 + 右侧自定义 View（可选） + ”›” 箭头，点击触发回调。
     * 箭头由控件统一渲染，调用方无需自己拼字符串。
     */
    public static Row actionArrow(Context c, String title, View right, final OnClick cb) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        if (right != null) wrap.addView(right);
        TextView arrow = text(c, "›", BODY_SIZE + 4, TEXT_SECONDARY);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(c, 6f);
        wrap.addView(arrow, alp);
        Row r = new Row(c, title, wrap, false);
        r.setOnClickListener(v -> { if (cb != null) cb.onClick(); });
        return r;
    }

    /** 普通文本行（不可点，用于展示版本/作者等信息）。 */
    public static View info(Context c, String title, String value) {
        TextView v = text(c, value == null ? "" : value, SUB_SIZE, TEXT_SECONDARY);
        return new Row(c, title, v, false);
    }

    /**
     * 双行条目：主标题（第一行）+ 说明（第二行，次要色、小字号）。
     * 用于“开源项目致谢”等需要上下两行的展示。
     */
    public static View infoBlock(Context c, String title, String subtitle) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setMinimumHeight(dp(c, ROW_H));
        col.setGravity(Gravity.CENTER_VERTICAL);

        TextView t1 = text(c, title == null ? "" : title, BODY_SIZE, TEXT_PRIMARY);
        col.addView(t1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        if (subtitle != null && subtitle.length() > 0) {
            TextView t2 = text(c, subtitle, SUB_SIZE, TEXT_SECONDARY);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp2.topMargin = dp(c, 2f);
            col.addView(t2, lp2);
        }
        return col;
    }

    /**
     * 双行条目（可点击）：右侧带 › 箭头，点击触发回调（用于跳转链接）。
     */
    public static View infoBlock(Context c, String title, String subtitle, final OnClick cb) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setMinimumHeight(dp(c, ROW_H));
        col.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout line = new LinearLayout(c);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = text(c, title == null ? "" : title, BODY_SIZE, TEXT_PRIMARY);
        texts.addView(t1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        if (subtitle != null && subtitle.length() > 0) {
            TextView t2 = text(c, subtitle, SUB_SIZE, TEXT_SECONDARY);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp2.topMargin = dp(c, 2f);
            texts.addView(t2, lp2);
        }
        line.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = text(c, "›", BODY_SIZE + 4, TEXT_SECONDARY);
        line.addView(arrow);

        col.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        col.setOnClickListener(v -> { if (cb != null) cb.onClick(); });
        return col;
    }

    /** 点击回调（避免依赖 View.OnClickListener 的生命周期混淆）。 */
    public interface OnClick { void onClick(); }

    /** 滑杆行（右侧显示数值） */
    public static View slider(Context c, String title, float value,
                              float min, float max, final String unit,
                              Slider.OnChange cb) {
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);

        final TextView val = text(c, fmt(value) + (unit == null ? "" : unit),
                SUB_SIZE, TEXT_SECONDARY);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.rightMargin = dp(c, 8);
        right.addView(val, vlp);

        final Slider sl = new Slider(c, value, min, max);
        if (cb != null) sl.setOnChange(cb);
        sl.setOnChange(new Slider.OnChange() {
            @Override public void onChange(float v) {
                val.setText(fmt(v) + (unit == null ? "" : unit));
                if (cb != null) cb.onChange(v);
            }
        });
        right.addView(sl);

        return new Row(c, title, right, false);
    }

    private static String fmt(float v) {
        if (v == (long) v) return String.valueOf((long) v);
        return String.valueOf(Math.round(v));
    }

    // ============================================================
    //  ⑥ 组合工厂：模糊顶栏 + 可滚动页面
    // ============================================================

    /**
     * 模糊顶栏：一个固定高度的横向容器，背景会透视 sample 的内容并模糊。
     *
     * @param c      上下文
     * @param title  标题文字
     * @param sample 要透视觉察的内容 View（滚动容器）
     */
    /**
     * 顶栏：置顶、固定高度、不吃触摸（把滑动事件让给下方 ScrollView）。
     * sample 为被遮盖的滚动内容容器，用于实时采样做透视模糊。
     */
    public static android.widget.FrameLayout header(Context c, String title, final View sample) {
        // 外层用 FrameLayout 便于"模糊层 + 标题层"叠放
        final android.widget.FrameLayout bar = new android.widget.FrameLayout(c);
        bar.setClickable(false);
        bar.setFocusable(false);
        bar.setWillNotDraw(false);
        bar.setBackgroundColor(0x00000000);

        // 标题层（叠在上方，靠底部对齐）
        TextView t = text(c, title, TITLE_SIZE, TEXT_PRIMARY);
        android.widget.FrameLayout.LayoutParams tlp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.BOTTOM | Gravity.START;
        tlp.leftMargin = dp(c, SIDE);
        tlp.bottomMargin = dp(c, 10);
        bar.addView(t, tlp);

        // 先放一个占位（避免刚进页面时顶栏透空）
        // 白纱改由 Sampler 的独立白纱层承担，bar 自身透明（否则会叠两层）
        bar.setBackgroundColor(0x00000000);

        // 挂透视模糊（attachBehindBlur 会把 ImageView 插到 index 0）
        bar.post(() -> {
            try {
                // 顶栏统一走 Sampler：它内部会"壁纸垫底 + 叠内容"，
                // 因此顶栏能同时糊到【背景壁纸】和【滚过来的卡片】。
                blurBehind(bar, sample, HEADER_BLUR, true);
                Effects.logDiag("header: 毛玻璃（壁纸+内容）");
                Effects.logDiag("header.post: bar=" + bar.getWidth() + "x" + bar.getHeight()
                        + " children=" + bar.getChildCount());
            } catch (Throwable t2) {
                Effects.logDiag("header.post 失败: " + t2);
            }
        });
        return bar;
    }

    /** header 的 FrameLayout 版本（推荐用这个，避免类型转换麻烦）。 */
    public static android.widget.FrameLayout headerF(Context c, String title, final View sample) {
        return headerF(c, title, sample, statusBarH(c));
    }

    /**
     * headerF 带状态栏上边距：标题避开状态栏，模糊层铺满整条（含状态栏区）。
     *
     * 结构（关键：模糊层不能受 padding 影响，所以用【内层容器】承载 padding）：
     *   bar (FrameLayout, 无 padding, 高 = statusBar + HEADER_H)
     *     ├ [模糊 ImageView]  ← attachBehindBlur 插到 index 0，MATCH_PARENT 铺满
     *     └ inner (FrameLayout, paddingTop = statusBar)   ← 标题避开状态栏
     *         └ TextView 标题
     */
    public static android.widget.FrameLayout headerF(Context c, String title, final View sample,
                                                     int padTop) {
        final android.widget.FrameLayout bar = new android.widget.FrameLayout(c);
        bar.setClickable(false);
        bar.setFocusable(false);
        bar.setWillNotDraw(false);
        // 白纱改由 Sampler 的独立白纱层承担，bar 自身透明（否则会叠两层）
        bar.setBackgroundColor(0x00000000);

        // 内层容器：只负责把标题压到状态栏下方，不干扰模糊层
        final android.widget.FrameLayout inner = new android.widget.FrameLayout(c);
        inner.setPadding(0, padTop, 0, 0);
        bar.addView(inner, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView t = text(c, title, TITLE_SIZE, TEXT_PRIMARY);
        android.widget.FrameLayout.LayoutParams tlp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.BOTTOM | Gravity.START;
        tlp.leftMargin = dp(c, SIDE);
        tlp.bottomMargin = dp(c, 10);
        inner.addView(t, tlp);
        t.setTag("softui:title");   // 供 setTitle() 定位标题

        // 底部分割线：贴在 bar 最底部，半透明
        if (HEADER_LINE_H > 0f) {
            View line = new View(c);
            line.setBackgroundColor(HEADER_LINE_COLOR);
            android.widget.FrameLayout.LayoutParams llp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(c, HEADER_LINE_H));
            llp.gravity = Gravity.BOTTOM;
            bar.addView(line, llp);
        }

        bar.post(() -> {
            try {
                // 顶栏统一走 Sampler（壁纸垫底 + 内容），通栏无圆角
                blurBehind(bar, sample, HEADER_BLUR, true);
                bar.setBackgroundColor(0x00000000);
                Effects.logDiag("header.post: 毛玻璃（壁纸+内容）"
                        + " bar=" + bar.getWidth() + "x" + bar.getHeight()
                        + " children=" + bar.getChildCount());
            } catch (Throwable t2) {
                Effects.logDiag("header.post 失败: " + t2);
            }
        });
        return bar;
    }

    /**
     * 可滚动页面 + 透视模糊顶栏（一条龙装配）。
     *
     * 结构：
     *   FrameLayout(shell)
     *     ├ ScrollView（内容，顶部用 padding 留出顶栏高度）
     *     └ header（置顶，不吃触摸）
     *
     * 关键点：
     *  - 内容顶部留白 = HEADER_H（用 padding，滚动时内容会穿过顶栏下方，形成透视）
     *  - 顶栏 setClickable(false)，触摸穿透到 ScrollView
     */
    public static View scrollingScreen(Context c, String title) {
        return scrollingScreen(c, title, null);
    }

    /**
     * 带背景图的滚动页。
     *
     * 层级（从下到上）：
     *   ① BackdropView（背景图，清晰）  ← 新增
     *   ② ScrollView（内容）
     *   ③ 顶栏 bar（毛玻璃）
     *
     * bg = null 时退回纯色背景，行为与旧版一致。
     */
    public static View scrollingScreen(Context c, String title,
                                       android.graphics.Bitmap bg) {
        final android.widget.FrameLayout shell = new android.widget.FrameLayout(c);
        shell.setBackgroundColor(CANVAS);

        // —— ① 背景图层（最底）——
        if (bg != null && !bg.isRecycled()) {
            BackdropView backdrop = new BackdropView(c);
            backdrop.setBitmap(bg);
            shell.addView(backdrop, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // 状态栏高度（顶栏总高 = 状态栏 + 标题区）
        final int sbh = statusBarH(c);

        // 内容容器：顶部留白 = 顶栏总高 + 呼吸间距（避免第一项贴着顶栏）
        final LinearLayout content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(c, SIDE), sbh + dp(c, HEADER_H) + dp(c, HEADER_GAP),
                dp(c, SIDE), dp(c, SIDE));
        // 第一张卡片与顶栏底边之间额外留白（避免滚动时内容被顶到顶栏里）
        content.setClipToPadding(true);

        final android.widget.ScrollView sc = new android.widget.ScrollView(c);
        sc.setVerticalScrollBarEnabled(false);
        sc.setFillViewport(false);
        // 关键：ScrollView 必须能收到触摸
        sc.setClickable(true);
        sc.setFocusable(true);
        // 内容用 ScrollView.LayoutParams（MATCH_PARENT 宽 + WRAP_CONTENT 高）
        sc.addView(content, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(sc, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏：采样源 = ScrollView；总高 = 状态栏 + HEADER_H
        final android.widget.FrameLayout bar = headerF(c, title, sc, sbh);
        android.widget.FrameLayout.LayoutParams blp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        sbh + dp(c, HEADER_H));
        blp.gravity = Gravity.TOP;
        shell.addView(bar, blp);

        Effects.logDiag("scrollingScreen 构建: HEADER_H(dp)=" + HEADER_H
                + " statusBar(px)=" + sbh
                + " padTop(px)=" + (sbh + dp(c, HEADER_H) + dp(c, HEADER_GAP))
                + " headerH(px)=" + (sbh + dp(c, HEADER_H))
                + " gap(px)=" + dp(c, HEADER_GAP));
        return shell;
    }

    /** 取 scrollingScreen 的内容容器，往里加卡片。 */
    public static LinearLayout contentOf(View scrollingScreen) {
        // 结构固定：FrameLayout[0]=ScrollView, ScrollView[0]=content LinearLayout
        if (scrollingScreen instanceof android.view.ViewGroup) {
            android.view.ViewGroup shell = (android.view.ViewGroup) scrollingScreen;
            for (int i = 0; i < shell.getChildCount(); i++) {
                View ch = shell.getChildAt(i);
                if (ch instanceof android.widget.ScrollView) {
                    android.widget.ScrollView sc = (android.widget.ScrollView) ch;
                    if (sc.getChildCount() > 0
                            && sc.getChildAt(0) instanceof LinearLayout) {
                        return (LinearLayout) sc.getChildAt(0);
                    }
                }
            }
        }
        return null;
    }

    // ============================================================
    //  ⑦ 底部标签栏（底栏）与页面切换
    //
    //    TabBar       等宽标签，选中只高亮文字（纯代码，零图片）
    //    attachFooter 挂到页面底部 + 毛玻璃 + 给内容补底部留白
    //    setTitle     切页时改顶栏标题
    //    scrollToTop  切页时回到顶部
    //    refreshGlass 切页后刷新全部玻璃层
    //
    //  底栏的毛玻璃与顶栏同一套实现：糊「壁纸 + 滚到它背后的内容」，
    //  没有任何自实现模糊 —— 模糊仍然由系统 RenderEffect 完成。
    // ============================================================

    /** 底栏标签点击回调。 */
    public interface OnTab { void onTab(int index); }

    /**
     * 底部标签栏：等宽标签，选中只高亮文字。
     *
     * 结构：
     *   bar(FrameLayout)
     *     ├ [0] 玻璃层（attachFooter 里由 blurBehind 插到最底）
     *     ├ [1] 顶部细分割线（与顶栏底部分割线呼应）
     *     └ [2] inner（paddingBottom = 手势条高度；标签垂直居中）
     *            └ 标签行（等宽）
     */
    public static class TabBar extends android.widget.FrameLayout {

        private final TextView[] labels;
        private final android.widget.FrameLayout inner;
        private int index;

        public TabBar(Context c, String[] items, final OnTab cb) {
            super(c);
            inner = new android.widget.FrameLayout(c);
            addView(inner, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            inner.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));

            int n = (items == null) ? 0 : items.length;
            labels = new TextView[n];
            for (int i = 0; i < n; i++) {
                final int idx = i;
                TextView t = text(c, items[i], TAB_SIZE, TEXT_SECONDARY);
                t.setGravity(Gravity.CENTER);
                t.setOnClickListener(v -> {
                    select(idx);
                    if (cb != null) cb.onTab(idx);
                });
                row.addView(t, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f));
                labels[i] = t;
            }
            if (n > 0) applySelection();
        }

        /** 底部内边距（手势条高度），让标签不被手势条压住。 */
        public void setBottomInset(int px) {
            inner.setPadding(0, 0, 0, Math.max(0, px));
        }

        /** 选中某一项（不触发回调）。 */
        public void select(int i) {
            if (labels.length == 0) return;
            index = Math.max(0, Math.min(labels.length - 1, i));
            applySelection();
        }

        public int selected() { return index; }

        /** 选中态：只改文字颜色（选中 = TAB_SEL，未选 = TEXT_SECONDARY）。 */
        private void applySelection() {
            for (int i = 0; i < labels.length; i++) {
                labels[i].setTextColor(i == index ? TAB_SEL : TEXT_SECONDARY);
            }
        }
    }

    /**
     * 把底栏挂到页面底部，并自动：
     *   ① 开毛玻璃（糊「壁纸 + 滚到它背后的内容」，与顶栏同一套）
     *   ② 给内容补底部留白（最后一张卡片不会被底栏盖住）
     */
    public static void attachFooter(View shell, TabBar bar, float heightDp) {
        if (!(shell instanceof android.widget.FrameLayout) || bar == null) return;
        android.widget.FrameLayout fl = (android.widget.FrameLayout) shell;
        Context c = bar.getContext();
        int nav = navBarH(c);
        bar.setBottomInset(nav);

        if (HEADER_LINE_H > 0f) {
            View line = new View(c);
            line.setBackgroundColor(HEADER_LINE_COLOR);
            android.widget.FrameLayout.LayoutParams llp =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(c, HEADER_LINE_H));
            llp.gravity = Gravity.TOP;
            bar.addView(line, llp);
        }

        int h = dp(c, heightDp) + nav;
        fl.addView(bar, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM));

        LinearLayout content = contentOf(shell);
        if (content != null) {
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                    content.getPaddingRight(), h + dp(c, SIDE));
        }

        android.widget.ScrollView sc = scrollerOf(shell);
        if (sc != null) blurBehind(bar, sc, HEADER_BLUR, true);
    }

    /** 取页面里的 ScrollView（滚动容器）。 */
    public static android.widget.ScrollView scrollerOf(View shell) {
        if (shell instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) shell;
            for (int i = 0; i < g.getChildCount(); i++) {
                View ch = g.getChildAt(i);
                if (ch instanceof android.widget.ScrollView) {
                    return (android.widget.ScrollView) ch;
                }
            }
        }
        return null;
    }

    /** 回到顶部（切页时用）。 */
    public static void scrollToTop(View shell) {
        android.widget.ScrollView sc = scrollerOf(shell);
        if (sc != null) sc.scrollTo(0, 0);
    }

    /** 改顶栏标题（标题 TextView 带 "softui:title" 标记）。 */
    public static void setTitle(View root, String title) {
        if (root == null || title == null) return;
        View t = root.findViewWithTag("softui:title");
        if (t instanceof TextView) ((TextView) t).setText(title);
    }

    /** 刷新全部玻璃层（切页 / 布局突变后调用）。 */
    public static void refreshGlass() {
        GlassSync.refresh();
    }

    // 颜色插值
    private static int blend(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t);
        int r = (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
        int g = (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
        return Color.argb(a, r, g, b);
    }
}