package com.xiaoai.islandnotify;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

final class AlarmScheduler {

    private AlarmScheduler() {}

    private static final int SERVICE_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

    static Intent buildServiceIntent(String targetPackage, String serviceClassName, String action) {
        Intent intent = new Intent(action);
        intent.setClassName(targetPackage, serviceClassName);
        return intent;
    }

    // 使用宿主 Context；权限由宿主声明，并在运行时检查，模块清单无法代表宿主权限。
    @android.annotation.SuppressLint("MissingPermission")
    static boolean scheduleAlarmClock(Context ctx,
                                      Intent serviceIntent,
                                      int requestCode,
                                      String showAction,
                                      String showPackage,
                                      int showRequestCode,
                                      boolean showUpdateCurrent,
                                      long triggerAtMillis) {
        if (ctx == null || serviceIntent == null || showAction == null || showPackage == null) return false;
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return false;
        try {
            if (!am.canScheduleExactAlarms()) {
                Log.w("IslandNotify", "精确闹钟权限不可用：" + ctx.getPackageName());
                return false;
            }
            PendingIntent servicePi = PendingIntent.getService(ctx, requestCode, serviceIntent, SERVICE_FLAGS);
            PendingIntent showPi = PendingIntent.getBroadcast(
                    ctx,
                    showRequestCode,
                    new Intent(showAction).setPackage(showPackage),
                    showFlags(showUpdateCurrent));
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAtMillis, showPi), servicePi);
            return true;
        } catch (SecurityException e) {
            // 检查通过后权限仍可能被撤销，不将失败的调度记作成功。
            Log.w("IslandNotify", "精确闹钟调度被拒绝：" + ctx.getPackageName(), e);
            return false;
        }
    }

    static void cancelAlarmClock(Context ctx,
                                 Intent serviceIntent,
                                 int requestCode,
                                 String showAction,
                                 String showPackage,
                                 int showRequestCode,
                                 boolean showUpdateCurrent) {
        if (ctx == null || serviceIntent == null || showAction == null || showPackage == null) return;
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;

        PendingIntent servicePi = PendingIntent.getService(ctx, requestCode, serviceIntent, SERVICE_FLAGS);
        if (servicePi != null) {
            am.cancel(servicePi);
            servicePi.cancel();
        }
        PendingIntent showPi = PendingIntent.getBroadcast(
                ctx,
                showRequestCode,
                new Intent(showAction).setPackage(showPackage),
                showFlags(showUpdateCurrent));
        if (showPi != null) {
            am.cancel(showPi);
            showPi.cancel();
        }
    }

    static int reqCodeForMuteAction(int alarmId, String action) {
        int aid = alarmId & 0x00FFFFFF;
        if ("com.xiaoai.islandnotify.DO_MUTE".equals(action)) return aid | 0x01000000;
        if ("com.xiaoai.islandnotify.DO_UNMUTE".equals(action)) return aid | 0x02000000;
        if ("com.xiaoai.islandnotify.DO_DND_ON".equals(action)) return aid | 0x03000000;
        return aid | 0x04000000;
    }

    private static int showFlags(boolean updateCurrent) {
        int flags = PendingIntent.FLAG_IMMUTABLE;
        if (updateCurrent) flags |= PendingIntent.FLAG_UPDATE_CURRENT;
        return flags;
    }
}

