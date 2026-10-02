package com.xiaoai.islandnotify;

import android.content.SharedPreferences;

final class ConfigMigration {

    private ConfigMigration() {}

    static void migrateBaseConfig(SharedPreferences sp, SharedPreferences.Editor ed, String notifTriggerKey) {
        migrateTemplateKeys(sp, ed);
        migrateSingleTimeoutKey(sp, ed, "to_island", "island_dismiss_trigger");
        migrateSingleTimeoutKey(sp, ed, "to_notif", notifTriggerKey);
        normalizeSingleNotifPhase(sp, ed, notifTriggerKey);
        migrateTimeoutConfigV3(sp, ed, notifTriggerKey);
        purgeLegacyConfigKeys(ed);
    }

    private static void migrateTemplateKeys(SharedPreferences sp, SharedPreferences.Editor ed) {
        for (String baseKey : ConfigDefaults.TEMPLATE_STAGE_MIGRATION_KEYS) {
            String old = safeString(sp.getString(baseKey, ""));
            if (old.isEmpty()) continue;
            for (String suffix : ConfigDefaults.STAGE_SUFFIXES) {
                String stageKey = baseKey + suffix;
                if (safeString(sp.getString(stageKey, "")).isEmpty()) {
                    ed.putString(stageKey, old);
                }
            }
            ed.remove(baseKey);
        }
    }

    private static void migrateSingleTimeoutKey(SharedPreferences sp, SharedPreferences.Editor ed,
                                           String prefix, String triggerKey) {
        int oldVal = sp.getInt(prefix + "_val", ConfigDefaults.TIMEOUT_VALUE);
        String oldUnit = safeString(sp.getString(prefix + "_unit", ConfigDefaults.TIMEOUT_UNIT));
        if (oldVal < 0) {
            ed.remove(prefix + "_val");
            ed.remove(prefix + "_unit");
            return;
        }
        int stageIndex = ConfigDefaults.stageIndexByPhase(
                safeString(sp.getString(triggerKey, ConfigDefaults.NOTIF_TRIGGER)));
        String valKey = prefix + "_val_" + ConfigDefaults.stagePhase(stageIndex);
        String unitKey = prefix + "_unit_" + ConfigDefaults.stagePhase(stageIndex);
        if (sp.getInt(valKey, ConfigDefaults.TIMEOUT_VALUE) < 0) {
            ed.putInt(valKey, oldVal);
            ed.putString(unitKey, oldUnit.isEmpty() ? ConfigDefaults.TIMEOUT_UNIT : oldUnit);
        }
        ed.remove(prefix + "_val");
        ed.remove(prefix + "_unit");
    }

    private static void normalizeSingleNotifPhase(SharedPreferences sp, SharedPreferences.Editor ed, String notifTriggerKey) {
        int selectedIdx = ConfigDefaults.stageIndexByPhase(
                safeString(sp.getString(notifTriggerKey, ConfigDefaults.NOTIF_TRIGGER)));
        if (sp.getInt("to_notif_val_" + ConfigDefaults.stagePhase(selectedIdx), ConfigDefaults.TIMEOUT_VALUE) < 0) {
            for (int i = 0; i < ConfigDefaults.STAGE_PHASES.length; i++) {
                if (sp.getInt("to_notif_val_" + ConfigDefaults.stagePhase(i), ConfigDefaults.TIMEOUT_VALUE) >= 0) {
                    selectedIdx = i;
                    break;
                }
            }
        }

        for (int i = 0; i < ConfigDefaults.STAGE_PHASES.length; i++) {
            if (i == selectedIdx) continue;
            String phase = ConfigDefaults.stagePhase(i);
            if (sp.getInt("to_notif_val_" + phase, ConfigDefaults.TIMEOUT_VALUE) >= 0) {
                ed.putInt("to_notif_val_" + phase, ConfigDefaults.TIMEOUT_VALUE);
            }
        }
        String selectedPhase = ConfigDefaults.stagePhase(selectedIdx);
        if (!selectedPhase.equals(sp.getString(notifTriggerKey, ConfigDefaults.NOTIF_TRIGGER))) {
            ed.putString(notifTriggerKey, selectedPhase);
        }
    }

    static boolean purgeLegacyConfigKeys(SharedPreferences.Editor ed) {
        if (ed == null) return false;
        ed.remove("to_island_val");
        ed.remove("to_island_unit");
        ed.remove("to_notif_val");
        ed.remove("to_notif_unit");
        ed.remove("notif_dismiss_value");
        ed.remove("notif_dismiss_unit");
        ed.remove("island_dismiss_value");
        ed.remove("island_dismiss_unit");
        ed.remove("island_dismiss_trigger");
        ed.remove("use_default_behavior");
        return true;
    }

    private static void migrateTimeoutConfigV3(SharedPreferences sp, SharedPreferences.Editor ed, String notifTriggerKey) {
        int selectedIdx = ConfigDefaults.stageIndexByPhase(
                safeString(sp.getString(notifTriggerKey, ConfigDefaults.NOTIF_TRIGGER)));
        boolean hasConfiguredNotifStage = false;
        for (int i = 0; i < ConfigDefaults.STAGE_PHASES.length; i++) {
            String phase = ConfigDefaults.stagePhase(i);
            if (sp.getInt("to_notif_val_" + phase, ConfigDefaults.TIMEOUT_VALUE) >= 0) {
                hasConfiguredNotifStage = true;
            }
            String islandUnitKey = "to_island_unit_" + phase;
            String notifUnitKey = "to_notif_unit_" + phase;
            String islandUnit = safeString(sp.getString(islandUnitKey, ConfigDefaults.TIMEOUT_UNIT));
            String notifUnit = safeString(sp.getString(notifUnitKey, ConfigDefaults.TIMEOUT_UNIT));
            String islandNorm = normalizeTimeoutUnit(islandUnit);
            String notifNorm = normalizeTimeoutUnit(notifUnit);
            if (!islandNorm.equals(islandUnit)) {
                ed.putString(islandUnitKey, islandNorm);
            }
            if (!notifNorm.equals(notifUnit)) {
                ed.putString(notifUnitKey, notifNorm);
            }
        }

        if (!sp.contains(ConfigDefaults.KEY_NOTIF_GLOBAL_DEFAULT)) {
            ed.putBoolean(ConfigDefaults.KEY_NOTIF_GLOBAL_DEFAULT, !hasConfiguredNotifStage);
        }

        boolean notifDefault = sp.contains(ConfigDefaults.KEY_NOTIF_GLOBAL_DEFAULT)
                ? sp.getBoolean(ConfigDefaults.KEY_NOTIF_GLOBAL_DEFAULT, true)
                : !hasConfiguredNotifStage;
        if (!notifDefault) {
            String selectedPhase = ConfigDefaults.stagePhase(selectedIdx);
            String selectedValKey = "to_notif_val_" + selectedPhase;
            int selectedVal = sp.getInt(selectedValKey, ConfigDefaults.TIMEOUT_VALUE);
            if (selectedVal <= 0) {
                ed.putInt(selectedValKey, 1);
            }
        }
    }

    private static String normalizeTimeoutUnit(String unit) {
        if ("s".equals(unit)) return "s";
        if ("h".equals(unit)) return "h";
        return ConfigDefaults.TIMEOUT_UNIT;
    }

    static String safeString(String value) {
        return value == null ? "" : value;
    }
}
