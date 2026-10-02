package com.xiaoai.islandnotify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

public class TotalWeekReceiver extends BroadcastReceiver {
    public static final String ACTION_UPDATE_TOTAL_WEEK = "com.xiaoai.islandnotify.ACTION_UPDATE_TOTAL_WEEK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_UPDATE_TOTAL_WEEK.equals(intent.getAction())) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            String sender = getSentFromPackage();
            if (sender != null && !"com.miui.voiceassist".equals(sender)
                    && !context.getPackageName().equals(sender)) {
                Log.w("IslandNotify", "TotalWeekReceiver: rejected sender " + sender);
                return;
            }
        }
        // Android 13 或系统未提供发送方身份时保留旧宿主同步；这里不构成完整身份认证。
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) return;
            // getInt 仅接受 Integer，缺失或类型不匹配返回默认值，不做字符串/浮点数转换。
            int tw = extras.getInt("course_total_week", 0);
            if (tw <= 0) return;
            SharedPreferences sp = context.getSharedPreferences("island_runtime", Context.MODE_PRIVATE);
            sp.edit().putInt("course_total_week", tw).apply();
            Log.d("IslandNotify", "TotalWeekReceiver: updated total week to " + tw);
        } catch (RuntimeException e) {
            Log.w("IslandNotify", "TotalWeekReceiver: invalid payload", e);
        }
    }
}
