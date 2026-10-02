package com.xiaoai.islandnotify.hook;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.net.Uri;
import android.service.notification.StatusBarNotification;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;
import com.xiaoai.islandnotify.StageDisplayState;

import com.xiaoai.islandnotify.AlarmScheduler;
import com.xiaoai.islandnotify.ConfigDefaults;
import com.xiaoai.islandnotify.CourseScheduleParser;
import com.xiaoai.islandnotify.HolidayManager;
import com.xiaoai.islandnotify.MiuiSettingsInvoker;
import com.xiaoai.islandnotify.PrefsAccess;
import com.xiaoai.islandnotify.TimeTableHelperInvoker;
import com.xiaoai.islandnotify.TotalWeekReceiver;
import com.xiaoai.islandnotify.modernhook.XC_MethodHook;
import com.xiaoai.islandnotify.modernhook.XposedBridge;

import static com.xiaoai.islandnotify.modernhook.XposedHelpers.findAndHookMethod;

/**
 * LSPosed 主 Hook 类
 * 功能：拦截 com.miui.voiceassist 发送的"课程表提醒"通知，
 *       注入 miui.focus.param 参数，将其升级为小米超级岛通知。
 */
public class MainHook {

    private static final String TAG = "IslandNotifyHook";

    /** 目标应用包名（小爱同学） */
    private static final String TARGET_PACKAGE = "com.miui.voiceassist";

    /** AlarmManager 触发上课静音（发给 voiceassist 自身，不受 MIUI 电池限制） */
    private static final String ACTION_DO_MUTE   = "com.xiaoai.islandnotify.DO_MUTE";
    /** AlarmManager 触发下课解除静音（发给 voiceassist 自身，不受 MIUI 电池限制） */
    private static final String ACTION_DO_UNMUTE    = "com.xiaoai.islandnotify.DO_UNMUTE";
    /** AlarmManager 触发上课开启勿扰（DND） */
    private static final String ACTION_DO_DND_ON    = "com.xiaoai.islandnotify.DO_DND_ON";
    /** AlarmManager 触发下课关闭勿扰（DND） */
    private static final String ACTION_DO_DND_OFF   = "com.xiaoai.islandnotify.DO_DND_OFF";
    /** 超级岛按钮手动触发：立即应用所有已配置的静音/勿扰 */
    private static final String ACTION_MANUAL_MUTE   = "com.xiaoai.islandnotify.MANUAL_MUTE";
    /** 超级岛按钮手动触发：立即解除所有已配置的静音/勿扰 */
    private static final String ACTION_MANUAL_UNMUTE = "com.xiaoai.islandnotify.MANUAL_UNMUTE";
    /** 超级岛按钮手动触发：我要逃课（取消通知，必要时回滚模块已执行的静音/勿扰） */
    private static final String ACTION_MANUAL_SKIP_CLASS = "com.xiaoai.islandnotify.MANUAL_SKIP_CLASS";
    /** 每日 00:01 跨日重调广播 Action（链式保证次日课程 alarm 不丢失） */
    private static final String ACTION_RESCHEDULE_DAILY = "com.xiaoai.islandnotify.ACTION_RESCHEDULE_DAILY";
    /** WakeUp 数据源同步到超级小爱进程 */
    private static final String ACTION_WAKEUP_COURSE_SYNC = WakeupHook.ACTION_WAKEUP_COURSE_SYNC;
    /** 拾光数据源同步到超级小爱进程 */
    private static final String ACTION_SHIGUANG_COURSE_SYNC = ShiguangHook.ACTION_SHIGUANG_COURSE_SYNC;
    /** 通知定时取消广播 Action（替代 Handler.postDelayed，setAlarmClock 保证精确触发） */
    private static final String ACTION_NOTIF_CANCEL = "com.xiaoai.islandnotify.ACTION_NOTIF_CANCEL";
    private static final String ACTION_STAGE_HIDE = "com.xiaoai.islandnotify.ACTION_STAGE_HIDE";
    private static final String ACTION_PRESENTATION_DISMISS = "com.xiaoai.islandnotify.PRESENTATION_DISMISS";
    private static final String PRESENTATION_PREFIX = "course_display_";
    private final java.util.Map<String, Presentation> mPresentations = new java.util.LinkedHashMap<>();
    private boolean mPresentationsLoaded;
    /** shareData 拖拽分享图片在 miui.focus.pics Bundle 中的 key */
    private static final String PIC_KEY_SHARE = "miui.focus.pic_share";
    /** 测试通知专用标记：用于避免被“旧课表残留精确清理”误删 */
    private static final String KEY_TEST_NOTIF_MARKER = "xiaoai.test.manual_notification";
    /** voiceassist Manifest 中已声明的 Service，用于 AlarmManager 在进程死后强制拉起 */
    private static final String UPLOAD_STATE_SERVICE = "com.xiaomi.voiceassistant.UploadStateService";

    /** 点击课程卡片整体 → 跳转课表页的 Intent URI */
    private static final String COURSE_TABLE_INTENT =
            "intent://aiweb?url=https%3A%2F%2Fi.ai.mi.com%2Fh5%2Fprecache%2Fai-schedule%2F%23%2FtodayLesson" +
            "&flag=805339136&noBack=false&statusBarColor=FFFFFF&statusBarTextBlack=true" +
            "&navigationBarColor=FFFFFF#Intent;scheme=voiceassist;package=com.miui.voiceassist;end";

    /** 岛通知主参数 Key */
    private static final String KEY_FOCUS_PARAM = "miui.focus.param";

    /** SharedPreferences 名称（与 MainActivity 保持一致） */
    private static final String PREFS_NAME = "island_custom";
    private static final String PREFS_RUNTIME_NAME = "island_runtime";
    private static final String PREFS_WAKEUP_MIRROR = "island_wakeup_mirror";
    private static final String PREFS_SHIGUANG_MIRROR = "island_shiguang_mirror";
    /** 模块自身包名，用于跨进程读取 SharedPreferences */
    private static final String MODULE_PKG  = "com.xiaoai.islandnotify";

    /** AlarmManager 闹钟触发岛状态更新的广播 Action */
    private static final String ACTION_ISLAND_UPDATE = "com.xiaoai.islandnotify.ACTION_ISLAND_UPDATE";
    /** 触发目标应用发送测试通知的广播 Action */
    private static final String ACTION_TEST_NOTIFY = "com.xiaoai.islandnotify.ACTION_TEST_NOTIFY";
    /** 定时触发课前提醒通知的广播 Action */
    private static final String ACTION_COURSE_REMINDER = "com.xiaoai.islandnotify.ACTION_COURSE_REMINDER";
    /** CourseData SharedPreferences 名称（voiceassist 自身） */
    private static final String PREFS_COURSE_DATA = "CourseData";
    /** 课程数据源：超级小爱原始 CourseData */
    private static final String SOURCE_XIAOAI = "xiaoai";
    /** 课程数据源：WakeUp 镜像 */
    private static final String SOURCE_WAKEUP = "wakeup";
    /** 课程数据源：拾光镜像 */
    private static final String SOURCE_SHIGUANG = "shiguang";
    /** WakeUp 包名（作为通知点击目标） */
    private static final String PKG_WAKEUP = "com.suda.yzune.wakeupschedule";
    /** 拾光包名（作为通知点击目标） */
    private static final String PKG_SHIGUANG = "com.xingheyuzhuan.shiguangschedule";
    /** 配置项：课程数据源 */
    private static final String KEY_COURSE_DATA_SOURCE = "course_data_source";
    /** WakeUp 镜像存储键（写入 voiceassist 自身 island_runtime） */
    private static final String KEY_WAKEUP_MIRROR_BEAN = "wakeup_mirror_week_course_bean";
    private static final String KEY_WAKEUP_MIRROR_HASH = "wakeup_mirror_week_course_hash";
    /** 拾光镜像存储键（写入 voiceassist 自身独立 SP） */
    private static final String KEY_SHIGUANG_MIRROR_BEAN = "shiguang_mirror_week_course_bean";
    private static final String KEY_SHIGUANG_MIRROR_HASH = "shiguang_mirror_week_course_hash";
    /** 课前提醒分钟数配置键（存入 island_custom SP） */
    private static final String KEY_REMINDER_MINUTES = "reminder_minutes_before";
    /** 课前提醒默认提前分钟数 */
    private static final int DEFAULT_REMINDER_MINUTES = ConfigDefaults.REMINDER_MINUTES;
    /** CourseData.xml FileObserver，跨进程写入时仍能感知 */
    private android.os.FileObserver mCourseDataObserver;
    /** CourseData 变化防抖延迟（ms）：合并同一次写入触发的多个 inotify 事件 */
    private static final int RESCHEDULE_DEBOUNCE_MS = 1500;
    /** 防抖 Handler，懒加载避免 Xposed 初始化阶段 Looper 未就绪导致 NPE */
    private android.os.Handler mRescheduleHandler;
    /** 防抖 token，用于 removeCallbacksAndMessages */
    private final Object mRescheduleToken = new Object();
    /** UploadStateService.onStartCommand 是否已成功注入，避免重复 hook */
    private volatile boolean mUploadStateServiceHooked = false;
    /** 上次成功调度时 weekCourseBean 的 hashCode；FileObserver 触发时若内容未变则跳过重调度，避免补发重复通知 */
    private volatile int mLastCourseDataHash = 0;
    /** 测试通知时间戳去重：记录上一次毫秒值 */
    private static volatile long sLastTestNotifEpochMs = 0L;
    /** 测试通知时间戳去重：同毫秒内自增序号 */
    private static volatile int sLastTestNotifSeqInMs = 0;
    /** 上一条测试通知的 ID，用于发新测试前自动取消旧通知；-1 表示尚无 */
    private static volatile int sLastTestNotifId = -1;
    /** 上一条测试通知的 Tag，配合 ID 精确取消，避免误伤同 ID 的非测试通知 */
    private static volatile String sLastTestNotifTag = null;
    /** 已调度的课前提醒 alarmId 集合，关闭开关或重新调度时用于批量取消 */
    private final java.util.Set<Integer> mScheduledAlarmIds = new java.util.HashSet<>();
    /** 统一保护提醒与静音闹钟 ID 的读写和持久化。 */
    private final Object mScheduledIdsLock = new Object();
    /** 保护跳过课程 token 的读取、修改、保存，避免并发写入丢失。 */
    private final Object mSkippedAutomationLock = new Object();

    // ── 自动静音相关常量 ──
    private static final String KEY_MUTE_ENABLED         = "mute_enabled";
    private static final String KEY_MUTE_MINS_BEFORE      = "mute_mins_before";   // 上课前多少分钟静音
    private static final String KEY_UNMUTE_ENABLED        = "unmute_enabled";
    private static final String KEY_UNMUTE_MINS_AFTER     = "unmute_mins_after";  // 下课后多少分钟取消静音
    private static final String KEY_DND_ENABLED           = "dnd_enabled";        // 勿扰独立开关
    private static final String KEY_DND_MINS_BEFORE       = "dnd_mins_before";    // 上课前多少分钟开启勿扰
    private static final String KEY_UNDND_ENABLED         = "undnd_enabled";      // 下课自动关闭勿扰
    private static final String KEY_UNDND_MINS_AFTER      = "undnd_mins_after";   // 下课后多少分钟关闭勿扰
    private static final String KEY_REPOST_ENABLED         = "repost_enabled";     // 全局补发开关（通知/静音/勿扰）
    /** 上次执行“跨日重调”的日期标记（year*1000 + dayOfYear） */
    private static final String KEY_LAST_DAILY_RESCHEDULE_DAY = "last_daily_reschedule_day";
    private static final String KEY_COURSE_TOTAL_WEEK = "course_total_week";
    private static final String KEY_SCHEDULED_ALARM_IDS = "scheduled_alarm_ids";
    private static final String KEY_SCHEDULED_MUTE_IDS = "scheduled_mute_ids";
    private static final String KEY_SKIPPED_AUTOMATION_TOKENS = "skipped_automation_tokens";
    private static final String KEY_RUNTIME_MODULE_MUTE_APPLIED = "runtime_module_mute_applied";
    private static final String KEY_RUNTIME_MODULE_DND_APPLIED = "runtime_module_dnd_applied";
    private static final String SETTINGS_CACHE_PREFIX = "settings_util_class_@";
    private static final String TIMETABLE_CACHE_PREFIX = "timetable_helper_class_@";
    private static final String KEY_RUNTIME_MIGRATION_DONE = "runtime_storage_v1_done";
    private static final int    DEFAULT_MUTE_MINS_BEFORE  = ConfigDefaults.MINUTES_OFFSET;
    private static final int    DEFAULT_UNMUTE_MINS_AFTER = ConfigDefaults.MINUTES_OFFSET;
    private static final int    DEFAULT_DND_MINS_BEFORE   = ConfigDefaults.MINUTES_OFFSET;
    private static final int    DEFAULT_UNDND_MINS_AFTER  = ConfigDefaults.MINUTES_OFFSET;
    // ── 自动叫醒（系统闹钟）相关常量 ──
    private static final String ACTION_SCHEDULE_CLOCK_ALARMS  = DeskClockHook.ACTION_SCHEDULE_CLOCK_ALARMS;
    private static final String DESKCLOCK_PKG                  = "com.android.deskclock";
    private static final String KEY_WAKEUP_MORNING_ENABLED      = "wakeup_morning_enabled";
    private static final String KEY_WAKEUP_MORNING_LAST_SEC     = "wakeup_morning_last_sec";
    private static final String KEY_WAKEUP_MORNING_RULES_JSON    = "wakeup_morning_rules_json";
    private static final String KEY_WAKEUP_AFTERNOON_ENABLED      = "wakeup_afternoon_enabled";
    private static final String KEY_WAKEUP_AFTERNOON_FIRST_SEC    = "wakeup_afternoon_first_sec";
    private static final String KEY_WAKEUP_AFTERNOON_RULES_JSON   = "wakeup_afternoon_rules_json";
    private static final int    DEFAULT_WAKEUP_MORNING_LAST_SEC       = ConfigDefaults.WAKEUP_MORNING_LAST_SEC;
    private static final int    DEFAULT_WAKEUP_AFTERNOON_FIRST_SEC    = ConfigDefaults.WAKEUP_AFTERNOON_FIRST_SEC;
    private static final String DEFAULT_WAKEUP_MORNING_RULES_JSON     = ConfigDefaults.WAKEUP_MORNING_RULES_JSON;
    private static final String DEFAULT_WAKEUP_AFTERNOON_RULES_JSON   = ConfigDefaults.WAKEUP_AFTERNOON_RULES_JSON;
    /** 静音功能开关（volatile，跨 Xposed 回调线程读取） */
    private static volatile boolean sMuteEnabled   = ConfigDefaults.SWITCH_DISABLED;
    /** 取消静音功能开关 */
    private static volatile boolean sUnmuteEnabled = ConfigDefaults.SWITCH_DISABLED;
    /** 勿扰独立开关（与静音相互独立，可同时启用） */
    private static volatile boolean sDndEnabled    = ConfigDefaults.SWITCH_DISABLED;
    private static volatile boolean sUnDndEnabled  = ConfigDefaults.SWITCH_DISABLED;
    /** 自动叫醒功能开关 */
    private static volatile boolean sWakeupMorningEnabled   = ConfigDefaults.SWITCH_DISABLED;
    private static volatile boolean sWakeupAfternoonEnabled = ConfigDefaults.SWITCH_DISABLED;
    /** 全局补发开关：控制通知补发与课中即时静音/勿扰 */
    private static volatile boolean sRepostEnabled = ConfigDefaults.REPOST_ENABLED;
    /** 超级岛按钮功能模式：0=仅静音, 1=仅勿扰, 2=两者, 3=逃课 */
    private static volatile int sIslandButtonMode = ConfigDefaults.ISLAND_BUTTON_MODE;
    /** 已调度的静音/取消静音 alarm reqCode 集合，用于批量取消 */
    private final java.util.Set<Integer> mScheduledMuteIds = new java.util.HashSet<>();
    /** API 101 remote prefs 监听（示例同款能力） */
    private SharedPreferences mObservedRemotePrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener mRemotePrefsListener;

