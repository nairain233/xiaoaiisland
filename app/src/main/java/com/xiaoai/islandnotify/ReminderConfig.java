package com.xiaoai.islandnotify;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** 默认提醒与按起始节次覆盖的统一配置。 */
public final class ReminderConfig {
    public static final String DEFAULT_KEY = "reminder_minutes_before";
    public static final String SECTION_PREFIX = "reminder_minutes_before_section_";
    public static final int MAX_SECTION = 30;
    public static final int MAX_MINUTES = 9999;

    public final int defaultMinutes;
    public final Map<Integer, Integer> sectionMinutes;

    private ReminderConfig(int defaultMinutes, Map<Integer, Integer> sectionMinutes) {
        this.defaultMinutes = defaultMinutes;
        this.sectionMinutes = Collections.unmodifiableMap(sectionMinutes);
    }

    public static ReminderConfig read(SharedPreferences prefs) {
        Map<String, ?> values = PrefsAccess.resolve(prefs).getAll();
        if (values == null) values = Collections.emptyMap();
        int defaultMinutes = validMinutes(values.get(DEFAULT_KEY), ConfigDefaults.REMINDER_MINUTES);
        Map<Integer, Integer> sections = new TreeMap<>();
        for (int section = 1; section <= MAX_SECTION; section++) {
            int minutes = validMinutes(values.get(sectionKey(section)), -1);
            if (minutes >= 0) sections.put(section, minutes);
        }
        return new ReminderConfig(defaultMinutes, sections);
    }

    public int minutesBefore(int firstSection) {
        return sectionMinutes.getOrDefault(firstSection, defaultMinutes);
    }

    public long triggerAt(long startMs, int firstSection) {
        return startMs - (long) minutesBefore(firstSection) * 60_000L;
    }

    public static String sectionKey(int section) {
        if (section < 1 || section > MAX_SECTION) {
            throw new IllegalArgumentException("节次必须在 1～30 之间");
        }
        return SECTION_PREFIX + section;
    }

    public static boolean isReminderKey(String key) {
        if (DEFAULT_KEY.equals(key)) return true;
        if (key == null || !key.startsWith(SECTION_PREFIX)) return false;
        try {
            int section = Integer.parseInt(key.substring(SECTION_PREFIX.length()));
            return section >= 1 && section <= MAX_SECTION && sectionKey(section).equals(key);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static int validMinutes(Object value, int fallback) {
        return value instanceof Integer && (Integer) value >= 0 && (Integer) value <= MAX_MINUTES
                ? (Integer) value : fallback;
    }
}
