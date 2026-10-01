package com.xiaoai.islandnotify

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.miuix.kmp.nav.core.NavKey

class AppNavigationTest {
    @Test
    fun repeatedDestinationDoesNotCreateDuplicateEntries() {
        val stack = mutableListOf<NavKey>(AppRoute.Home)
        openRoute(stack, AppRoute.Timeout)
        openRoute(stack, AppRoute.Timeout)
        assertEquals(listOf(AppRoute.Home, AppRoute.Timeout), stack)
    }

    @Test
    fun reopeningCoveredDestinationReturnsToItsExistingEntry() {
        val stack = mutableListOf<NavKey>(AppRoute.Empty, AppRoute.Reminder, AppRoute.About)
        openRoute(stack, AppRoute.Reminder)
        assertEquals(listOf(AppRoute.Empty, AppRoute.Reminder), stack)
    }

    @Test
    fun resizingKeepsTheDestinationAndDetailHistory() {
        val stack = mutableListOf<NavKey>(AppRoute.Home, AppRoute.Timeout, AppRoute.About)
        setNavigationRoot(stack, split = true)
        assertEquals(listOf(AppRoute.Empty, AppRoute.Timeout, AppRoute.About), stack)
        setNavigationRoot(stack, split = false)
        assertEquals(listOf(AppRoute.Home, AppRoute.Timeout, AppRoute.About), stack)
    }

    @Test
    fun savedStackRestoresEveryDestinationForBothRootModes() {
        val destinations = listOf(
            AppRoute.TestNotify, AppRoute.StatusCustom, AppRoute.ExpandedCustom,
            AppRoute.Timeout, AppRoute.Reminder, AppRoute.Mute, AppRoute.Wakeup,
            AppRoute.Holiday, AppRoute.About,
        )
        for (root in listOf(AppRoute.Home, AppRoute.Empty)) {
            val saved: List<AppRoute> = listOf(root) + destinations
            val restored = Json.decodeFromString<List<AppRoute>>(Json.encodeToString(saved))
            assertEquals(saved, restored)
        }
    }

    @Test
    fun splitLayoutPreservesLandscapeAndLargeWindowRules() {
        assertTrue(useSplitLayout(720f, 400f, landscape = true))
        assertTrue(useSplitLayout(840f, 480f, landscape = false))
        assertFalse(useSplitLayout(839f, 900f, landscape = false))
        assertFalse(useSplitLayout(900f, 479f, landscape = false))
        assertFalse(useSplitLayout(400f, 900f, landscape = false))
    }
}
