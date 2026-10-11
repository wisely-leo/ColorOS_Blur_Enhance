package com.shortcutblur;
public final class ClockIds {
    private ClockIds() {}

    public static final int HOUR     = 0x7f0a02cf;
    public static final int COLON    = 0x7f0a02c8;
    public static final int MINUTES  = 0x7f0a02d3;
    public static final int DATE     = 0x7f0a02ca;
    public static final int WEATHER  = 0x7f0a02da;
    public static final int WEEK     = 0x7f0a02db;
    public static final int WEATHER2 = 0x7f0a02d6;
    public static final int LUNAR    = 0x7f0a02cb;
    public static final int WEATHER_IMG1 = 0x7f0a02d9;
    public static final int WEATHER_IMG2 = 0x7f0a0276;
    public static final int TARGET_ROOT = 0x7f0a017b;
    public static final int CONTAINER = 0x7f0a02ab;

    public static final int HOUR17    = 0x7f0a02c8;
    public static final int COLON17   = 0x7f0a02c1;
    public static final int MINUTE17  = 0x7f0a02cc;

    public static final int DATE17    = 0x7f0a02c3;
    public static final int LUNAR17   = 0x7f0a02c4;
    public static final int WICON17   = 0x7f0a0278;
    public static final int TEMP17    = 0x7f0a02d3;

    // ===== 真我 (realme) 版时钟 =====
    // 依据 realme 时钟 App 16.19.5 的 res/layout/digital_widget.xml
    public static final int RM_ROOT      = 0x7f0a01ab;  // widget 根 LinearLayout
    public static final int RM_CONTAINER = 0x7f0a04b2;  // 背景容器（bg=widget_top_bg，模糊层挂这）
    public static final int RM_TIME_BIG  = 0x7f0a0595;  // 时间(大) TextClock
    public static final int RM_TIME_SMALL= 0x7f0a009d;  // 时间(小) TextClock
    public static final int RM_DATE      = 0x7f0a0170;  // 日期 TextClock
    public static final int RM_WEATHER   = 0x7f0a05a8;  // 天气 TextView

    // ===== 真我 (realme) Local 版时钟（local_* 系列，设备上实际显示的样式）=====
    // 依据 realme 时钟 App 16.19.5 的 res/layout/xxx_clock_widget_view_t.xml
    // 注意：RM_L_HOUR(0x7f0a02cc) / RM_L_LUNAR(0x7f0a02c8) 与一加 ID 数字撞车，
    //       但同一时刻只会有一个 OEM 的时钟在场，findViewById 只会命中当前树，撞车无害。
    public static final int RM_L_HOUR     = 0x7f0a02cc;  // local_hour_txt        (TextClock)
    public static final int RM_L_MINUTE   = 0x7f0a02d0;  // local_minutes_txt     (TextClock)
    public static final int RM_L_COLON    = 0x7f0a02c5;  // local_colon_txt       (TextView)
    public static final int RM_L_DATE     = 0x7f0a02c7;  // local_date_info_txt   (TextClock)
    public static final int RM_L_LUNAR    = 0x7f0a02c8;  // local_date_lunar_info_txt (TextView)
    public static final int RM_L_WEATHER  = 0x7f0a02d7;  // local_weather_info_txt    (TextView)
    public static final int RM_L_WICON    = 0x7f0a0273;  // iv_weather_type       (ImageView)

    // ===== 真我 (realme) 天气/日历 横向小组件（weather_widget_layout）=====
    // 依据真我时钟 App 16.19.5 的 res/layout（widescreen 样式）。
    // 结构：weather_widget_layout → calendar(日期) + divider_line + iv_weather_type + local_weather_info_txt(温度)
    public static final int RM_CALENDAR   = 0x7f0a00eb;  // calendar                 (TextClock，日期+星期)
    public static final int RM_W_LAYOUT   = 0x7f0a05a9;  // weather_widget_layout    (根容器)
    public static final int RM_W_DIVIDER  = 0x7f0a01b6;  // divider_line             (分隔线)

    public static final int[] TEXT_IDS = {

            HOUR, COLON, MINUTES, DATE, WEATHER, WEEK, WEATHER2, LUNAR,

            HOUR17, COLON17, MINUTE17, DATE17, LUNAR17, TEMP17,

            // 真我 (realme) 追加，不动上面任何旧值
            RM_TIME_BIG, RM_TIME_SMALL, RM_DATE, RM_WEATHER,

            // 真我 (realme) local_* 版追加
            RM_L_HOUR, RM_L_MINUTE, RM_L_COLON, RM_L_DATE, RM_L_LUNAR, RM_L_WEATHER,

            // 真我 (realme) 天气/日历横向小组件追加
            RM_CALENDAR
    };
    public static final int[] ICON_IDS = {

            WEATHER_IMG1, WEATHER_IMG2,

            WICON17,

            // 真我 (realme) local_* 版天气图标
            RM_L_WICON
    };

    /** 真我 (realme) 时钟需「强制锁定文字透明度」的 ID：
     *  真我时钟 App 会在运行时给文字推「不透明的实心色」（0xffffffff），
     *  且自带颜色动画，导致挂在容器上的模糊层被实心文字盖住（看不到模糊）。
     *  对这批 ID，模块统一把 alpha 强改成一个固定值（RGB 锁白），从而露出模糊层。 */
    public static final int[] REALME_LOCK_IDS = {
            RM_L_HOUR, RM_L_MINUTE, RM_L_COLON, RM_L_DATE, RM_L_LUNAR, RM_L_WEATHER,
            RM_CALENDAR
    };

    /** 是否为真我 (realme) 需锁定透明度的时钟文字 ID。 */
    public static boolean isRealmeLockId(int id) {
        for (int x : REALME_LOCK_IDS) { if (x == id) return true; }
        return false;
    }
}
