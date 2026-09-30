/*
 * ColorOS Blur Enhance —— ColorOS 16 桌面 / 多任务 / 时钟组件的动态模糊增强（LSPosed 模块）
 * Copyright (C) 2026 wisely-leo
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.shortcutblur;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

import static com.shortcutblur.BlurLib.*;

/**
 * 多任务（Recents / Overview）模糊控制器。
 * 从 BlurEnhanceModule 抽出并解耦：不再依赖 XposedModule，通过 {@link HookApi} 注册 hook。
 * 入口：
 *   installProbes(loader, api)   —— 入场/退场信号 & 诊断探针 hook
 *   installStateHooks(loader, api)—— LauncherState 状态机 hook
 *   applyConf(intent)            —— 接收 SETCONF 广播里的 recents 参数
 *   describe()                   —— VER 日志用的一行状态
 */
final class RecentsBlur {
    private RecentsBlur() {}

    /** 解耦用的 hook 注册接口（由模块注入）。 */
    public interface HookApi {
        void hook(String id, Executable target, XposedInterface.Hooker hooker);
    }

    private static HookApi API;


    private static volatile boolean sStateInOverview = false;


    private static volatile float RECENTS_BLUR_MAX = 64.0f;

    // 缩放钳制（纯几何，不含判断）：scale 不低于此值
    // 缩放钳制默认值（0.96）；可被 /sdcard/Download/ColorOSBlurEnhance.conf 的 scaleMin= 覆盖

    private static volatile float sScaleClampMin = 0.96f;

    private static volatile long sConfLastRead = 0L;

    private static final String CONF_PATH = "/sdcard/Download/ColorOSBlurEnhance.conf";
    // anchor 模式：true=Workspace（图标网格层，不含卡片）；false=DragLayer（整个桌面容器）

    private static volatile boolean sAnchorUseWorkspace = false;

    private static volatile boolean sConfErrorLogged = false;


    private static volatile float sRecentsLastRadius = -1.0f;


    private static volatile ValueAnimator sRecentsBlurAnim = null;

    private static volatile float sRecentsAnimRadius = 0.0f;



    private static volatile View sRecentsBlurView = null;


    private static volatile float sRecentsTargetRadius = -1.0f;


    private static volatile int sRecentsPhase = 0;


    private static volatile boolean sRecentsBlurDoneForEntry = false;


    private static final long ENTER_FADE_MS = 180L;

    private static final long EXIT_FADE_MS = 120L;


    private static volatile ValueAnimator sRecentsEnterAnim = null;


    private static volatile ValueAnimator sRecentsExitAnim = null;


    private static volatile boolean sRecentsArmed = false;


    private static final long ARM_DELAY_MS = 180L;

    private static Runnable sPendingArm = null;


    private static final android.os.Handler sRecentsHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private static Runnable sPendingClear = null;


    private static final long EXIT_DEBOUNCE_MS = 120L;
    // [v17] 进场去抖 + alpha 下降早期进场

    private static final long ENTER_DEBOUNCE_MS = 80L;

    private static volatile Runnable sPendingEnter = null;

    private static volatile boolean sDescentEnterEnabled = false; // [v18] 回退：默认关

    private static volatile float sMinScaleSeen = 1.0f;
    // [v24] v41 的 scale 阈值退场（scaleexit=off 可关）

    private static volatile boolean sScaleExitEnabled = true;
    // [v23] tint=on：进场把效果换成红色滤镜，用于肉眼判定"效果何时出现在屏幕上"

    private static volatile boolean sTintEnabled = false;
    // [v22] goToState 作为真正的状态机触发（gts=off 关闭）

    private static volatile boolean sGtsEnabled = true;
    // [v21] 最近一次成功解析的锚点：供没有上下文的调用路径（scale/alpha 传 null）回退

    private static volatile View sLastAnchor = null;
    // [v26] 诊断探针总开关（PixelCopy 位图采样 / 绘制探针 / View 树 dump）——默认关，正式运行零额外开销

