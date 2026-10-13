package com.shortcutblur;

/**
 * 反射目标符号集中表（可选整洁度优化，不涉及平台适配）。
 *
 * <p>本模块定向服务 ColorOS 16（C16），硬编码目标符号是**刻意的设计选择**：
 * 精准瞄准目标符号比"版本探测 + 降级"更高效、更少冗余。本类**不引入任何版本分支**，
 * 仅把原先散落在各文件的 CLS_*（类名）/ M_*（方法名）集中到一处，
 * 便于"改一处、查一处"。
 *
 * <p>说明：包名白名单（PKG_*）、调参常量（BLUR_RADIUS 等）与业务强相关，
 * 保留在原声明处，不纳入本表。
 */
public final class Symbols {
    private Symbols() {}

    // ===== launcher3 popup / 图标模糊（原 BlurEnhanceModule）=====
    public static final String CLS_POPUP_BLUR_VIEW = "com.android.launcher3.popup.PopupBlurView";
    public static final String CLS_OPLUS_POPUP = "com.android.launcher3.popup.OplusPopupContainerWithArrow";
    public static final String CLS_ARROW_POPUP = "com.android.launcher3.popup.ArrowPopup";
    /** 快捷菜单弹窗本体（卡片视图）：showForIcon 的返回类型。 */
    public static final String CLS_POPUP_CONTAINER = "com.android.launcher3.popup.PopupContainerWithArrow";
    /** 二级（"更多功能"）菜单窗口：主子两级菜单容器 + 背景模糊开关。 */
    public static final String CLS_COUI_POPUP_LIST_WINDOW = "com.coui.appcompat.poplist.COUIPopupListWindow";
    /** 强制开启二级菜单背景模糊的方法名（COUI 官方 API）。 */
    public static final String M_SET_USE_BG_BLUR = "setUseBackgroundBlur";
    /** 二级（"更多功能"）菜单窗口实现类（持有 mMain/mSubMenuWrapper）。 */
    public static final String CLS_MORE_FUNCTIONS_WINDOW = "com.android.launcher3.popup.MoreFunctionsPopupListWindow";
    public static final String CLS_OPLUS_EFFECT = "com.oplus.view.OplusViewBackgroundRenderEffect";
    public static final String CLS_DRAWABLE = "android.graphics.drawable.Drawable";
    public static final String CLS_OBJECT_ANIMATOR = "android.animation.ObjectAnimator";
    public static final String CLS_PROPERTY = "android.util.Property";
    public static final String CLS_ANIMATOR_SET = "android.animation.AnimatorSet";
    public static final String CLS_ANIMATOR = "android.animation.Animator";
    public static final String CLS_TIME_INTERPOLATOR = "android.animation.TimeInterpolator";
    public static final String CLS_DECELERATE = "android.view.animation.DecelerateInterpolator";
    public static final String M_GET_POP_BLUR_VIEW = "getPopBlurView";
    /** ArrowPopup（弹窗基类）给弹窗内容容器设背景的方法：卡片"纯色背景"的来源。 */
    public static final String M_ASSIGN_MARGINS = "assignMarginsAndBackgrounds";
    public static final String CLS_EA = "e.a";
    public static final String CLS_BLUR_MGR = "com.oplus.posteffect.manager.BlurDrawableManager";

    // ===== launcher / recents（原 BlurLib）=====
    public static final String CLS_LAUNCHER = "com.android.launcher.Launcher";
    public static final String CLS_WORKSPACE_SCRIM = "com.android.launcher3.views.WorkSpaceScrimView";
    public static final String CLS_OPLUS_DRAGLAYER = "com.android.launcher3.OplusDragLayer";
    public static final String CLS_LAUNCHER_RECENTS_VIEW = "com.android.quickstep.views.LauncherRecentsView";
    public static final String CLS_LAUNCHER_STATE = "com.android.launcher3.LauncherState";
    public static final String M_ON_STATE_TRANSITION_START = "onStateTransitionStart";
    public static final String M_ON_STATE_TRANSITION_COMPLETE = "onStateTransitionComplete";
    public static final String M_ON_STATE_TRANSITION_CANCEL = "onStateTransitionCancel";

    // ===== glass / posteffect（原 GlassColorHook / GlyphBlurRenderer 内联）=====
    public static final String CLS_CONTINUOUS = "com.oplus.posteffect.drawable.ContinuousBlurDrawable";
    public static final String CLS_BLUR_PARAM = "com.oplus.posteffect.BlurParam";
    public static final String CLS_BLUR_PATH_PROVIDER = "com.oplus.posteffect.path.BlurDrawablePathProvider";

    // ===== 输入法（原 ImeBlurHook）=====
    public static final String CLS_VRM = "com.oplus.view.ViewRootManager";

    // ===== 全局搜索（原 QuickSearchBlur）=====
public static final String CLS_RUN_TIME_CONFIG = "com.heytap.quicksearchbox.core.constant.RunTimeConfig";
    public static final String FLD_QS_ENTER_SOURCE_FLAG = "QS_ENTER_SOURCE_FLAG";
    // ===== ActivityThread（获取全局 Application，原 BlurLib / GlyphBlurRenderer / GlassColorHook 内联）=====
    public static final String CLS_ACTIVITY_THREAD = "android.app.ActivityThread";
    public static final String M_CURRENT_APPLICATION = "currentApplication";
    // ===== SurfaceControl（原 BlurEnhanceModule 内联 3 处）=====
    public static final String CLS_SURFACE_CONTROL = "android.view.SurfaceControl";
}
