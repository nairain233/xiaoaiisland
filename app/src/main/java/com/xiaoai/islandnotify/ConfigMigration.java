package com.xiaoai.islandnotify;

import android.content.SharedPreferences;

final class ConfigMigration {
    private ConfigMigration() {}

    static void migrateBaseConfig(SharedPreferences sp, SharedPreferences.Editor ed) {
        for (String baseKey : ConfigDefaults.TEMPLATE_STAGE_MIGRATION_KEYS) {
            String old = safeString(sp.getString(baseKey, ""));
            if (old.isEmpty()) continue;
            for (String suffix : ConfigDefaults.STAGE_SUFFIXES) {
                String stageKey = baseKey + suffix;
                if (safeString(sp.getString(stageKey, "")).isEmpty()) ed.putString(stageKey, old);
            }
            ed.remove(baseKey);
        }
        int oldValue = sp.getInt("to_island_val", -1);
        String oldPhase = safeString(sp.getString("island_dismiss_trigger", "pre"));
        for (int i = 0; i < 3; i++) {
            String phase = ConfigDefaults.stagePhase(i);
            String valueKey = "to_island_val_" + phase;
            int value = sp.getInt(valueKey, -1);
            String unit = sp.getString("to_island_unit_" + phase, ConfigDefaults.TIMEOUT_UNIT);
            if (value <= 0 && oldValue > 0 && phase.equals(oldPhase)) {
                value = oldValue;
                unit = sp.getString("to_island_unit", ConfigDefaults.TIMEOUT_UNIT);
            }
            ed.putInt(valueKey, value > 0 ? value : ConfigDefaults.TIMEOUT_VALUE);
            ed.putString("to_island_unit_" + phase,
                    value > 0 ? TimeoutConfig.safeUnit(unit) : ConfigDefaults.TIMEOUT_UNIT);
            if (!sp.contains(ConfigDefaults.stageEnabledKey(i))) {
                ed.putBoolean(ConfigDefaults.stageEnabledKey(i), true);
            }
        }
        purgeLegacyConfigKeys(ed);
    }

    static boolean purgeLegacyConfigKeys(SharedPreferences.Editor ed) {
        if (ed == null) return false;
        for (String key : new String[]{"to_island_val", "to_island_unit", "to_notif_val",
                "to_notif_unit", "notif_dismiss_value", "notif_dismiss_unit",
                "notif_dismiss_trigger", "to_notif_global_default", "island_dismiss_value",
                "island_dismiss_unit", "island_dismiss_trigger", "use_default_behavior"}) {
            ed.remove(key);
        }
        for (String phase : ConfigDefaults.STAGE_PHASES) {
            ed.remove("to_notif_val_" + phase);
            ed.remove("to_notif_unit_" + phase);
        }
        return true;
    }

    static String safeString(String value) {
        return value == null ? "" : value;
    }
}
