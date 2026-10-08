package com.xiaoai.islandnotify;

public final class ConfigDefaults {

    private ConfigDefaults() {}

    public static final int STAGE_PRE = 0;
    public static final int STAGE_ACTIVE = 1;
    public static final int STAGE_POST = 2;
    public static final int REMINDER_MINUTES = 15;
    public static final int MINUTES_OFFSET = 0;
    public static final int TIMEOUT_VALUE = 60;
    public static final String TIMEOUT_UNIT = "m";
    public static final boolean SWITCH_DISABLED = false;
    public static final boolean REPOST_ENABLED = false;
    public static final int ISLAND_BUTTON_MODE = 0;
    public static final int WAKEUP_MORNING_LAST_SEC = 4;
    public static final int WAKEUP_AFTERNOON_FIRST_SEC = 5;
    public static final String WAKEUP_MORNING_RULES_JSON = "[{\"sec\":1,\"hour\":7,\"minute\":0}]";
    public static final String WAKEUP_AFTERNOON_RULES_JSON = "[{\"sec\":5,\"hour\":12,\"minute\":0}]";
    static final String[] TEMPLATE_BASE_KEYS = {"tpl_a", "tpl_b", "tpl_ticker"};
    static final String[] STAGE_SUFFIXES = {"_pre", "_active", "_post"};
    static final String[] STAGE_PHASES = {"pre", "active", "post"};
    // 模板占位符保留 Unicode 转义，明确表示键对应的字符；只抑制转义写法检查。
    @SuppressWarnings("UnnecessaryUnicodeEscape")
    static final String[] DEFAULT_TPL_A = {
            "{\u6559\u5ba4}", "{\u8bfe\u540d}", "{\u8bfe\u540d}"
    };
    // 与其他默认模板统一保留 Unicode 转义，避免同形字符在复制时混入。
    @SuppressWarnings("UnnecessaryUnicodeEscape")
    static final String[] DEFAULT_TPL_B = {
            "{\u5f00\u59cb}\u4e0a\u8bfe", "{\u7ed3\u675f}\u4e0b\u8bfe", "\u5df2\u7ecf\u4e0b\u8bfe"
    };
    // 显式保留模板字符及全角分隔符的码点，不影响最终显示文本。
    @SuppressWarnings("UnnecessaryUnicodeEscape")
    static final String[] DEFAULT_TPL_TICKER = {
            "{\u6559\u5ba4}\uFF5C{\u5f00\u59cb}\u4e0a\u8bfe",
            "{\u8bfe\u540d}\uFF5C{\u7ed3\u675f}\u4e0b\u8bfe",
            "{\u8bfe\u540d}\uFF5C\u5df2\u7ecf\u4e0b\u8bfe"
    };
    static final String[] EXPANDED_TPL_KEYS = {
            "tpl_base_title",
            "tpl_hint_title",
            "tpl_hint_subtitle",
            "tpl_hint_content",
            "tpl_hint_subcontent",
            "tpl_base_content",
            "tpl_base_subcontent"
    };
    static final String[] TEMPLATE_STAGE_MIGRATION_KEYS = {
            "tpl_a",
            "tpl_b",
            "tpl_ticker",
            "tpl_base_title",
            "tpl_hint_title",
            "tpl_hint_subtitle",
            "tpl_hint_content",
            "tpl_hint_subcontent",
            "tpl_base_content",
            "tpl_base_subcontent"
    };
    // 与旧配置迁移中的占位符保持一致，Unicode 转义在运行时仍是普通中文字符。
    @SuppressWarnings("UnnecessaryUnicodeEscape")
    static final String[][] DEFAULT_EXPANDED_TPLS_V2 = {
            {"{\u8bfe\u540d}", "{\u5012\u8ba1\u65f6}", "{\u6559\u5ba4}", "\u5373\u5c06\u4e0a\u8bfe", "\u5730\u70b9", "{\u5f00\u59cb} | {\u7ed3\u675f}", ""},
            {"{\u8bfe\u540d}", "{\u5012\u8ba1\u65f6}", "{\u6559\u5ba4}", "\u8ddd\u79bb\u4e0b\u8bfe", "\u5730\u70b9", "{\u5f00\u59cb} | {\u7ed3\u675f}", ""},
            {"{\u8bfe\u540d}", "{\u6b63\u8ba1\u65f6}", "{\u6559\u5ba4}", "\u5df2\u7ecf\u4e0b\u8bfe", "\u5730\u70b9", "{\u5f00\u59cb} | {\u7ed3\u675f}", ""}
    };

    static int intDefault(String key, int fallback) {
        if (key == null) return fallback;
        if (key.startsWith("to_island_val_")) {
            return TIMEOUT_VALUE;
        }
        return switch (key) {
            case "reminder_minutes_before" -> REMINDER_MINUTES;
            case "mute_mins_before", "unmute_mins_after", "dnd_mins_before", "undnd_mins_after" -> MINUTES_OFFSET;
            case "island_button_mode" -> ISLAND_BUTTON_MODE;
            case "wakeup_morning_last_sec" -> WAKEUP_MORNING_LAST_SEC;
            case "wakeup_afternoon_first_sec" -> WAKEUP_AFTERNOON_FIRST_SEC;
            default -> fallback;
        };
    }