    private static volatile boolean sDiagEnabled = false;
    // [v26] 缓存的桌面 DragLayer（scale/alpha hook 中记录），StateManager 无 View 上下文时兜底锚点

    private static volatile View sCachedDragLayer = null;
    // [v19] 纯状态机模式：可见性退场也默认关（visexit=on 可开回来）

    private static volatile boolean sVisExitEnabled = false;
    // [v18] scale 阈值进场（回退 vis/alpha 进场后，唯一入场时机判据）

    private static volatile boolean sScaleEnterEnabled = true; // [v20] 低优先级补进场（状态机仍是主判据）

    private static volatile float sEnterScale = 1.0f; // [v20] 一旦从 1.0 跌落

    private static volatile float sLastScale = 1.0f;

    private static volatile boolean sVisEnterEnabled = false;

    private static volatile Runnable sPendingExit = null;

    private static volatile float sLastAlphaIn = -1.0f;

    private static volatile float sMinAlphaIn = 1.0f;

    private static volatile boolean sSawAlphaDescent = false;

    private static volatile boolean sSelfAlphaCall = false;

    private static volatile boolean sAlphaExitEnabled = false; // [v19] 纯状态机：默认关
    // [v12] 早期信号探针的去重键

    private static volatile String sLastVisKey = "";
    // [v11] View.setRenderEffect 轨迹：看我们的效果何时/被谁清掉

    private static volatile String sLastFxKey = "";
    // [v10] 入场头几次施加后探测 RenderEffect 是否真的存在

    private static volatile int sFxProbeLeft = 0;
    // [v9] A/B 开关：新模块是否强开 launcher 的 supportIconBlur。默认 true = 保持现状
    // [v24] 不动原生桌面图标模糊：false = 原样传递 launcher 自己的取值

    private static volatile boolean sForceIconBlur = false;
    // [v7] 模糊目标集合（可能多个：图标层 + dock 等）

    private static final java.util.List<View> sBlurTargets = new java.util.ArrayList<View>();

    private static volatile Object sRecentsViewObj = null;

    private static volatile String sBlurMode = "draglayer";

    private static volatile boolean sClampEnabled = true;


