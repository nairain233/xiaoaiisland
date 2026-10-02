package com.xiaoai.islandnotify;

/** 与 Android 通知解耦的阶段状态，隐藏保留生命周期，终止禁止恢复。 */
public final class StageDisplayState {
    public enum Effect { NONE, HIDE, SHOW_ALERT, SHOW_SILENT }

    public int stage = -1;
    public boolean visible;
    public boolean hasShown;
    public boolean terminated;
    public boolean enabled;
    public long durationMs;
    public long deadlineMs;
    public long revision;

    public static int stageAt(long nowMs, long startMs, long endMs) {
        if (endMs > 0 && nowMs >= endMs) return ConfigDefaults.STAGE_POST;
        return nowMs >= startMs ? ConfigDefaults.STAGE_ACTIVE : ConfigDefaults.STAGE_PRE;
    }

    public static boolean canTakeOver(long nowMs, long reminderMs, boolean terminated, boolean enabled) {
        return !terminated && enabled && nowMs >= reminderMs;
    }

    public Effect enter(int nextStage, boolean nextEnabled, long nextDurationMs,
                        long nowMs, boolean configurationChange) {
        if (terminated || nextStage < stage || nextStage < 0 || nextStage > 2) return Effect.NONE;
        boolean newStage = nextStage != stage;
        boolean changed = enabled != nextEnabled || durationMs != nextDurationMs;
        if (!newStage && (!configurationChange || !changed)) return Effect.NONE;
        boolean wasVisible = visible;
        stage = nextStage;
        enabled = nextEnabled;
        durationMs = Math.max(1000L, nextDurationMs);
        revision++;
        visible = nextEnabled;
        deadlineMs = visible ? nowMs + durationMs : 0L;
        if (!visible) return wasVisible ? Effect.HIDE : Effect.NONE;
        boolean alert = !hasShown && !configurationChange;
        hasShown = true;
        return alert ? Effect.SHOW_ALERT : Effect.SHOW_SILENT;
    }

    public Effect expire(int expectedStage, long expectedRevision, long nowMs) {
        if (terminated || !visible || stage != expectedStage || revision != expectedRevision
                || nowMs < deadlineMs) return Effect.NONE;
        visible = false;
        deadlineMs = 0L;
        revision++;
        return Effect.HIDE;
    }

    public void terminate() {
        visible = false;
        terminated = true;
        deadlineMs = 0L;
        revision++;
    }

    public String encode() {
        return stage + "," + visible + "," + hasShown + "," + terminated + ","
                + enabled + "," + durationMs + "," + deadlineMs + "," + revision;
    }

    public static StageDisplayState decode(String text) {
        StageDisplayState result = new StageDisplayState();
        if (text == null || text.isEmpty()) return result;
        String[] values = text.split(",");
        if (values.length != 8) throw new IllegalArgumentException("无效的阶段状态");
        result.stage = Integer.parseInt(values[0]);
        result.visible = Boolean.parseBoolean(values[1]);
        result.hasShown = Boolean.parseBoolean(values[2]);
        result.terminated = Boolean.parseBoolean(values[3]);
        result.enabled = Boolean.parseBoolean(values[4]);
        result.durationMs = Long.parseLong(values[5]);
        result.deadlineMs = Long.parseLong(values[6]);
        result.revision = Long.parseLong(values[7]);
        return result;
    }
}
