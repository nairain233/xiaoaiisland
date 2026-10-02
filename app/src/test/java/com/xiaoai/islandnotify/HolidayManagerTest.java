package com.xiaoai.islandnotify;

import org.junit.Test;

import java.util.TimeZone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HolidayManagerTest {

    @Test
    public void adjacentDaysIncludeMonthYearAndLeapDayBoundaries() {
        assertTrue(HolidayManager.isAdjacentDay("2026-10-01", "2026-10-02"));
        assertTrue(HolidayManager.isAdjacentDay("2026-09-30", "2026-10-01"));
        assertTrue(HolidayManager.isAdjacentDay("2026-12-31", "2027-01-01"));
        assertTrue(HolidayManager.isAdjacentDay("2024-02-28", "2024-02-29"));
        assertTrue(HolidayManager.isAdjacentDay("2024-02-29", "2024-03-01"));
    }

    @Test
    public void identicalReversedAndSeparatedDaysAreNotAdjacent() {
        assertFalse(HolidayManager.isAdjacentDay("2026-10-01", "2026-10-01"));
        assertFalse(HolidayManager.isAdjacentDay("2026-10-02", "2026-10-01"));
        assertFalse(HolidayManager.isAdjacentDay("2026-10-01", "2026-10-03"));
    }

    @Test
    public void invalidDatesDoNotMergeHolidayRanges() {
        assertFalse(HolidayManager.isAdjacentDay(null, "2026-10-02"));
        assertFalse(HolidayManager.isAdjacentDay("2026-10-01", null));
        assertFalse(HolidayManager.isAdjacentDay("", "2026-10-02"));
        assertFalse(HolidayManager.isAdjacentDay("2026-10-01", "invalid"));
        assertFalse(HolidayManager.isAdjacentDay("2026-02-29", "2026-03-02"));
        assertFalse(HolidayManager.isAdjacentDay("2026-02-30", "2026-03-03"));
        assertFalse(HolidayManager.isAdjacentDay("2026-10-01", "2026-10-02extra"));
        assertFalse(HolidayManager.isAdjacentDay("+999999999-12-31", "+999999999-12-31"));
    }

    @Test
    public void daylightSavingChangesDoNotAffectCalendarAdjacency() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            assertTrue(HolidayManager.isAdjacentDay("2026-03-08", "2026-03-09"));
            assertTrue(HolidayManager.isAdjacentDay("2026-11-01", "2026-11-02"));
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            assertTrue(HolidayManager.isAdjacentDay("2026-03-08", "2026-03-09"));
            assertTrue(HolidayManager.isAdjacentDay("2026-11-01", "2026-11-02"));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