    private static void armBlurTargets(View anchor) {
        sBlurTargets.clear();
        if (anchor == null) return;
        String mode = sBlurMode;
        if ("workspace".equals(mode) || "draglayer".equals(mode) || !(anchor instanceof ViewGroup)) {
            sBlurTargets.add(anchor);
        } else {
            ViewGroup g = (ViewGroup) anchor;
            View rec = findRecentsChildOf(g);
            if (rec == null) {
                sBlurTargets.add(anchor);
            } else {
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c == null || c == rec) continue;
                    sBlurTargets.add(c);
                }
            }
        }
        ModuleLog.d("BLURTARGET", "mode=" + mode + " count=" + sBlurTargets.size()
                + " names=" + blurTargetNames());
    }

    // 从 RecentsView 实例往上走，找到"它是 DragLayer 直接子 View"的那一层（要排除的卡片子树）

    private static View findRecentsChildOf(ViewGroup dragLayer) {
        Object rv = sRecentsViewObj;
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
            View t = sBlurTargets.get(i);
            sb.append(t == null ? "null" : t.getClass().getSimpleName()).append(' ');
        }
        return sb.toString();
    }

    // 入场时一次性打印图层清单，看清 DragLayer 里到底有什么

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

    // 入场后 700ms 内统计桌面重绘帧数：0 => 桌面被快照化(未实时重绘)，模糊自然"迟到"

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

    // [v9] 用 PixelCopy 采样屏幕像素的"清晰度"，程序化测出模糊真正上屏的时刻（不依赖录屏）
    //      sharpness = 相邻像素灰度差的平均值：越糊 => 越小；越清晰 => 越大
    // 从 View 的 Context 解包出 Activity 取 Window（PixelCopy 没有 View 重载）

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

    // [v12] 早期信号探针：只打时间戳，不改行为。目的＝找出"比 onStateTransitionStart 更早"的时刻

    private static int installEarlySignalProbes(ClassLoader loader) {
        int n = 0;

        // 候选1：StateManager.goToState（状态机入口）
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
                                        // [v22] 提升为真正的状态机触发：比 onStateTransitionStart 更靠源头
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

        // 候选2：launcher 自己为"上滑"创建的图标模糊动画
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

        // 候选3：Recents/Overview 视图的可见性变化
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

    // [v14] 唯一退场入口：状态机 Normal 与 visible=false 谁先到谁触发（保证每次进场都有对应退场）

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


    // [v13] 唯一入场入口：可由"卡片视图变可见"或状态机兜底触发

    private static void enterOverviewFrom(Object lrvSelf, String why) {
        if (sStateInOverview) return;
        View anchor = resolveBlurAnchor(lrvSelf);
        // [v22] 拿不到锚点就不占用状态：否则先到的信号会把后面的信号去重吃掉，导致整段没模糊
        if (anchor == null) {
            ModuleLog.d("STATEBLUR", "ENTER skipped (anchor null) why=" + why);
            return;
        }
        sStateInOverview = true;
        sMinAlphaIn = 1.0f;
        sSawAlphaDescent = false;
        sMinScaleSeen = 1.0f; // [v25]
        ModuleLog.d("STATEBLUR", "ENTER overview (" + why + ") -> startEnterFadeIn anchor=" + anchorName(anchor));
        cancelPendingRecentsClear();
        sRecentsPhase = 1;
        startEnterFadeIn(anchor);
    }


    // [v17] 进场去抖：visible=true 先挂 ENTER_DEBOUNCE_MS，期间 visible=false 回来就取消

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

    // [v16] 退场去抖：visible=false 先挂 EXIT_DEBOUNCE_MS，期间 visible=true 回来就取消

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
        sRecentsBlurView = v;
        armBlurTargets(v);
        // [v26] 诊断探针默认关（动画期 Bitmap/PixelCopy/View 树扫描 -> 额外 I/O 与拷贝）
        if (sDiagEnabled) {
            dumpDragLayerTree(v);
            startDrawProbe(v);
            startPixelProbe(v, "enter");
        }
        // [v25] 恢复 v41 的渐进入场动画：0 -> 64，180ms，Decelerate（原来被 v4 改成立即置满，渐进没了）
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

    // [stateOnly] 原“缩放回到 1.0 时清场”的入口已移除。


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
                if (v != null) { try { v.setRenderEffect(null); } catch (Throwable ignore) {} }
                ModuleLog.d("DRAGALPHA", why + " -> exit fade-out done (phase=IDLE)");
            }
        });
        sRecentsExitAnim = va;
        try { va.start(); } catch (Throwable t) { ModuleLog.e("DRAGALPHA", "exit anim start failed", t); }
        ModuleLog.d("DRAGALPHA", "exit fade-out started (" + from + " -> 0, why=" + why + ")");
        if (sDiagEnabled) startPixelProbe(v, "exit");
    }

    // [v7] 扇出：v 是锚点；真正被模糊的是 sBlurTargets（可能含图标层+dock 多个 View）

    private static void applySelfBlur(View v, float r) {
        sRecentsTargetRadius = r;
        if (sBlurTargets.isEmpty()) {
            blurOne(v, r);
        } else {
            for (int i = 0; i < sBlurTargets.size(); i++) {
                blurOne(sBlurTargets.get(i), r);
            }
        }
    }


    private static void blurOne(View v, float r) {
        if (v == null) return;
        sRecentsBlurView = v;
        sRecentsTargetRadius = r;

        if (sAnchorUseWorkspace && r > 0.5f) {
            sSelfAlphaCall = true;
            try { v.setAlpha(1.0f); } catch (Throwable ignore) {}
            sSelfAlphaCall = false;
        }
        float rApplied = r;
        try {
            // [v23] tint 模式：红色滤镜（观测量测用，默认关）
            if (rApplied > 0.5f && sTintEnabled) {
                RenderEffect fx = RenderEffect.createColorFilterEffect(
                        new android.graphics.BlendModeColorFilter(
                                0xB0FF0000, android.graphics.BlendMode.SRC_ATOP));
                ModuleLog.d("TINT", "colorFilter on=" + v.getClass().getSimpleName());
                v.setRenderEffect(fx);
            } else {
                BlurLib.setBlurRadius(v, rApplied); // [refactor] 改用库函数
            }
            ModuleLog.dv("BLURAPPLY", "r=" + rApplied + " on=" + v.getClass().getSimpleName()
                    + " setOk=true");
        } catch (Throwable t) {
            ModuleLog.e("BLURAPPLY", "setRenderEffect failed r=" + rApplied, t);
        }
    }


    private static void forceClearRecentsBlur(View v, String why) {
        sBlurTargets.clear();
        ValueAnimator old = sRecentsBlurAnim;
        if (old != null) {
            try { old.cancel(); } catch (Throwable ignore) {}
            sRecentsBlurAnim = null;
        }
        sRecentsAnimRadius = 0.0f;
        sRecentsLastRadius = -1.0f;
        sRecentsBlurView = null;
        sRecentsTargetRadius = -1.0f;

        sRecentsPhase = 0;
        sRecentsBlurDoneForEntry = false;
        if (v != null) {
            try { v.setRenderEffect(null); } catch (Throwable ignore) {}
        }
        ModuleLog.d("DRAGALPHA", why + " -> hard clear (phase=IDLE)");
    }


    private static int installRecentsIconBlurProbe(ClassLoader loader) {
        int n = 0;
        installEarlySignalProbes(loader);

        // [v11] 监听进程内每一次 setRenderEffect：我们的效果是否被清、被谁清、何时清
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
                                    // [v16] alpha 回升 = 过渡正在退回 -> 早期退场（比 visible=false 早）
                                    // 安全阀：必须先在本轮 overview 内见过 alpha 下降，否则进场首帧 a=1.0 会误判
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
                                    // [v17] 桌面开始淡出（alpha 下降）= 正在离开桌面 -> 立即进场（状态机可晚 2.9s）
                                    if (!sSelfAlphaCall && !sStateInOverview && sDescentEnterEnabled
                                            && prevA >= 0.95f && a < 0.95f) {
                                        ModuleLog.d("STATEBLUR", "alpha falling (" + prevA + " -> " + a + ") -> early enter");
                                        cancelPendingExit();
                                        enterOverviewFrom(null, "alphaFall");
                                    }

                                    try {
                                        if (self instanceof View) {
                                            View v = (View) self;
                                            sCachedDragLayer = v; // [v26] cache DragLayer
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

        // [stateOnly] 原基于 OplusDragLayer 缩放的旧判断链已整体移除；进/退完全由 LauncherState 状态机驱动。

        // [v24] v41 的入场钩子：launcher 自己的"进入概览动画"入口
        //（com.android.quickstep.touch.SwipeToRecentAnimationHelper.goOverviewAnimation）
        //  v41 就是靠它拿到入场时机的：反射读 mDragLayer -> startEnterFadeIn
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

        // --- scale 钳制：scale 不低于 SCALE_CLAMP_MIN（桌面最多缩 5%）；无进/退判断 ---
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
                                        Object self = chain.getThisObject(); // [v21] = OplusDragLayer 本身
                                        if (self instanceof View) sCachedDragLayer = (View) self; // [v26] 缓存 DragLayer
                                        Object[] a = chain.getArgs().toArray();
                                        if (a.length > 0 && a[0] instanceof Number) {
                                            float f = ((Number) a[0]).floatValue();
                                            reloadConfigIfStale();
                                            // [v25] scale 阈值退场：必须"先跌破 0.96、再回升过 0.988"才算回桌面
                                            //（v24 只判 >=0.988，而下降途中 1.0->0.92 必然经过 0.988，导致刚挂上就误退）
                                            if (f < sMinScaleSeen) sMinScaleSeen = f;
                                            if (sScaleExitEnabled && sStateInOverview && sMinScaleSeen < 0.96f && f >= 0.988f) {
                                                ModuleLog.d("STATEBLUR", "scale rose to " + f + " (min seen " + sMinScaleSeen
                                                        + ") -> early exit (scaleRise)");
                                                exitOverviewFrom(self, "scaleRise");
                                            }
                                            // [v20] scale 补进场（低优先级）：一旦桌面 scale 从 1.0 跌落就挂模糊；
                                            // 状态机仍是主判据：它先到就不用 scale；它给 Normal/Cancel 也能立刻退掉模糊
                                            // 需“从 >= 阈值 下穿到 < 阈值-1e-3”，否则退出时桌面回弹的帧会把模糊又拉回来
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
                                        // [已停用] 旧兜底清场，避免与状态机驱动冲突（仅诊断）
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
                                                // [已停用] 旧兜底清场（仅诊断）
                                                ModuleLog.d("EXITPROBE", "windowVisible [diag only]");
                                            }
                                        } else {
                                            float a = (args.length > 0 && args[0] instanceof Number)
                                                    ? ((Number) args[0]).floatValue() : 1.0f;
                                            ModuleLog.d("EXITPROBE", "setAlphaByTaskView a=" + a + " radius=" + sRecentsLastRadius);
                                            if (a >= 0.999f && self instanceof View && sRecentsLastRadius >= 0.0f) {
                                                // [已停用] 旧兜底清场（仅诊断）
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

    // =========================================================================
    // 状态机驱动的进/退判断（替换 OplusDragLayer.setScaleX 穿越 0.96 的启发式）
    //
    // 真信号：LauncherRecentsView implements StateManager$StateListener，
    //   onStateTransitionStart(LauncherState toState)      —— 过渡开始
    //   onStateTransitionComplete(LauncherState finalState) —— 过渡结束
    // 与动画/手势无关，覆盖所有入口（手势/通知/长按/返回），中途松手也归到最终态。
    // =========================================================================

    private static int installRecentsStateBlurHooks(ClassLoader loader) {
        int n = 0;
        try {
            Class<?> lrv = Reflect.loadClass(CLS_LAUNCHER_RECENTS_VIEW, loader);
            Class<?> ls = Reflect.loadClass(CLS_LAUNCHER_STATE, loader);
            if (lrv == null || ls == null) {
                ModuleLog.d("STATEBLUR", "[miss] LRV or LauncherState");
                return 0;
            }

            // --- onStateTransitionStart(LauncherState) → 进入 OVERVIEW 时入场 ---
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
                                sRecentsViewObj = self;
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

            // [v13] 进场真起点：卡片视图变可见（实测比状态机早 ~1.5s，与桌面淡出同步）
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

            // --- onStateTransitionComplete(LauncherState) → 回到 NORMAL 时退场 ---
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
                                    // 兜底：若 start(NORMAL) 未触发退场，这里补一次
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

            // --- onStateTransitionCancel(LauncherState) -> 过渡被取消（手势回弹）时清掉模糊 ---
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

    // 目标模糊锚点：优先用 Launcher 的 DragLayer（覆盖整个桌面+过渡层）
    // [v21] 记录并返回锚点：所有成功解析都走这里，供无上下文路径回退

    private static View useAnchor(View v) {
        if (v != null) sLastAnchor = v;
        return v;
    }


    private static View resolveBlurAnchor(Object lrvSelf) {
        try {
            // 1) 若 sRecentsBlurView 仍有效，直接复用
            View cached = sRecentsBlurView;
            if (cached != null) return useAnchor(cached);
            // 1b) [v21] 无上下文（scale / alpha 路径传 null）时回退到最近一次成功解析的锚点
            View last = sLastAnchor;
            if (last != null) {
                try {
                    if (last.isAttachedToWindow()) return last;
                } catch (Throwable ignore) {}
            }
            // 2) 通过 Launcher 单例拿 DragLayer
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
            // 3) 退化：用 lrvSelf 的父 View
            if (lrvSelf instanceof View) {
                View v = (View) lrvSelf;
                if (v.getParent() instanceof View) return useAnchor((View) v.getParent());
                return useAnchor(v);
            }
            // 4) [v26] 兜底：scale/alpha hook 缓存的桌面 DragLayer（StateManager 无 View 上下文时用）
            View cdl = sCachedDragLayer;
            if (cdl != null) {
                try { if (cdl.isAttachedToWindow()) return useAnchor(cdl); } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
        ModuleLog.d("ANCHOR", "[v21] resolveBlurAnchor FAILED lrvSelf="
                + (lrvSelf == null ? "null" : lrvSelf.getClass().getSimpleName())
                + " lastAnchor=" + (sLastAnchor == null ? "null" : "detached"));
        return null;
    }

    // 判断 state 是否属于"多任务/Overview 态"（含 modal / split / quick switch）

    private static void probeRenderEffect(final View v, final String tag) {
        if (v == null) return;
        final long[] delays = {120L, 400L, 900L, 1600L};
        for (int i = 0; i < delays.length; i++) {
            final long d = delays[i];
            sRecentsHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        Object re = Reflect.call(v, "getRenderEffect", 0);
                        String wsInfo = "";
                        try {
                            Object p = v.getParent();
                            if (p instanceof ViewGroup) {
                                ViewGroup g = (ViewGroup) p;
                                for (int j = 0; j < g.getChildCount(); j++) {
                                    View c = g.getChildAt(j);
                                    String cn = c.getClass().getName();
                                    if (cn.contains("Workspace") && !cn.contains("Scrim")) {
                                        wsInfo = " wsAlpha=" + c.getAlpha() + " wsVis=" + c.getVisibility();
                                    }
                                }
                            }
                        } catch (Throwable ignore) {}
                        ModuleLog.d("REPROBE", tag + " +" + d + "ms on=" + v.getClass().getSimpleName()
                                + " renderEffect=" + (re == null ? "NULL" : "alive") + wsInfo);
                    } catch (Throwable t) {
                        ModuleLog.e("REPROBE", "probe failed", t);
                    }
                }
            }, d);
        }
    }


    // ---------------------------------------------------------------
    // 安装入口（由 BlurEnhanceModule 调用）
    // ---------------------------------------------------------------
    static int installProbes(ClassLoader loader, HookApi api) {
        API = api;
        return installRecentsIconBlurProbe(loader);
    }

    static int installStateHooks(ClassLoader loader, HookApi api) {
        API = api;
        return installRecentsStateBlurHooks(loader);
    }

    // ---------------------------------------------------------------
    // 配置：由模块的 SETCONF 广播转交（recents 参数）
    // ---------------------------------------------------------------
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
                View old = sRecentsBlurView;
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

    /** VER 日志用的一行状态（供模块拼接）。 */
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
        // 源1: Settings.System（settings put system coloros_blur_scale_min 0.96 / coloros_blur_anchor workspace）
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
        // 源2: 文件（launcher 可能因 EACCES 读不了；读不了只记一次）
        try {
            java.io.File f = new java.io.File(CONF_PATH);
            if (!f.exists()) return;
            java.io.BufferedReader br = new java.io.BufferedReader(
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
            br.close();
            sConfErrorLogged = false;
        } catch (Throwable t) {
            if (!sConfErrorLogged) {
                sConfErrorLogged = true;
                ModuleLog.e("CONF", "file conf unreadable (EACCES?) - fallback settings/default", t);
            }
        }
    }
}
