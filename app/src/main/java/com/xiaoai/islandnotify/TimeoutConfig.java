package com.xiaoai.islandnotify;

import android.content.SharedPreferences;

final class TimeoutConfig {
    final boolean[] enabled = new boolean[3];
    final int[] islandVals = new int[3];
    final String[] islandUnits = new String[3];

    static TimeoutConfig read(SharedPreferences sp) {
        TimeoutConfig cfg = new TimeoutConfig();
        for (int i = 0; i < cfg.enabled.length; i++) {
            String phase = ConfigDefaults.stagePhase(i);
            cfg.enabled[i] = sp.getBoolean(ConfigDefaults.stageEnabledKey(i), true);
            int value = sp.getInt("to_island_val_" + phase, ConfigDefaults.TIMEOUT_VALUE);
            cfg.islandVals[i] = value > 0 ? value : ConfigDefaults.TIMEOUT_VALUE;
            cfg.islandUnits[i] = value > 0
                    ? safeUnit(sp.getString("to_island_unit_" + phase, ConfigDefaults.TIMEOUT_UNIT))
                    : ConfigDefaults.TIMEOUT_UNIT;
        }
        return cfg;
    }

    void write(SharedPreferences.Editor ed) {
        if (ed == null) return;
        for (int i = 0; i < enabled.length; i++) {
            String phase = ConfigDefaults.stagePhase(i);
            ed.putBoolean(ConfigDefaults.stageEnabledKey(i), enabled[i]);
            ed.putInt("to_island_val_" + phase,
                    islandVals[i] > 0 ? islandVals[i] : ConfigDefaults.TIMEOUT_VALUE);
            ed.putString("to_island_unit_" + phase, safeUnit(islandUnits[i]));
        }
        ConfigMigration.purgeLegacyConfigKeys(ed);
    }

    static String safeUnit(String unit) {
        if ("s".equals(unit)) return "s";
        if ("h".equals(unit)) return "h";
        return ConfigDefaults.TIMEOUT_UNIT;
    }
}