    static boolean boolDefault(String key, boolean fallback) {
        if (key == null) return fallback;
        if (key.startsWith("stage_enabled_")) return true;
        return switch (key) {
            case "repost_enabled" -> REPOST_ENABLED;
            case "out_effect_enabled", "out_effect_expand_enabled" -> true;
            // legacy keys (兼容旧版本)
            case "out_effect_status_enabled", "status_left_text_dynamic_highlight_enabled",
                    "status_right_text_dynamic_highlight_enabled" -> false;
            case "mute_enabled", "unmute_enabled", "dnd_enabled", "undnd_enabled",
                    "wakeup_morning_enabled", "wakeup_afternoon_enabled", "active_countdown_to_end" -> SWITCH_DISABLED;
            default -> fallback;
        };
    }

    static String stringDefault(String key, String fallback) {
        if (key == null) return fallback;
        if (key.startsWith("to_island_unit_")) {
            return TIMEOUT_UNIT;
        }
        return switch (key) {
            case "course_data_source" -> "xiaoai";
            case "wakeup_morning_rules_json" -> WAKEUP_MORNING_RULES_JSON;
            case "wakeup_afternoon_rules_json" -> WAKEUP_AFTERNOON_RULES_JSON;
            default -> fallback;
        };
    }

    public static String stagedTemplateDefault(String key, String suffix, String fallback) {
        int keyIndex = -1;
        for (int i = 0; i < TEMPLATE_BASE_KEYS.length; i++) {
            if (TEMPLATE_BASE_KEYS[i].equals(key)) {
                keyIndex = i;
                break;
            }
        }
        int stageIndex = -1;
        for (int i = 0; i < STAGE_SUFFIXES.length; i++) {
            if (STAGE_SUFFIXES[i].equals(suffix)) {
                stageIndex = i;
                break;
            }
        }
        if (stageIndex < 0 || keyIndex < 0) return fallback;
        if (keyIndex == 0) return DEFAULT_TPL_A[stageIndex];
        if (keyIndex == 1) return DEFAULT_TPL_B[stageIndex];
        return DEFAULT_TPL_TICKER[stageIndex];
    }

    static int stageIndexByPhase(String phase) {
        if (phase == null) return STAGE_PRE;
        for (int i = 0; i < STAGE_PHASES.length; i++) {
            if (STAGE_PHASES[i].equals(phase)) return i;
        }
        return STAGE_PRE;
    }

    public static String stageSuffix(int stageIndex) {
        int idx = normalizeStageIndex(stageIndex);
        return STAGE_SUFFIXES[idx];
    }

    public static String stagePhase(int stageIndex) {
        int idx = normalizeStageIndex(stageIndex);
        return STAGE_PHASES[idx];
    }

    public static String stageEnabledKey(int stageIndex) {
        return "stage_enabled_" + stagePhase(stageIndex);
    }

    static int normalizeStageIndex(int stageIndex) {
        if (stageIndex < STAGE_PRE || stageIndex >= STAGE_PHASES.length) return STAGE_PRE;
        return stageIndex;
    }

    public static boolean isConfigKey(String key) {
        if (key == null || key.isEmpty()) return false;
        if (ReminderConfig.isReminderKey(key)) return true;
        if (key.startsWith("tpl_") || key.startsWith("to_island_")
                || key.startsWith("to_notif_") || key.startsWith("stage_enabled_")) return true;
        if ("migration_config_v1_done".equals(key)
                || "migration_config_v2_done".equals(key)
                || "notif_dismiss_trigger".equals(key)) return true;
        return "mute_enabled".equals(key)
                || "mute_mins_before".equals(key)
                || "unmute_enabled".equals(key)
                || "unmute_mins_after".equals(key)
                || "dnd_enabled".equals(key)
                || "dnd_mins_before".equals(key)
                || "undnd_enabled".equals(key)
                || "undnd_mins_after".equals(key)
                || "repost_enabled".equals(key)
                || "course_data_source".equals(key)
                || "active_countdown_to_end".equals(key)
                || "island_button_mode".equals(key)
                || "icon_a".equals(key)
                || "status_text_highlight_custom_color_argb".equals(key) // legacy
                || "status_left_text_dynamic_highlight_enabled".equals(key)
                || "status_right_text_dynamic_highlight_enabled".equals(key)
                || "out_effect_enabled".equals(key)
                || "out_effect_status_enabled".equals(key)
                || "out_effect_expand_enabled".equals(key)
                || "wakeup_morning_enabled".equals(key)
                || "wakeup_morning_last_sec".equals(key)
                || "wakeup_morning_rules_json".equals(key)
                || "wakeup_afternoon_enabled".equals(key)
                || "wakeup_afternoon_first_sec".equals(key)
                || "wakeup_afternoon_rules_json".equals(key);
    }

    public static String expandedTemplateDefault(int stageIndex, int keyIndex, String fallback) {
        if (stageIndex < 0 || stageIndex >= DEFAULT_EXPANDED_TPLS_V2.length) return fallback;
        if (keyIndex < 0 || keyIndex >= DEFAULT_EXPANDED_TPLS_V2[stageIndex].length) return fallback;
        return DEFAULT_EXPANDED_TPLS_V2[stageIndex][keyIndex];
    }
}
