package com.xiaoai.islandnotify;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class TimeoutConfigTest {
    @Test public void sharedDurationConversionHandlesUnitsAndLegacyDefaultsWithoutOverflow() {
        MemoryPrefs memory = new MemoryPrefs();
        memory.values.put("to_island_val_pre", 999);
        memory.values.put("to_island_unit_pre", "h");
        assertEquals(3596400000L, PrefsAccess.readStageDurationMs(memory.prefs, 0));
        memory.values.put("to_island_unit_pre", "s");
        assertEquals(999000L, PrefsAccess.readStageDurationMs(memory.prefs, 0));
        memory.values.put("to_island_val_pre", -1);
        memory.values.put("to_island_unit_pre", "h");
        assertEquals(3600000L, PrefsAccess.readStageDurationMs(memory.prefs, 0));
    }

    /** 内存实现仅覆盖本配置使用的接口，不依赖 Android 运行时。 */
    private static final class MemoryPrefs {
        final Map<String, Object> values = new HashMap<>();
        final Map<String, Object> pending = new HashMap<>();
        final SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("put")) pending.put((String) args[0], args[1]);
                    else if (name.equals("remove")) pending.put((String) args[0], null);
                    else if (name.equals("apply") || name.equals("commit")) {
                        pending.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
                        pending.clear();
                        return name.equals("commit") ? true : null;
                    }
                    return proxy;
                });
        final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAll" -> new HashMap<>(values);
                    case "contains" -> values.containsKey(args[0]);
                    case "edit" -> editor;
                    default -> values.getOrDefault(args[0], args[1]);
                });
    }

    @Test public void defaultsEnableAllStagesForSixtyMinutes() {
        MemoryPrefs memory = new MemoryPrefs();
        TimeoutConfig config = TimeoutConfig.read(memory.prefs);
        assertArrayEquals(new boolean[]{true, true, true}, config.enabled);
        assertArrayEquals(new int[]{60, 60, 60}, config.islandVals);
        assertArrayEquals(new String[]{"m", "m", "m"}, config.islandUnits);
        for (int stage = 0; stage < 3; stage++) {
            assertTrue(ConfigDefaults.boolDefault(ConfigDefaults.stageEnabledKey(stage), false));
            assertTrue(ConfigDefaults.isConfigKey(ConfigDefaults.stageEnabledKey(stage)));
        }
    }

    @Test public void togglingAStagePreservesItsDurationAndTheOtherStages() {
        MemoryPrefs memory = new MemoryPrefs();
        TimeoutConfig config = TimeoutConfig.read(memory.prefs);
        config.islandVals[1] = 17;
        config.islandUnits[1] = "s";
        config.enabled[1] = false;
        config.write(memory.editor);
        memory.editor.apply();
        TimeoutConfig restored = TimeoutConfig.read(memory.prefs);
        assertFalse(restored.enabled[1]);
        assertEquals(17, restored.islandVals[1]);
        assertEquals("s", restored.islandUnits[1]);
        restored.enabled[1] = true;
        restored.write(memory.editor);
        memory.editor.apply();
        assertEquals(17, TimeoutConfig.read(memory.prefs).islandVals[1]);
        assertEquals(60, restored.islandVals[0]);
        assertEquals(60, restored.islandVals[2]);
    }

    @Test public void migrationKeepsPositiveDurationsAndNormalizesOldSystemDefaults() {
        MemoryPrefs memory = new MemoryPrefs();
        memory.values.put("to_island_val_pre", 22);
        memory.values.put("to_island_unit_pre", "s");
        memory.values.put("to_island_val_active", -1);
        memory.values.put("to_island_unit_active", "h");
        memory.values.put("stage_enabled_post", false);
        ConfigMigration.migrateBaseConfig(memory.prefs, memory.editor);
        memory.editor.apply();
        TimeoutConfig config = TimeoutConfig.read(memory.prefs);
        assertEquals(22, config.islandVals[0]);
        assertEquals("s", config.islandUnits[0]);
        assertEquals(60, config.islandVals[1]);
        assertEquals("m", config.islandUnits[1]);
        assertFalse(config.enabled[2]);
    }

    @Test public void migratingAnOldBackupRemovesEveryNotificationExpiryRule() {
        MemoryPrefs memory = new MemoryPrefs();
        for (String phase : ConfigDefaults.STAGE_PHASES) {
            memory.values.put("to_notif_val_" + phase, 1);
            memory.values.put("to_notif_unit_" + phase, "s");
        }
        memory.values.put("to_notif_global_default", false);
        memory.values.put("notif_dismiss_trigger", "active");
        memory.values.put("to_notif_val", 2);
        ConfigMigration.migrateBaseConfig(memory.prefs, memory.editor);
        memory.editor.apply();
        assertFalse(memory.values.keySet().stream().anyMatch(key -> key.startsWith("to_notif_")
                || key.equals("notif_dismiss_trigger")));
        Map<String, Object> first = new HashMap<>(memory.values);
        ConfigMigration.migrateBaseConfig(memory.prefs, memory.editor);
        memory.editor.apply();
        assertEquals(first, memory.values);
    }

    @Test public void oldSingleIslandDurationMovesOnlyToItsSelectedStage() {
        MemoryPrefs memory = new MemoryPrefs();
        memory.values.put("to_island_val", 3);
        memory.values.put("to_island_unit", "h");
        memory.values.put("island_dismiss_trigger", "post");
        ConfigMigration.migrateBaseConfig(memory.prefs, memory.editor);
        memory.editor.apply();
        TimeoutConfig config = TimeoutConfig.read(memory.prefs);
        assertEquals(3, config.islandVals[2]);
        assertEquals("h", config.islandUnits[2]);
        assertEquals(60, config.islandVals[0]);
        assertFalse(memory.values.containsKey("to_island_val"));
    }
}
