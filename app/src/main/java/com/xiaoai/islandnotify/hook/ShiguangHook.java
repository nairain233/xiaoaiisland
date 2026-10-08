package com.xiaoai.islandnotify.hook;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.xiaoai.islandnotify.CourseScheduleParser;
import com.xiaoai.islandnotify.modernhook.XC_MethodHook;
import com.xiaoai.islandnotify.modernhook.XposedBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.xiaoai.islandnotify.modernhook.XposedHelpers.findAndHookMethod;

public class ShiguangHook {

    static final String ACTION_SHIGUANG_COURSE_SYNC =
            "com.xiaoai.islandnotify.ACTION_SHIGUANG_COURSE_SYNC";
    static final String ACTION_REQUEST_SHIGUANG_SYNC =
            "com.xiaoai.islandnotify.ACTION_REQUEST_SHIGUANG_SYNC";

    private static final String TAG = "IslandNotifyShiguang";
    private static final String TARGET_PACKAGE = "com.xingheyuzhuan.shiguangschedule";
    private static final String TARGET_VOICEASSIST = "com.miui.voiceassist";
    /** voiceassist 侧被 MainHook hook 了 onStartCommand 的 Service，用于把已被杀的进程拉起来 */
    private static final String VOICEASSIST_UPLOAD_SERVICE =
            "com.xiaomi.voiceassistant.UploadStateService";
    private static final String DB_NAME = "main_app_database";
    private static final String DATASTORE_NAME = "app_settings.preferences_pb";
    private static final String HOOKED_KEY = "xiaoai.island.shiguang.hooked";
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** 拾光主库 v6 起节次挂 timeTableId，并引入作息绑定与组合作息。 */
    private static final int DB_VER_TIME_TABLE = 6;
    private static final String COL_TIME_TABLE_ID = "timeTableId";
    private static final String COL_COURSE_TABLE_ID = "courseTableId";
    private android.os.FileObserver mDbObserver;
    private android.os.FileObserver mStoreObserver;
    private android.os.Handler mHandler;
    private final Object mSyncToken = new Object();
    private volatile int mLastPushedHash = 0;
    /** 自身读库产生的文件事件在此时间前一律忽略，避免「读 → 改 -shm/-wal → 再读」自激循环 */
    private volatile long mSelfReadUntilMs = 0L;

    public void handleLoadPackage(String packageName, String processName, ClassLoader classLoader) {
        if (!TARGET_PACKAGE.equals(packageName)) return;
        if (!TARGET_PACKAGE.equals(processName)) return;
        if (System.getProperty(HOOKED_KEY) != null) return;
        System.setProperty(HOOKED_KEY, "1");
        hookApplicationOnCreate(classLoader);
        XposedBridge.log(TAG + ": 已注入目标进程 → " + TARGET_PACKAGE);
    }

