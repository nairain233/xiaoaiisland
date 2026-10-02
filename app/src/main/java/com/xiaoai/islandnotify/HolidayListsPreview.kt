package com.xiaoai.islandnotify

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Preview(name = "假期列表 · 浅色", widthDp = 320, heightDp = 1600)
@Preview(
    name = "假期列表 · 深色", widthDp = 320, heightDp = 1600,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "假期列表 · 1.5 倍字体", widthDp = 320, heightDp = 2400, fontScale = 1.5f)
@Preview(name = "假期列表 · 2 倍字体", widthDp = 320, heightDp = 3000, fontScale = 2f)
@Preview(name = "假期列表 · 宽屏", widthDp = 600, heightDp = 1000)
@Composable
private fun HolidayListsPreview() {
    HolidayPreviewContent(empty = false)
}

@Preview(name = "空假期列表 · 浅色", widthDp = 320, heightDp = 700)
@Preview(
    name = "空假期列表 · 深色", widthDp = 320, heightDp = 700,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun EmptyHolidayListsPreview() {
    HolidayPreviewContent(empty = true)
}

@Composable
private fun HolidayPreviewContent(empty: Boolean) {
    val dark = isSystemInDarkTheme()
    val controller = remember(dark) {
        ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
    }
    val holidays = remember(empty) {
        if (empty) emptyList() else listOf(
            HolidayManager.HolidayEntry(
                "2026-10-01", "2026-10-07", "国庆节", HolidayManager.TYPE_HOLIDAY, false,
            ),
            HolidayManager.HolidayEntry(
                "2026-10-08", null, "较长的自定义节假日名称", HolidayManager.TYPE_HOLIDAY, true,
            ),
        )
    }
    val workswaps = remember(empty) {
        if (empty) emptyList() else listOf(
            HolidayManager.HolidayEntry(
                "2026-10-10", null, "国庆调休", HolidayManager.TYPE_WORKSWAP, false,
            ).apply { followWeek = 6; followWeekday = 4 },
            HolidayManager.HolidayEntry(
                "2026-10-11", null, "较长的自定义调休工作日名称", HolidayManager.TYPE_WORKSWAP, true,
            ).apply { followWeek = 7; followWeekday = 1 },
        )
    }
    MiuixTheme(controller = controller) {
        RouteScaffold(title = "假期/调休", canBack = true, onBack = {}) { modifier, padding ->
            SettingsPage(modifier = modifier, pagePadding = padding) {
                item(key = "holidays") {
                    HolidayEntriesSection(
                        title = "节假日",
                        entries = holidays,
                        addSummary = "添加节假日日期或区间",
                        onEdit = {}, onDelete = {}, onAdd = {},
                    )
                }
                item(key = "workswaps") {
                    HolidayEntriesSection(
                        title = "调休工作日",
                        entries = workswaps,
                        addSummary = "添加调休上班日与跟随周次",
                        onEdit = {}, onDelete = {}, onAdd = {},
                    )
                }
            }
        }
    }
}
