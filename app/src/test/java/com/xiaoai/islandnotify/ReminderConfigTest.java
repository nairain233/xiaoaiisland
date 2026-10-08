package com.xiaoai.islandnotify;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReminderConfigTest {
    @Test public void missingRulesPreserveTheLegacyDefaultAndUnknownSectionsFallBack() {
        MemoryPrefs memory = new MemoryPrefs();
        assertEquals(15, ReminderConfig.read(null).defaultMinutes);
        assertEquals(15, ReminderConfig.read(memory.prefs).minutesBefore(1));
        memory.values.put(ReminderConfig.DEFAULT_KEY, 27);
        ReminderConfig config = ReminderConfig.read(memory.prefs);
        for (int section : new int[]{-1, 0, 1, 30, 31}) {
            assertEquals(27, config.minutesBefore(section));
        }
    }

    @Test public void rulesAreSortedAndRemovingOneRestoresTheCurrentDefault() {
        MemoryPrefs memory = new MemoryPrefs();
        memory.values.put(ReminderConfig.DEFAULT_KEY, 15);
        memory.values.put(ReminderConfig.sectionKey(3), 5);
        memory.values.put(ReminderConfig.sectionKey(1), 20);
        ReminderConfig config = ReminderConfig.read(memory.prefs);
        assertEquals(Arrays.asList(1, 3), new ArrayList<>(config.sectionMinutes.keySet()));
        assertEquals(20, config.minutesBefore(1));
        assertEquals(5, config.minutesBefore(3));
        assertEquals(15, config.minutesBefore(4));
        memory.prefs.edit().remove(ReminderConfig.sectionKey(1))
                .putInt(ReminderConfig.DEFAULT_KEY, 12).apply();
        ReminderConfig restored = ReminderConfig.read(memory.prefs);
        assertEquals(12, restored.minutesBefore(1));
        assertEquals(5, restored.minutesBefore(3));
        assertEquals(20, config.minutesBefore(1));
    }

    @Test public void zeroAndMaximumMinutesRemainValidAndInvalidValuesFallBack() {
        MemoryPrefs memory = new MemoryPrefs();
        Object[] invalidValues = {-1, 10000, "20", 20L, false};
        for (Object invalid : invalidValues) {
            memory.values.put(ReminderConfig.DEFAULT_KEY, invalid);
            memory.values.put(ReminderConfig.sectionKey(3), invalid);
            ReminderConfig config = ReminderConfig.read(memory.prefs);
            assertEquals(15, config.defaultMinutes);
            assertEquals(15, config.minutesBefore(3));
            assertTrue(config.sectionMinutes.isEmpty());
        }
        memory.values.put(ReminderConfig.DEFAULT_KEY, 0);
        memory.values.put(ReminderConfig.sectionKey(3), 9999);
        assertEquals(0, ReminderConfig.read(memory.prefs).minutesBefore(1));
        assertEquals(9999, ReminderConfig.read(memory.prefs).minutesBefore(3));
    }

    @Test public void eachCourseUsesItsOwnStartSectionAndStrictTriggerTime() {
        MemoryPrefs memory = new MemoryPrefs();
        memory.values.put(ReminderConfig.sectionKey(1), 0);
        memory.values.put(ReminderConfig.sectionKey(3), 20);
        memory.values.put(ReminderConfig.sectionKey(4), 5);
        ReminderConfig config = ReminderConfig.read(memory.prefs);
        long start = 36_000_000L; // 10:00
        long previousEnd = 35_400_000L; // 09:50
        assertEquals(start, config.triggerAt(start, 1));
        assertEquals(34_800_000L, config.triggerAt(start, 3)); // 09:40，连堂课按第 3 节
        assertTrue(config.triggerAt(start, 3) < previousEnd);
        assertEquals(35_100_000L, config.triggerAt(start, -1));
        memory.values.put(ReminderConfig.sectionKey(3), 9999);
        assertEquals(1_800_000_000_000L - 599_940_000L,
                ReminderConfig.read(memory.prefs).triggerAt(1_800_000_000_000L, 3));
    }

    @Test public void configCopyKeepsCanonicalSectionKeysAndRejectsUnrelatedKeys() {
        MemoryPrefs memory = new MemoryPrefs();
        Map<String, Object> imported = new HashMap<>();
        imported.put(ReminderConfig.DEFAULT_KEY, 10);
        imported.put(ReminderConfig.sectionKey(30), 7);
        for (String suffix : new String[]{"0", "31", "01", "-1", "abc", "", "999999999999"}) {
            String key = ReminderConfig.SECTION_PREFIX + suffix;
            assertFalse(ReminderConfig.isReminderKey(key));
            assertFalse(ConfigDefaults.isConfigKey(key));
            imported.put(key, 4);
        }
        imported.put("course_total_week", 20);
        PrefsAccess.copyAllFiltered(memory.prefs, imported, true);
        assertEquals(2, memory.values.size());
        assertEquals(7, ReminderConfig.read(memory.prefs).minutesBefore(30));
        assertFalse(ReminderConfig.isReminderKey(null));
        memory.prefs.edit().clear().apply();
        assertTrue(ReminderConfig.read(memory.prefs).sectionMinutes.isEmpty());
        assertEquals(15, ReminderConfig.read(memory.prefs).defaultMinutes);
    }

    /** 使用内存偏好验证配置读写，无需 Android 运行时。 */
    private static final class MemoryPrefs {
        final Map<String, Object> values = new HashMap<>();
        final Map<String, Object> pending = new HashMap<>();
        final SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("put")) pending.put((String) args[0], args[1]);
                    else if (name.equals("remove")) pending.put((String) args[0], null);
                    else if (name.equals("clear")) { values.clear(); pending.clear(); }
                    else if (name.equals("apply") || name.equals("commit")) {
                        pending.forEach((key, value) -> {
                            if (value == null) values.remove(key); else values.put(key, value);
                        });
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
}
