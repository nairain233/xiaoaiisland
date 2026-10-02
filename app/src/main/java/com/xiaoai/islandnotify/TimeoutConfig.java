package com.xiaoai.islandnotify;

import android.content.SharedPreferences;

final class TimeoutConfig {

    final int[] islandVals;
    final String[] islandUnits;
    final int[] notifVals;
    final String[] notifUnits;
    int notifTriggerStage;
    boolean notifGlobalDefault;

    private TimeoutConfig() {
        int stageCount = ConfigDefaults.STAGE_PHASES.length;
        islandVals = new int[stageCount];
        islandUnits = new String[stageCount];
        notifVals = new int[stageCount];
        notifUnits = new String[stageCount];
        notifTriggerStage = ConfigDefaults.STAGE_PRE;
        notifGlobalDefault = true;
    }

    static TimeoutConfig read(SharedPreferences sp) {
        TimeoutConfig cfg = new TimeoutConfig();
        for (int i = 0; i < ConfigDefaults.STAGE_PHASES.length; i++) {
            String phase = ConfigDefaults.stagePhase(i);
            cfg.islandVals[i] = sp.getInt("to_island_val_" + phase, ConfigDefaults.TIMEOUT_VALUE);
            cfg.islandUnits[i] = safeUnit(sp.getString("to_island_unit_" + phase, ConfigDefaults.TIMEOUT_UNIT));
            cfg.notifVals[i] = sp.getInt("to_notif_val_" + phase, ConfigDefaults.TIMEOUT_VALUE);
            cfg.notifUnits[i] = safeUnit(sp.getString("to_notif_unit_" + phase, ConfigDefaults.TIMEOUT_UNIT));
        }

        cfg.notifTriggerStage = ConfigDefaults.stageIndexByPhase(
                ConfigMigration.safeString(sp.getString(
                        ConfigDefaults.KEY_NOTIF_DISMISS_TRIGGER, ConfigDefaults.NOTIF_TRIGGER)));
        if (cfg.notifVals[cfg.notifTriggerStage] < 0) {
            for (int i = 0; i < ConfigDefaults.STAGE_PHASES.length; i++) {
                if (cfg.notifVals[i] >= 0) {
                    cfg.notifTriggerStage = i;
                    break;
                }
            }
        }
        if (sp.contains(ConfigDefaults.KEY_NOTIF_GLOBAL_DEFAULT)) {
            cfg.notifGlobalDefault = sp.getBoolean(ConfigDefaults.KEY_NOTIF_GLOBAL_DEFAULT, true);
        } else {
            cfg.notifGlobalDefault = true;
            for (int i = 0; i < ConfigDefaults.STAGE_PHASES.length; i++) {
                if (cfg.notifVals[i] >= 0) {
                    cfg.notifGlobalDefault = false;
                    break;
                }
            }
        }
        return cfg;
    }

    void write(SharedPreferences.Editor ed) {
        if (ed == null) return;
        int selectedStage = ConfigDefaults.normalizeStageIndex(notifTriggerStage);
        for (int i = 0; i < ConfigDefaults.STAGE_PHASES.length; i++) {
            String phase = ConfigDefaults.stagePhase(i);
            ed.putInt("to_island_val_" + phase, islandVals[i]);
            ed.putString("to_island_unit_" + phase, safeUnit(islandUnits[i]));
        }

        ed.putString(ConfigDefaults.KEY_NOTIF_DISMISS_TRIGGER, ConfigDefaults.stagePhase(selectedStage));
        ed.putBoolean(ConfigDefaults.KEY_NOTIF_GLOBAL_DEFAULT, notifGlobalDefault);
        for (int i = 0; i < ConfigDefaults.STAGE_PHASES.length; i++) {
            String phase = ConfigDefaults.stagePhase(i);
            ed.putInt("to_notif_val_" + phase, ConfigDefaults.TIMEOUT_VALUE);
            ed.putString("to_notif_unit_" + phase, safeUnit(notifUnits[i]));
        }
        if (!notifGlobalDefault && notifVals[selectedStage] >= 0) {
            String selectedPhase = ConfigDefaults.stagePhase(selectedStage);
            ed.putInt("to_notif_val_" + selectedPhase, notifVals[selectedStage]);
            ed.putString("to_notif_unit_" + selectedPhase, safeUnit(notifUnits[selectedStage]));
        }
        ConfigMigration.purgeLegacyConfigKeys(ed);
    }

    private static String safeUnit(String unit) {
        if ("s".equals(unit)) return "s";
        if ("h".equals(unit)) return "h";
        return ConfigDefaults.TIMEOUT_UNIT;
    }
}
