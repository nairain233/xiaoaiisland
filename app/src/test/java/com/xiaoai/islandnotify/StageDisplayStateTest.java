package com.xiaoai.islandnotify;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.xiaoai.islandnotify.StageDisplayState.Effect.*;

public class StageDisplayStateTest {
    @Test public void zeroGapCoursesEachEnterTheirOwnStageAtTheExactBoundary() {
        assertEquals(ConfigDefaults.STAGE_POST, StageDisplayState.stageAt(20000, 1000, 20000));
        assertEquals(ConfigDefaults.STAGE_ACTIVE, StageDisplayState.stageAt(20000, 20000, 80000));
    }

    @Test public void zeroMinuteReminderAndActiveBoundaryAlertOnlyOnceInEitherOrder() {
        long start = 20000, end = 80000;
        for (boolean reminderFirst : new boolean[]{true, false}) {
            StageDisplayState state = new StageDisplayState();
            int reminderStage = StageDisplayState.stageAt(start, start, end);
            int firstStage = reminderFirst ? reminderStage : ConfigDefaults.STAGE_ACTIVE;
            int secondStage = reminderFirst ? ConfigDefaults.STAGE_ACTIVE : reminderStage;
            assertEquals(SHOW_ALERT, state.enter(firstStage, true, 10000, start, false));
            long revision = state.revision;
            assertEquals(NONE, state.enter(secondStage, true, 10000, start, false));
            assertEquals(revision, state.revision);
        }
    }

    @Test public void allEightSwitchCombinationsAlertOnlyOnTheFirstVisibleStage() {
        for (int mask = 0; mask < 8; mask++) {
            StageDisplayState state = new StageDisplayState();
            boolean shown = false;
            for (int stage = 0; stage < 3; stage++) {
                boolean enabled = (mask & (1 << stage)) != 0;
                boolean wasVisible = state.visible;
                StageDisplayState.Effect effect = state.enter(stage, enabled, 10000, stage * 60000L, false);
                assertEquals("mask=" + mask + " stage=" + stage,
                        enabled ? (shown ? SHOW_SILENT : SHOW_ALERT) : (wasVisible ? HIDE : NONE), effect);
                assertEquals(enabled, state.visible);
                shown |= enabled;
            }
        }
    }

    @Test public void timeoutHidesOnlyTheCurrentStageAndAllowsTheNextStage() {
        StageDisplayState state = new StageDisplayState();
        assertEquals(SHOW_ALERT, state.enter(0, true, 10000, 1000, false));
        assertEquals(NONE, state.expire(0, state.revision, 10999));
        assertEquals(HIDE, state.expire(0, state.revision, 11000));
        assertEquals(NONE, state.enter(0, true, 10000, 12000, false));
        assertEquals(SHOW_SILENT, state.enter(1, true, 10000, 60000, false));
    }

    @Test public void duplicateBoundaryDoesNotExtendTheDeadlineOrRepeatTheAlert() {
        StageDisplayState state = new StageDisplayState();
        state.enter(1, true, 10000, 1000, false);
        long revision = state.revision;
        assertEquals(NONE, state.enter(1, true, 10000, 5000, false));
        assertEquals(11000, state.deadlineMs);
        assertEquals(revision, state.revision);
    }

    @Test public void previousStageAndOldTimeoutCannotHideARecoveredNotification() {
        StageDisplayState state = new StageDisplayState();
        state.enter(0, true, 10000, 1000, false);
        long previousRevision = state.revision;
        state.enter(1, true, 10000, 5000, false);
        assertEquals(NONE, state.enter(0, true, 10000, 15000, false));
        assertEquals(NONE, state.expire(0, previousRevision, 15000));
        assertTrue(state.visible);
    }

    @Test public void settingsImmediatelyHideAndSilentlyRestoreWithANewDeadline() {
        StageDisplayState state = new StageDisplayState();
        state.enter(1, true, 10000, 1000, false);
        assertEquals(HIDE, state.enter(1, false, 10000, 2000, true));
        assertEquals(SHOW_SILENT, state.enter(1, true, 10000, 3000, true));
        assertEquals(13000, state.deadlineMs);
    }

    @Test public void changingDurationResetsTheTimerWithoutSound() {
        StageDisplayState state = new StageDisplayState();
        state.enter(1, true, 10000, 1000, false);
        long oldRevision = state.revision;
        assertEquals(SHOW_SILENT, state.enter(1, true, 20000, 2000, true));
        assertEquals(22000, state.deadlineMs);
        assertEquals(NONE, state.expire(1, oldRevision, 30000));
    }

    @Test public void unrelatedStageSettingsDoNotResetTheCurrentDeadline() {
        StageDisplayState state = new StageDisplayState();
        state.enter(0, true, 10000, 1000, false);
        assertEquals(NONE, state.enter(0, true, 10000, 5000, true));
        assertEquals(11000, state.deadlineMs);
    }

    @Test public void firstDisplayCausedBySettingsIsSilentAndLaterStagesStaySilent() {
        StageDisplayState state = new StageDisplayState();
        assertEquals(SHOW_SILENT, state.enter(1, true, 10000, 1000, true));
        assertEquals(SHOW_SILENT, state.enter(2, true, 10000, 20000, false));
    }

    @Test public void restartDuringAHiddenStageKeepsTheLessonAndItsAlertHistory() {
        StageDisplayState state = new StageDisplayState();
        state.enter(0, true, 10000, 1000, false);
        state.enter(1, false, 10000, 5000, false);
        StageDisplayState restored = StageDisplayState.decode(state.encode());
        assertFalse(restored.visible);
        assertTrue(restored.hasShown);
        assertEquals(SHOW_SILENT, restored.enter(2, true, 10000, 20000, false));
    }

    @Test public void restartKeepsTheAbsoluteTimeoutAndRejectsStaleEvents() {
        StageDisplayState state = new StageDisplayState();
        state.enter(1, true, 10000, 1000, false);
        StageDisplayState restored = StageDisplayState.decode(state.encode());
        assertEquals(11000, restored.deadlineMs);
        assertEquals(HIDE, restored.expire(1, state.revision, 12000));
        assertEquals(NONE, restored.expire(1, state.revision, 12000));
    }

    @Test public void dismissalAndSkippingPermanentlyEndTheLesson() {
        StageDisplayState state = new StageDisplayState();
        state.enter(0, true, 10000, 1000, false);
        state.terminate();
        StageDisplayState restored = StageDisplayState.decode(state.encode());
        assertEquals(NONE, restored.enter(1, true, 10000, 2000, false));
        assertEquals(NONE, restored.enter(2, true, 10000, 3000, true));
        assertFalse(restored.visible);
    }

    @Test public void disablingThePreStageStillAllowsFirstAlertInActiveOrPost() {
        for (int first = 1; first <= 2; first++) {
            StageDisplayState state = new StageDisplayState();
            for (int stage = 0; stage < first; stage++) state.enter(stage, false, 10000, stage * 1000L, false);
            assertEquals(SHOW_ALERT, state.enter(first, true, 10000, 20000, false));
        }
    }
}