    /** 辅助方法：持久化已调度的 ID 集合到 SP */
    private void saveScheduledIds(Context ctx, String key, java.util.Set<Integer> ids) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray();
            synchronized (mScheduledIdsLock) {
                for (Integer id : ids) arr.put(id);
                sp.edit().putString(key, arr.toString()).apply();
            }
        } catch (Throwable ignored) {}
    }

    /** 辅助方法：从 SP 加载已调度的 ID 集合 */
    private java.util.Set<Integer> loadScheduledIds(Context ctx, String key) {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE);
            String json = sp.getString(key, null);
            if (json != null) {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) ids.add(arr.getInt(i));
            }
        } catch (Throwable ignored) {}
        return ids;
    }

    private void saveSkippedAutomationTokens(Context ctx, java.util.Set<String> tokens) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE);
            JSONArray arr = new JSONArray();
            // tokens 是调用方新建的局部集合；读改写过程由 mSkippedAutomationLock 保护。
            for (String token : tokens) {
                if (token != null && !token.isEmpty()) arr.put(token);
            }
            sp.edit().putString(KEY_SKIPPED_AUTOMATION_TOKENS, arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private java.util.Set<String> loadSkippedAutomationTokens(Context ctx) {
        java.util.Set<String> tokens = new java.util.HashSet<>();
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE);
            String json = sp.getString(KEY_SKIPPED_AUTOMATION_TOKENS, null);
            if (json == null || json.isEmpty()) return tokens;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String token = arr.optString(i, "");
                if (!token.isEmpty()) tokens.add(token);
            }
        } catch (Throwable ignored) {}
        return tokens;
    }

    private String todayDateToken() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private String buildSkippedAutomationToken(String dateToken, int automationAlarmId) {
        return dateToken + "#" + (automationAlarmId & 0x00FFFFFF);
    }

    private void markAutomationSkippedToday(Context ctx, int automationAlarmId) {
        if (automationAlarmId < 0) return;
        synchronized (mSkippedAutomationLock) {
            try {
                String today = todayDateToken();
                java.util.Set<String> tokens = loadSkippedAutomationTokens(ctx);
                java.util.Set<String> next = new java.util.HashSet<>();
                String todayPrefix = today + "#";
                for (String token : tokens) {
                    if (token != null && token.startsWith(todayPrefix)) next.add(token);
                }
                next.add(buildSkippedAutomationToken(today, automationAlarmId));
                saveSkippedAutomationTokens(ctx, next);
            } catch (Throwable ignored) {}
        }
    }

    private boolean isAutomationSkippedToday(Context ctx, int automationAlarmId) {
        if (automationAlarmId < 0) return false;
        try {
            String token = buildSkippedAutomationToken(todayDateToken(), automationAlarmId);
            return loadSkippedAutomationTokens(ctx).contains(token);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 跨日后清空“今日逃课跳过”token，避免历史日期残留。 */
    private void clearSkippedAutomationTokens(Context ctx) {
        synchronized (mSkippedAutomationLock) {
            try {
                SharedPreferences sp = ctx.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE);
                sp.edit().remove(KEY_SKIPPED_AUTOMATION_TOKENS).apply();
            } catch (Throwable ignored) {}
        }
    }

    /** 通知 id → 当前持有该通知的课程名，防止旧课程的陈旧 STATE_FINISHED 广播在新课更新后覆写岛 */
    private final java.util.Map<Integer, String> mNotifCourseOwner =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 展示通知 id → 该岛绑定的规范 alarmId。连续课程换发后展示 id（派生 id）≠ 规范 id，
     *  残留清理/补发去重凭此映射把在线的派生岛归位到今日合法课程，避免因 id 对不上误删/补发重复。 */
    private final java.util.Map<Integer, Integer> mNotifAutomationById =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 获取（或创建）防抖 Handler，保证在主 Looper 就绪后才初始化。 */
    private android.os.Handler getRescheduleHandler() {
        if (mRescheduleHandler == null) {
            mRescheduleHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        return mRescheduleHandler;
    }
    /** 跨 ClassLoader 的防重复注入标记 key（存于 boot classloader 的 System.properties） */
    private static final String HOOKED_KEY = "xiaoai.island.hooked";

    // ─────────────────────────────────────────────────────────────

    public void handleLoadPackage(String packageName, String processName, ClassLoader classLoader) {
        // 只注入目标进程
        if (!TARGET_PACKAGE.equals(packageName)) {
            return;
        }
        // 只注入主进程（processName == packageName），子进程如 :push/:remote 跳过
        // 各 OS 进程的 System.properties 相互独立，仅靠 HOOKED_KEY 无法去重跨进程重复
        if (!TARGET_PACKAGE.equals(processName)) {
            return;
        }
        // 同一进程可能因多 ClassLoader 被调用多次，只注册一次
        // 用 System.setProperty 而非 static 字段，确保跨 ClassLoader 生效
        if (System.getProperty(HOOKED_KEY) != null) return;
        System.setProperty(HOOKED_KEY, "1");
        XposedBridge.log(TAG + ": 已注入目标进程 → " + TARGET_PACKAGE);
        hookApplicationOnCreate(classLoader);
        hookUploadStateService(classLoader);
        hookNotifyMethods(classLoader);
    }

    /** Hook 目标 App 的 Application.onCreate，在其进程内注册业务广播接收器。 */
    private void hookApplicationOnCreate(ClassLoader classLoader) {
        findAndHookMethod("android.app.Application", classLoader,
                "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context appCtx = (Context) param.thisObject;
                // 部分系统在 onPackageLoaded 阶段 defaultClassLoader 可能为空，
                // 在 Application.onCreate 使用真实 app classloader 补挂 Service hook。
                hookUploadStateService(appCtx.getClassLoader());
                IntentFilter filter = new IntentFilter(ACTION_ISLAND_UPDATE);
                filter.addAction(ACTION_TEST_NOTIFY);
                filter.addAction(ACTION_COURSE_REMINDER);
                filter.addAction(ACTION_DO_MUTE);
                filter.addAction(ACTION_DO_UNMUTE);
                filter.addAction(ACTION_DO_DND_ON);
                filter.addAction(ACTION_DO_DND_OFF);
                filter.addAction(ACTION_MANUAL_MUTE);
                filter.addAction(ACTION_MANUAL_UNMUTE);
                filter.addAction(ACTION_MANUAL_SKIP_CLASS);
                filter.addAction(ACTION_RESCHEDULE_DAILY);
                filter.addAction(ACTION_NOTIF_CANCEL);
                filter.addAction(ACTION_STAGE_HIDE);
                filter.addAction(ACTION_PRESENTATION_DISMISS);
                filter.addAction(ACTION_WAKEUP_COURSE_SYNC);
                filter.addAction(ACTION_SHIGUANG_COURSE_SYNC);
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent == null ? null : intent.getAction();
                        if (dispatchSimpleAction(context, intent, action)) return;
                        if (ACTION_ISLAND_UPDATE.equals(action)) {
                            handlePresentationUpdate(context, intent);
                        } else if (ACTION_TEST_NOTIFY.equals(action)) {
                            // 由模块 APP 触发，在目标进程内构造并发送测试通知
                            String tCourseName = intent.getStringExtra("course_name");
                            String tStartTime  = intent.getStringExtra("start_time");
                            String tEndTime    = intent.getStringExtra("end_time");
                            String tClassroom  = intent.getStringExtra("classroom");
                            String tSection    = intent.getStringExtra("section_range");
                            String tTeacher    = intent.getStringExtra("teacher");
                            if (tCourseName == null || tCourseName.isEmpty()) tCourseName = "高等数学";
                            if (tStartTime  == null || tStartTime.isEmpty())  tStartTime  = "00:00";
                            if (tEndTime    == null || tEndTime.isEmpty())    tEndTime    = "00:00";
                            if (tClassroom  == null || tClassroom.isEmpty())  tClassroom  = "教科A-101";
                            if (tSection == null) tSection = "";
                            if (tTeacher == null) tTeacher = "";

                            android.app.NotificationManager tnm =
                                    context.getSystemService(android.app.NotificationManager.class);
                            if (tnm == null) return;

                            long nowEpochMs = System.currentTimeMillis();
                            int seqInMs;
                            synchronized (MainHook.class) {
                                if (nowEpochMs == sLastTestNotifEpochMs) {
                                    sLastTestNotifSeqInMs++;
                                } else {
                                    sLastTestNotifEpochMs = nowEpochMs;
                                    sLastTestNotifSeqInMs = 0;
                                }
                                seqInMs = sLastTestNotifSeqInMs;
                            }
                            int tNotifId = (int) (nowEpochMs & 0x7fffffffL);
                            if (seqInMs > 0) {
                                tNotifId = (tNotifId + seqInMs) & 0x7fffffff;
                            }
                            if (tNotifId <= 0) {
                                tNotifId = 1 + seqInMs;
                            }
                            String tNotifTag = "xiaoai_test_" + nowEpochMs + "_" + seqInMs;
                            // 先取消上一条测试通知，避免堆积
                            if (sLastTestNotifId != -1) {
                                if (sLastTestNotifTag != null && !sLastTestNotifTag.isEmpty()) {
                                    tnm.cancel(sLastTestNotifTag, sLastTestNotifId);
                                } else {
                                    tnm.cancel(sLastTestNotifId);
                                }
                                mNotifAutomationById.remove(sLastTestNotifId);
                                XposedBridge.log(TAG + ": 已取消上一条测试通知 tag=" + sLastTestNotifTag
                                        + " id=" + sLastTestNotifId);
                            }
                            sLastTestNotifId = tNotifId;
                            sLastTestNotifTag = tNotifTag;
                            XposedBridge.log(TAG + ": 即将发出测试通知 → " + tCourseName + " @" + tStartTime);
                            CourseInfo tInfo = new CourseInfo(tCourseName, tStartTime, tEndTime, tClassroom, tSection, tTeacher);
                            Presentation test = preparePresentation(context, tInfo, tNotifId,
                                    tNotifTag, tNotifId, nowEpochMs, false, -1, true);
                            displayPresentation(context, test, false);
                            XposedBridge.log(TAG + ": 已在目标进程发出测试通知 id=" + tNotifId);
                            // 测试通知按用户设定的时间逻辑调度静音/取消静音闹钟：
                            // 分钟数直接从 intent 读取（MainActivity 调用时已携带），不读 SP，消除跨进程缓存旧值问题
                            boolean tMuteEnabled   = intent.getBooleanExtra("mute_enabled",   sMuteEnabled);
                            boolean tUnmuteEnabled = intent.getBooleanExtra("unmute_enabled", sUnmuteEnabled);
                            boolean tDndEnabled    = intent.getBooleanExtra("dnd_enabled",    sDndEnabled);
                            boolean tUnDndEnabled  = intent.getBooleanExtra("undnd_enabled",  sUnDndEnabled);
                            if (tMuteEnabled || tUnmuteEnabled || tDndEnabled || tUnDndEnabled) {
                                long tNow       = System.currentTimeMillis();
                                // 使用 MainActivity 传来的精确毫秒时间戳，与真实调度逻辑完全一致
                                long classStartMs = intent.getLongExtra("start_ms", tNow + 60_000L);
                                long classEndMs   = intent.getLongExtra("end_ms",   tNow + 120_000L);
                                if (tMuteEnabled || tUnmuteEnabled) {
                                    int  tMuteBefore    = intent.getIntExtra(KEY_MUTE_MINS_BEFORE,  DEFAULT_MUTE_MINS_BEFORE);
                                    int  tUnmuteAfter   = intent.getIntExtra(KEY_UNMUTE_MINS_AFTER, DEFAULT_UNMUTE_MINS_AFTER);
                                    long tMuteTrigger   = classStartMs - (long) tMuteBefore  * 60_000L;
                                    long tUnmuteTrigger = classEndMs   + (long) tUnmuteAfter * 60_000L;
                                    if (tMuteEnabled) {
                                        if (tMuteTrigger <= tNow) {
                                            applyMuteState(context, true, tCourseName);
                                        } else {
                                            scheduleMuteAlarm(context, tCourseName, tMuteTrigger, tNotifId);
                                            XposedBridge.log(TAG + ": 测试 → 静音将在 " + (tMuteTrigger - tNow) / 1_000 + " 秒后触发");
                                        }
                                    }
                                    if (tUnmuteEnabled) {
                                        scheduleUnmuteAlarm(context, tCourseName, tUnmuteTrigger, tNotifId);
                                        XposedBridge.log(TAG + ": 测试 → 取消静音将在 " + (tUnmuteTrigger - tNow) / 1_000 + " 秒后触发");
                                    }
                                }
                                if (tDndEnabled || tUnDndEnabled) {
                                    int  tDndBefore    = intent.getIntExtra(KEY_DND_MINS_BEFORE,  DEFAULT_DND_MINS_BEFORE);
                                    int  tUnDndAfter   = intent.getIntExtra(KEY_UNDND_MINS_AFTER, DEFAULT_UNDND_MINS_AFTER);
                                    long tDndTrigger   = classStartMs - (long) tDndBefore  * 60_000L;
                                    long tUnDndTrigger = classEndMs   + (long) tUnDndAfter * 60_000L;
                                    if (tDndEnabled) {
                                        if (tDndTrigger <= tNow) {
                                            applyDndState(context, true, tCourseName);
                                        } else {
                                            scheduleDndOnAlarm(context, tCourseName, tDndTrigger, tNotifId);
                                            XposedBridge.log(TAG + ": 测试 → 勿扰将在 " + (tDndTrigger - tNow) / 1_000 + " 秒后触发");
                                        }
                                    }
                                    if (tUnDndEnabled) {
                                        scheduleDndOffAlarm(context, tCourseName, tUnDndTrigger, tNotifId);
                                        XposedBridge.log(TAG + ": 测试 → 关闭勿扰将在 " + (tUnDndTrigger - tNow) / 1_000 + " 秒后触发");
                                    }
                                }
                            }
                        } else if (ACTION_COURSE_REMINDER.equals(action)) {
                            handlePresentationUpdate(context, intent);
                        }
                    }
                };
                ContextCompat.registerReceiver(appCtx, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
                XposedBridge.log(TAG + ": [Application] 广播接收器已注册");
                // 动态定位小米内部 SettingsUtil（change 方法），用于静音/勿扰模式切换，结果按版本号缓存
                MiuiSettingsInvoker.init(appCtx, appCtx.getClassLoader());
                bootstrapRemotePrefsUnified(appCtx);
                // 从 SP 读取开关状态
                loadPresentations(appCtx);
                SharedPreferences initPrefs = getConfigPrefs();
                refreshRuntimeSwitchesFromPrefs(initPrefs);
                registerRemotePrefsListener(appCtx);
                // 加载持久化的闹钟 ID
                synchronized (mScheduledIdsLock) {
                    mScheduledAlarmIds.addAll(loadScheduledIds(appCtx, KEY_SCHEDULED_ALARM_IDS));
                    mScheduledMuteIds.addAll(loadScheduledIds(appCtx, KEY_SCHEDULED_MUTE_IDS));
                }
                scheduleTodayWakeupAlarms(appCtx);
                // 启动 CourseData 监听
                registerCourseDataListener(appCtx);
                // 启动时先检查是否错过了 00:01 跨日重调（例如关机跨日）；若错过则补触发一次主动更新
                boolean recovered = tryRecoverMissedDailyReschedule(appCtx);
                if (!recovered) {
                    safeReschedule(appCtx, "island_startup", false);
                }
                // 初始化课表内容哈希，确保 FileObserver 首次触发时能正确跳过未实质变动的写入。
                try {
                    String initBj = readActiveCourseBeanJson(appCtx, initPrefs);
                    if (initBj != null && !initBj.isEmpty()) {
                        mLastCourseDataHash = stableCourseHash(initBj);
                    }
                } catch (Throwable ignored) {}
                // 跨日自动重调：每天 00:01 重新调度当日闹钟，链式保证次日不丢失
                scheduleMidnightReschedule(appCtx);
                XposedBridge.log(TAG + ": 偷好同步接收器已注册，课前提醒已开启");
            }
        });
    }

    /**
     * 创建指向 UploadStateService 的显式 Intent（供 AlarmManager 使用）。
     * PendingIntent.getService 在进程死亡后能强制拉起进程，
     * 解决 getBroadcast + 动态注册接收器在进程死后无人接收的问题。
     */
    private boolean dispatchSimpleAction(Context context, Intent intent, String action) {
        if (action == null) return true;
        if (ACTION_DO_MUTE.equals(action)) {
            int automationAlarmId = intent.getIntExtra("automation_alarm_id", -1);
            if (isAutomationSkippedToday(context, automationAlarmId)) {
                XposedBridge.log(TAG + ": [逃课] 忽略静音自动化 alarmId=" + automationAlarmId);
                return true;
            }
            applyMuteState(context, true, intent.getStringExtra("course_name"));
            return true;
        }
        if (ACTION_DO_UNMUTE.equals(action)) {
            int automationAlarmId = intent.getIntExtra("automation_alarm_id", -1);
            if (isAutomationSkippedToday(context, automationAlarmId)) {
                XposedBridge.log(TAG + ": [逃课] 忽略取消静音自动化 alarmId=" + automationAlarmId);
                return true;
            }
            applyMuteState(context, false, intent.getStringExtra("course_name"));
            return true;
        }
        if (ACTION_DO_DND_ON.equals(action)) {
            int automationAlarmId = intent.getIntExtra("automation_alarm_id", -1);
            if (isAutomationSkippedToday(context, automationAlarmId)) {
                XposedBridge.log(TAG + ": [逃课] 忽略开启勿扰自动化 alarmId=" + automationAlarmId);
                return true;
            }
            applyDndState(context, true, intent.getStringExtra("course_name"));
            return true;
        }
        if (ACTION_DO_DND_OFF.equals(action)) {
            int automationAlarmId = intent.getIntExtra("automation_alarm_id", -1);
            if (isAutomationSkippedToday(context, automationAlarmId)) {
                XposedBridge.log(TAG + ": [逃课] 忽略关闭勿扰自动化 alarmId=" + automationAlarmId);
                return true;
            }
            applyDndState(context, false, intent.getStringExtra("course_name"));
            return true;
        }
        if (ACTION_MANUAL_MUTE.equals(action) || ACTION_MANUAL_UNMUTE.equals(action)) {
            boolean enable = ACTION_MANUAL_MUTE.equals(action);
            String courseName = intent.getStringExtra("course_name");
            if (sIslandButtonMode == 0 || sIslandButtonMode == 2) applyMuteState(context, enable, courseName);
            if (sIslandButtonMode == 1 || sIslandButtonMode == 2) applyDndState(context, enable, courseName);
            return true;
        }
        if (ACTION_MANUAL_SKIP_CLASS.equals(action)) {
            SharedPreferences runtime = context.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE);
            String courseName = intent.getStringExtra("course_name");
            int targetId = intent.getIntExtra("notif_id", -1);
            int automationAlarmId = intent.getIntExtra("automation_alarm_id", -1);
            terminatePresentations(context, automationAlarmId, targetId);
            markAutomationSkippedToday(context, automationAlarmId);
            cancelCourseAutomationAlarms(context, automationAlarmId);
            if (runtime.getBoolean(KEY_RUNTIME_MODULE_MUTE_APPLIED, false)) {
                applyMuteState(context, false, courseName);
            }
            if (runtime.getBoolean(KEY_RUNTIME_MODULE_DND_APPLIED, false)) {
                applyDndState(context, false, courseName);
            }
            String targetTag = intent.getStringExtra("notif_tag");
            int canceled = cancelTargetIslandNotification(context, targetId, targetTag);
            XposedBridge.log(TAG + ": [逃课] 已执行，取消通知 " + canceled + " 条"
                    + " automationAlarmId=" + automationAlarmId);
            return true;
        }
        if (ACTION_WAKEUP_COURSE_SYNC.equals(action)) {
            String beanJson = intent.getStringExtra("bean_json");
            if (beanJson == null || beanJson.isEmpty()) return true;
            int hash = stableCourseHash(beanJson);
            SharedPreferences wakeupMirror =
                    context.getSharedPreferences(PREFS_WAKEUP_MIRROR, Context.MODE_PRIVATE);
            int oldHash = wakeupMirror.getInt(KEY_WAKEUP_MIRROR_HASH, 0);
            if (hash == oldHash) return true;
            boolean isFirstSync = (oldHash == 0);
            wakeupMirror.edit()
                    .putString(KEY_WAKEUP_MIRROR_BEAN, beanJson)
                    .putInt(KEY_WAKEUP_MIRROR_HASH, hash)
                    .apply();
            SharedPreferences prefs = getConfigPrefs();
            if (isWakeupDataSource(prefs)) {
                mLastCourseDataHash = hash;
                XposedBridge.log(TAG + ": 收到 WakeUp 课程镜像，触发重调度 hash=" + hash);
                safeReschedule(context, "wakeup_source_sync", false);
            }
            if (isFirstSync) {
                showFirstSyncToast(context);
            }
            return true;
        }
        if (ACTION_SHIGUANG_COURSE_SYNC.equals(action)) {
            String beanJson = intent.getStringExtra("bean_json");
            if (beanJson == null || beanJson.isEmpty()) return true;
            int hash = stableCourseHash(beanJson);
            SharedPreferences mirror =
                    context.getSharedPreferences(PREFS_SHIGUANG_MIRROR, Context.MODE_PRIVATE);
            int oldHash = mirror.getInt(KEY_SHIGUANG_MIRROR_HASH, 0);
            if (hash == oldHash) return true;
            boolean isFirstSync = (oldHash == 0);
            mirror.edit()
                    .putString(KEY_SHIGUANG_MIRROR_BEAN, beanJson)
                    .putInt(KEY_SHIGUANG_MIRROR_HASH, hash)
                    .apply();
            SharedPreferences prefs = getConfigPrefs();
            if (isShiguangDataSource(prefs)) {
                mLastCourseDataHash = hash;
                XposedBridge.log(TAG + ": 收到拾光课程镜像，触发重调度 hash=" + hash);
                safeReschedule(context, "shiguang_source_sync", false);
            }
            if (isFirstSync) {
                showFirstSyncToast(context);
            }
            return true;
        }
        if (ACTION_RESCHEDULE_DAILY.equals(action)) {
            XposedBridge.log(TAG + ": [daily-reschedule] trigger");
            boolean fromSourceChange = intent.getBooleanExtra("from_source_change", false);
            String newSource = intent.getStringExtra("new_source");
            SharedPreferences prefs = getConfigPrefs();
            refreshRuntimeSwitchesFromPrefs(prefs);
            clearSkippedAutomationTokens(context);
            markDailyRescheduleRun(context);
            safeReschedule(context, "island_reschedule_daily", true);
            if (fromSourceChange && newSource != null) {
                checkMirrorAndNotify(context, newSource);
            }
            return true;
        }
        if (ACTION_STAGE_HIDE.equals(action) || ACTION_PRESENTATION_DISMISS.equals(action)) {
            handlePresentationEnd(context, intent, ACTION_PRESENTATION_DISMISS.equals(action));
            return true;
        }
        if (ACTION_NOTIF_CANCEL.equals(action)) {
            // 旧版整课取消事件停用，防止升级前遗留闹钟破坏阶段恢复。
            return true;
        }
        return false;
    }

    private Intent createServiceIntent(String action) {
        return AlarmScheduler.buildServiceIntent(TARGET_PACKAGE, UPLOAD_STATE_SERVICE, action);
    }

    private int cancelTargetIslandNotification(Context context, int notifId, String notifTag) {
        if (notifId < 0) return 0;
        try {
            android.app.NotificationManager nm =
                    context.getSystemService(android.app.NotificationManager.class);
            if (nm == null) return 0;
            if (notifTag != null && !notifTag.isEmpty()) nm.cancel(notifTag, notifId);
            else nm.cancel(notifId);
            mNotifCourseOwner.remove(notifId);
            mNotifAutomationById.remove(notifId);
            return 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cancelTargetIslandNotification 失败 -> " + t.getMessage());
            return 0;
        }
    }

    private void cancelCourseAutomationAlarms(Context ctx, int automationAlarmId) {
        if (automationAlarmId < 0) return;
        int alarmId = automationAlarmId & 0x00FFFFFF;
        try {
            for (String action : new String[]{ACTION_DO_MUTE, ACTION_DO_UNMUTE, ACTION_DO_DND_ON, ACTION_DO_DND_OFF}) {
                int reqCode = AlarmScheduler.reqCodeForMuteAction(alarmId, action);
                Intent dummy = createServiceIntent(action);
                AlarmScheduler.cancelAlarmClock(
                        ctx, dummy, reqCode,
                        action, TARGET_PACKAGE, reqCode | 0x40000000,
                        true);
            }
            synchronized (mScheduledIdsLock) {
                mScheduledMuteIds.remove(alarmId);
                saveScheduledIds(ctx, KEY_SCHEDULED_MUTE_IDS, mScheduledMuteIds);
            }
            XposedBridge.log(TAG + ": [逃课] 已取消该课自动化闹钟 alarmId=" + alarmId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cancelCourseAutomationAlarms 失败 -> " + t.getMessage());
        }
    }

    /** 判断给定 action 是否为闹钟触发的 action（需通过 Service 拉起进程） */
    private static boolean isAlarmAction(String action) {
        return ACTION_COURSE_REMINDER.equals(action)
                || ACTION_ISLAND_UPDATE.equals(action)
                || ACTION_DO_MUTE.equals(action)
                || ACTION_DO_UNMUTE.equals(action)
                || ACTION_DO_DND_ON.equals(action)
                || ACTION_DO_DND_OFF.equals(action)
                || ACTION_RESCHEDULE_DAILY.equals(action)
                || ACTION_NOTIF_CANCEL.equals(action)
                || ACTION_STAGE_HIDE.equals(action)
                || ACTION_PRESENTATION_DISMISS.equals(action)
                // 镜像同步也走这条路：第三方课表用 startService 把本进程拉起来后，
                // 由此转成包内广播，避免进程已被杀时推送静默丢失。
                || ACTION_WAKEUP_COURSE_SYNC.equals(action)
                || ACTION_SHIGUANG_COURSE_SYNC.equals(action);
    }

    /**
     * Hook voiceassist 的 UploadStateService.onStartCommand，拦截闹钟触发的 Intent。
     * 当进程被杀后，AlarmManager 通过 PendingIntent.getService 强制拉起进程：
     *   系统启动进程 → Application.onCreate（Xposed 注入 + 动态 BR 注册）
     *   → Service.onStartCommand（本 hook 拦截）→ 转发为包内广播 → 动态 BR 处理。
     */
    private void hookUploadStateService(ClassLoader classLoader) {
        if (mUploadStateServiceHooked) return;
        if (classLoader == null) {
            XposedBridge.log(TAG + ": hookUploadStateService 跳过：classLoader 为空，等待 Application.onCreate 重试");
            return;
        }
        try {
            // UploadStateService 未覆写 onStartCommand，需用 getMethod 搜索继承链
            Class<?> svcClass = classLoader.loadClass(UPLOAD_STATE_SERVICE);
            java.lang.reflect.Method onStartCmd = svcClass.getMethod(
                    "onStartCommand", Intent.class, int.class, int.class);
            XposedBridge.hookMethod(onStartCmd, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // 仅拦截 UploadStateService 实例，放行其他 Service
                    if (!UPLOAD_STATE_SERVICE.equals(param.thisObject.getClass().getName()))
                        return;
                    Intent intent = (Intent) param.args[0];
                    if (intent == null || intent.getAction() == null) return;
                    String action = intent.getAction();
                    if (!isAlarmAction(action)) return;

                    Context ctx = (Context) param.thisObject;
                    // 清除 component（Service 目标），转为包内隐式广播
                    Intent fwd = new Intent(action);
                    if (intent.getExtras() != null) fwd.putExtras(intent.getExtras());
                    fwd.setPackage(TARGET_PACKAGE);
                    ctx.sendBroadcast(fwd);
                    param.setResult(android.app.Service.START_NOT_STICKY);
                    XposedBridge.log(TAG + ": [Service→BR] 转发 " + action);
                }
            });
            mUploadStateServiceHooked = true;
            XposedBridge.log(TAG + ": hookUploadStateService 已注入");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hookUploadStateService 失败: " + t.getMessage());
        }
    }

    /**
     * 统一重调度逻辑：
     * 1. 触发 proactive 主动更新（可选）。
     * 2. 强制清除哈希（mLastCourseDataHash=0），确保后续 FileObserver 或延时任务必执行。
     * 3. 立即执行一次调度（即刻响应）。
     * 4. 5秒后再次执行调度（等待网络更新完成）。
     */
    private void safeReschedule(Context context, String from, boolean proactive) {
        if (proactive) {
            TimeTableHelperInvoker.init(context, context.getClassLoader());
            TimeTableHelperInvoker.triggerUpdate(context, from, false);
        }
        mLastCourseDataHash = 0;
        
        // 立即执行一次（使用现有数据，即刻反馈）
        scheduleTodayCourseReminders(context, null, false);
        scheduleTodayMuteAlarms(context, false);
        scheduleTodayWakeupAlarms(context);

        // 5秒后再次执行（等待主动更新写盘成功）
        // skipRepost=true：仅重新调度未来闹钟，跳过补发通知和即时静音/勿扰，
        // 避免与第一次执行重复导致岛重新弹出和多余状态切换。
        getRescheduleHandler().postDelayed(() -> {
            scheduleTodayCourseReminders(context, null, true);
            scheduleTodayMuteAlarms(context, true);
            scheduleTodayWakeupAlarms(context);
            // 若是跨日重调，链式调度下一个午夜
            if ("island_reschedule_daily".equals(from)) {
                scheduleMidnightReschedule(context);
            }
        }, 5000);
    }

    /** 将“跨日重调已执行”持久化为今天，供启动时漏触发补偿判断。 */
    private void markDailyRescheduleRun(Context context) {
        try {
            int today = getTodayDayMarker();
            context.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_LAST_DAILY_RESCHEDULE_DAY, today)
                    .apply();
        } catch (Throwable ignored) {}
    }

    /**
     * 若 00:01 跨日重调因关机/宿主未运行而漏触发，则在宿主启动时补触发一次。
     * 返回 true 表示已执行补偿重调（含主动更新），false 表示无需补偿。
     */
    private boolean tryRecoverMissedDailyReschedule(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE);
            int today = getTodayDayMarker();
            int last  = sp.getInt(KEY_LAST_DAILY_RESCHEDULE_DAY, -1);
            if (last < 0) {
                sp.edit().putInt(KEY_LAST_DAILY_RESCHEDULE_DAY, today).apply();
                return false;
            }
            if (last == today) {
                return false;
            }
            if (last > today) {
                // 用户手动回拨日期或时区变化，重置标记但不触发主动更新
                sp.edit().putInt(KEY_LAST_DAILY_RESCHEDULE_DAY, today).apply();
                return false;
            }
            XposedBridge.log(TAG + ": 检测到错过跨日重调，执行补偿（last=" + last + ", today=" + today + ")");
            sp.edit().putInt(KEY_LAST_DAILY_RESCHEDULE_DAY, today).apply();
            clearSkippedAutomationTokens(context);
            safeReschedule(context, "island_reschedule_daily", true);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 漏触发补偿检查失败 -> " + t.getMessage());
            return false;
        }
    }

    /** 生成“今天”标记值：year*1000 + dayOfYear，跨年可比较且无需字符串解析。 */
    private int getTodayDayMarker() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int year = cal.get(java.util.Calendar.YEAR);
        int dayOfYear = cal.get(java.util.Calendar.DAY_OF_YEAR);
        return year * 1000 + dayOfYear;
    }

    /**
     * 读取 voiceassist 自身的 CourseData SharedPreferences，解析今日课程，
     * 为每节课在开始前 N 分钟设置 AlarmManager 精确闹钟。
     */
    private void scheduleTodayCourseReminders(Context ctx, String cachedBeanJson) {
        scheduleTodayCourseReminders(ctx, cachedBeanJson, false);
    }

    /**
     * @param skipRepost 为 true 时跳过“补发”逻辑（进程重启后第二次延迟调度时使用），
     *                   仅重新调度未来闹钟，避免重复触发岛动画。
     */
    private synchronized void scheduleTodayCourseReminders(Context ctx, String cachedBeanJson, boolean skipRepost) {
        try {
            final String beanJson;
            if (cachedBeanJson != null) {
                beanJson = cachedBeanJson;
            } else {
                SharedPreferences prefs = getConfigPrefs();
                String raw = readActiveCourseBeanJson(ctx, prefs);
                if (raw == null || raw.isEmpty()) {
                    XposedBridge.log(TAG + ": 课程数据为空，跳过课前提醒调度 source="
                            + readCourseSource(prefs));
                    return;
                }
                beanJson = raw;
                mLastCourseDataHash = stableCourseHash(beanJson);
            }
            if (beanJson.isEmpty()) {
                XposedBridge.log(TAG + ": 课程数据为空，跳过课前提醒调度");
                return;
            }

            cancelAllScheduledAlarms(ctx);
            java.util.Set<Integer> validAlarmIds = new java.util.HashSet<>();

            java.text.SimpleDateFormat holidayFmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            String todayDateStr = holidayFmt.format(new java.util.Date());
            if (HolidayManager.isHoliday(todayDateStr)) {
                prunePresentations(ctx, java.util.Collections.emptySet());
                cancelStaleNotifications(ctx, java.util.Collections.emptySet());
                XposedBridge.log(TAG + ": 今日 " + todayDateStr + " 为节假日，跳过课前提醒调度");
                return;
            }
            HolidayManager.HolidayEntry workSwapDay = HolidayManager.getWorkSwap(todayDateStr);

            java.util.Calendar cal = java.util.Calendar.getInstance();
            int calDay = cal.get(java.util.Calendar.DAY_OF_WEEK);
            int todayDay = (calDay == java.util.Calendar.SUNDAY) ? 7 : (calDay - 1);

            CourseScheduleParser.ParsedSchedule parsed = CourseScheduleParser.parse(beanJson);
            int currentWeek = parsed.presentWeek;
            if (workSwapDay != null && workSwapDay.followWeek > 0 && workSwapDay.followWeekday > 0) {
                XposedBridge.log(TAG + ": 今日 " + todayDateStr + " 为调休工作日，"
                        + "按第" + workSwapDay.followWeek + "周" + workSwapDay.followDesc() + " 调度");
                todayDay = workSwapDay.followWeekday;
                currentWeek = workSwapDay.followWeek;
            }

            int totalWeek = parsed.totalWeek;
            // 学期外（已结束 / 未开始）不提前返回：越界周在 CourseSlot.isInWeek 中天然不匹配
            // 任何课程，继续走完流程才能持久化 totalWeek 并清理残留通知。
            // 同时丢弃调休的周数覆盖，否则学期结束后残留的调休配置会把周数拉回学期内。
            if (parsed.isTermEnded()) {
                XposedBridge.log(TAG + ": 当前第" + parsed.presentWeek + " 周，已超过学期总周数 "
                        + totalWeek + "，学期已结束，不再调度课程提醒");
                currentWeek = parsed.presentWeek;
            } else if (parsed.isTermNotStarted()) {
                XposedBridge.log(TAG + ": 学期尚未开始（推算第" + parsed.presentWeek
                        + " 周），不调度课程提醒");
                currentWeek = parsed.presentWeek;
            }

            SharedPreferences prefs = getConfigPrefs();
            SharedPreferences runtimePrefs = ctx.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE);
            if (totalWeek > 0) {
                runtimePrefs.edit().putInt(KEY_COURSE_TOTAL_WEEK, totalWeek).apply();
                Intent twIntent = new Intent(TotalWeekReceiver.ACTION_UPDATE_TOTAL_WEEK);
                twIntent.setPackage(MODULE_PKG);
                twIntent.putExtra(KEY_COURSE_TOTAL_WEEK, totalWeek);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // 由系统提供宿主身份，接收端不信任 Intent 中自报的发送方。
                    android.app.BroadcastOptions options = android.app.BroadcastOptions.makeBasic()
                            .setShareIdentityEnabled(true);
                    ctx.sendBroadcast(twIntent, null, options.toBundle());
                } else {
                    ctx.sendBroadcast(twIntent);
                }
            }
            int reminderMinutes = readConfigInt(prefs, KEY_REMINDER_MINUTES, DEFAULT_REMINDER_MINUTES);
            long nowMs = System.currentTimeMillis();
            long reminderMs = (long) reminderMinutes * 60_000L;

            java.util.List<TodayCourseSlot> todaySlots = new java.util.ArrayList<>();
            for (CourseScheduleParser.CourseSlot course : parsed.courses) {
                if (course.day != todayDay) continue;
                if (!course.isInWeek(currentWeek)) continue;
                long startMs = computeClassStartMs(course.startTime);
                long endMs = computeClassStartMs(course.endTime);
                if (startMs < 0 || endMs < 0) continue;
                todaySlots.add(new TodayCourseSlot(course, startMs, endMs));
            }
            todaySlots.sort(java.util.Comparator.comparingLong(slot -> slot.startMs));

            int scheduledCount = 0;
            int previousId = -1;
            boolean previousSkipped = false;
            for (int si = 0; si < todaySlots.size(); si++) {
                TodayCourseSlot slot = todaySlots.get(si);
                CourseScheduleParser.CourseSlot course = slot.slot;
                CourseInfo info = new CourseInfo(course.courseName, course.startTime, course.endTime,
                        course.classroom, course.sectionRange, course.teacher);
                int alarmId = buildCourseNotificationId(course.courseName, course.startTime,
                        course.endTime, course.classroom, course.sectionRange, course.teacher);
                validAlarmIds.add(alarmId);
                boolean skipped = isAutomationSkippedToday(ctx, alarmId);
                int predecessor = previousId;
                boolean predecessorSkipped = previousSkipped;
                previousId = alarmId;
                previousSkipped = skipped;
                if (skipped) {
                    terminatePresentations(ctx, alarmId, alarmId);
                    continue;
                }
                long triggerMs = slot.startMs - reminderMs;
                boolean consecutive = false;
                if (si > 0) {
                    long previousEnd = todaySlots.get(si - 1).endMs;
                    long gap = slot.startMs - previousEnd;
                    if (gap >= 0 && gap <= reminderMs) {
                        triggerMs = previousEnd;
                        consecutive = !predecessorSkipped;
                    }
                }
                Presentation presentation = preparePresentation(ctx, info, alarmId, null, alarmId,
                        triggerMs, consecutive, predecessor, false);
                if (presentation.state.terminated) continue;
                schedulePresentationBoundaries(ctx, presentation);
                if (presentation.state.visible) {
                    expirePresentationIfDue(ctx, presentation);
                    if (presentation.state.visible && findPresentationNotification(ctx, presentation) == null) {
                        presentation.state.terminate();
                        savePresentation(ctx, presentation);
                    } else {
                        schedulePresentationHide(ctx, presentation);
                    }
                }
                if (triggerMs <= nowMs && nowMs < slot.endMs
                        && !skipRepost && sRepostEnabled) {
                    displayPresentation(ctx, presentation, false);
                }
                scheduledCount++;
            }
            prunePresentations(ctx, validAlarmIds);
            XposedBridge.log(TAG + ": 今日课前提醒已调度 " + scheduledCount
                    + " 条（第" + currentWeek + " 周，提前 " + reminderMinutes + " 分钟）");

            cancelStaleNotifications(ctx, validAlarmIds);
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": scheduleTodayCourseReminders 失败 -> " + e.getMessage());
        }
    }

    /**
     * 提取 weekCourseBean JSON 中真正影响调度的字段（courses / sectionTimes / presentWeek /
     * totalWeek / weekStart）并计算其 hashCode，过滤掉 updateTime、level、rtPresentWeek、
     * startSemester 等无关字段，避免误判「内容已变化」。
     */
    private int stableCourseHash(String beanJson) {
        return CourseScheduleParser.stableHash(beanJson);
    }





    /**
     * 取消 mScheduledAlarmIds 中所有已调度的课前提醒 AlarmManager 闹钟。
     * 不影响静音闹钟（静音功能独立于自定义提醒开关）。
     */
    private void cancelAllScheduledAlarms(Context ctx) {
        java.util.Set<Integer> idsToCancel = loadScheduledIds(ctx, KEY_SCHEDULED_ALARM_IDS);
        try {
            for (int id : idsToCancel) {
                Intent dummy = createServiceIntent(ACTION_COURSE_REMINDER);
                AlarmScheduler.cancelAlarmClock(
                        ctx, dummy, id,
                        ACTION_COURSE_REMINDER, TARGET_PACKAGE, id | 0x50000000,
                        true);
            }
            if (!idsToCancel.isEmpty()) {
                XposedBridge.log(TAG + ": 已取消 " + idsToCancel.size() + " 个课前提醒闹钟");
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": cancelAllScheduledAlarms 失败 → " + e.getMessage());
        }
        synchronized (mScheduledIdsLock) {
            mScheduledAlarmIds.clear();
            saveScheduledIds(ctx, KEY_SCHEDULED_ALARM_IDS, mScheduledAlarmIds);
        }
    }

    /**
     * 精确清理：遍历通知栏，如果发现某通知是我方发出的，但 ID 不在有效集合内，则取消它。
     * 存在于有效集合内的通知不主动取消，由后续 notify() 平滑更新，避免闪烁。
     */
    private void cancelStaleNotifications(Context ctx, java.util.Set<Integer> validIds) {
        try {
            android.app.NotificationManager nm = ctx.getSystemService(android.app.NotificationManager.class);
            if (nm == null) return;
            int count = 0;
            for (android.service.notification.StatusBarNotification sbn : nm.getActiveNotifications()) {
                android.app.Notification n = sbn.getNotification();
                String ch = safeStr(n.getChannelId());
                boolean isOurs = (n.extras != null && n.extras.containsKey(KEY_FOCUS_PARAM))
                        || (n.extras != null && n.extras.containsKey("xiaoai.test.course_name"))
                        || "xiaoai_course_reminder_alert".equals(ch)
                        || ISLAND_UPDATE_CHANNEL.equals(ch)
                        || ch.contains("COURSE_SCHEDULER_REMINDER");
                boolean isManualTest = (n.extras != null && n.extras.getBoolean(KEY_TEST_NOTIF_MARKER, false))
                        || safeStr(sbn.getTag()).startsWith("xiaoai_test_");
                        
                if (isOurs) {
                    if (isManualTest) continue;
                    int id = sbn.getId();
                    // 展示 id 可能是连续课程换发的派生 id，凭映射归位到规范 alarmId 判定是否仍属今日合法课程，
                    // 避免把刚换发的在岛通知当作残留误删（也覆盖课程数据变化后该课不再判定为"连续"的情形）。
                    Integer autoId = mNotifAutomationById.get(id);
                    boolean stillValid = validIds.contains(id)
                            || (autoId != null && validIds.contains(autoId));
                    if (!stillValid) {
                        nm.cancel(sbn.getTag(), id);
                        mNotifCourseOwner.remove(id);
                        mNotifAutomationById.remove(id);
                        count++;
                    }
                }
            }
            if (count > 0) XposedBridge.log(TAG + ": 已精确清理 " + count + " 个旧课表残留通知");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": cancelStaleNotifications 失败 -> " + t.getMessage());
        }
    }

    /** 取消所有静音 / 取消静音闹钟。 */
    private void cancelAllMuteAlarms(Context ctx) {
        java.util.Set<Integer> idsToCancel = loadScheduledIds(ctx, KEY_SCHEDULED_MUTE_IDS);
        try {
            for (int id : idsToCancel) {
                for (String action : new String[]{ACTION_DO_MUTE, ACTION_DO_UNMUTE, ACTION_DO_DND_ON, ACTION_DO_DND_OFF}) {
                    int reqCode = AlarmScheduler.reqCodeForMuteAction(id, action);
                    Intent dummy = createServiceIntent(action);
                    AlarmScheduler.cancelAlarmClock(
                            ctx, dummy, reqCode,
                            action, TARGET_PACKAGE, reqCode | 0x40000000,
                            true);
                }
            }
            if (!idsToCancel.isEmpty()) {
                XposedBridge.log(TAG + ": 已取消 " + idsToCancel.size() + " 个静音闹钟");
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": cancelAllMuteAlarms 失败 → " + e.getMessage());
        }
        synchronized (mScheduledIdsLock) {
            mScheduledMuteIds.clear();
            saveScheduledIds(ctx, KEY_SCHEDULED_MUTE_IDS, mScheduledMuteIds);
        }
    }

    /** 设置上课静音闹钟，发给 voiceassist 自身（系统应用，不受 MIUI 电池优化限制）。
     * reqCode = (alarmId & 0x00FFFFFF) | 0x01000000，与课前提醒不重叠。 */
    private void scheduleMuteAlarm(Context ctx, String courseName, long triggerMs, int alarmId) {
        try {
            int reqCode = AlarmScheduler.reqCodeForMuteAction(alarmId, ACTION_DO_MUTE);
            Intent intent = createServiceIntent(ACTION_DO_MUTE);
            intent.putExtra("course_name", courseName);
            intent.putExtra("automation_alarm_id", alarmId);
            boolean scheduled = AlarmScheduler.scheduleAlarmClock(
                    ctx, intent, reqCode,
                    ACTION_DO_MUTE, TARGET_PACKAGE, reqCode | 0x40000000,
                    true, triggerMs);
            if (!scheduled) {
                XposedBridge.log(TAG + ": scheduleMuteAlarm 跳过：AlarmManager 不可用");
                return;
            }
            synchronized (mScheduledIdsLock) {
                mScheduledMuteIds.add(alarmId);
                saveScheduledIds(ctx, KEY_SCHEDULED_MUTE_IDS, mScheduledMuteIds);
            }
            long secsLeft = (triggerMs - System.currentTimeMillis()) / 1_000;
            XposedBridge.log(TAG + ": 静音闹钟已设(AlarmClock) " + courseName + " 约 " + secsLeft + " 秒后触发");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": scheduleMuteAlarm 失败 → " + e.getMessage());
        }
    }

    /**
     * 设置次日 00:01 的跨日重调闹钟。
     * 链式调用：每次触发后在 ACTION_RESCHEDULE_DAILY handler 内再次调用本方法，
     * 确保功能永久生效，无需重启 voiceassist。
     * reqCode 固定为 0x99000001，不与课程/静音 alarm 冲突。
     */
    private void scheduleMidnightReschedule(Context ctx) {
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 1);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            long triggerMs = cal.getTimeInMillis();
            Intent intent = createServiceIntent(ACTION_RESCHEDULE_DAILY);
            boolean scheduled = AlarmScheduler.scheduleAlarmClock(
                    ctx, intent, 0x99000001,
                    ACTION_RESCHEDULE_DAILY, TARGET_PACKAGE, 0x99000002,
                    false, triggerMs);
            if (!scheduled) {
                XposedBridge.log(TAG + ": scheduleMidnightReschedule 跳过：AlarmManager 不可用");
                return;
            }
            String fmt = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(new java.util.Date(triggerMs));
            XposedBridge.log(TAG + ": 跨日重调闹钟已设(AlarmClock) → " + fmt);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": scheduleMidnightReschedule 失败 → " + e.getMessage());
        }
    }

    /** 设置下课取消静音闹钟。reqCode = (alarmId & 0x00FFFFFF) | 0x02000000。 */
    private void scheduleUnmuteAlarm(Context ctx, String courseName, long triggerMs, int alarmId) {
        try {
            int reqCode = AlarmScheduler.reqCodeForMuteAction(alarmId, ACTION_DO_UNMUTE);
            Intent intent = createServiceIntent(ACTION_DO_UNMUTE);
            intent.putExtra("course_name", courseName);
            intent.putExtra("automation_alarm_id", alarmId);
            boolean scheduled = AlarmScheduler.scheduleAlarmClock(
                    ctx, intent, reqCode,
                    ACTION_DO_UNMUTE, TARGET_PACKAGE, reqCode | 0x40000000,
                    true, triggerMs);
            if (!scheduled) {
                XposedBridge.log(TAG + ": scheduleUnmuteAlarm 跳过：AlarmManager 不可用");
                return;
            }
            synchronized (mScheduledIdsLock) {
                mScheduledMuteIds.add(alarmId);
                saveScheduledIds(ctx, KEY_SCHEDULED_MUTE_IDS, mScheduledMuteIds);
            }
            long secsLeft = (triggerMs - System.currentTimeMillis()) / 1_000;
            XposedBridge.log(TAG + ": 取消静音闹钟已设(AlarmClock) " + courseName + " 约 " + secsLeft + " 秒后触发");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": scheduleUnmuteAlarm 失败 → " + e.getMessage());
        }
    }

    /** 设置上课开启勿扰闹钟。reqCode = (alarmId & 0x00FFFFFF) | 0x03000000。 */
    private void scheduleDndOnAlarm(Context ctx, String courseName, long triggerMs, int alarmId) {
        try {
            int reqCode = AlarmScheduler.reqCodeForMuteAction(alarmId, ACTION_DO_DND_ON);
            Intent intent = createServiceIntent(ACTION_DO_DND_ON);
            intent.putExtra("course_name", courseName);
            intent.putExtra("automation_alarm_id", alarmId);
            boolean scheduled = AlarmScheduler.scheduleAlarmClock(
                    ctx, intent, reqCode,
                    ACTION_DO_DND_ON, TARGET_PACKAGE, reqCode | 0x40000000,
                    true, triggerMs);
            if (!scheduled) {
                XposedBridge.log(TAG + ": scheduleDndOnAlarm 跳过：AlarmManager 不可用");
                return;
            }
            synchronized (mScheduledIdsLock) {
                mScheduledMuteIds.add(alarmId);
                saveScheduledIds(ctx, KEY_SCHEDULED_MUTE_IDS, mScheduledMuteIds);
            }
            long secsLeft = (triggerMs - System.currentTimeMillis()) / 1_000;
            XposedBridge.log(TAG + ": 开启勿扰闹钟已设(AlarmClock) " + courseName + " 约 " + secsLeft + " 秒后触发");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": scheduleDndOnAlarm 失败 → " + e.getMessage());
        }
    }

    /** 设置下课关闭勿扰闹钟。reqCode = (alarmId & 0x00FFFFFF) | 0x04000000。 */
    private void scheduleDndOffAlarm(Context ctx, String courseName, long triggerMs, int alarmId) {
        try {
            int reqCode = AlarmScheduler.reqCodeForMuteAction(alarmId, ACTION_DO_DND_OFF);
            Intent intent = createServiceIntent(ACTION_DO_DND_OFF);
            intent.putExtra("course_name", courseName);
            intent.putExtra("automation_alarm_id", alarmId);
            boolean scheduled = AlarmScheduler.scheduleAlarmClock(
                    ctx, intent, reqCode,
                    ACTION_DO_DND_OFF, TARGET_PACKAGE, reqCode | 0x40000000,
                    true, triggerMs);
            if (!scheduled) {
                XposedBridge.log(TAG + ": scheduleDndOffAlarm 跳过：AlarmManager 不可用");
                return;
            }
            synchronized (mScheduledIdsLock) {
                mScheduledMuteIds.add(alarmId);
                saveScheduledIds(ctx, KEY_SCHEDULED_MUTE_IDS, mScheduledMuteIds);
            }
            long secsLeft = (triggerMs - System.currentTimeMillis()) / 1_000;
            XposedBridge.log(TAG + ": 关闭勿扰闹钟已设(AlarmClock) " + courseName + " 约 " + secsLeft + " 秒后触发");
        } catch (Exception e) {
            XposedBridge.log(TAG + ": scheduleDndOffAlarm 失败 → " + e.getMessage());
        }
    }

    /** 在 voiceassist 进程内执行静音/恢复铃声。 */
    private void applyMuteState(Context ctx, boolean mute, String courseName) {
        String modeTip = mute ? "静音" : "恢复铃声";
        AudioManager audioMgr = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int beforeMode = audioMgr != null ? audioMgr.getRingerMode() : -1;
        boolean ok = MiuiSettingsInvoker.applyMute(ctx, mute);
        if (ok) {
            if (mute) {
                // 仅在确实由模块从非静音切到静音时，标记为“模块执行”
                if (beforeMode != AudioManager.RINGER_MODE_SILENT) {
                    setModuleMuteApplied(ctx, true);
                }
            } else {
                setModuleMuteApplied(ctx, false);
            }
        }
        XposedBridge.log(TAG + ": [" + modeTip + "] MiuiSettingsInvoker " + (ok ? "成功" : "失败 ← " + courseName));
    }

    /** 在 voiceassist 进程内开启/关闭勿扰（DND）模式。 */
    private void applyDndState(Context ctx, boolean enable, String courseName) {
        String modeTip = enable ? "开启勿扰" : "关闭勿扰";
        android.app.NotificationManager nm =
                ctx.getSystemService(android.app.NotificationManager.class);
        int beforeFilter = nm != null
                ? nm.getCurrentInterruptionFilter()
                : android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        boolean ok = MiuiSettingsInvoker.applyDnd(ctx, enable);
        if (ok) {
            if (enable) {
                // 仅在确实由模块从非勿扰切到勿扰时，标记为“模块执行”
                if (beforeFilter != android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                    setModuleDndApplied(ctx, true);
                }
            } else {
                setModuleDndApplied(ctx, false);
            }
        }
        XposedBridge.log(TAG + ": [" + modeTip + "] MiuiSettingsInvoker " + (ok ? "成功" : "失败 ← " + courseName));
    }

    private void setModuleMuteApplied(Context ctx, boolean applied) {
        try {
            ctx.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_RUNTIME_MODULE_MUTE_APPLIED, applied)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void setModuleDndApplied(Context ctx, boolean applied) {
        try {
            ctx.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_RUNTIME_MODULE_DND_APPLIED, applied)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 独立读取 CourseData 并调度今日静音/取消静音闹钟。
     * 完全独立于自定义提醒开关，两者可单独启用。
     */
    private void scheduleTodayMuteAlarms(Context ctx) {
        scheduleTodayMuteAlarms(ctx, false);
    }

    /**
     * @param skipImmediate 为 true 时跳过“课中立即静音/勿扰”逻辑，
     *                      仅重新调度未来闹钟。
     */
    private void scheduleTodayMuteAlarms(Context ctx, boolean skipImmediate) {
        cancelAllMuteAlarms(ctx);
        if (!sMuteEnabled && !sUnmuteEnabled && !sDndEnabled && !sUnDndEnabled) {
            return;
        }
        try {
            java.text.SimpleDateFormat dateFmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            String todayDateStr = dateFmt.format(new java.util.Date());
            if (HolidayManager.isHoliday(todayDateStr)) {
                XposedBridge.log(TAG + ": 静音/勿扰：今日 " + todayDateStr + " 为节假日，跳过调度");
                return;
            }
            HolidayManager.HolidayEntry workSwap = HolidayManager.getWorkSwap(todayDateStr);

            SharedPreferences prefs = getConfigPrefs();
            String beanJson = readActiveCourseBeanJson(ctx, prefs);
            if (beanJson == null || beanJson.isEmpty()) {
                return;
            }

            java.util.Calendar cal = java.util.Calendar.getInstance();
            int calDay = cal.get(java.util.Calendar.DAY_OF_WEEK);
            int calTodayDay = (calDay == java.util.Calendar.SUNDAY) ? 7 : (calDay - 1);
            int todayDay = (workSwap != null && workSwap.followWeekday > 0)
                    ? workSwap.followWeekday : calTodayDay;

            CourseScheduleParser.ParsedSchedule parsed = CourseScheduleParser.parse(beanJson);
            int baseWeek = parsed.presentWeek;
            // 学期外忽略调休的周数覆盖：越界周不匹配任何课程，自然不会排静音/勿扰闹钟
            boolean outOfTerm = parsed.isTermEnded() || parsed.isTermNotStarted();
            final int currentWeek = (!outOfTerm && workSwap != null && workSwap.followWeek > 0)
                    ? workSwap.followWeek : baseWeek;

            int muteMinsBefore = readConfigInt(prefs, KEY_MUTE_MINS_BEFORE, DEFAULT_MUTE_MINS_BEFORE);
            int unmuteMinsAfter = readConfigInt(prefs, KEY_UNMUTE_MINS_AFTER, DEFAULT_UNMUTE_MINS_AFTER);
            int dndMinsBefore = readConfigInt(prefs, KEY_DND_MINS_BEFORE, DEFAULT_DND_MINS_BEFORE);
            int unDndMinsAfter = readConfigInt(prefs, KEY_UNDND_MINS_AFTER, DEFAULT_UNDND_MINS_AFTER);
            long nowMs = System.currentTimeMillis();
            int count = 0;

            for (CourseScheduleParser.CourseSlot course : parsed.courses) {
                if (course.day != todayDay) continue;
                if (!course.isInWeek(currentWeek)) continue;
                String startTime = course.startTime;
                String endTime = course.endTime;
                if (startTime.isEmpty() || endTime.isEmpty()) continue;

                long startMs = computeClassStartMs(startTime);
                long endMs = computeClassStartMs(endTime);
                if (startMs < 0 || endMs < 0) continue;

                String courseName = course.courseName;
                int alarmId = buildCourseNotificationId(
                        courseName, startTime, endTime, course.classroom, course.sectionRange, course.teacher);
                if (isAutomationSkippedToday(ctx, alarmId)) {
                    XposedBridge.log(TAG + ": [逃课] 跳过该课自动化调度 alarmId=" + alarmId
                            + " " + courseName + "@" + startTime);
                    continue;
                }

                if (sMuteEnabled) {
                    long muteTriggerMs = startMs - (long) muteMinsBefore * 60_000L;
                    if (muteTriggerMs <= nowMs && nowMs < endMs && !skipImmediate && sRepostEnabled) {
                        AudioManager audioMgr = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                        if (audioMgr != null && audioMgr.getRingerMode() != AudioManager.RINGER_MODE_SILENT) {
                            applyMuteState(ctx, true, courseName);
                        } else {
                            XposedBridge.log(TAG + ": [跳过静音] 已处于静音状态 " + courseName);
                        }
                    } else if (muteTriggerMs > nowMs) {
                        scheduleMuteAlarm(ctx, courseName, muteTriggerMs, alarmId);
                        count++;
                    }
                }
                if (sUnmuteEnabled) {
                    long unmuteTriggerMs = endMs + (long) unmuteMinsAfter * 60_000L;
                    if (unmuteTriggerMs > nowMs) {
                        scheduleUnmuteAlarm(ctx, courseName, unmuteTriggerMs, alarmId);
                        count++;
                    }
                }
                if (sDndEnabled) {
                    long dndTriggerMs = startMs - (long) dndMinsBefore * 60_000L;
                    if (dndTriggerMs <= nowMs && nowMs < endMs && !skipImmediate && sRepostEnabled) {
                        android.app.NotificationManager dndNm =
                                ctx.getSystemService(android.app.NotificationManager.class);
                        if (dndNm != null && dndNm.getCurrentInterruptionFilter()
                                != android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                            applyDndState(ctx, true, courseName);
                        } else {
                            XposedBridge.log(TAG + ": [跳过勿扰] 已处于勿扰状态 " + courseName);
                        }
                    } else if (dndTriggerMs > nowMs) {
                        scheduleDndOnAlarm(ctx, courseName, dndTriggerMs, alarmId);
                        count++;
                    }
                }
                if (sUnDndEnabled) {
                    long unDndTriggerMs = endMs + (long) unDndMinsAfter * 60_000L;
                    if (unDndTriggerMs > nowMs) {
                        scheduleDndOffAlarm(ctx, courseName, unDndTriggerMs, alarmId);
                        count++;
                    }
                }
            }
            XposedBridge.log(TAG + ": 静音/勿扰闹钟已调度 " + count + " 个");
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": scheduleTodayMuteAlarms 失败 -> " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 自动叫醒：向 deskclock 进程发送广播，携带今日首节课时间
    // ═══════════════════════════════════════════════════════════════

    /**
     * 将今日课程原始数据 + 用户叫醒配置打包发给 deskclock 进程。
     * 所有课程解析、判断是否有课、决定时间的逻辑全部由 DeskClockHook 在 deskclock 进程内完成。
     * 叫醒关闭时发送 clear_only；旧闹钟清理和目标时间校验由 DeskClockHook 处理。
     */
    private void scheduleTodayWakeupAlarms(Context ctx) {
        if (!sWakeupMorningEnabled && !sWakeupAfternoonEnabled) {
            sendClearClockAlarms(ctx);
            return;
        }
        try {
            // ── 节假日 / 调休检查（与课前提醒逻辑一致）──
            java.text.SimpleDateFormat dateFmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            String todayDateStr = dateFmt.format(new java.util.Date());
            if (HolidayManager.isHoliday(todayDateStr)) {
                XposedBridge.log(TAG + ": 叫醒：今日 " + todayDateStr + " 为节假日，清除叫醒闹钟");
                sendClearClockAlarms(ctx);
                return;
            }
            HolidayManager.HolidayEntry workSwap = HolidayManager.getWorkSwap(todayDateStr);

            SharedPreferences sourcePrefs = getConfigPrefs();
            String beanJson = readActiveCourseBeanJson(ctx, sourcePrefs);
            if (beanJson == null || beanJson.isEmpty()) {
                sendClearClockAlarms(ctx);
                return;
            }

            // ── 解决进程未启动问题 ──
            // 1. Tickle：通过查询 ContentProvider 迫使 deskclock 进程启动
            tickleDeskClock(ctx);

            // 2. 延时 1s 发送：确保 deskclock 进程完成初始化并进入 Looper 循环
            getRescheduleHandler().postDelayed(() -> {
                try {
                    SharedPreferences prefs = getConfigPrefs();
                    Intent schedIntent = new Intent(ACTION_SCHEDULE_CLOCK_ALARMS);
                    schedIntent.setPackage(DESKCLOCK_PKG);
                    // 关键标志位：允许触发已停止的应用，且提高接收优先级
                    schedIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
                    
                    // 原始课程数据（deskclock 侧自行解析）
                    schedIntent.putExtra("bean_json", beanJson);
                    // 调休工作日：传入覆盖的 todayDay / currentWeek，供 DeskClockHook 使用
                    if (workSwap != null && workSwap.followWeek > 0 && workSwap.followWeekday > 0) {
                        schedIntent.putExtra("today_day_override",    workSwap.followWeekday);
                        schedIntent.putExtra("current_week_override", workSwap.followWeek);
                    }
                    // 上午配置
                    if (sWakeupMorningEnabled) {
                        schedIntent.putExtra("morning_enabled",      true);
                        schedIntent.putExtra("morning_last_sec",     readConfigInt(prefs, KEY_WAKEUP_MORNING_LAST_SEC, DEFAULT_WAKEUP_MORNING_LAST_SEC));
                        schedIntent.putExtra("morning_rules_json",   readConfigString(prefs, KEY_WAKEUP_MORNING_RULES_JSON, DEFAULT_WAKEUP_MORNING_RULES_JSON));
                    }
                    // 下午配置
                    if (sWakeupAfternoonEnabled) {
                        schedIntent.putExtra("afternoon_enabled",    true);
                        schedIntent.putExtra("afternoon_first_sec",  readConfigInt(prefs, KEY_WAKEUP_AFTERNOON_FIRST_SEC, DEFAULT_WAKEUP_AFTERNOON_FIRST_SEC));
                        schedIntent.putExtra("afternoon_rules_json", readConfigString(prefs, KEY_WAKEUP_AFTERNOON_RULES_JSON, DEFAULT_WAKEUP_AFTERNOON_RULES_JSON));
                    }
                    ctx.sendBroadcast(schedIntent);
                    XposedBridge.log(TAG + ": 叫醒配置已转发给 deskclock (已延迟 1s)");
                } catch (Throwable e) {
                    XposedBridge.log(TAG + ": postDelayed scheduleWakeup 失败 → " + e.getMessage());
                }
            }, 1000);

        } catch (Throwable e) {
            XposedBridge.log(TAG + ": scheduleTodayWakeupAlarms 失败 → " + e.getMessage());
        }
    }

    /** 通过查询 ContentProvider 迫使 deskclock 进程拉起 */
    private void tickleDeskClock(Context ctx) {
        try {
            // 注意：URI 必须与 DeskClockHook 中的 getAlarmContentUri 匹配
            Uri uri = Uri.parse("content://com.android.deskclock/alarm");
            android.database.Cursor c = ctx.getContentResolver().query(uri, 
                    new String[]{"_id"}, null, null, "_id LIMIT 1");
            if (c != null) {
                c.close();
                XposedBridge.log(TAG + ": [Tickle] DeskClock 进程已试探触发");
            }
        } catch (Throwable e) {
            // 即使失败（如无权限）也可能是系统限制，通常足以拉起进程
            XposedBridge.log(TAG + ": [Tickle] DeskClock 试探失败（正常现象）→ " + e.getMessage());
        }
    }

    /** 向 deskclock 进程发广播，仅清除之前创建的叫醒闹钟 */
    private void sendClearClockAlarms(Context ctx) {
        tickleDeskClock(ctx);
        getRescheduleHandler().postDelayed(() -> {
            try {
                Intent i = new Intent(ACTION_SCHEDULE_CLOCK_ALARMS);
                i.setPackage(DESKCLOCK_PKG);
                i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
                i.putExtra("clear_only", true);
                ctx.sendBroadcast(i);
            } catch (Throwable ignored) {}
        }, 500); // 清理逻辑延迟稍短即可
    }


    /**
     * 用 FileObserver 监听 CourseData.xml 的写入（包括跨进程写入）。
     * Android SP 采用原子 rename（.bak → 目标文件），故监听 shared_prefs/ 目录的
     * MOVED_TO 事件（同时保留 CLOSE_WRITE 兜底），过滤文件名 CourseData.xml。
     */
    private void registerCourseDataListener(Context ctx) {
        if (mCourseDataObserver != null) return; // 已注册，防重复

        // voiceassist 自有数据目录下的 shared_prefs/
        java.io.File prefsDir = new java.io.File(ctx.getApplicationInfo().dataDir, "shared_prefs");

        mCourseDataObserver = new android.os.FileObserver(
                prefsDir,
                android.os.FileObserver.MOVED_TO | android.os.FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, String path) {
                if (path == null || !path.equals("CourseData.xml")) return;
                if (!isXiaoaiDataSource(getConfigPrefs())) return;
                // 防抖：移除上次未执行的任务，延迟 1500ms 执行
                getRescheduleHandler().removeCallbacksAndMessages(mRescheduleToken);
                getRescheduleHandler().postDelayed(() -> {
                    // ── 回调层统一哈希去重：课前提醒 + 静音两条路径共享同一份检查 ──
                    // 只在这里【读取并比较】哈希；mLastCourseDataHash 的【更新】由 scheduleTodayCourseReminders
                    // 负责（RESCHEDULE_DAILY / SYNC_PREFS 强制重调时也会先重置为 0）。
                    // 若只开静音（sCustomReminderEnabled=false），mLastCourseDataHash 由 init block 初始化。
                    String bj = null;
                    try {
                        bj = readCourseDataBean(ctx);
                        if (bj != null && !bj.isEmpty()) {
                            int h = stableCourseHash(bj);
                            if (h == mLastCourseDataHash) {
                                XposedBridge.log(TAG + ": [FileObserver] 内容未变化，跳过重调度（hash=" + h + "）");
                                return;
                            }
                            // 内容已变化：更新哈希，后续两个调度函数均需执行
                            mLastCourseDataHash = h;
                        }
                    } catch (Throwable ignored) {}
                    XposedBridge.log(TAG + ": CourseData.xml 已变化，重新调度课前提醒");
                    scheduleTodayCourseReminders(ctx, bj);
                    scheduleTodayMuteAlarms(ctx);
                }, mRescheduleToken, RESCHEDULE_DEBOUNCE_MS);
            }
        };
        mCourseDataObserver.startWatching();
        XposedBridge.log(TAG + ": CourseData FileObserver 已启动，监控目录: " + prefsDir.getAbsolutePath());
    }

    /**
     * Hook NotificationManager 的两个 notify 重载，在通知发出前注入岛参数。
     */
    private void hookNotifyMethods(ClassLoader classLoader) {

        // ① notify(int id, Notification notification)
        try {
            findAndHookMethod(
                    "android.app.NotificationManager",
                    classLoader,
                    "notify",
                    int.class,
                    Notification.class,
                    new NotifyHook(1) // notification 在 args[1]
            );
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": Hook notify(int,Notification) 失败 → " + e.getMessage());
        }

        // ② notify(String tag, int id, Notification notification)
        try {
            findAndHookMethod(
                    "android.app.NotificationManager",
                    classLoader,
                    "notify",
                    String.class,
                    int.class,
                    Notification.class,
                    new NotifyHook(2) // notification 在 args[2]
            );
        } catch (Throwable e) {
            XposedBridge.log(TAG + ": Hook notify(String,int,Notification) 失败 → " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部 Hook 实现
    // ═══════════════════════════════════════════════════════════════

    /** 拦截通知发送，并按课程内容注入超级岛参数。 */
    private class NotifyHook extends XC_MethodHook {

        private final int notifArgIndex;

        /** @param notifArgIndex Notification 对象在 args 数组中的下标 */
        NotifyHook(int notifArgIndex) {
            this.notifArgIndex = notifArgIndex;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Notification notification = (Notification) param.args[notifArgIndex];
            if (notification == null) return;

            // 我方通知（已含岛参数）直接放行
            if (isAlreadyIsland(notification)) return;

            // 我方课程通知（携带课程标记）直接放行
            if (notification.extras != null
                    && notification.extras.containsKey("xiaoai.test.course_name")) return;

            // 小爱原生课程提醒 → 屏蔽，由我方通知替代
            if (isScheduleNotification(notification)) {
                param.setResult(null);
                XposedBridge.log(TAG + ": 已屏蔽小爱原生课程提醒通知");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 通知识别逻辑
    // ─────────────────────────────────────────────────────────────

    /**
     * 判断该通知是否已经携带超级岛参数（避免重复注入）。
     */
    private boolean isAlreadyIsland(Notification notification) {
        if (notification.extras == null) return false;
        return notification.extras.containsKey(KEY_FOCUS_PARAM);
    }

    /**
     * 判断是否为课程表提醒通知。
     * 实测 logcat 确认 channelId = {@code "COURSE_SCHEDULER_REMINDER_sound"}。
     */
    private boolean isScheduleNotification(Notification notification) {
        String channelId = safeStr(notification.getChannelId());
        // 匹配 voiceassist 自身的课程提醒渠道，以及我们自己的独立提醒渠道
        boolean hit = channelId.contains("COURSE_SCHEDULER_REMINDER")
                || channelId.equals("xiaoai_course_reminder_alert");
        if (hit) XposedBridge.log(TAG + ": 命中 channelId=" + channelId);
        return hit;
    }

    // ─────────────────────────────────────────────────────────────
    // 超级岛参数注入
    // ─────────────────────────────────────────────────────────────


    /** 隐藏时持久化课程快照，恢复展示不再依赖通知栏中存在源通知。 */
    private static final class Presentation {
        String key;
        CourseInfo info;
        int id;
        String tag;
        int automationId;
        int day;
        long generation;
        long triggerMs;
        long startMs;
        long endMs;
        boolean consecutive;
        int previousId;
        boolean test;
        StageDisplayState state = new StageDisplayState();
    }

    private synchronized void loadPresentations(Context ctx) {
        if (mPresentationsLoaded) return;
        mPresentationsLoaded = true;
        SharedPreferences runtime = ctx.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE);
        for (java.util.Map.Entry<String, ?> entry : runtime.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PRESENTATION_PREFIX)) continue;
            try {
                JSONObject json = new JSONObject((String) entry.getValue());
                Presentation p = new Presentation();
                p.key = entry.getKey();
                p.info = new CourseInfo(json.getString("name"), json.getString("start"),
                        json.getString("end"), json.getString("room"),
                        json.optString("section"), json.optString("teacher"));
                p.id = json.getInt("id");
                p.tag = json.isNull("tag") ? null : json.optString("tag");
                p.automationId = json.getInt("automation");
                p.day = json.getInt("day");
                p.generation = json.getLong("generation");
                p.triggerMs = json.getLong("trigger");
                p.startMs = json.getLong("startMs");
                p.endMs = json.getLong("endMs");
                p.consecutive = json.optBoolean("consecutive");
                p.previousId = json.optInt("previous", -1);
                p.test = json.optBoolean("test");
                p.state = StageDisplayState.decode(json.optString("state"));
                mPresentations.put(p.key, p);
                if (p.day != getTodayDayMarker()) {
                    stopPresentation(ctx, p);
                    runtime.edit().remove(p.key).apply();
                    mPresentations.remove(p.key);
                } else if (!p.state.terminated) {
                    mNotifCourseOwner.put(p.id, p.info.courseName);
                    mNotifAutomationById.put(p.id, p.automationId);
                    expirePresentationIfDue(ctx, p);
                    schedulePresentationBoundaries(ctx, p);
                    schedulePresentationHide(ctx, p);
                }
            } catch (Exception e) {
                runtime.edit().remove(entry.getKey()).apply();
                XposedBridge.log(TAG + ": 恢复阶段状态失败 -> " + e.getMessage());
            }
        }
    }

    private synchronized void savePresentation(Context ctx, Presentation p) {
        try {
            JSONObject json = new JSONObject();
            json.put("name", p.info.courseName).put("start", p.info.startTime)
                    .put("end", p.info.endTime).put("room", p.info.classroom)
                    .put("section", p.info.sectionRange).put("teacher", p.info.teacher)
                    .put("id", p.id).put("tag", p.tag == null ? JSONObject.NULL : p.tag)
                    .put("automation", p.automationId).put("day", p.day)
                    .put("generation", p.generation).put("trigger", p.triggerMs)
                    .put("startMs", p.startMs).put("endMs", p.endMs)
                    .put("consecutive", p.consecutive).put("previous", p.previousId)
                    .put("test", p.test).put("state", p.state.encode());
            ctx.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE)
                    .edit().putString(p.key, json.toString()).apply();
        } catch (Exception e) {
            XposedBridge.log(TAG + ": 保存阶段状态失败 -> " + e.getMessage());
        }
    }

    private synchronized Presentation preparePresentation(Context ctx, CourseInfo info, int id,
            String tag, int automationId, long triggerMs, boolean consecutive,
            int previousId, boolean test) {
        loadPresentations(ctx);
        String key = PRESENTATION_PREFIX + getTodayDayMarker() + "_"
                + (test ? safeStr(tag) : readCourseSource(getConfigPrefs()) + "_" + automationId);
        Presentation p = mPresentations.get(key);
        if (p == null) {
            for (Presentation previous : new java.util.ArrayList<>(mPresentations.values())) {
                if (test ? previous.test : (!previous.test && previous.id == id)) {
                    stopPresentation(ctx, previous);
                    mPresentations.remove(previous.key);
                    ctx.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE)
                            .edit().remove(previous.key).apply();
                }
            }
            p = new Presentation();
            p.key = key;
            p.info = info;
            p.id = id;
            p.tag = tag;
            p.automationId = automationId;
            p.day = getTodayDayMarker();
            p.generation = System.currentTimeMillis();
            p.startMs = computeClassStartMs(info.startTime);
            p.endMs = computeClassStartMs(info.endTime);
            p.test = test;
            mPresentations.put(key, p);
            // 升级前的取消闹钟必须停止，且旧广播已在接收端禁用。
            for (int i = 3; i <= 5; i++) {
                int requestCode = id * 10 + i;
                AlarmScheduler.cancelAlarmClock(ctx, createServiceIntent(ACTION_NOTIF_CANCEL),
                        requestCode, ACTION_NOTIF_CANCEL, TARGET_PACKAGE,
                        requestCode | 0x60000000, false);
            }
            Notification existing = findPresentationNotification(ctx, p);
            if (existing != null) {
                int stage = currentPresentationStage(p, System.currentTimeMillis());
                p.state.enter(stage, true, readStageDurationMs(stage),
                        System.currentTimeMillis(), true);
            }
        }
        p.triggerMs = triggerMs;
        p.consecutive = consecutive;
        p.previousId = previousId;
        savePresentation(ctx, p);
        schedulePresentationBoundaries(ctx, p);
        if (p.state.stage >= 0 && (p.state.enabled != readStageEnabled(p.state.stage)
                || p.state.durationMs != readStageDurationMs(p.state.stage))) {
            displayPresentation(ctx, p, true);
        }
        return p;
    }

    private int currentPresentationStage(Presentation p, long now) {
        return StageDisplayState.stageAt(now, p.startMs, p.endMs);
    }

    private boolean readStageEnabled(int stage) {
        return readConfigBool(getConfigPrefs(), ConfigDefaults.stageEnabledKey(stage), true);
    }

    private long readStageDurationMs(int stage) {
        return PrefsAccess.readStageDurationMs(getConfigPrefs(), stage);
    }

    private Intent presentationIntent(Presentation p, String action, int stage) {
        Intent intent = createServiceIntent(action);
        intent.setData(Uri.parse("xiaoaiisland://presentation/" + Uri.encode(p.key) + "/" + stage));
        intent.putExtra("presentation_key", p.key);
        intent.putExtra("generation", p.generation);
        intent.putExtra("state", stage);
        intent.putExtra("revision", p.state.revision);
        return intent;
    }

    private synchronized void schedulePresentationBoundaries(Context ctx, Presentation p) {
        if (p.state.terminated) return;
        long[] times = {p.triggerMs, p.startMs, p.endMs};
        for (int stage = 0; stage < times.length; stage++) {
            String action = stage == 0 ? ACTION_COURSE_REMINDER : ACTION_ISLAND_UPDATE;
            int requestCode = p.id * 10 + stage;
            Intent intent = presentationIntent(p, action, stage);
            if (times[stage] <= System.currentTimeMillis()) {
                AlarmScheduler.cancelAlarmClock(ctx, intent, requestCode, action,
                        TARGET_PACKAGE, requestCode | 0x60000000, false);
                continue;
            }
            boolean scheduled = AlarmScheduler.scheduleAlarmClock(ctx, intent, requestCode,
                    action, TARGET_PACKAGE, requestCode | 0x60000000, false, times[stage]);
            if (stage == 0 && scheduled) {
                synchronized (mScheduledIdsLock) {
                    mScheduledAlarmIds.add(p.automationId);
                    saveScheduledIds(ctx, KEY_SCHEDULED_ALARM_IDS, mScheduledAlarmIds);
                }
            }
        }
    }

    private synchronized void schedulePresentationHide(Context ctx, Presentation p) {
        if (!p.state.visible || p.state.terminated) return;
        if (p.state.deadlineMs <= System.currentTimeMillis()) {
            expirePresentationIfDue(ctx, p);
            return;
        }
        int requestCode = p.id * 10 + 3;
        AlarmScheduler.scheduleAlarmClock(ctx,
                presentationIntent(p, ACTION_STAGE_HIDE, p.state.stage), requestCode,
                ACTION_STAGE_HIDE, TARGET_PACKAGE, requestCode | 0x60000000,
                false, p.state.deadlineMs);
    }

    private synchronized Notification findPresentationNotification(Context ctx, Presentation p) {
        android.app.NotificationManager nm = ctx.getSystemService(android.app.NotificationManager.class);
        if (nm == null) return null;
        for (StatusBarNotification sbn : nm.getActiveNotifications()) {
            if (sbn.getId() == p.id && java.util.Objects.equals(sbn.getTag(), p.tag)) {
                return sbn.getNotification();
            }
        }
        return null;
    }

    private synchronized void hidePresentation(Context ctx, Presentation p) {
        android.app.NotificationManager nm = ctx.getSystemService(android.app.NotificationManager.class);
        if (nm == null) return;
        if (p.tag == null) nm.cancel(p.id);
        else nm.cancel(p.tag, p.id);
    }

    private synchronized void stopPresentation(Context ctx, Presentation p) {
        if (p.state.terminated) return;
        p.state.terminate();
        hidePresentation(ctx, p);
        for (int stage = 0; stage < 3; stage++) {
            String action = stage == 0 ? ACTION_COURSE_REMINDER : ACTION_ISLAND_UPDATE;
            int requestCode = p.id * 10 + stage;
            AlarmScheduler.cancelAlarmClock(ctx, presentationIntent(p, action, stage),
                    requestCode, action, TARGET_PACKAGE, requestCode | 0x60000000, false);
            requestCode = p.id * 10 + 3;
            AlarmScheduler.cancelAlarmClock(ctx, presentationIntent(p, ACTION_STAGE_HIDE, stage),
                    requestCode, ACTION_STAGE_HIDE, TARGET_PACKAGE, requestCode | 0x60000000, false);
        }
        mNotifCourseOwner.remove(p.id);
        mNotifAutomationById.remove(p.id);
        savePresentation(ctx, p);
    }

    private synchronized void expirePresentationIfDue(Context ctx, Presentation p) {
        if (p.state.expire(p.state.stage, p.state.revision, System.currentTimeMillis())
                == StageDisplayState.Effect.HIDE) {
            hidePresentation(ctx, p);
            if (p.state.stage == STATE_FINISHED) p.state.terminate();
            savePresentation(ctx, p);
        }
    }

    private synchronized void displayPresentation(Context ctx, Presentation p, boolean configurationChange) {
        long now = System.currentTimeMillis();
        if (p.state.terminated || p.day != getTodayDayMarker() || now < p.triggerMs) return;
        if (!p.test && isAutomationSkippedToday(ctx, p.automationId)) {
            stopPresentation(ctx, p);
            return;
        }
        expirePresentationIfDue(ctx, p);
        if (p.state.terminated) return;
        if (p.state.visible && findPresentationNotification(ctx, p) == null) {
            stopPresentation(ctx, p);
            return;
        }
        int stage = currentPresentationStage(p, now);
        // 相邻课程的先后顺序由实际开启阶段决定，不依赖两个闹钟的到达顺序。
        for (Presentation next : mPresentations.values()) {
            if (next.consecutive && next.previousId == p.automationId
                    && StageDisplayState.canTakeOver(now, next.triggerMs, next.state.terminated,
                            readStageEnabled(currentPresentationStage(next, now)))) {
                stopPresentation(ctx, p);
                return;
            }
        }
        StageDisplayState.Effect effect = p.state.enter(stage, readStageEnabled(stage),
                readStageDurationMs(stage), now, configurationChange);
        if (effect == StageDisplayState.Effect.HIDE) hidePresentation(ctx, p);
        if (effect == StageDisplayState.Effect.SHOW_ALERT || effect == StageDisplayState.Effect.SHOW_SILENT) {
            if (p.consecutive) {
                for (Presentation previous : mPresentations.values()) {
                    if (previous.automationId == p.previousId && !previous.test && !previous.state.terminated) {
                        stopPresentation(ctx, previous);
                    }
                }
            }
            publishPresentation(ctx, p, effect == StageDisplayState.Effect.SHOW_ALERT);
        }
        savePresentation(ctx, p);
        schedulePresentationHide(ctx, p);
    }

    private void publishPresentation(Context ctx, Presentation p, boolean alert) {
        android.app.NotificationManager nm = ctx.getSystemService(android.app.NotificationManager.class);
        if (nm == null) return;
        String channel = alert ? "xiaoai_course_reminder_alert" : ISLAND_UPDATE_CHANNEL;
        if (nm.getNotificationChannel(channel) == null) {
            android.app.NotificationChannel nc = new android.app.NotificationChannel(channel,
                    alert ? "课程提醒" : "岛状态更新",
                    alert ? android.app.NotificationManager.IMPORTANCE_HIGH : android.app.NotificationManager.IMPORTANCE_LOW);
            nc.enableVibration(alert);
            if (!alert) nc.setSound(null, null);
            nm.createNotificationChannel(nc);
        }
        Intent dismiss = presentationIntent(p, ACTION_PRESENTATION_DISMISS, p.state.stage);
        PendingIntent deleteIntent = PendingIntent.getService(ctx, p.id, dismiss,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = p.state.stage == STATE_COUNTDOWN
                ? "[" + p.info.courseName + "]快到了，提前准备一下吧" : p.info.courseName;
        Notification notification = new Notification.Builder(ctx, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(p.info.startTime + " - " + p.info.endTime + "  " + p.info.classroom)
                .setAutoCancel(true).setOnlyAlertOnce(!alert).setDeleteIntent(deleteIntent).build();
        notification.extras.putString("xiaoai.test.course_name", p.info.courseName);
        notification.extras.putString("xiaoai.test.start_time", p.info.startTime);
        notification.extras.putString("xiaoai.test.end_time", p.info.endTime);
        notification.extras.putString("xiaoai.test.classroom", p.info.classroom);
        notification.extras.putBoolean(KEY_TEST_NOTIF_MARKER, p.test);
        notification.extras.putAll(buildIslandExtras(p.info, p.state.stage, getConfigPrefs(),
                ctx, notification, p.id, p.tag, p.automationId));
        try {
            notification.contentIntent = PendingIntent.getActivity(ctx, 1, buildCourseOpenIntent(ctx, getConfigPrefs()),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } catch (Exception e) {
            XposedBridge.log(TAG + ": 构建课程跳转失败 -> " + e.getMessage());
        }
        if (p.tag == null) nm.notify(p.id, notification);
        else nm.notify(p.tag, p.id, notification);
        mNotifCourseOwner.put(p.id, p.info.courseName);
        mNotifAutomationById.put(p.id, p.automationId);
        XposedBridge.log(TAG + ": 阶段显示 state=" + p.state.stage + " alert=" + alert + " id=" + p.id);
    }

    private synchronized void handlePresentationUpdate(Context ctx, Intent intent) {
        loadPresentations(ctx);
        Presentation p = mPresentations.get(intent.getStringExtra("presentation_key"));
        if (p == null || p.generation != intent.getLongExtra("generation", -1)
                || p.state.terminated || intent.getIntExtra("state", -1) < p.state.stage) return;
        displayPresentation(ctx, p, false);
    }

    private synchronized void handlePresentationEnd(Context ctx, Intent intent, boolean dismissed) {
        loadPresentations(ctx);
        Presentation p = mPresentations.get(intent.getStringExtra("presentation_key"));
        if (p == null || p.generation != intent.getLongExtra("generation", -1)) return;
        if (dismissed) {
            // 旧阶段的清除事件不能终止已恢复的通知。
            if (p.state.stage != intent.getIntExtra("state", -1)
                    || p.state.revision != intent.getLongExtra("revision", -1)) return;
            stopPresentation(ctx, p);
        } else if (p.state.expire(intent.getIntExtra("state", -1),
                intent.getLongExtra("revision", -1), System.currentTimeMillis())
                == StageDisplayState.Effect.HIDE) {
            hidePresentation(ctx, p);
            if (p.state.stage == STATE_FINISHED) p.state.terminate();
            savePresentation(ctx, p);
        }
    }

    private synchronized void refreshPresentationSettings(Context ctx) {
        loadPresentations(ctx);
        long now = System.currentTimeMillis();
        for (Presentation p : new java.util.ArrayList<>(mPresentations.values())) {
            if (p.state.terminated || p.day != getTodayDayMarker() || now < p.triggerMs) continue;
            if (p.endMs > 0 && now >= p.endMs && (!p.state.hasShown
                    || now > p.endMs + readStageDurationMs(STATE_FINISHED))) continue;
            displayPresentation(ctx, p, true);
            schedulePresentationBoundaries(ctx, p);
        }
    }

    private synchronized void terminatePresentations(Context ctx, int automationId, int notificationId) {
        loadPresentations(ctx);
        for (Presentation p : new java.util.ArrayList<>(mPresentations.values())) {
            if (p.automationId == automationId || p.id == notificationId) stopPresentation(ctx, p);
        }
    }

    private synchronized void prunePresentations(Context ctx, java.util.Set<Integer> validIds) {
        loadPresentations(ctx);
        for (Presentation p : new java.util.ArrayList<>(mPresentations.values())) {
            boolean sameDay = p.day == getTodayDayMarker();
            boolean sameSource = p.key.startsWith(PRESENTATION_PREFIX + p.day + "_"
                    + readCourseSource(getConfigPrefs()) + "_");
            if (sameDay && (p.test || (sameSource && validIds.contains(p.automationId)))) continue;
            stopPresentation(ctx, p);
            mPresentations.remove(p.key);
            ctx.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE).edit().remove(p.key).apply();
        }
    }

    private static String safeStr(String s) {
        return s != null ? s : "";
    }

    private Intent buildCourseOpenIntent(Context ctx, SharedPreferences prefs) throws Exception {
        String source = readCourseSource(prefs);
        Intent launchIntent = null;
        if (SOURCE_WAKEUP.equalsIgnoreCase(source)) {
            launchIntent = ctx.getPackageManager().getLaunchIntentForPackage(PKG_WAKEUP);
        } else if (SOURCE_SHIGUANG.equalsIgnoreCase(source)) {
            launchIntent = ctx.getPackageManager().getLaunchIntentForPackage(PKG_SHIGUANG);
        }
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return launchIntent;
        }
        return Intent.parseUri(COURSE_TABLE_INTENT, Intent.URI_INTENT_SCHEME);
    }

    // 岛状态常量
    private static final int STATE_COUNTDOWN = 0; // 倒计时（上课前）
    private static final int STATE_ELAPSED   = 1; // 正计时（上课中）
    private static final int STATE_FINISHED  = 2; // 正计时（已下课）
    private static final String ISLAND_UPDATE_CHANNEL = "xiaoai_island_update_silent";



    /**
     * 统一构建超级岛 JSON。三种状态的差异通过 state 参数区分：
     *   STATE_COUNTDOWN：上课前倒计时，上课静音按钮
     *   STATE_ELAPSED  ：上课中正计时，上课静音按钮
     *   STATE_FINISHED ：下课后正计时，解除静音按钮
     */
    private Bundle buildIslandExtras(
            CourseInfo info,
            int state,
            SharedPreferences prefs,
            Context ctx,
            Notification sourceNotification,
            int notificationId,
            String notificationTag,
            int automationAlarmId) {
        IslandContentBuilder.CourseSnapshot snapshot = new IslandContentBuilder.CourseSnapshot(
                info.courseName, info.startTime, info.endTime,
                info.classroom, info.sectionRange, info.teacher);
        IslandContentBuilder.BuildOptions options = new IslandContentBuilder.BuildOptions(
                sIslandButtonMode,
                ACTION_MANUAL_MUTE,
                ACTION_MANUAL_UNMUTE,
                ACTION_MANUAL_SKIP_CLASS,
                TARGET_PACKAGE,
                PIC_KEY_SHARE,
                ctx,
                sourceNotification != null ? sourceNotification.getSmallIcon() : null,
                notificationId,
                notificationTag,
                automationAlarmId);
        return IslandContentBuilder.build(snapshot, state, prefs, options);
    }

    private int buildCourseNotificationId(
            String courseName,
            String startTime,
            String endTime,
            String classroom,
            String sectionRange,
            String teacher) {
        return Math.abs((safeStr(courseName)
                + safeStr(startTime)
                + safeStr(endTime)
                + safeStr(classroom)
                + safeStr(sectionRange)
                + safeStr(teacher)).hashCode()) & 0x00FFFFFF;
    }



    private void bootstrapRemotePrefsUnified(Context ctx) {
        try {
            SharedPreferences remoteHoliday = XposedBridge.getRemotePreferences(HolidayManager.PREFS_HOLIDAY);
            SharedPreferences hostConfig = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences hostRuntime = ctx.getSharedPreferences(PREFS_RUNTIME_NAME, Context.MODE_PRIVATE);
            SharedPreferences hostHoliday = ctx.getSharedPreferences(HolidayManager.PREFS_HOLIDAY, Context.MODE_PRIVATE);

            HolidayManager.setRemotePrefs(remoteHoliday);
            migrateRuntimeStorageOnce(hostConfig, hostRuntime);
            purgeHostConfigKeys(hostConfig);
            PrefsAccess.deleteLocalIfEmpty(ctx, PREFS_NAME);
            clearPrefs(hostHoliday);
            PrefsAccess.deleteLocalIfEmpty(ctx, HolidayManager.PREFS_HOLIDAY);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": bootstrapRemotePrefsUnified failed -> " + t.getMessage());
        }
    }

    private void purgeHostConfigKeys(SharedPreferences hostConfig) {
        if (hostConfig == null) return;
        try {
            java.util.Map<String, ?> all = hostConfig.getAll();
            if (all == null || all.isEmpty()) return;
            SharedPreferences.Editor ed = hostConfig.edit();
            boolean changed = false;
            for (String key : all.keySet()) {
                if (isConfigKey(key)) {
                    ed.remove(key);
                    changed = true;
                }
            }
            if (changed) ed.apply();
        } catch (Throwable ignored) {}
    }

    private void clearPrefs(SharedPreferences prefs) {
        PrefsAccess.clearIfNotEmpty(prefs);
    }


    private boolean isConfigKey(String key) {
        return ConfigDefaults.isConfigKey(key);
    }

    private void migrateRuntimeStorageOnce(SharedPreferences hostConfig, SharedPreferences hostRuntime) {
        if (hostConfig == null || hostRuntime == null) return;
        try {
            if (hostRuntime.getBoolean(KEY_RUNTIME_MIGRATION_DONE, false)) {
                migrateAddedRuntimeKeys(hostConfig, hostRuntime);
                return;
            }
            SharedPreferences.Editor runtimeEd = hostRuntime.edit();
            SharedPreferences.Editor hostConfigEd = hostConfig.edit();
            boolean moved = false;

            moved |= moveIntKey(hostConfig, hostConfigEd, hostRuntime, runtimeEd, KEY_COURSE_TOTAL_WEEK);
            moved |= moveIntKey(hostConfig, hostConfigEd, hostRuntime, runtimeEd, KEY_LAST_DAILY_RESCHEDULE_DAY);
            moved |= moveStringKey(hostConfig, hostConfigEd, hostRuntime, runtimeEd, KEY_SCHEDULED_ALARM_IDS);
            moved |= moveStringKey(hostConfig, hostConfigEd, hostRuntime, runtimeEd, KEY_SCHEDULED_MUTE_IDS);
            moved |= movePrefixedStringKeys(hostConfig, hostConfigEd, hostRuntime, runtimeEd, SETTINGS_CACHE_PREFIX);
            moved |= movePrefixedStringKeys(hostConfig, hostConfigEd, hostRuntime, runtimeEd, TIMETABLE_CACHE_PREFIX);

            if (moved) runtimeEd.apply();
            hostConfigEd.apply();

            hostRuntime.edit().putBoolean(KEY_RUNTIME_MIGRATION_DONE, true).apply();
            if (moved) XposedBridge.log(TAG + ": 运行态键已迁移到 island_runtime");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": migrateRuntimeStorageOnce failed -> " + t.getMessage());
        }
    }

    private void migrateAddedRuntimeKeys(SharedPreferences hostConfig, SharedPreferences hostRuntime) {
        try {
            SharedPreferences.Editor runtimeEd = hostRuntime.edit();
            SharedPreferences.Editor hostConfigEd = hostConfig.edit();
            boolean moved = movePrefixedStringKeys(hostConfig, hostConfigEd, hostRuntime, runtimeEd, TIMETABLE_CACHE_PREFIX);
            if (moved) {
                runtimeEd.apply();
                hostConfigEd.apply();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": migrateAddedRuntimeKeys failed -> " + t.getMessage());
        }
    }

    private boolean moveIntKey(SharedPreferences src, SharedPreferences.Editor srcEd,
                               SharedPreferences runtime, SharedPreferences.Editor runtimeEd,
                               String key) {
        if (!src.contains(key)) return false;
        if (!runtime.contains(key)) runtimeEd.putInt(key, src.getInt(key, 0));
        srcEd.remove(key);
        return true;
    }

    private boolean moveStringKey(SharedPreferences src, SharedPreferences.Editor srcEd,
                                  SharedPreferences runtime, SharedPreferences.Editor runtimeEd,
                                  String key) {
        if (!src.contains(key)) return false;
        String value = src.getString(key, "");
        if (!runtime.contains(key)) runtimeEd.putString(key, value);
        srcEd.remove(key);
        return true;
    }

    private boolean movePrefixedStringKeys(SharedPreferences src, SharedPreferences.Editor srcEd,
                                           SharedPreferences runtime, SharedPreferences.Editor runtimeEd,
                                           String prefix) {
        java.util.Map<String, ?> all = src.getAll();
        if (all == null || all.isEmpty()) return false;
        boolean moved = false;
        for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
            String key = e.getKey();
            if (key == null || !key.startsWith(prefix)) continue;
            Object value = e.getValue();
            if (!runtime.contains(key) && value instanceof String) {
                runtimeEd.putString(key, (String) value);
            }
            srcEd.remove(key);
            moved = true;
        }
        return moved;
    }

    private void registerRemotePrefsListener(Context ctx) {
        try {
            com.xiaoai.islandnotify.modernhook.XSharedPreferences remote =
                    new com.xiaoai.islandnotify.modernhook.XSharedPreferences(PREFS_NAME);
            remote.reload();
            if (mRemotePrefsListener != null && mObservedRemotePrefs != null) {
                mObservedRemotePrefs.unregisterOnSharedPreferenceChangeListener(mRemotePrefsListener);
            }
            mObservedRemotePrefs = remote;
            mRemotePrefsListener = (sp, key) -> {
                refreshRuntimeSwitchesFromPrefs(sp);
                if (key == null || key.startsWith("stage_enabled_")
                        || key.startsWith("to_island_")) {
                    refreshPresentationSettings(ctx);
                }
                if (isRescheduleRelatedKey(key)) {
                    safeReschedule(ctx, "remote_prefs_changed:" + key, false);
                }
                if (isWakeupRelatedKey(key)) {
                    scheduleTodayWakeupAlarms(ctx);
                }
            };
            remote.registerOnSharedPreferenceChangeListener(mRemotePrefsListener);
            XposedBridge.log(TAG + ": remote prefs listener registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": registerRemotePrefsListener failed -> " + t.getMessage());
        }
    }

    private void refreshRuntimeSwitchesFromPrefs(SharedPreferences prefs) {
        sMuteEnabled            = readConfigBool(prefs, KEY_MUTE_ENABLED, ConfigDefaults.SWITCH_DISABLED);
        sUnmuteEnabled          = readConfigBool(prefs, KEY_UNMUTE_ENABLED, ConfigDefaults.SWITCH_DISABLED);
        sDndEnabled             = readConfigBool(prefs, KEY_DND_ENABLED, ConfigDefaults.SWITCH_DISABLED);
        sUnDndEnabled           = readConfigBool(prefs, KEY_UNDND_ENABLED, ConfigDefaults.SWITCH_DISABLED);
        sWakeupMorningEnabled   = readConfigBool(prefs, KEY_WAKEUP_MORNING_ENABLED, ConfigDefaults.SWITCH_DISABLED);
        sWakeupAfternoonEnabled = readConfigBool(prefs, KEY_WAKEUP_AFTERNOON_ENABLED, ConfigDefaults.SWITCH_DISABLED);
        sRepostEnabled          = readConfigBool(prefs, KEY_REPOST_ENABLED, ConfigDefaults.REPOST_ENABLED);
        sIslandButtonMode       = readConfigInt(prefs, "island_button_mode", ConfigDefaults.ISLAND_BUTTON_MODE);
    }

    private boolean isRescheduleRelatedKey(String key) {
        if (key == null) return false;
        return KEY_REMINDER_MINUTES.equals(key)
                || KEY_MUTE_ENABLED.equals(key)
                || KEY_MUTE_MINS_BEFORE.equals(key)
                || KEY_UNMUTE_ENABLED.equals(key)
                || KEY_UNMUTE_MINS_AFTER.equals(key)
                || KEY_DND_ENABLED.equals(key)
                || KEY_DND_MINS_BEFORE.equals(key)
                || KEY_UNDND_ENABLED.equals(key)
                || KEY_UNDND_MINS_AFTER.equals(key)
                || KEY_REPOST_ENABLED.equals(key)
                || KEY_COURSE_DATA_SOURCE.equals(key);
    }

    private boolean isWakeupRelatedKey(String key) {
        if (key == null) return false;
        return KEY_WAKEUP_MORNING_ENABLED.equals(key)
                || KEY_WAKEUP_MORNING_LAST_SEC.equals(key)
                || KEY_WAKEUP_MORNING_RULES_JSON.equals(key)
                || KEY_WAKEUP_AFTERNOON_ENABLED.equals(key)
                || KEY_WAKEUP_AFTERNOON_FIRST_SEC.equals(key)
                || KEY_WAKEUP_AFTERNOON_RULES_JSON.equals(key);
    }

    /**
     * 跨进程读取模块自身的 SharedPreferences。
     * 使用 XSharedPreferences 绕过 Android 9+ 的沙箱文件权限限制。
     * createPackageContext+MODE_PRIVATE 在 Android 9+ 会被 SELinux 拦截，无法使用。
     */
    private SharedPreferences loadConfigPrefsRemoteFirst() {
        try {
            com.xiaoai.islandnotify.modernhook.XSharedPreferences remote =
                    new com.xiaoai.islandnotify.modernhook.XSharedPreferences(PREFS_NAME);
            remote.reload();
            return remote;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": remote prefs load failed -> " + t.getMessage());
            return new com.xiaoai.islandnotify.modernhook.XSharedPreferences(PREFS_NAME);
        }
    }

    private SharedPreferences getConfigPrefs() {
        return loadConfigPrefsRemoteFirst();
    }

    private String readCourseSource(SharedPreferences prefs) {
        String source = readConfigString(prefs, KEY_COURSE_DATA_SOURCE, SOURCE_XIAOAI);
        if (source.isEmpty()) return SOURCE_XIAOAI;
        return source;
    }

    private boolean isWakeupDataSource(SharedPreferences prefs) {
        return SOURCE_WAKEUP.equalsIgnoreCase(readCourseSource(prefs));
    }

    private boolean isShiguangDataSource(SharedPreferences prefs) {
        return SOURCE_SHIGUANG.equalsIgnoreCase(readCourseSource(prefs));
    }

    private boolean isXiaoaiDataSource(SharedPreferences prefs) {
        return !isWakeupDataSource(prefs) && !isShiguangDataSource(prefs);
    }

    private String readCourseDataBean(Context ctx) {
        try {
            @SuppressWarnings("deprecation")
            SharedPreferences coursePrefs = ctx.getSharedPreferences(
                    PREFS_COURSE_DATA, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
            return coursePrefs.getString("weekCourseBean", null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String readWakeupMirrorBean(Context ctx) {
        try {
            SharedPreferences wakeupMirror =
                    ctx.getSharedPreferences(PREFS_WAKEUP_MIRROR, Context.MODE_PRIVATE);
            return wakeupMirror.getString(KEY_WAKEUP_MIRROR_BEAN, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String readShiguangMirrorBean(Context ctx) {
        try {
            SharedPreferences mirror =
                    ctx.getSharedPreferences(PREFS_SHIGUANG_MIRROR, Context.MODE_PRIVATE);
            return mirror.getString(KEY_SHIGUANG_MIRROR_BEAN, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String readActiveCourseBeanJson(Context ctx, SharedPreferences prefs) {
        if (isWakeupDataSource(prefs)) {
            return readWakeupMirrorBean(ctx);
        }
        if (isShiguangDataSource(prefs)) {
            return readShiguangMirrorBean(ctx);
        }
        return readCourseDataBean(ctx);
    }

    /** 切换数据源后检查对应 mirror 是否存在，不存在则提示用户打开对应 App。 */
    private void checkMirrorAndNotify(Context ctx, String source) {
        if (!SOURCE_WAKEUP.equals(source) && !SOURCE_SHIGUANG.equals(source)) return;
        String bean = SOURCE_WAKEUP.equals(source)
                ? readWakeupMirrorBean(ctx)
                : readShiguangMirrorBean(ctx);
        if (bean != null && !bean.isEmpty()) return;
        String msg = SOURCE_WAKEUP.equals(source)
                ? "请打开 WakeUp 课程表以完成数据同步"
                : "请打开拾光课程表以完成数据同步";
        showToast(ctx, msg);
    }

    /** 首次同步成功时提示用户后续自动同步不再提醒。 */
    private void showFirstSyncToast(Context ctx) {
        showToast(ctx, "课程数据已同步成功，后续更改将自动同步，不再提醒");
    }

    private void showToast(Context ctx, String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show());
    }

    private int readConfigInt(SharedPreferences prefs, String key, int fallback) {
        return PrefsAccess.readConfigInt(prefs, key, fallback);
    }

    private boolean readConfigBool(SharedPreferences prefs, String key, boolean fallback) {
        return PrefsAccess.readConfigBool(prefs, key, fallback);
    }

    private String readConfigString(SharedPreferences prefs, String key, String fallback) {
        return PrefsAccess.readConfigString(prefs, key, fallback);
    }

    /**
     * 计算今天上课开始时间的毫秒时间戳。
     * 用于 hintInfo.timerInfo（倒计时组件）。
     */
    private static long computeClassStartMs(String startTime) {
        if (startTime == null || startTime.isEmpty()) return -1;
        try {
            String[] parts = startTime.split(":");
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, h);
            cal.set(java.util.Calendar.MINUTE, m);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);
            return cal.getTimeInMillis();
        } catch (Exception e) {
            return -1;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 数据结构
    // ─────────────────────────────────────────────────────────────

    private static final class TodayCourseSlot {
        final CourseScheduleParser.CourseSlot slot;
        final long startMs;
        final long endMs;

        TodayCourseSlot(CourseScheduleParser.CourseSlot slot, long startMs, long endMs) {
            this.slot = slot;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private static class CourseInfo {
        final String courseName;
        final String startTime;
        final String endTime;
        final String classroom;
        final String sectionRange;
        final String teacher;

        CourseInfo(String courseName, String startTime, String endTime, String classroom, String sectionRange, String teacher) {
            this.courseName = courseName;
            this.startTime  = startTime;
            this.endTime    = endTime;
            this.classroom  = classroom;
            this.sectionRange = sectionRange == null ? "" : sectionRange;
            this.teacher = teacher == null ? "" : teacher;
        }
    }
}
