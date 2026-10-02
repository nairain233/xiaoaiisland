package com.xiaoai.islandnotify;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class HolidayManager {

    public static final String PREFS_HOLIDAY = "island_holiday";
    private static volatile SharedPreferences sRemotePrefs;

    public static final int TYPE_HOLIDAY = 0;
    public static final int TYPE_WORKSWAP = 1;

    public static void setRemotePrefs(SharedPreferences remotePrefs) {
        sRemotePrefs = remotePrefs;
    }

    public static void clearRemotePrefs() {
        sRemotePrefs = null;
    }

    private static SharedPreferences resolvePrefs() {
        return PrefsAccess.resolve(sRemotePrefs);
    }

    public static class HolidayEntry {
        public String date;
        public String endDate;
        public String name;
        public int type;
        public int followWeek = -1;
        public int followWeekday = -1;
        public boolean isCustom;

        public HolidayEntry() {}

        public HolidayEntry(String date, String endDate, String name, int type, boolean isCustom) {
            this.date = date;
            this.endDate = endDate;
            this.name = name;
            this.type = type;
            this.isCustom = isCustom;
        }

        JSONObject toJson() {
            try {
                JSONObject j = new JSONObject();
                j.put("date", date);
                j.put("endDate", endDate != null ? endDate : "");
                j.put("name", name);
                j.put("type", type);
                j.put("fw", followWeek);
                j.put("fwd", followWeekday);
                j.put("c", isCustom);
                return j;
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        static HolidayEntry fromJson(JSONObject j) {
            HolidayEntry e = new HolidayEntry();
            e.date = j.optString("date", "");
            e.endDate = j.optString("endDate", "");
            e.name = j.optString("name", "");
            e.type = j.optInt("type", TYPE_HOLIDAY);
            e.followWeek = j.optInt("fw", -1);
            e.followWeekday = j.optInt("fwd", -1);
            e.isCustom = j.optBoolean("c", false);
            return e;
        }

        public String followDesc() {
            if (followWeek < 1 || followWeekday < 1) return "未配置";
            String[] wds = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            String wd = followWeekday <= 7 ? wds[followWeekday] : "周?";
            return "第" + followWeek + "周 " + wd;
        }

        public boolean isMatch(String targetDate) {
            if (targetDate == null || targetDate.isEmpty()) return false;
            if (date != null && date.equals(targetDate)) return true;
            if (date != null && endDate != null && !endDate.isEmpty()) {
                return targetDate.compareTo(date) >= 0 && targetDate.compareTo(endDate) <= 0;
            }
            return false;
        }
    }

    public static List<HolidayEntry> loadEntries(int year) {
        SharedPreferences sp = resolvePrefs();
        String raw = sp.getString("list_" + year, null);
        List<HolidayEntry> list = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                list.add(HolidayEntry.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static String entriesToJson(List<HolidayEntry> entries) {
        JSONArray arr = new JSONArray();
        for (HolidayEntry e : entries) arr.put(e.toJson());
        return arr.toString();
    }

    public static void saveEntries(int year, List<HolidayEntry> entries) {
        resolvePrefs()
                .edit()
                .putString("list_" + year, entriesToJson(entries))
                .apply();
    }

    public static void mergeAndSave(int year, List<HolidayEntry> apiEntries) {
        List<HolidayEntry> existing = loadEntries(year);
        List<HolidayEntry> merged = new ArrayList<>();
        for (HolidayEntry e : existing) {
            if (e.isCustom) merged.add(e);
        }
        for (HolidayEntry ae : apiEntries) {
            boolean conflict = false;
            for (HolidayEntry ce : merged) {
                if (ce.date.equals(ae.date)) {
                    conflict = true;
                    break;
                }
            }
            if (!conflict) merged.add(ae);
        }
        merged.sort(Comparator.comparing(entry -> entry.date));
        saveEntries(year, merged);
    }

    public static boolean isHoliday(String date) {
        try {
            int year = Integer.parseInt(date.substring(0, 4));
            for (HolidayEntry e : loadEntries(year)) {
                if (e.type == TYPE_HOLIDAY && e.isMatch(date)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static HolidayEntry getWorkSwap(String date) {
        try {
            int year = Integer.parseInt(date.substring(0, 4));
            for (HolidayEntry e : loadEntries(year)) {
                if (e.type == TYPE_WORKSWAP && e.isMatch(date)) return e;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static List<HolidayEntry> parseApiResponse(String json) {
        List<HolidayEntry> result = new ArrayList<>();
        if (json == null) return result;
        String raw = json.trim();
        if (raw.isEmpty()) return result;

        try {
            JSONObject obj = new JSONObject(raw);
            JSONArray arr = obj.getJSONArray("dates");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject d = arr.getJSONObject(i);
                String date = d.optString("date", "");
                String name = d.optString("name_cn", "");
                if (name.isEmpty()) name = d.optString("name", "");
                String type = d.optString("type", "");
                if ("public_holiday".equals(type)) {
                    addEntry(result, date, name, true);
                } else if ("transfer_workday".equals(type)) {
                    addEntry(result, date, name, false);
                }
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return mergeConsecutiveEntries(result);
    }

    private static void addEntry(List<HolidayEntry> list, String date, String name, boolean isHoliday) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) return;
        if (name == null || name.isEmpty()) name = date;
        list.add(new HolidayEntry(date, "", name, isHoliday ? TYPE_HOLIDAY : TYPE_WORKSWAP, false));
    }

    private static List<HolidayEntry> mergeConsecutiveEntries(List<HolidayEntry> list) {
        if (list.isEmpty()) return list;
        list.sort(Comparator.comparing(entry -> entry.date));
        List<HolidayEntry> merged = new ArrayList<>();
        HolidayEntry current = null;
        for (HolidayEntry e : list) {
            if (current == null) {
                current = e;
                merged.add(current);
            } else if (current.type == e.type
                    && current.name.equals(e.name)
                    && current.isCustom == e.isCustom
                    && current.followWeek == e.followWeek
                    && current.followWeekday == e.followWeekday) {
                String lastDate = (current.endDate != null && !current.endDate.isEmpty())
                        ? current.endDate : current.date;
                if (isAdjacentDay(lastDate, e.date)) {
                    current.endDate = e.date;
                    continue;
                }
                current = e;
                merged.add(current);
            } else {
                current = e;
                merged.add(current);
            }
        }
        return merged;
    }

    static boolean isAdjacentDay(String d1, String d2) {
        if (d1 == null || d2 == null) return false;
        try {
            // 按日历日期比较，不受默认时区和夏令时导致的日长变化影响。
            return LocalDate.parse(d1).plusDays(1).equals(LocalDate.parse(d2));
        } catch (DateTimeException e) {
            return false;
        }
    }
}
