package com.xiaoai.islandnotify;

import android.content.SharedPreferences;

final class ConfigMigration {

    private ConfigMigration() {}

    static boolean migrateBaseConfig(SharedPreferences sp, SharedPreferences.Editor ed, String notifTriggerKey) {
        boolean changed = false;
        changed |= migrateTemplateKeys(sp, ed);
        changed |= migrateSingleTimeoutKey(sp, ed, "to_island", "island_dismiss_trigger");
        changed |= migrateSingleTimeoutKey(sp, ed, "to_notif", notifTriggerKey);
        changed |= normalizeSingleNotifPhase(sp, ed, notifTriggerKey);
        changed |= migrateTimeoutConfigV3(sp, ed, notifTriggerKey);
        changed |= purgeLegacyConfigKeys(ed);
        return changed;
    }

    static boolean migrateTemplateKeys(SharedPreferences sp, SharedPreferences.Editor ed) {
        boolean changed = false;
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
            changed = true;
        }
        return changed;
    }

    static boolean migrateSingleTimeoutKey(SharedPreferences sp, SharedPreferences.Editor ed,
                                           String prefix, String triggerKey) {
        int oldVal = sp.getInt(prefix + "_val", ConfigDefaults.TIMEOUT_VALUE);
        String oldUnit = safeString(sp.getString(prefix + "_unit", ConfigDefaults.TIMEOUT_UNIT));
        if (oldVal < 0) {
            ed.remove(prefix + "_val");
            ed.remove(prefix + "_unit");
            return false;
        }
        int stageIndex = ConfigDefaults.stageIndexByPhase(
                safeString(sp.getString(triggerKey, ConfigDefaults.NOTIF_TRIGGER)));
        String valKey = prefix + "_val_" + ConfigDefaults.stagePhase(stageIndex);
        String unitKey = prefix + "_unit_" + ConfigDefaults.stagePhase(stageIndex);
        boolean changed = false;
        if (sp.getInt(valKey, ConfigDefaults.TIMEOUT_VALUE) < 0) {
            ed.putInt(valKey, oldVal);
            ed.putString(unitKey, oldUnit.isEmpty() ? ConfigDefaults.TIMEOUT_UNIT : oldUnit);
            changed = true;
        }
        ed.remove(prefix + "_val");
        ed.remove(prefix + "_unit");
        return changed;
    }

    static boolean normalizeSingleNotifPhase(SharedPreferences sp, SharedPreferences.Editor ed, String notifTriggerKey) {
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

        boolean changed = false;
        for (int i = 0; i < ConfigDefaults.STAGE_PHASES.length; i++) {
            if (i == selectedIdx) continue;
            String phase = ConfigDefaults.stagePhase(i);
            if (sp.getInt("to_notif_val_" + phase, ConfigDefaults.TIMEOUT_VALUE) >= 0) {
                ed.putInt("to_notif_val_" + phase, ConfigDefaults.TIMEOUT_VALUE);
                changed = true;
            }
        }
        String selectedPhase = ConfigDefaults.stagePhase(selectedIdx);
        if (!selectedPhase.equals(sp.getString(notifTriggerKey, ConfigDefaults.NOTIF_TRIGGER))) {
            ed.putString(notifTriggerKey, selectedPhase);
            changed = true;
        }
        return changed;
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

    static boolean migrateTimeoutConfigV3(SharedPreferences sp, SharedPreferences.Editor ed, String notifTriggerKey) {
        boolean changed = false;
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
                changed = true;
            }
            if (!notifNorm.equals(notifUnit)) {
                ed.putString(notifUnitKey, notifNorm);
                changed = true;
            }
        }

        if (!sp.contains(ConfigDefaults.KEY_NOTIF_GLOBAL_DEFAULT)) {
            ed.putBoolean(ConfigDefaults.KEY_NOTIF_GLOBAL_DEFAULT, !hasConfiguredNotifStage);
            changed = true;
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
                changed = true;
            }
        }
        return changed;
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