    private void hookApplicationOnCreate(ClassLoader classLoader) {
        findAndHookMethod("android.app.Application", classLoader,
                "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context appCtx = (Application) param.thisObject;
                        registerSyncRequestReceiver(appCtx);
                        registerDbObserver(appCtx);
                        registerDataStoreObserver(appCtx);
                        postSync(appCtx, 350L, "startup");
                    }
                });
    }

    private void registerSyncRequestReceiver(Context ctx) {
        android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_REQUEST_SHIGUANG_SYNC);
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (!ACTION_REQUEST_SHIGUANG_SYNC.equals(action)) return;
                postSync(context, 120L, "manual_request");
            }
        };
        androidx.core.content.ContextCompat.registerReceiver(
                ctx, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
    }

    private void registerDbObserver(Context ctx) {
        if (mDbObserver != null) return;
        File dbFile = ctx.getDatabasePath(DB_NAME);
        File dbDir = dbFile == null ? null : dbFile.getParentFile();
        if (dbDir == null || !dbDir.exists()) return;
        mDbObserver = new android.os.FileObserver(
                dbDir,
                android.os.FileObserver.MOVED_TO
                        | android.os.FileObserver.CLOSE_WRITE
                        | android.os.FileObserver.MODIFY) {
            @Override
            public void onEvent(int event, String path) {
                if (path == null || !path.startsWith(DB_NAME)) return;
                // -shm 只是 WAL 的读端索引：只读打开数据库也会写它，
                // 据此触发同步会形成「读 → 改 -shm → 再读」的自激循环。
                if (path.endsWith("-shm")) return;
                if (System.currentTimeMillis() < mSelfReadUntilMs) return;
                postSync(ctx, 650L, "db_changed:" + path);
            }
        };
        mDbObserver.startWatching();
    }

    private void registerDataStoreObserver(Context ctx) {
        if (mStoreObserver != null) return;
        File store = new File(ctx.getFilesDir(), "datastore/" + DATASTORE_NAME);
        File dir = store.getParentFile();
        if (dir == null || !dir.exists()) return;
        mStoreObserver = new android.os.FileObserver(
                dir,
                android.os.FileObserver.MOVED_TO
                        | android.os.FileObserver.CLOSE_WRITE
                        | android.os.FileObserver.MODIFY) {
            @Override
            public void onEvent(int event, String path) {
                if (!DATASTORE_NAME.equals(path)) return;
                postSync(ctx, 220L, "datastore_changed");
            }
        };
        mStoreObserver.startWatching();
    }

    private void postSync(Context ctx, long delayMs, String reason) {
        android.os.Handler handler = getHandler();
        handler.removeCallbacksAndMessages(mSyncToken);
        handler.postDelayed(() -> syncAndPush(ctx, reason), mSyncToken, delayMs);
    }

    private android.os.Handler getHandler() {
        if (mHandler == null) {
            mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        return mHandler;
    }

    private void syncAndPush(Context ctx, String reason) {
        try {
            String beanJson = buildWeekCourseBeanFromShiguang(ctx);
            if (beanJson == null || beanJson.isEmpty()) return;
            int hash = CourseScheduleParser.stableHash(beanJson);
            if (hash == mLastPushedHash) return;

            // 先用 startService 把可能已被杀的 voiceassist 拉起来（MainHook 的 Service hook
            // 会把它转成包内广播）；广播只能进到运行中的动态接收器，进程不在时会静默丢失。
            boolean started = startVoiceassistService(ctx, beanJson, hash);

            Intent sync = new Intent(ACTION_SHIGUANG_COURSE_SYNC);
            sync.setPackage(TARGET_VOICEASSIST);
            sync.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
            sync.putExtra("bean_json", beanJson);
            sync.putExtra("hash", hash);
            ctx.sendBroadcast(sync);

            // 只有 startService 成功才算确定送达；否则不记账，留给下次事件重试，
            // 避免推送丢失后镜像永久停留在旧数据上。
            if (started) mLastPushedHash = hash;
            XposedBridge.log(TAG + ": 已推送拾光课程镜像 -> voiceassist reason=" + reason
                    + " hash=" + hash + " service=" + started);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": syncAndPush 失败 -> " + t.getMessage());
        }
    }

    /** 通过 startService 投递并顺带拉起 voiceassist 进程，成功返回 true。 */
    private boolean startVoiceassistService(Context ctx, String beanJson, int hash) {
        try {
            Intent svc = new Intent(ACTION_SHIGUANG_COURSE_SYNC);
            svc.setClassName(TARGET_VOICEASSIST, VOICEASSIST_UPLOAD_SERVICE);
            svc.putExtra("bean_json", beanJson);
            svc.putExtra("hash", hash);
            return ctx.startService(svc) != null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": startService 拉起 voiceassist 失败 -> " + t.getMessage());
            return false;
        }
    }

    private String buildWeekCourseBeanFromShiguang(Context ctx) throws Exception {
        File db = ctx.getDatabasePath(DB_NAME);
        if (db == null || !db.exists()) return null;

        SQLiteDatabase sqLiteDb = null;
        Cursor c = null;
        try {
            sqLiteDb = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            String currentTableId = resolveCurrentTableId(ctx, sqLiteDb);
            if (currentTableId == null || currentTableId.isEmpty()) return null;

            TableConfig config = loadTableConfig(sqLiteDb, currentTableId);
            TimeSlotResolution slotRes = loadTimeSlots(sqLiteDb, currentTableId);
            Map<Integer, String[]> normalSlots = slotRes.baseSlots;

            Map<String, List<Integer>> weeksByCourse = new HashMap<>();
            c = sqLiteDb.rawQuery(
                    "SELECT courseId, weekNumber FROM course_weeks " +
                            "WHERE courseId IN (SELECT id FROM courses WHERE courseTableId = ?) " +
                            "ORDER BY courseId ASC, weekNumber ASC",
                    new String[]{currentTableId});
            int maxWeek = 0;
            while (c.moveToNext()) {
                String cid = safeStr(c.getString(0));
                int week = c.getInt(1);
                if (cid.isEmpty() || week <= 0) continue;
                List<Integer> weeks = weeksByCourse.get(cid);
                if (weeks == null) {
                    weeks = new ArrayList<>();
                    weeksByCourse.put(cid, weeks);
                }
                weeks.add(week);
                if (week > maxWeek) maxWeek = week;
            }
            c.close();
            c = null;

            JSONArray sectionTimes = new JSONArray();
            Set<Integer> seenSections = new HashSet<>();
            for (Map.Entry<Integer, String[]> e : normalSlots.entrySet()) {
                int sec = e.getKey();
                String start = e.getValue()[0];
                String end = e.getValue()[1];
                if (isInvalidSectionTime(start, end)) continue;
                JSONObject st = new JSONObject();
                st.put("i", sec);
                st.put("s", start);
                st.put("e", end);
                sectionTimes.put(st);
                seenSections.add(sec);
            }

            // 节次以「基准 ∪ 各规则」为准，避免丢弃仅在规则区间可解析的课程。
            // 当天是否有可用时间交由消费侧判断。
            Set<Integer> resolvableSections = new HashSet<>(seenSections);
            for (ComboRuleSlots rule : slotRes.rules) {
                resolvableSections.addAll(rule.slots.keySet());
            }

            int syntheticSec = 1000;
            Map<String, Integer> customTimeToSec = new HashMap<>();
            JSONArray courses = new JSONArray();

            c = sqLiteDb.rawQuery(
                    "SELECT id, name, teacher, position, day, startSection, endSection, " +
                            "isCustomTime, customStartTime, customEndTime " +
                            "FROM courses WHERE courseTableId = ? ORDER BY day ASC, startSection ASC",
                    new String[]{currentTableId});
            while (c.moveToNext()) {
                String courseId = safeStr(c.getString(0));
                String name = safeStr(c.getString(1));
                String teacher = safeStr(c.getString(2));
                String position = safeStr(c.getString(3));
                int day = c.getInt(4);
                boolean isCustom = c.getInt(7) == 1;
                if (name.isEmpty() || day < 1 || day > 7) continue;

                List<Integer> weeks = weeksByCourse.get(courseId);
                if (weeks == null || weeks.isEmpty()) continue;
                String weeksSpec = toWeeksSpec(weeks);
                if (weeksSpec.isEmpty()) continue;

                String sectionsSpec;
                String customStart = "";
                String customEnd = "";
                if (isCustom) {
                    customStart = safeStr(c.getString(8));
                    customEnd = safeStr(c.getString(9));
                    if (isInvalidSectionTime(customStart, customEnd)) continue;
                    // 优先落到时间上覆盖它的真实节次：自动叫醒的规则是按真实节次配置的，
                    // 合成节次号查不到任何规则，这类课就永远参与不了叫醒。
                    int matchedSec = matchSectionByTime(normalSlots, customStart);
                    if (matchedSec > 0) {
                        sectionsSpec = String.valueOf(matchedSec);
                    } else {
                        String slotKey = customStart + "|" + customEnd;
                        Integer secIdx = customTimeToSec.get(slotKey);
                        if (secIdx == null) {
                            while (seenSections.contains(syntheticSec)) syntheticSec++;
                            secIdx = syntheticSec++;
                            customTimeToSec.put(slotKey, secIdx);
                            JSONObject st = new JSONObject();
                            st.put("i", secIdx);
                            st.put("s", customStart);
                            st.put("e", customEnd);
                            sectionTimes.put(st);
                            seenSections.add(secIdx);
                        }
                        sectionsSpec = String.valueOf(secIdx);
                        XposedBridge.log(TAG + ": 自定义时间 " + customStart + "-" + customEnd
                                + " 不落在任何节次区间内，使用合成节次 " + secIdx
                                + "（该课不参与自动叫醒）course=" + name);
                    }
                } else {
                    if (c.isNull(5) || c.isNull(6)) continue;
                    int startSec = c.getInt(5);
                    int endSec = c.getInt(6);
                    if (startSec <= 0 || endSec <= 0) continue;
                    int minSec = Math.min(startSec, endSec);
                    int maxSec = Math.max(startSec, endSec);
                    if (!resolvableSections.contains(minSec) || !resolvableSections.contains(maxSec)) continue;
                    sectionsSpec = minSec == maxSec ? String.valueOf(minSec) : (minSec + "-" + maxSec);
                }

                JSONObject course = new JSONObject();
                course.put("day", day);
                course.put("name", name);
                course.put("teacher", teacher);
                course.put("position", position);
                course.put("sections", sectionsSpec);
                course.put("weeks", weeksSpec);
                // 自定义时间显式写入：解析器优先采用它，映射到真实节次后也不会被该节次的默认时间覆盖。
                if (isCustom) {
                    course.put("startTime", customStart);
                    course.put("endTime", customEnd);
                }
                courses.put(course);
            }
            c.close();
            c = null;

            int totalWeek = config.semesterTotalWeeks > 0 ? config.semesterTotalWeeks : (maxWeek > 0 ? maxWeek : 30);
            int presentWeek = computePresentWeek(config.semesterStartDate, config.sundayFirst);

            if (courses.length() == 0) {
                XposedBridge.log(TAG + ": 镜像构建得到 0 门课程（节次数=" + sectionTimes.length()
                        + "，周次记录=" + weeksByCourse.size() + "），请检查 time_slots/course_weeks 读取");
            }

            JSONObject setting = new JSONObject();
            setting.put("presentWeek", presentWeek);
            setting.put("totalWeek", totalWeek);
            setting.put("weekStart", 1);
            setting.put("sectionTimes", sectionTimes);
            // 保留全部日期规则，消费侧跨日重解析时自行选择，无需拾光进程在场。
            JSONArray sectionTimeRules = buildSectionTimeRules(slotRes.rules);
            if (sectionTimeRules.length() > 0) {
                setting.put("sectionTimeRules", sectionTimeRules);
            }
            setting.put("startDate", config.semesterStartDate);
            setting.put("sundayFirst", config.sundayFirst);

            JSONObject data = new JSONObject();
            data.put("setting", setting);
            data.put("courses", courses);

            JSONObject root = new JSONObject();
            root.put("data", data);
            return root.toString();
        } finally {
            if (c != null) c.close();
            if (sqLiteDb != null) sqLiteDb.close();
            // 关库可能触发 WAL checkpoint，写回主库与 -wal 会再次唤起 FileObserver
            mSelfReadUntilMs = System.currentTimeMillis() + 800L;
        }
    }

    private String resolveCurrentTableId(Context ctx, SQLiteDatabase db) {
        String fromStore = readCurrentTableIdFromDataStore(ctx);
        if (fromStore != null && !fromStore.isEmpty()) return fromStore;
        // 查询/读取失败维持原有回退；资源关闭异常仍交给上层处理。
        Cursor c;
        try {
            c = db.rawQuery("SELECT id FROM course_tables ORDER BY createdAt DESC LIMIT 1", null);
        } catch (Throwable ignored) {
            return null;
        }
        try (c) {
            try {
                if (c.moveToFirst()) return safeStr(c.getString(0));
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String readCurrentTableIdFromDataStore(Context ctx) {
        File file = new File(ctx.getFilesDir(), "datastore/" + DATASTORE_NAME);
        if (!file.exists()) return null;
        FileInputStream fis = null;
        try {
            byte[] buf = new byte[(int) Math.min(file.length(), 64 * 1024L)];
            fis = new FileInputStream(file);
            int read = fis.read(buf);
            if (read <= 0) return null;
            String raw = new String(buf, 0, read, StandardCharsets.ISO_8859_1);
            int idx = raw.indexOf("current_course_table_id");
            String scope = idx >= 0 ? raw.substring(idx, Math.min(raw.length(), idx + 200)) : raw;
            Matcher m = UUID_PATTERN.matcher(scope);
            if (m.find()) return m.group();
            m = UUID_PATTERN.matcher(raw);
            if (m.find()) return m.group();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (fis != null) fis.close();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private TableConfig loadTableConfig(SQLiteDatabase db, String tableId) {
        Cursor c;
        try {
            c = db.rawQuery(
                    "SELECT semesterStartDate, semesterTotalWeeks, firstDayOfWeek " +
                            "FROM course_table_config WHERE courseTableId = ? LIMIT 1",
                    new String[]{tableId});
        } catch (Throwable ignored) {
            return new TableConfig("", 0, false);
        }
        try (c) {
            try {
                if (c.moveToFirst()) {
                    String startDate = normalizeStartDate(safeStr(c.getString(0)));
                    int totalWeeks = c.getInt(1);
                    int firstDay = c.getInt(2);
                    boolean sundayFirst = (firstDay == 0 || firstDay == 7);
                    return new TableConfig(startDate, totalWeeks, sundayFirst);
                }
            } catch (Throwable ignored) {
            }
        }
        return new TableConfig("", 0, false);
    }

    /** 按主库版本读取基准作息及全部组合规则，不在读库时按当天求值。 */
    private TimeSlotResolution loadTimeSlots(SQLiteDatabase db, String courseTableId) {
        if (safeDbVersion(db) >= DB_VER_TIME_TABLE) {
            return loadTimeSlotsV6(db, courseTableId);
        }
        Map<Integer, String[]> slots = new HashMap<>();
        queryTimeSlots(db, COL_COURSE_TABLE_ID, courseTableId, slots);
        if (slots.isEmpty()) {
            XposedBridge.log(TAG + ": 未读到任何节次时间(legacy) table=" + courseTableId);
        }
        return new TimeSlotResolution(slots, null);
    }

    /** SINGLE 读目标作息；COMBO 读基准及规则；无绑定或失效时回退专属作息。 */
    private TimeSlotResolution loadTimeSlotsV6(SQLiteDatabase db, String courseTableId) {
        String[] binding = readCourseTimeBinding(db, courseTableId);
        if (binding != null && !binding[1].isEmpty() && "COMBO".equalsIgnoreCase(binding[0])) {
            return loadComboResolution(db, courseTableId, binding[1]);
        }
        Map<Integer, String[]> slots = new HashMap<>();
        if (binding != null && !binding[1].isEmpty()) {
            queryTimeSlots(db, COL_TIME_TABLE_ID, binding[1], slots);
        }
        if (slots.isEmpty()) {
            queryTimeSlots(db, COL_TIME_TABLE_ID, courseTableId, slots);
        }
        if (slots.isEmpty()) {
            XposedBridge.log(TAG + ": 未读到任何节次时间(v6) table=" + courseTableId);
        }
        return new TimeSlotResolution(slots, null);
    }

    /** 对齐与拾光一致：保留基准节次编号，用目标作息的同号节次覆盖时间。 */
    private TimeSlotResolution loadComboResolution(SQLiteDatabase db, String courseTableId, String comboId) {
        String baseTableId = readComboBaseTimeTableId(db, comboId);
        if (baseTableId.isEmpty()) baseTableId = courseTableId;

        Map<Integer, String[]> baseSlots = new HashMap<>();
        queryTimeSlots(db, COL_TIME_TABLE_ID, baseTableId, baseSlots);

        List<ComboRuleSlots> rules = new ArrayList<>();
        for (ComboRule rule : readComboRules(db, comboId)) {
            if (rule.targetId.isEmpty() || rule.targetId.equals(baseTableId)) continue;
            if (rule.startDate.isEmpty() || rule.endDate.isEmpty()) continue;
            Map<Integer, String[]> target = new HashMap<>();
            queryTimeSlots(db, COL_TIME_TABLE_ID, rule.targetId, target);
            if (target.isEmpty()) continue;
            rules.add(new ComboRuleSlots(rule.startDate, rule.endDate, alignSlots(baseSlots, target)));
        }
        if (baseSlots.isEmpty() && rules.isEmpty()) {
            queryTimeSlots(db, COL_TIME_TABLE_ID, courseTableId, baseSlots);
        }
        return new TimeSlotResolution(baseSlots, rules);
    }

    /** 基准为空则使用目标作息，否则仅覆盖基准中的同号节次。 */
    private static Map<Integer, String[]> alignSlots(Map<Integer, String[]> base, Map<Integer, String[]> target) {
        if (base.isEmpty()) return target;
        Map<Integer, String[]> aligned = new HashMap<>();
        for (Map.Entry<Integer, String[]> e : base.entrySet()) {
            String[] time = target.get(e.getKey());
            aligned.put(e.getKey(), time != null ? time : e.getValue());
        }
        return aligned;
    }

    private String[] readCourseTimeBinding(SQLiteDatabase db, String courseTableId) {
        Cursor c;
        try {
            c = db.rawQuery(
                    "SELECT targetType, targetId FROM course_time_bindings WHERE courseTableId = ? LIMIT 1",
                    new String[]{courseTableId});
        } catch (Throwable ignored) {
            return null;
        }
        try (c) {
            try {
                if (c.moveToFirst()) {
                    return new String[]{safeStr(c.getString(0)), safeStr(c.getString(1))};
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private String readComboBaseTimeTableId(SQLiteDatabase db, String comboId) {
        Cursor c;
        try {
            c = db.rawQuery("SELECT baseTimeTableId FROM time_table_combos WHERE id = ? LIMIT 1",
                    new String[]{comboId});
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 读取 time_table_combos 失败 -> " + t.getMessage());
            return "";
        }
        try (c) {
            try {
                if (c.moveToFirst()) return safeStr(c.getString(0));
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": 读取 time_table_combos 失败 -> " + t.getMessage());
            }
        }
        return "";
    }

    /** 保留拾光 DAO 的规则行顺序，不添加 ORDER BY。 */
    private List<ComboRule> readComboRules(SQLiteDatabase db, String comboId) {
        List<ComboRule> out = new ArrayList<>();
        Cursor c;
        try {
            c = db.rawQuery(
                    "SELECT startDate, endDate, targetTimeTableId FROM time_table_combo_rules WHERE comboId = ?",
                    new String[]{comboId});
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 读取 time_table_combo_rules 失败 -> " + t.getMessage());
            return out;
        }
        try (c) {
            try {
                while (c.moveToNext()) {
                    out.add(new ComboRule(safeStr(c.getString(0)), safeStr(c.getString(1)), safeStr(c.getString(2))));
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": 读取 time_table_combo_rules 失败 -> " + t.getMessage());
            }
        }
        return out;
    }

    private void queryTimeSlots(SQLiteDatabase db, String column, String timeTableId,
                                Map<Integer, String[]> out) {
        Cursor c;
        try {
            c = db.rawQuery(
                    "SELECT number, startTime, endTime FROM time_slots WHERE " + column
                            + " = ? ORDER BY number ASC",
                    new String[]{timeTableId});
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 读取 time_slots 失败 column=" + column
                    + " id=" + timeTableId + " -> " + t.getMessage());
            return;
        }
        try (c) {
            try {
                while (c.moveToNext()) {
                    out.put(c.getInt(0), new String[]{safeStr(c.getString(1)), safeStr(c.getString(2))});
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": 读取 time_slots 失败 column=" + column
                        + " id=" + timeTableId + " -> " + t.getMessage());
            }
        }
    }

    /** 版本读取失败按 legacy 处理，与旧版读取路径兼容。 */
    private static int safeDbVersion(SQLiteDatabase db) {
        try {
            return db.getVersion();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 读取数据库版本失败，按 legacy 处理 -> " + t.getMessage());
            return 0;
        }
    }

    /**
     * 找出时间上覆盖 startTime 的真实节次编号（节次开始 ≤ startTime &lt; 节次结束），找不到返回 -1。
     * 多个节次同时覆盖时取编号最小的。
     */
    private static int matchSectionByTime(Map<Integer, String[]> slots, String startTime) {
        int target = toMinutes(startTime);
        if (target < 0 || slots == null) return -1;
        int best = -1;
        for (Map.Entry<Integer, String[]> e : slots.entrySet()) {
            int slotStart = toMinutes(e.getValue()[0]);
            int slotEnd = toMinutes(e.getValue()[1]);
            if (slotStart < 0 || slotEnd <= slotStart) continue;
            if (target < slotStart || target >= slotEnd) continue;
            int sec = e.getKey();
            if (best < 0 || sec < best) best = sec;
        }
        return best;
    }

    /** "HH:mm" → 当日分钟数，格式非法返回 -1。 */
    private static int toMinutes(String hhmm) {
        if (hhmm == null || hhmm.length() != 5 || hhmm.charAt(2) != ':') return -1;
        try {
            int h = Integer.parseInt(hhmm.substring(0, 2));
            int m = Integer.parseInt(hhmm.substring(3, 5));
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String toWeeksSpec(List<Integer> weeks) {
        if (weeks == null || weeks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < weeks.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(weeks.get(i));
        }
        return sb.toString();
    }

    /**
     * 按学期开始日期推算当前周序号，不做夹取：学期未开始时 ≤ 0，学期结束后大于总周数，
     * 由消费侧（CourseScheduleParser / MainHook）据此判断学期状态。
     * startDate 缺失时无从推算，退回第 1 周以免误判成学期已结束而停掉所有提醒。
     */
    private int computePresentWeek(String startDate, boolean sundayFirst) {
        if (startDate == null || startDate.isEmpty()) return 1;
        int[] ymd = parseYmd(startDate);
        if (ymd == null) return 1;

        Calendar start = Calendar.getInstance(Locale.US);
        start.set(Calendar.YEAR, ymd[0]);
        start.set(Calendar.MONTH, Math.max(0, ymd[1] - 1));
        start.set(Calendar.DAY_OF_MONTH, Math.max(1, ymd[2]));
        clearClock(start);

        Calendar today = Calendar.getInstance(Locale.US);
        clearClock(today);

        int weekStartDay = sundayFirst ? Calendar.SUNDAY : Calendar.MONDAY;
        alignToWeekStart(start, weekStartDay);
        alignToWeekStart(today, weekStartDay);

        long diffDays = (today.getTimeInMillis() - start.getTimeInMillis()) / 86_400_000L;
        return (int) Math.floor(diffDays / 7.0d) + 1;
    }

    private static void clearClock(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private static void alignToWeekStart(Calendar c, int weekStartDay) {
        int cur = c.get(Calendar.DAY_OF_WEEK);
        int delta = cur - weekStartDay;
        if (delta < 0) delta += 7;
        if (delta != 0) c.add(Calendar.DAY_OF_MONTH, -delta);
    }

    private static String normalizeStartDate(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        return s.replace('/', '-').replace('.', '-');
    }

    private static int[] parseYmd(String raw) {
        try {
            String[] parts = raw.split("-");
            if (parts.length < 3) return null;
            int y = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            int d = Integer.parseInt(parts[2].trim());
            if (y <= 0 || m <= 0 || d <= 0) return null;
            return new int[]{y, m, d};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isInvalidSectionTime(String start, String end) {
        return start.isEmpty() || end.isEmpty()
                || "00:00".equals(start) || "00:00".equals(end);
    }

    private static String safeStr(String value) {
        return value == null ? "" : value;
    }

    /** 将规则日期区间及对齐后的节次时间写入镜像，跳过非法时间。 */
    private static JSONArray buildSectionTimeRules(List<ComboRuleSlots> rules) throws org.json.JSONException {
        JSONArray arr = new JSONArray();
        for (ComboRuleSlots rule : rules) {
            JSONArray times = new JSONArray();
            for (Map.Entry<Integer, String[]> e : rule.slots.entrySet()) {
                String start = e.getValue()[0];
                String end = e.getValue()[1];
                if (isInvalidSectionTime(start, end)) continue;
                JSONObject time = new JSONObject();
                time.put("i", e.getKey());
                time.put("s", start);
                time.put("e", end);
                times.put(time);
            }
            if (times.length() == 0) continue;
            JSONObject obj = new JSONObject();
            obj.put("s", rule.startDate);
            obj.put("e", rule.endDate);
            obj.put("t", times);
            arr.put(obj);
        }
        return arr;
    }

    private static final class TimeSlotResolution {
        final Map<Integer, String[]> baseSlots;
        final List<ComboRuleSlots> rules;

        TimeSlotResolution(Map<Integer, String[]> baseSlots, List<ComboRuleSlots> rules) {
            this.baseSlots = baseSlots;
            this.rules = rules == null ? java.util.Collections.emptyList() : rules;
        }
    }

    private static final class ComboRuleSlots {
        final String startDate;
        final String endDate;
        final Map<Integer, String[]> slots;

        ComboRuleSlots(String startDate, String endDate, Map<Integer, String[]> slots) {
            this.startDate = startDate;
            this.endDate = endDate;
            this.slots = slots;
        }
    }

    private static final class ComboRule {
        final String startDate;
        final String endDate;
        final String targetId;

        ComboRule(String startDate, String endDate, String targetId) {
            this.startDate = startDate;
            this.endDate = endDate;
            this.targetId = targetId;
        }
    }

    private static final class TableConfig {
        final String semesterStartDate;
        final int semesterTotalWeeks;
        final boolean sundayFirst;

        TableConfig(String semesterStartDate, int semesterTotalWeeks, boolean sundayFirst) {
            this.semesterStartDate = semesterStartDate == null ? "" : semesterStartDate;
            this.semesterTotalWeeks = semesterTotalWeeks;
            this.sundayFirst = sundayFirst;
        }
    }
}
