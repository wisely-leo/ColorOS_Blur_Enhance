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

    public static final int RM_ROOT      = 0x7f0a01ab;
    public static final int RM_CONTAINER = 0x7f0a04b2;
    public static final int RM_TIME_BIG  = 0x7f0a0595;
    public static final int RM_TIME_SMALL= 0x7f0a009d;
    public static final int RM_DATE      = 0x7f0a0170;
    public static final int RM_WEATHER   = 0x7f0a05a8;

    public static final int RM_L_HOUR     = 0x7f0a02cc;
    public static final int RM_L_MINUTE   = 0x7f0a02d0;
    public static final int RM_L_COLON    = 0x7f0a02c5;
    public static final int RM_L_DATE     = 0x7f0a02c7;
    public static final int RM_L_LUNAR    = 0x7f0a02c8;
    public static final int RM_L_WEATHER  = 0x7f0a02d7;
    public static final int RM_L_WICON    = 0x7f0a0273;

    public static final int RM_CALENDAR   = 0x7f0a00eb;
    public static final int RM_W_LAYOUT   = 0x7f0a05a9;
    public static final int RM_W_DIVIDER  = 0x7f0a01b6;

    public static final int[] TEXT_IDS = {

            HOUR, COLON, MINUTES, DATE, WEATHER, WEEK, WEATHER2, LUNAR,

            HOUR17, COLON17, MINUTE17, DATE17, LUNAR17, TEMP17,

            RM_TIME_BIG, RM_TIME_SMALL, RM_DATE, RM_WEATHER,

            RM_L_HOUR, RM_L_MINUTE, RM_L_COLON, RM_L_DATE, RM_L_LUNAR, RM_L_WEATHER,

            RM_CALENDAR
    };
    public static final int[] ICON_IDS = {

            WEATHER_IMG1, WEATHER_IMG2,

            WICON17,

            RM_L_WICON
    };

    public static final int[] REALME_LOCK_IDS = {
            RM_L_HOUR, RM_L_MINUTE, RM_L_COLON, RM_L_DATE, RM_L_LUNAR, RM_L_WEATHER,
            RM_CALENDAR
    };

    public static boolean isRealmeLockId(int id) {
        for (int x : REALME_LOCK_IDS) { if (x == id) return true; }
        return false;
    }
}
