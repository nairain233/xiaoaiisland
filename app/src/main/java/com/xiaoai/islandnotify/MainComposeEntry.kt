package com.xiaoai.islandnotify

import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.ColorSpace
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.NumberPickerDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

object MainComposeEntry {

    @JvmStatic
    fun install(activity: MainActivity) {
        activity.setContent {
            MainComposeApp(activity = activity)
        }
    }
}

private data class StageCustomState(
    var tplA: String = "",
    var tplB: String = "",
    var tplTicker: String = "",
    var baseTitle: String = "",
    var hintTitle: String = "",
    var hintSubtitle: String = "",
    var hintContent: String = "",
    var hintSubcontent: String = "",
    var baseContent: String = "",
    var baseSubcontent: String = "",
)

private data class TimeoutUiState(
    val enabled: MutableList<Boolean> = mutableListOf(true, true, true),
    val islandVals: MutableList<Int> = mutableListOf(60, 60, 60),
    val islandUnits: MutableList<String> = mutableListOf("m", "m", "m"),
)

private data class WakeRule(
    var sec: String,
    var hour: String,
    var minute: String,
)

private data class HolidayDraft(
    var date: String,
    var endDate: String = "",
    var name: String = "",
)

private data class WorkSwapDraft(
    var date: String,
    var name: String = "",
    var followWeek: Int = 1,
    var followWeekday: Int = 1,
)

private const val MAX_MINUTE_VALUE = 9999
private const val RELEASES_URL =
    "https://github.com/nairain233/xiaoaiisland/releases"
private const val FORK_AUTHOR_URL = "https://github.com/nairain233"

@Composable
private fun MainComposeApp(
    activity: MainActivity,
) {
    val aboutState = remember { AboutComposeState() }
    val darkTheme = isSystemInDarkTheme()
    val themeController = remember(aboutState.monetEnabled) {
        ThemeController(
            colorSchemeMode = if (aboutState.monetEnabled) {
                ColorSchemeMode.MonetSystem
            } else {
                ColorSchemeMode.System
            },
        )
    }
    val refreshTick by ComposeRefreshBus.tick.collectAsState()
    val settingsState = remember { SettingsComposeState() }
    val holidayState = remember { HolidayComposeState() }

    LaunchedEffect(refreshTick) {
        activity.uiSyncFrameworkServiceState()
        settingsState.loadFrom(activity)
        holidayState.loadFrom(activity)
        aboutState.loadFrom(activity)
    }

    MiuixAppShell(themeController) { route, onOpen, onBack ->
        RouteScaffold(
            title = route.title,
            canBack = route != AppRoute.Home,
            onBack = onBack
        ) { pageModifier, pagePadding ->
            when (route) {
                AppRoute.Home -> HomeEntryPage(
                    modifier = pageModifier,
                    pagePadding = pagePadding,
                    state = settingsState,
                    darkTheme = darkTheme,
                    onRefresh = { activity.requestComposeRefresh() },
                    onOpen = onOpen,
                    onResetConfirmed = {
                        val count = activity.uiResetAllConfigToDefaults()
                        Toast.makeText(activity, "已恢复默认配置：$count 项", Toast.LENGTH_SHORT)
                            .show()
                        activity.requestComposeRefresh()
                    },
                    onExportConfig = { activity.uiExportAllConfig() },
                    onImportConfig = { activity.uiImportAllConfig() },
                )

                AppRoute.TestNotify -> TestNotifyPage(
                    activity,
                    settingsState,
                    pageModifier,
                    pagePadding
                )

                AppRoute.StatusCustom -> StatusCustomPage(
                    activity,
                    settingsState,
                    pageModifier,
                    pagePadding
                )

                AppRoute.ExpandedCustom -> ExpandedCustomPage(
                    activity,
                    settingsState,
                    pageModifier,
                    pagePadding
                )

                AppRoute.Timeout -> TimeoutPage(activity, settingsState, pageModifier, pagePadding)
                AppRoute.Reminder -> ReminderPage(
                    activity,
                    settingsState,
                    pageModifier,
                    pagePadding
                )

                AppRoute.Mute -> MutePage(activity, settingsState, pageModifier, pagePadding)
                AppRoute.Wakeup -> WakeupPage(activity, settingsState, pageModifier, pagePadding)
                AppRoute.Holiday -> HolidayTab(activity, holidayState, pageModifier, pagePadding)
                AppRoute.About -> AboutTab(activity, aboutState, onOpen, pageModifier, pagePadding)
                AppRoute.ThirdPartyLibraries -> ThirdPartyLibrariesPage(activity, pageModifier, pagePadding)
                AppRoute.Empty -> Unit
            }
        }
    }
}

private val AppRoute.title: String
    get() = when (this) {
        AppRoute.Home -> "课程表超级岛"
        AppRoute.TestNotify -> "测试通知"
        AppRoute.StatusCustom -> "状态栏岛自定义"
        AppRoute.ExpandedCustom -> "展开态自定义"
        AppRoute.Timeout -> "阶段显示与时长"
        AppRoute.Reminder -> "课前提醒"
        AppRoute.Mute -> "上课免打扰"
        AppRoute.Wakeup -> "自动叫醒"
        AppRoute.Holiday -> "假期/调休"
        AppRoute.About -> "关于"
        AppRoute.ThirdPartyLibraries -> "第三方库"
        AppRoute.Empty -> ""
    }

private class SettingsComposeState {
    var frameworkActive by mutableStateOf(false)
    var frameworkDesc by mutableStateOf("")
    var courseName by mutableStateOf("高等数学")
    var classroom by mutableStateOf("教科A-101")
    val stageStates = mutableStateListOf(StageCustomState(), StageCustomState(), StageCustomState())
    var iconAEnabled by mutableStateOf(true)
    var statusTextCustomColorArgb by mutableIntStateOf(0xFFFFFFFF.toInt())
    var outEffectStatusEnabled by mutableStateOf(true)
    var outEffectExpandEnabled by mutableStateOf(true)
    var outEffectStatusCustomColorEnabled by mutableStateOf(false)
    var outEffectStatusCustomColorArgb by mutableIntStateOf(0xFFFFFFFF.toInt())
    var outEffectExpandCustomColorEnabled by mutableStateOf(false)
    var outEffectExpandCustomColorArgb by mutableIntStateOf(0xFFFFFFFF.toInt())
    var timeoutState by mutableStateOf(TimeoutUiState())
    var courseDataSource by mutableStateOf("xiaoai")
    var reminderMinutes by mutableStateOf("15")
    var repostEnabled by mutableStateOf(true)
    var muteEnabled by mutableStateOf(false)
    var muteMinsBefore by mutableStateOf("0")
    var unmuteEnabled by mutableStateOf(false)
    var unmuteMinsAfter by mutableStateOf("0")
    var dndEnabled by mutableStateOf(false)
    var dndMinsBefore by mutableStateOf("0")
    var undndEnabled by mutableStateOf(false)
    var undndMinsAfter by mutableStateOf("0")
    var islandButtonMode by mutableIntStateOf(0)
    var wakeupMorningEnabled by mutableStateOf(false)
    var wakeupMorningLastSec by mutableStateOf("4")
    val wakeupMorningRules = mutableStateListOf<WakeRule>()
    var wakeupAfternoonEnabled by mutableStateOf(false)
    var wakeupAfternoonFirstSec by mutableStateOf("5")
    val wakeupAfternoonRules = mutableStateListOf<WakeRule>()

    fun loadFrom(activity: MainActivity) {
        frameworkActive = activity.uiFrameworkActive()
        frameworkDesc = activity.uiFrameworkDesc()
        val prefs = activity.uiConfigPrefs()
        val suffixes = ConfigDefaults.STAGE_SUFFIXES
        for (i in suffixes.indices) {
            val suffix = suffixes[i]
            val prev = stageStates[i]
            stageStates[i] = prev.copy(
                tplA = PrefsAccess.readStagedTemplate(prefs, "tpl_a", suffix, ""),
                tplB = PrefsAccess.readStagedTemplate(prefs, "tpl_b", suffix, ""),
                tplTicker = PrefsAccess.readStagedTemplate(prefs, "tpl_ticker", suffix, ""),
                baseTitle = PrefsAccess.readStagedString(
                    prefs,
                    ConfigDefaults.EXPANDED_TPL_KEYS[0],
                    suffix,
                    ConfigDefaults.expandedTemplateDefault(i, 0, ""),
                ),
                hintTitle = PrefsAccess.readStagedString(
                    prefs,
                    ConfigDefaults.EXPANDED_TPL_KEYS[1],
                    suffix,
                    ConfigDefaults.expandedTemplateDefault(i, 1, ""),
                ),
                hintSubtitle = PrefsAccess.readStagedString(
                    prefs,
                    ConfigDefaults.EXPANDED_TPL_KEYS[2],
                    suffix,
                    ConfigDefaults.expandedTemplateDefault(i, 2, ""),
                ),
                hintContent = PrefsAccess.readStagedString(
                    prefs,
                    ConfigDefaults.EXPANDED_TPL_KEYS[3],
                    suffix,
                    ConfigDefaults.expandedTemplateDefault(i, 3, ""),
                ),
                hintSubcontent = PrefsAccess.readStagedString(
                    prefs,
                    ConfigDefaults.EXPANDED_TPL_KEYS[4],
                    suffix,
                    ConfigDefaults.expandedTemplateDefault(i, 4, ""),
                ),
                baseContent = PrefsAccess.readStagedString(
                    prefs,
                    ConfigDefaults.EXPANDED_TPL_KEYS[5],
                    suffix,
                    ConfigDefaults.expandedTemplateDefault(i, 5, ""),
                ),
                baseSubcontent = PrefsAccess.readStagedString(
                    prefs,
                    ConfigDefaults.EXPANDED_TPL_KEYS[6],
                    suffix,
                    ConfigDefaults.expandedTemplateDefault(i, 6, ""),
                ),
            )
        }
        iconAEnabled = PrefsAccess.readConfigBool(prefs, "icon_a", true)
        statusTextCustomColorArgb = PrefsAccess.readConfigInt(
            prefs,
            "status_text_highlight_custom_color_argb",
            0xFFFFFFFF.toInt(),
        )
        val legacyOutEffectEnabled = PrefsAccess.readConfigBool(prefs, "out_effect_enabled", true)
        val legacyOutEffectExists = prefs.contains("out_effect_enabled")
        val statusEffectDefault = if (legacyOutEffectExists) legacyOutEffectEnabled else false
        val expandEffectDefault = if (legacyOutEffectExists) legacyOutEffectEnabled else true
        outEffectStatusEnabled = PrefsAccess.readConfigBool(
            prefs,
            "out_effect_status_enabled",
            statusEffectDefault,
        )
        outEffectExpandEnabled = PrefsAccess.readConfigBool(
            prefs,
            "out_effect_expand_enabled",
            expandEffectDefault,
        )
        outEffectStatusCustomColorEnabled = PrefsAccess.readConfigBool(
            prefs,
            "out_effect_status_custom_color_enabled",
            false,
        )
        outEffectStatusCustomColorArgb = PrefsAccess.readConfigInt(
            prefs,
            "out_effect_status_custom_color_argb",
            0xFFFFFFFF.toInt(),
        )
        outEffectExpandCustomColorEnabled = PrefsAccess.readConfigBool(
            prefs,
            "out_effect_expand_custom_color_enabled",
            false,
        )
        outEffectExpandCustomColorArgb = PrefsAccess.readConfigInt(
            prefs,
            "out_effect_expand_custom_color_argb",
            0xFFFFFFFF.toInt(),
        )
        timeoutState = readTimeoutState(prefs)
        courseDataSource = PrefsAccess.readConfigString(prefs, "course_data_source", "xiaoai")
        reminderMinutes = PrefsAccess.readConfigInt(prefs, "reminder_minutes_before", 15).toString()
        repostEnabled = PrefsAccess.readConfigBool(prefs, "repost_enabled", true)
        muteEnabled = PrefsAccess.readConfigBool(prefs, "mute_enabled", false)
        muteMinsBefore = PrefsAccess.readConfigInt(prefs, "mute_mins_before", 0).toString()
        unmuteEnabled = PrefsAccess.readConfigBool(prefs, "unmute_enabled", false)
        unmuteMinsAfter = PrefsAccess.readConfigInt(prefs, "unmute_mins_after", 0).toString()
        dndEnabled = PrefsAccess.readConfigBool(prefs, "dnd_enabled", false)
        dndMinsBefore = PrefsAccess.readConfigInt(prefs, "dnd_mins_before", 0).toString()
        undndEnabled = PrefsAccess.readConfigBool(prefs, "undnd_enabled", false)
        undndMinsAfter = PrefsAccess.readConfigInt(prefs, "undnd_mins_after", 0).toString()
        islandButtonMode = PrefsAccess.readConfigInt(prefs, "island_button_mode", 0)
        wakeupMorningEnabled = PrefsAccess.readConfigBool(prefs, "wakeup_morning_enabled", false)
        wakeupMorningLastSec =
            PrefsAccess.readConfigInt(prefs, "wakeup_morning_last_sec", 4).toString()
        wakeupAfternoonEnabled =
            PrefsAccess.readConfigBool(prefs, "wakeup_afternoon_enabled", false)
        wakeupAfternoonFirstSec =
            PrefsAccess.readConfigInt(prefs, "wakeup_afternoon_first_sec", 5).toString()
        wakeupMorningRules.clear()
        wakeupMorningRules.addAll(
            parseWakeRules(
                PrefsAccess.readConfigString(
                    prefs,
                    "wakeup_morning_rules_json",
                    ConfigDefaults.WAKEUP_MORNING_RULES_JSON,
                )
            )
        )
        wakeupAfternoonRules.clear()
        wakeupAfternoonRules.addAll(
            parseWakeRules(
                PrefsAccess.readConfigString(
                    prefs,
                    "wakeup_afternoon_rules_json",
                    ConfigDefaults.WAKEUP_AFTERNOON_RULES_JSON,
                )
            )
        )
    }
}

private class HolidayComposeState {
    var year by mutableIntStateOf(Calendar.getInstance().get(Calendar.YEAR))
    val holidayEntries = mutableStateListOf<HolidayManager.HolidayEntry>()
    val workswapEntries = mutableStateListOf<HolidayManager.HolidayEntry>()

    fun loadFrom(activity: MainActivity) {
        val all = HolidayManager.loadEntries(year)
        holidayEntries.clear()
        workswapEntries.clear()
        all.forEach {
            if (it.type == HolidayManager.TYPE_HOLIDAY) holidayEntries += it else workswapEntries += it
        }
    }
}

private class AboutComposeState {
    var version by mutableStateOf("未知版本")
    var hideIcon by mutableStateOf(false)
    var monetEnabled by mutableStateOf(false)
    var predictiveBackEnabled by mutableStateOf(true)

    fun loadFrom(activity: MainActivity) {
        version = activity.uiReadAppVersionName()
        hideIcon = activity.uiIsHideIconEnabled()
        monetEnabled = activity.uiIsMonetEnabled()
        predictiveBackEnabled = activity.uiIsPredictiveBackEnabled()
    }
}

@Composable
private fun HomeEntryPage(
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = PaddingValues(0.dp),
    state: SettingsComposeState,
    darkTheme: Boolean,
    onRefresh: () -> Unit,
    onOpen: (AppRoute) -> Unit,
    onResetConfirmed: () -> Unit,
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit,
) {
    var showResetDialog by remember { mutableStateOf(false) }
    var showConfigTransferDialog by remember { mutableStateOf(false) }
    SettingsPage(modifier = modifier, pagePadding = pagePadding) {
        item {
            LspStatusCard(
                active = state.frameworkActive,
                frameworkDesc = state.frameworkDesc,
                darkTheme = darkTheme,
                onRefresh = onRefresh,
            )
        }
        item {
            SettingsSection {
                ArrowPreference(
                    title = "测试通知",
                    summary = "发送一条测试通知以测试显示效果",
                    onClick = { onOpen(AppRoute.TestNotify) },
                )
            }
        }
        item {
            SettingsSection {
                ArrowPreference(
                    title = "状态栏岛自定义",
                    summary = "按上课前/中/后三个阶段配置状态栏岛与息屏展示",
                    onClick = { onOpen(AppRoute.StatusCustom) },
                )
                ArrowPreference(
                    title = "展开态自定义",
                    summary = "按上课前/中/后三个阶段配置展开态全部文本模板",
                    onClick = { onOpen(AppRoute.ExpandedCustom) },
                )
                ArrowPreference(
                    title = "阶段显示与时长",
                    summary = "独立启用课前、课中、课后，设置岛与通知的显示时长",
                    onClick = { onOpen(AppRoute.Timeout) },
                )
                ArrowPreference(
                    title = "课前提醒",
                    summary = "配置数据源及提前提醒分钟数与补发策略",
                    onClick = { onOpen(AppRoute.Reminder) },
                )
                ArrowPreference(
                    title = "上课免打扰",
                    summary = "自动化静音或勿扰",
                    onClick = { onOpen(AppRoute.Mute) },
                )
                ArrowPreference(
                    title = "自动叫醒",
                    summary = "根据上午下午首节课程自动设定一定时间的闹钟",
                    onClick = { onOpen(AppRoute.Wakeup) },
                )
            }
        }
        item {
            SettingsSection {
                ArrowPreference(
                    title = "全局恢复默认",
                    summary = "恢复模块默认配置",
                    onClick = { showResetDialog = true },
                )
                ArrowPreference(
                    title = "导入/导出配置",
                    summary = "导入或导出全部自定义配置",
                    onClick = { showConfigTransferDialog = true },
                )
                ArrowPreference(
                    title = "假期/调休",
                    summary = "管理节假日与调休",
                    onClick = { onOpen(AppRoute.Holiday) },
                )
                ArrowPreference(
                    title = "关于",
                    summary = "查看版本、作者信息及模块本体设置",
                    onClick = { onOpen(AppRoute.About) },
                )
            }
        }

    }

    ConfirmationDialog(
        show = showResetDialog,
        title = "恢复默认",
        summary = "将清空所有配置并恢复默认值，是否继续？",
        confirmText = "清空",
        onDismissRequest = { showResetDialog = false },
        onConfirm = {
            showResetDialog = false
            onResetConfirmed()
        },
    )

    if (showConfigTransferDialog) {
        OverlayDialog(
            show = true,
            title = "导入/导出配置",
            onDismissRequest = { showConfigTransferDialog = false },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = "导入",
                    minHeight = 50.dp,
                    onClick = {
                        showConfigTransferDialog = false
                        onImportConfig()
                    },
                )
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = "导出",
                    minHeight = 50.dp,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        showConfigTransferDialog = false
                        onExportConfig()
                    },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = "取消",
                minHeight = 46.dp,
                onClick = { showConfigTransferDialog = false },
            )
        }
    }
}

@Composable
private fun StatusCustomPage(
    activity: MainActivity,
    state: SettingsComposeState,
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = PaddingValues(0.dp),
) {
    var editDialog by remember { mutableStateOf<EditDialogSpec?>(null) }
    var showGlowColorDialog by remember { mutableStateOf(false) }
    var showTextColorDialog by remember { mutableStateOf(false) }
    val stageLabels = remember { listOf("上课前", "上课中", "下课后") }

    fun persistStatusConfig() {
        alignExpandedTimerWithStatus(state.stageStates)
        val editor = activity.uiEditConfigPrefs()
        ConfigDefaults.STAGE_SUFFIXES.forEachIndexed { idx, suffix ->
            val stageItem = state.stageStates[idx]
            editor.putString("tpl_a$suffix", stageItem.tplA.trim())
            editor.putString("tpl_b$suffix", stageItem.tplB.trim())
            editor.putString("tpl_ticker$suffix", stageItem.tplTicker.trim())
            editor.putString("tpl_hint_title$suffix", stageItem.hintTitle.trim())
            editor.putString("tpl_hint_subtitle$suffix", stageItem.hintSubtitle.trim())
        }
        editor.putBoolean("icon_a", state.iconAEnabled)
        editor.putInt("status_text_highlight_custom_color_argb", state.statusTextCustomColorArgb)
        editor.putBoolean("out_effect_status_enabled", state.outEffectStatusEnabled)
        editor.putBoolean(
            "out_effect_status_custom_color_enabled",
            state.outEffectStatusCustomColorEnabled,
        )
        editor.putInt(
            "out_effect_status_custom_color_argb",
            state.outEffectStatusCustomColorArgb,
        )
        editor.apply()
    }

    val hints = rememberDismissibleHints(activity)
    SettingsPage(modifier = modifier, pagePadding = pagePadding) {
        item {
            InformationCard(
                text = "可用变量：{课名} {开始} {结束} {教室} {节次} {教师} {倒计时} {正计时}",
            )
        }
        dismissibleHint(
            hints = hints,
            key = "hint_status_custom_timer_rule",
            text = "状态栏岛仅岛B支持计时变量，计时变量需放在开头，可在后面拼接文本；上课前不支持{正计时}，下课后不支持{倒计时}。",
        )
        dismissibleHint(
            hints = hints,
            key = "hint_status_custom_conflict",
            text = "保存时会做同阶段计时冲突校验：保存状态栏岛时会将展开态主要小文本1/2对齐到状态栏岛B；保存展开态时会将状态栏岛B对齐到展开态。同阶段展开态与状态栏岛B只能保留一种计时类型（正计时或倒计时）。",
        )
        items(stageLabels.indices.toList()) { i ->
            val stage = state.stageStates[i]
            val label = stageLabels[i]
            SettingsSection(
                title = label,
            ) {
                ArrowPreference(
                    title = "岛A（左侧文字）",
                    endActions = { PreferenceValue(stage.tplA.ifBlank { "未设置" }) },
                    onClick = {
                        editDialog = EditDialogSpec(
                            title = "$label - 岛A（左侧文字）",
                            initialValue = stage.tplA,
                            onConfirm = {
                                state.stageStates[i] = state.stageStates[i].copy(tplA = it)
                                persistStatusConfig()
                            },
                        )
                    },
                )
                ArrowPreference(
                    title = "岛B（右侧文字）",
                    endActions = { PreferenceValue(stage.tplB.ifBlank { "未设置" }) },
                    onClick = {
                        editDialog = EditDialogSpec(
                            title = "$label - 岛B（右侧文字）",
                            initialValue = stage.tplB,
                            onConfirm = {
                                state.stageStates[i] = state.stageStates[i].copy(tplB = it)
                                persistStatusConfig()
                            },
                        )
                    },
                )
                ArrowPreference(
                    title = "息屏显示",
                    endActions = { PreferenceValue(stage.tplTicker.ifBlank { "未设置" }) },
                    onClick = {
                        editDialog = EditDialogSpec(
                            title = "$label - 息屏显示",
                            initialValue = stage.tplTicker,
                            onConfirm = {
                                state.stageStates[i] = state.stageStates[i].copy(tplTicker = it)
                                persistStatusConfig()
                            },
                        )
                    },
                )
            }
        }
        item {
            SettingsSection {
                SwitchPreference(
                    title = "岛A显示图标",
                    checked = state.iconAEnabled,
                    onCheckedChange = {
                        state.iconAEnabled = it
                        persistStatusConfig()
                    },
                )
                GlowColorValuePreference(
                    title = "文本颜色",
                    argb = state.statusTextCustomColorArgb,
                    onClick = { showTextColorDialog = true },
                )
                SwitchPreference(
                    title = "发光效果",
                    checked = state.outEffectStatusEnabled,
                    onCheckedChange = {
                        state.outEffectStatusEnabled = it
                        persistStatusConfig()
                    },
                )
                if (state.outEffectStatusEnabled) {
                    SwitchPreference(
                        title = "发光自定义颜色",
                        checked = state.outEffectStatusCustomColorEnabled,
                        onCheckedChange = {
                            state.outEffectStatusCustomColorEnabled = it
                            persistStatusConfig()
                        },
                    )
                    if (state.outEffectStatusCustomColorEnabled) {
                        GlowColorValuePreference(
                            title = "发光颜色",
                            argb = state.outEffectStatusCustomColorArgb,
                            onClick = { showGlowColorDialog = true },
                        )
                    }
                }
            }
        }

    }
    editDialog?.let { spec ->
        EditValueDialog(spec = spec, onDismiss = { editDialog = null })
    }
    if (showGlowColorDialog) {
        GlowColorPickerDialog(
            title = "选择发光颜色",
            initialArgb = state.outEffectStatusCustomColorArgb,
            onDismiss = { showGlowColorDialog = false },
            onConfirm = { argb ->
                state.outEffectStatusCustomColorArgb = argb
                persistStatusConfig()
                showGlowColorDialog = false
            },
        )
    }
    if (showTextColorDialog) {
        GlowColorPickerDialog(
            title = "选择文本颜色",
            initialArgb = state.statusTextCustomColorArgb,
            onDismiss = { showTextColorDialog = false },
            onConfirm = { argb ->
                state.statusTextCustomColorArgb = argb
                persistStatusConfig()
                showTextColorDialog = false
            },
        )
    }
}

@Composable
private fun ExpandedCustomPage(
    activity: MainActivity,
    state: SettingsComposeState,
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = PaddingValues(0.dp),
) {
    var editDialog by remember { mutableStateOf<EditDialogSpec?>(null) }
    var showColorDialog by remember { mutableStateOf(false) }
    val sectionTitles = remember { listOf("上课前", "上课中", "下课后") }

    fun persistExpandedConfig() {
        alignStatusTimerWithExpanded(state.stageStates)
        val editor = activity.uiEditConfigPrefs()
        ConfigDefaults.STAGE_SUFFIXES.forEachIndexed { idx, suffix ->
            val stageItem = state.stageStates[idx]
            editor.putString("tpl_b$suffix", stageItem.tplB.trim())
            editor.putString(
                "${ConfigDefaults.EXPANDED_TPL_KEYS[0]}$suffix",
                stageItem.baseTitle.trim()
            )
            editor.putString(
                "${ConfigDefaults.EXPANDED_TPL_KEYS[1]}$suffix",
                stageItem.hintTitle.trim()
            )
            editor.putString(
                "${ConfigDefaults.EXPANDED_TPL_KEYS[2]}$suffix",
                stageItem.hintSubtitle.trim()
            )
            editor.putString(
                "${ConfigDefaults.EXPANDED_TPL_KEYS[3]}$suffix",
                stageItem.hintContent.trim()
            )
            editor.putString(
                "${ConfigDefaults.EXPANDED_TPL_KEYS[4]}$suffix",
                stageItem.hintSubcontent.trim()
            )
            editor.putString(
                "${ConfigDefaults.EXPANDED_TPL_KEYS[5]}$suffix",
                stageItem.baseContent.trim()
            )
            editor.putString(
                "${ConfigDefaults.EXPANDED_TPL_KEYS[6]}$suffix",
                stageItem.baseSubcontent.trim()
            )
        }
        editor.putBoolean("out_effect_expand_enabled", state.outEffectExpandEnabled)
        editor.putBoolean(
            "out_effect_expand_custom_color_enabled",
            state.outEffectExpandCustomColorEnabled,
        )
        editor.putInt(
            "out_effect_expand_custom_color_argb",
            state.outEffectExpandCustomColorArgb,
        )
        editor.apply()
    }

    val hints = rememberDismissibleHints(activity)
    SettingsPage(modifier = modifier, pagePadding = pagePadding) {
        item {
            InformationCard(
                text = "可用变量：{课名} {开始} {结束} {教室} {节次} {教师} {倒计时} {正计时}",
            )
        }
        dismissibleHint(
            hints = hints,
            key = "hint_expanded_custom_timer_rule",
            text = "上课前不支持{正计时}，下课后不支持{倒计时}。计时变量仅主要小文本1/2支持，且不可与其他字符串拼接。",
        )
        dismissibleHint(
            hints = hints,
            key = "hint_expanded_custom_conflict",
            text = "保存时会做同阶段计时冲突校验：保存状态栏岛时会将展开态主要小文本1/2对齐到状态栏岛B；保存展开态时会将状态栏岛B对齐到展开态。同阶段展开态与状态栏岛B只能保留一种计时类型（正计时或倒计时）。",
        )
        items(sectionTitles.indices.toList()) { i ->
            val stage = state.stageStates[i]
            val title = sectionTitles[i]
            SettingsSection(
                title = title,
            ) {
                ArrowPreference(
                    title = "主要标题",
                    endActions = { PreferenceValue(stage.baseTitle.ifBlank { "未设置" }) },
                    onClick = {
                        editDialog = EditDialogSpec(
                            title = "$title - 主要标题",
                            initialValue = stage.baseTitle,
                            onConfirm = {
                                state.stageStates[i] = state.stageStates[i].copy(baseTitle = it)
                                persistExpandedConfig()
                            },
                        )
                    },
                )
                ArrowPreference(
                    title = "次要文本1",
                    endActions = { PreferenceValue(stage.baseContent.ifBlank { "未设置" }) },
                    onClick = {
                        editDialog = EditDialogSpec(
                            title = "$title - 次要文本1",
                            initialValue = stage.baseContent,
                            onConfirm = {
                                state.stageStates[i] = state.stageStates[i].copy(baseContent = it)
                                persistExpandedConfig()
                            },
                        )
                    },
                )
                ArrowPreference(
                    title = "次要文本2",
                    endActions = { PreferenceValue(stage.baseSubcontent.ifBlank { "未设置" }) },
                    onClick = {
                        editDialog = EditDialogSpec(
                            title = "$title - 次要文本2",
                            initialValue = stage.baseSubcontent,
                            onConfirm = {
                                state.stageStates[i] =
                                    state.stageStates[i].copy(baseSubcontent = it)
                                persistExpandedConfig()
                            },
                        )
                    },
                )
                ArrowPreference(
                    title = "前置文本1",
                    endActions = { PreferenceValue(stage.hintContent.ifBlank { "未设置" }) },
                    onClick = {
                        editDialog = EditDialogSpec(
                            title = "$title - 前置文本1",
                            initialValue = stage.hintContent,
                            onConfirm = {
                                state.stageStates[i] = state.stageStates[i].copy(hintContent = it)
                                persistExpandedConfig()
                            },
                        )
                    },
                )
                ArrowPreference(
                    title = "前置文本2",
                    endActions = { PreferenceValue(stage.hintSubcontent.ifBlank { "未设置" }) },
                    onClick = {
                        editDialog = EditDialogSpec(
                            title = "$title - 前置文本2",
                            initialValue = stage.hintSubcontent,
                            onConfirm = {
                                state.stageStates[i] =
                                    state.stageStates[i].copy(hintSubcontent = it)
                                persistExpandedConfig()
                            },
                        )
                    },
                )
                ArrowPreference(
                    title = "主要小文本1",
                    endActions = { PreferenceValue(stage.hintTitle.ifBlank { "未设置" }) },
                    onClick = {
                        editDialog = EditDialogSpec(
                            title = "$title - 主要小文本1",
                            initialValue = stage.hintTitle,
                            onConfirm = {
                                state.stageStates[i] = state.stageStates[i].copy(hintTitle = it)
                                persistExpandedConfig()
                            },
                        )
                    },
                )
                ArrowPreference(
                    title = "主要小文本2",
                    endActions = { PreferenceValue(stage.hintSubtitle.ifBlank { "未设置" }) },
                    onClick = {
                        editDialog = EditDialogSpec(
                            title = "$title - 主要小文本2",
                            initialValue = stage.hintSubtitle,
                            onConfirm = {
                                state.stageStates[i] = state.stageStates[i].copy(hintSubtitle = it)
                                persistExpandedConfig()
                            },
                        )
                    },
                )
            }
        }
        item {
            SettingsSection {
                SwitchPreference(
                    title = "发光效果",
                    checked = state.outEffectExpandEnabled,
                    onCheckedChange = {
                        state.outEffectExpandEnabled = it
                        persistExpandedConfig()
                    },
                )
                if (state.outEffectExpandEnabled) {
                    SwitchPreference(
                        title = "发光自定义颜色",
                        checked = state.outEffectExpandCustomColorEnabled,
                        onCheckedChange = {
                            state.outEffectExpandCustomColorEnabled = it
                            persistExpandedConfig()
                        },
                    )
                    if (state.outEffectExpandCustomColorEnabled) {
                        GlowColorValuePreference(
                            title = "发光颜色",
                            argb = state.outEffectExpandCustomColorArgb,
                            onClick = { showColorDialog = true },
                        )
                    }
                }
            }
        }

    }
    editDialog?.let { spec ->
        EditValueDialog(spec = spec, onDismiss = { editDialog = null })
    }
    if (showColorDialog) {
        GlowColorPickerDialog(
            initialArgb = state.outEffectExpandCustomColorArgb,
            onDismiss = { showColorDialog = false },
            onConfirm = { argb ->
                state.outEffectExpandCustomColorArgb = argb
                persistExpandedConfig()
                showColorDialog = false
            },
        )
    }
}

private fun formatColorHexArgb(argb: Int): String {
    return String.format(Locale.ROOT, "#%08X", argb)
}

private fun parseColorHexArgbOrNull(input: String): Int? {
    val text = input.trim().uppercase(Locale.ROOT)
    if (!text.startsWith("#")) return null
    return try {
        when (text.length) {
            7 -> (0xFF000000L or text.substring(1).toLong(16)).toInt()
            9 -> text.substring(1).toLong(16).toInt()
            else -> null
        }
    } catch (_: Throwable) {
        null
    }
}

private fun normalizeColorHexInput(raw: String): String {
    val upper = raw.uppercase(Locale.ROOT)
    val hexOnly = upper.filter { it in '0'..'9' || it in 'A'..'F' }
    return "#" + hexOnly.take(8)
}

@Composable
private fun GlowColorValuePreference(
    title: String,
    argb: Int,
    onClick: () -> Unit,
) {
    val previewColor = Color(argb)
    val borderColor = if (previewColor.luminance() > 0.92f) {
        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.32f)
    } else {
        Color.Transparent
    }
    ArrowPreference(
        title = title,
        endActions = {
            Row(
                modifier = Modifier.widthIn(max = 130.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(previewColor, RoundedCornerShape(4.dp))
                        .then(
                            if (borderColor != Color.Transparent) {
                                Modifier.border(1.dp, borderColor, RoundedCornerShape(4.dp))
                            } else {
                                Modifier
                            },
                        ),
                )
                Text(
                    text = formatColorHexArgb(argb),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun GlowColorPickerDialog(
    title: String = "选择发光颜色",
    initialArgb: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var pickedColor by remember(initialArgb) { mutableStateOf(Color(initialArgb)) }
    var hexInput by remember(initialArgb) { mutableStateOf(formatColorHexArgb(initialArgb)) }
    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        ColorPicker(
            color = pickedColor,
            onColorChanged = {
                pickedColor = it
                val hex = formatColorHexArgb(it.toArgb())
                if (hexInput != hex) hexInput = hex
            },
            colorSpace = ColorSpace.HSV,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(10.dp))
        TextField(
            value = hexInput,
            onValueChange = { value ->
                val normalized = normalizeColorHexInput(value)
                hexInput = normalized
                val parsed = parseColorHexArgbOrNull(normalized)
                if (parsed != null && parsed != pickedColor.toArgb()) {
                    pickedColor = Color(parsed)
                }
            },
            label = "#AARRGGBB / #RRGGBB",
            modifier = Modifier.fillMaxWidth(),
            textStyle = MiuixTheme.textStyles.main.copy(color = MiuixTheme.colorScheme.onSurface),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        Spacer(modifier = Modifier.height(12.dp))
        DialogActions(
            onDismiss = onDismiss,
            onConfirm = { onConfirm(pickedColor.toArgb()) },
        )
    }
}

@Composable
private fun TestNotifyPage(
    activity: MainActivity,
    state: SettingsComposeState,
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = PaddingValues(0.dp),
) {
    var editDialog by remember { mutableStateOf<EditDialogSpec?>(null) }
    val hints = rememberDismissibleHints(activity)
    SettingsPage(modifier = modifier, pagePadding = pagePadding) {
        dismissibleHint(
            hints = hints,
            key = "hint_test_notify",
            text = "发送一条模拟课程提醒，验证超级岛效果是否正常。如果未发送，请强制停止作用域和模块重试。如果测试通知正常但实际提醒失效，请在桌面或负一屏添加小爱课程表小组件。",
        )
        item(key = "section_0") {
            SettingsSection {
                ArrowPreference(
                    title = "课程名称",
                    endActions = { PreferenceValue(state.courseName.ifBlank { "未设置" }) },
                    onClick = {
                        editDialog = EditDialogSpec(
                            title = "课程名称",
                            initialValue = state.courseName,
                            onConfirm = { state.courseName = it },
                        )
                    },
                )
                ArrowPreference(
                    title = "教室",
                    endActions = { PreferenceValue(state.classroom.ifBlank { "未设置" }) },
                    onClick = {
                        editDialog = EditDialogSpec(
                            title = "教室",
                            initialValue = state.classroom,
                            onConfirm = { state.classroom = it },
                        )
                    },
                )

                Button(
                    onClick = {
                        activity.uiSendTestBroadcastToTarget(
                            60_000L,
                            state.courseName,
                            state.classroom
                        )
                        Toast.makeText(
                            activity,
                            "已发送测试通知，请查看超级岛效果",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text("发送测试通知")
                }
            }
        }
    }
    editDialog?.let { spec ->
        EditValueDialog(spec = spec, onDismiss = { editDialog = null })
    }
}

@Composable
private fun MutedText(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
    )
}

@Composable
private fun TimeoutPage(
    activity: MainActivity,
    state: SettingsComposeState,
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = PaddingValues(0.dp),
) {
    val stageLabels = remember { listOf("上课前", "上课中", "下课后") }
    var pickerStage by remember { mutableIntStateOf(-1) }

    fun persist(updated: TimeoutUiState) {
        val editor = activity.uiEditConfigPrefs()
        writeTimeoutState(editor, updated)
        editor.apply()
        state.timeoutState = updated
    }

    val hints = rememberDismissibleHints(activity)
    SettingsPage(modifier = modifier, pagePadding = pagePadding) {
        dismissibleHint(
            hints = hints,
            key = "hint_stage_display",
            text = "关闭阶段或显示时长到期后，岛与通知一起隐藏；下一个开启的阶段会重新显示。每节课仅首次显示时提醒，修改设置后恢复显示保持静默。默认显示时长为 60 分钟。",
        )
        stageLabels.forEachIndexed { idx, label ->
            item(key = "stage_display_$idx") {
                SettingsSection(title = label) {
                    SwitchPreference(
                        title = "启用",
                        checked = state.timeoutState.enabled[idx],
                        onCheckedChange = { checked ->
                            persist(state.timeoutState.copy(
                                enabled = state.timeoutState.enabled.toMutableList().apply {
                                    this[idx] = checked
                                },
                            ))
                            if (!checked && pickerStage == idx) pickerStage = -1
                        },
                    )
                    if (state.timeoutState.enabled[idx]) {
                        ArrowPreference(
                            title = "显示时长",
                            endActions = {
                                PreferenceValue(formatTimeoutDuration(
                                    state.timeoutState.islandVals[idx],
                                    state.timeoutState.islandUnits[idx],
                                ))
                            },
                            onClick = { pickerStage = idx },
                        )
                    }
                }
            }
        }
    }
    if (pickerStage in stageLabels.indices && state.timeoutState.enabled[pickerStage]) {
        val stage = pickerStage
        MiuixDurationPickerDialog(
            title = "显示时长（${stageLabels[stage]}）",
            initialValue = state.timeoutState.islandVals[stage],
            initialUnit = state.timeoutState.islandUnits[stage],
            onDismiss = { pickerStage = -1 },
            onConfirm = { value, unit ->
                persist(state.timeoutState.copy(
                    islandVals = state.timeoutState.islandVals.toMutableList().apply {
                        this[stage] = value
                    },
                    islandUnits = state.timeoutState.islandUnits.toMutableList().apply {
                        this[stage] = unit
                    },
                ))
                pickerStage = -1
            },
        )
    }
}

@Composable
private fun ReminderPage(
    activity: MainActivity,
    state: SettingsComposeState,
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = PaddingValues(0.dp),
) {
    var showReminderPicker by remember { mutableStateOf(false) }
    val dataSourceEntries = remember {
        listOf(
            "超级小爱",
            "WakeUp",
            "拾光",
        )
    }
    val dataSourceIndex = when {
        state.courseDataSource.equals("wakeup", ignoreCase = true) -> 1
        state.courseDataSource.equals("shiguang", ignoreCase = true) -> 2
        else -> 0
    }
    val hints = rememberDismissibleHints(activity)
    SettingsPage(modifier = modifier, pagePadding = pagePadding) {
        dismissibleHint(
            hints = hints,
            key = "hint_reminder",
            text = "自定义设置通知发送时机",
        )
        item(key = "section_0") {
            SettingsSection {
                OverlayDropdownPreference(
                    title = "课程数据源",
                    summary = "通知仍由超级小爱发出",
                    items = dataSourceEntries,
                    selectedIndex = dataSourceIndex,
                    onSelectedIndexChange = {
                        val source = when (it) {
                            1 -> "wakeup"
                            2 -> "shiguang"
                            else -> "xiaoai"
                        }
                        activity.uiEnsureScopeForCourseDataSource(source) {
                            state.courseDataSource = source
                            activity.uiEditConfigPrefs().putString("course_data_source", source)
                                .apply()
                            activity.uiOnCourseDataSourceChanged(source)
                        }
                    },
                )

                SwitchPreference(
                    title = "补发机制（全局）",
                    summary = "是否在错过提醒时间时补发，由于通知已稳定，不建议启用。",
                    checked = state.repostEnabled,
                    onCheckedChange = {
                        state.repostEnabled = it
                        activity.uiEditConfigPrefs().putBoolean("repost_enabled", it).apply()
                    },
                )

                ArrowPreference(
                    title = "提前提醒",
                    endActions = { PreferenceValue("${state.reminderMinutes.ifBlank { "15" }} 分钟") },
                    onClick = {
                        showReminderPicker = true
                    },
                )
            }
        }
    }
    if (showReminderPicker) {
        MiuixMinutePickerDialog(
            title = "提前提醒",
            initialValue = state.reminderMinutes.toIntOrNull() ?: 15,
            minValue = 0,
            maxValue = MAX_MINUTE_VALUE,
            onDismiss = { showReminderPicker = false },
            onConfirm = {
                state.reminderMinutes = it.toString()
                activity.uiEditConfigPrefs().putInt("reminder_minutes_before", it).apply()
                showReminderPicker = false
            },
        )
    }
}

@Composable
private fun MutePage(
    activity: MainActivity,
    state: SettingsComposeState,
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = PaddingValues(0.dp),
) {
    val buttonModeEntries = remember {
        listOf(
            "静音",
            "勿扰",
            "两者",
            "逃课",
        )
    }

    fun persistMuteConfigNow() {
        val muteBefore = clampMinuteValue(state.muteMinsBefore)
        val unmuteAfter = clampMinuteValue(state.unmuteMinsAfter)
        val dndBefore = clampMinuteValue(state.dndMinsBefore)
        val undndAfter = clampMinuteValue(state.undndMinsAfter)
        state.muteMinsBefore = muteBefore.toString()
        state.unmuteMinsAfter = unmuteAfter.toString()
        state.dndMinsBefore = dndBefore.toString()
        state.undndMinsAfter = undndAfter.toString()
        activity.uiEditConfigPrefs()
            .putBoolean("mute_enabled", state.muteEnabled)
            .putBoolean("unmute_enabled", state.unmuteEnabled)
            .putBoolean("dnd_enabled", state.dndEnabled)
            .putBoolean("undnd_enabled", state.undndEnabled)
            .putInt("mute_mins_before", muteBefore)
            .putInt("unmute_mins_after", unmuteAfter)
            .putInt("dnd_mins_before", dndBefore)
            .putInt("undnd_mins_after", undndAfter)
            .putInt("island_button_mode", state.islandButtonMode.coerceIn(0, 3))
            .apply()
    }

    val hints = rememberDismissibleHints(activity)
    SettingsPage(modifier = modifier, pagePadding = pagePadding) {
        item(key = "section_0") {
            SettingsSection {
                SwitchPreference(
                    title = "上课自动静音",
                    summary = "课程开始前指定时间将手机调为静音",
                    checked = state.muteEnabled,
                    onCheckedChange = {
                        state.muteEnabled = it
                        persistMuteConfigNow()
                    },
                )
                if (state.muteEnabled) {
                    MinuteEditor("上课前多少分钟静音", state.muteMinsBefore) {
                        state.muteMinsBefore = it
                        persistMuteConfigNow()
                    }
                }

                SwitchPreference(
                    title = "下课自动恢复铃声",
                    summary = "课程结束后指定时间恢复正常响铃",
                    checked = state.unmuteEnabled,
                    onCheckedChange = {
                        state.unmuteEnabled = it
                        persistMuteConfigNow()
                    },
                )
                if (state.unmuteEnabled) {
                    MinuteEditor("下课后多少分钟恢复铃声", state.unmuteMinsAfter) {
                        state.unmuteMinsAfter = it
                        persistMuteConfigNow()
                    }
                }



                SwitchPreference(
                    title = "上课自动开启勿扰",
                    summary = "课程开始前指定时间开启勿扰模式",
                    checked = state.dndEnabled,
                    onCheckedChange = {
                        state.dndEnabled = it
                        persistMuteConfigNow()
                    },
                )
                if (state.dndEnabled) {
                    MinuteEditor("上课前多少分钟开启勿扰", state.dndMinsBefore) {
                        state.dndMinsBefore = it
                        persistMuteConfigNow()
                    }
                }

                SwitchPreference(
                    title = "下课自动关闭勿扰",
                    summary = "课程结束后指定时间关闭勿扰，恢复正常通知",
                    checked = state.undndEnabled,
                    onCheckedChange = {
                        state.undndEnabled = it
                        persistMuteConfigNow()
                    },
                )
                if (state.undndEnabled) {
                    MinuteEditor("下课后多少分钟关闭勿扰", state.undndMinsAfter) {
                        state.undndMinsAfter = it
                        persistMuteConfigNow()
                    }
                }
            }
        }
        dismissibleHint(
            hints = hints,
            key = "hint_island_button_mode",
            text = "设置上课岛上显示的按钮执行的操作。两者即同时勿扰和静音，在岛上显示为静默。",
        )
        item(key = "超级岛按钮功能") {
            SettingsSection(
                title = "超级岛按钮功能",
            ) {
                OverlayDropdownPreference(
                    title = "按钮模式",
                    items = buttonModeEntries,
                    selectedIndex = state.islandButtonMode.coerceIn(0, 3),
                    onSelectedIndexChange = {
                        state.islandButtonMode = it
                        persistMuteConfigNow()
                    },
                )
            }
        }
    }

}

@Composable
private fun MinuteEditor(
    label: String,
    value: String,
    minValue: Int = 0,
    maxValue: Int = MAX_MINUTE_VALUE,
    onValue: (String) -> Unit,
) {
    var showMinutePicker by remember(label) { mutableStateOf(false) }
    val safeMin = minValue.coerceAtLeast(0)
    val safeMax = maxOf(safeMin, maxValue)
    val current = (value.toIntOrNull() ?: safeMin).coerceIn(safeMin, safeMax)
    ArrowPreference(
        title = label,
        endActions = { PreferenceValue("$current 分钟") },
        onClick = { showMinutePicker = true },
    )
    if (showMinutePicker) {
        MiuixMinutePickerDialog(
            title = label,
            initialValue = current,
            minValue = safeMin,
            maxValue = safeMax,
            onDismiss = { showMinutePicker = false },
            onConfirm = {
                onValue(it.toString())
                showMinutePicker = false
            },
        )
    }
}

@Composable
private fun SectionEditor(
    label: String,
    value: String,
    minSec: Int = 1,
    maxSec: Int = 30,
    onValue: (String) -> Unit,
) {
    var showPicker by remember(label) { mutableStateOf(false) }
    val safeMin = minSec.coerceAtLeast(1)
    val safeMax = maxOf(safeMin, maxSec)
    val currentSec = (value.toIntOrNull() ?: safeMin).coerceIn(safeMin, safeMax)
    ArrowPreference(
        title = label,
        endActions = { PreferenceValue("第${currentSec}节") },
        onClick = { showPicker = true },
    )
    if (showPicker) {
        MiuixSectionPickerDialog(
            title = label,
            initialSec = currentSec,
            minSec = safeMin,
            maxSec = safeMax,
            onDismiss = { showPicker = false },
            onConfirm = {
                onValue(it.toString())
                showPicker = false
            },
        )
    }
}

@Composable
private fun WakeupPage(
    activity: MainActivity,
    state: SettingsComposeState,
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = PaddingValues(0.dp),
) {
    val morningBoundary = (state.wakeupMorningLastSec.toIntOrNull() ?: 4).coerceAtLeast(1)
    val afternoonBoundary = (state.wakeupAfternoonFirstSec.toIntOrNull() ?: 5).coerceAtLeast(1)
    val morningRuleMax = morningBoundary
    val knownMaxSec = maxOf(
        morningBoundary,
        afternoonBoundary,
        state.wakeupMorningRules.maxOfOrNull { (it.sec.toIntOrNull() ?: 1).coerceAtLeast(1) } ?: 1,
        state.wakeupAfternoonRules.maxOfOrNull {
            (it.sec.toIntOrNull() ?: afternoonBoundary).coerceAtLeast(afternoonBoundary)
        } ?: afternoonBoundary,
    )
    val sectionBoundaryMax = maxOf(30, knownMaxSec)

    fun persistWakeupConfigNow() {
        val morningLast = (state.wakeupMorningLastSec.toIntOrNull() ?: 4).coerceAtLeast(1)
        val afternoonFirst = (state.wakeupAfternoonFirstSec.toIntOrNull() ?: 5).coerceAtLeast(1)
        state.wakeupMorningRules.replaceAll { rule ->
            rule.copy(
                sec = (rule.sec.toIntOrNull() ?: 1).coerceIn(1, morningLast).toString(),
            )
        }
        state.wakeupAfternoonRules.replaceAll { rule ->
            rule.copy(
                sec = (rule.sec.toIntOrNull() ?: afternoonFirst).coerceAtLeast(afternoonFirst)
                    .toString(),
            )
        }
        state.wakeupMorningLastSec = morningLast.toString()
        state.wakeupAfternoonFirstSec = afternoonFirst.toString()
        activity.uiEditConfigPrefs()
            .putBoolean("wakeup_morning_enabled", state.wakeupMorningEnabled)
            .putInt("wakeup_morning_last_sec", morningLast)
            .putString("wakeup_morning_rules_json", toWakeRulesJson(state.wakeupMorningRules))
            .putBoolean("wakeup_afternoon_enabled", state.wakeupAfternoonEnabled)
            .putInt("wakeup_afternoon_first_sec", afternoonFirst)
            .putString("wakeup_afternoon_rules_json", toWakeRulesJson(state.wakeupAfternoonRules))
            .apply()
    }

    val hints = rememberDismissibleHints(activity)
    SettingsPage(modifier = modifier, pagePadding = pagePadding) {
        dismissibleHint(
            hints = hints,
            key = "hint_wakeup",
            text = "根据课表在系统时钟创建叫醒闹钟",
        )
        item(key = "section_0") {
            SettingsSection {
                SwitchPreference(
                    title = "上午自动叫醒",
                    summary = "根据上午第一次课的节次指定闹钟设置",
                    checked = state.wakeupMorningEnabled,
                    onCheckedChange = {
                        if (it) {
                            activity.uiEnsureScopeForWakeupEnable {
                                state.wakeupMorningEnabled = true
                                persistWakeupConfigNow()
                            }
                            return@SwitchPreference
                        }
                        state.wakeupMorningEnabled = it
                        persistWakeupConfigNow()
                    },
                )


                SwitchPreference(
                    title = "下午自动叫醒",
                    summary = "根据下午第一次课的节次指定闹钟设置",
                    checked = state.wakeupAfternoonEnabled,
                    onCheckedChange = {
                        if (it) {
                            activity.uiEnsureScopeForWakeupEnable {
                                state.wakeupAfternoonEnabled = true
                                persistWakeupConfigNow()
                            }
                            return@SwitchPreference
                        }
                        state.wakeupAfternoonEnabled = it
                        persistWakeupConfigNow()
                    },
                )
            }
        }
        if (state.wakeupMorningEnabled) {
            item(key = "上午规则") {
                SettingsSection(
                    title = "上午规则",
                ) {
                    WakeRuleList(
                        rules = state.wakeupMorningRules,
                        defaultRule = WakeRule("1", "7", "00"),
                        minSec = 1,
                        maxSec = morningRuleMax,
                        onChanged = { persistWakeupConfigNow() },
                    )
                }
            }
        }
        if (state.wakeupAfternoonEnabled) {
            item(key = "下午规则") {
                SettingsSection(
                    title = "下午规则",
                ) {
                    WakeRuleList(
                        rules = state.wakeupAfternoonRules,
                        defaultRule = WakeRule("5", "12", "00"),
                        minSec = afternoonBoundary,
                        maxSec = maxOf(30, afternoonBoundary, knownMaxSec),
                        onChanged = { persistWakeupConfigNow() },
                    )
                }
            }
        }

        dismissibleHint(
            hints = hints,
            key = "hint_wakeup_section_boundary",
            text = "用于区分上午/下午课程边界",
        )
        item(key = "节次划分") {
            SettingsSection(
                title = "节次划分",
            ) {
                SectionEditor(
                    label = "上午最大节次（≤此节为上午）",
                    value = state.wakeupMorningLastSec,
                    minSec = 1,
                    maxSec = sectionBoundaryMax,
                ) {
                    state.wakeupMorningLastSec = it
                    persistWakeupConfigNow()
                }
                SectionEditor(
                    label = "下午起始节次（≥此节为下午）",
                    value = state.wakeupAfternoonFirstSec,
                    minSec = 1,
                    maxSec = sectionBoundaryMax,
                ) {
                    state.wakeupAfternoonFirstSec = it
                    persistWakeupConfigNow()
                }
            }
        }
    }

}

@Composable
private fun WakeRuleList(
    rules: MutableList<WakeRule>,
    defaultRule: WakeRule,
    minSec: Int = 1,
    maxSec: Int = 30,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val safeMin = minSec.coerceAtLeast(1)
    val safeMax = maxOf(safeMin, maxSec)
    var editingIndex by remember { mutableIntStateOf(-1) }
    var pendingDeleteIndex by remember { mutableIntStateOf(-1) }
    if (rules.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            rules.forEachIndexed { index, rule ->
                WakeRuleRow(
                    index = index,
                    rule = rule,
                    onEdit = { editingIndex = index },
                    onDelete = { pendingDeleteIndex = index },
                )
                if (index != rules.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
    }
    ArrowPreference(
        title = "新增规则",
        summary = "添加节次与叫醒时间",
        onClick = {
            val sec = (defaultRule.sec.toIntOrNull() ?: safeMin).coerceIn(safeMin, safeMax)
            rules += defaultRule.copy(sec = sec.toString())
            onChanged()
            editingIndex = rules.lastIndex
        },
    )
    if (editingIndex in rules.indices) {
        var sec by remember(editingIndex) {
            mutableIntStateOf(
                (rules[editingIndex].sec.toIntOrNull() ?: safeMin).coerceIn(
                    safeMin,
                    safeMax
                )
            )
        }
        var hour by remember(editingIndex) {
            mutableIntStateOf((rules[editingIndex].hour.toIntOrNull() ?: 0).coerceIn(0, 23))
        }
        var minute by remember(editingIndex) {
            mutableIntStateOf((rules[editingIndex].minute.toIntOrNull() ?: 0).coerceIn(0, 59))
        }
        var showSecPicker by remember(editingIndex) { mutableStateOf(false) }
        var showTimePicker by remember(editingIndex) { mutableStateOf(false) }
        OverlayDialog(
            show = true,
            title = "编辑规则",
            onDismissRequest = { editingIndex = -1 },
        ) {
            ArrowPreference(
                title = "节次: 第${sec}节",
                onClick = { showSecPicker = true },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            ArrowPreference(
                title = "时间: ${String.format(locale, "%02d", hour)}:${
                    String.format(
                        locale,
                        "%02d",
                        minute
                    )
                }",
                onClick = { showTimePicker = true },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            DialogActions(
                onDismiss = { editingIndex = -1 },
                onConfirm = {
                    if (editingIndex in rules.indices) {
                        rules[editingIndex] = rules[editingIndex].copy(
                            sec = sec.toString(),
                            hour = hour.toString(),
                            minute = String.format(
                                locale,
                                "%02d",
                                minute,
                            ),
                        )
                        onChanged()
                        Toast.makeText(
                            context,
                            "已保存规则 ${editingIndex + 1}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    editingIndex = -1
                },
            )
        }
        if (showSecPicker) {
            MiuixSectionPickerDialog(
                title = "选择节次",
                initialSec = sec,
                minSec = safeMin,
                maxSec = safeMax,
                onDismiss = { showSecPicker = false },
                onConfirm = {
                    sec = it
                    showSecPicker = false
                },
            )
        }
        if (showTimePicker) {
            MiuixTimePickerDialog(
                title = "选择时间",
                initialHour = hour,
                initialMinute = minute,
                onDismiss = { showTimePicker = false },
                onConfirm = { h, m ->
                    hour = h
                    minute = m
                    showTimePicker = false
                },
            )
        }
    }

    if (pendingDeleteIndex in rules.indices) {
        ConfirmationDialog(
            show = true,
            title = "删除规则",
            summary = "确定删除规则 ${pendingDeleteIndex + 1} 吗？",
            confirmText = "删除",
            onDismissRequest = { pendingDeleteIndex = -1 },
            onConfirm = {
                val idx = pendingDeleteIndex
                pendingDeleteIndex = -1
                if (idx in rules.indices) {
                    rules.removeAt(idx)
                    onChanged()
                    Toast.makeText(
                        context,
                        "已删除规则 ${idx + 1}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
        )
    }
}

@Composable
private fun WakeRuleRow(
    index: Int,
    rule: WakeRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val hour = rule.hour.toIntOrNull()?.coerceIn(0, 23) ?: 0
    val minute = rule.minute.toIntOrNull()?.coerceIn(0, 59) ?: 0
    val sec = rule.sec.toIntOrNull()?.coerceAtLeast(1) ?: 1
    CompactEditableEntry(onEdit = onEdit, onDelete = onDelete) {
        Text(
            "规则 ${index + 1}",
            style = MiuixTheme.textStyles.main,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "第${sec}节 -> ${String.format(locale, "%02d", hour)}:${
                String.format(
                    locale,
                    "%02d",
                    minute
                )
            }",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private fun clampMinuteValue(value: String): Int =
    value.toIntOrNull()?.coerceIn(0, MAX_MINUTE_VALUE) ?: 0

private fun normalizeTimeoutUnit(unit: String): String = when (unit) {
    "s" -> "s"
    "h" -> "h"
    else -> "m"
}

private fun timeoutUnitLabel(unit: String): String = when (normalizeTimeoutUnit(unit)) {
    "s" -> "秒"
    "h" -> "时"
    else -> "分"
}

private fun formatTimeoutDuration(value: Int, unit: String): String {
    if (value <= 0) return "默认"
    return "$value ${timeoutUnitLabel(unit)}"
}

private fun readTimeoutState(prefs: android.content.SharedPreferences): TimeoutUiState {
    val cfg = TimeoutConfig.read(PrefsAccess.resolve(prefs))
    return TimeoutUiState(
        enabled = cfg.enabled.toMutableList(),
        islandVals = cfg.islandVals.toMutableList(),
        islandUnits = cfg.islandUnits.toMutableList(),
    )
}

private fun writeTimeoutState(
    editor: android.content.SharedPreferences.Editor,
    state: TimeoutUiState,
) {
    val save = TimeoutConfig.read(PrefsAccess.resolve(null))
    for (i in state.islandVals.indices) {
        save.enabled[i] = state.enabled[i]
        save.islandVals[i] = state.islandVals[i]
        save.islandUnits[i] = normalizeTimeoutUnit(state.islandUnits[i])
    }
    save.write(editor)
}

private fun parseWakeRules(json: String): List<WakeRule> {
    return try {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(
                    WakeRule(
                        sec = obj.optInt("sec", 1).toString(),
                        hour = obj.optInt("hour", 7).toString(),
                        minute = String.format(
                            Locale.getDefault(),
                            "%02d",
                            obj.optInt("minute", 0)
                        ),
                    ),
                )
            }
        }
    } catch (_: Exception) {
        listOf(WakeRule("1", "7", "00"))
    }
}

private fun toWakeRulesJson(rules: List<WakeRule>): String {
    val arr = JSONArray()
    rules.forEach { rule ->
        val sec = (rule.sec.toIntOrNull() ?: 1).coerceAtLeast(1)
        val hour = (rule.hour.toIntOrNull() ?: 0).coerceIn(0, 23)
        val minute = (rule.minute.toIntOrNull() ?: 0).coerceIn(0, 59)
        arr.put(JSONObject().apply {
            put("sec", sec)
            put("hour", hour)
            put("minute", minute)
        })
    }
    return arr.toString()
}

private const val TOKEN_COUNTDOWN = "{倒计时}"
private const val TOKEN_ELAPSED = "{正计时}"

private fun alignExpandedTimerWithStatus(stages: MutableList<StageCustomState>): Int {
    var changed = 0
    stages.indices.forEach { index ->
        val stage = stages[index]
        val statusKind = detectTimerKind(stage.tplB.trim())
        val title = stage.hintTitle.trim()
        val subtitle = stage.hintSubtitle.trim()
        val titleKind = detectTimerKind(title)
        val subtitleKind = detectTimerKind(subtitle)
        var updated = stage
        if ((statusKind == -1 || statusKind == 1) && (titleKind == -1 || titleKind == 1) && statusKind != titleKind) {
            updated = updated.copy(hintTitle = forceTimerKind(title, statusKind))
            changed++
        }
        if ((statusKind == -1 || statusKind == 1) && (subtitleKind == -1 || subtitleKind == 1) && statusKind != subtitleKind) {
            updated = updated.copy(hintSubtitle = forceTimerKind(subtitle, statusKind))
            changed++
        }
        if (updated != stage) stages[index] = updated
    }
    return changed
}

private fun alignStatusTimerWithExpanded(stages: MutableList<StageCustomState>): Int {
    var changed = 0
    stages.indices.forEach { index ->
        val stage = stages[index]
        val expandedKind =
            detectExpandedTimerKind(stage.hintTitle.trim(), stage.hintSubtitle.trim())
        val statusKind = detectTimerKind(stage.tplB.trim())
        if ((expandedKind == -1 || expandedKind == 1) && (statusKind == -1 || statusKind == 1) && expandedKind != statusKind) {
            stages[index] = stage.copy(tplB = forceTimerKind(stage.tplB.trim(), expandedKind))
            changed++
        }
    }
    return changed
}

private fun detectExpandedTimerKind(hintTitle: String, hintSubtitle: String): Int {
    val titleKind = detectTimerKind(hintTitle)
    if (titleKind == -1 || titleKind == 1) return titleKind
    val subtitleKind = detectTimerKind(hintSubtitle)
    if (subtitleKind == -1 || subtitleKind == 1) return subtitleKind
    return 0
}

private fun detectTimerKind(text: String): Int {
    if (text.isBlank()) return 0
    val hasCountdown = text.contains(TOKEN_COUNTDOWN)
    val hasElapsed = text.contains(TOKEN_ELAPSED)
    if (hasCountdown && hasElapsed) return 2
    if (hasCountdown) return -1
    if (hasElapsed) return 1
    return 0
}

private fun forceTimerKind(text: String, targetKind: Int): String {
    if (targetKind >= 0) return text.replace(TOKEN_COUNTDOWN, TOKEN_ELAPSED)
    return text.replace(TOKEN_ELAPSED, TOKEN_COUNTDOWN)
}

@Composable
private fun HolidayTab(
    activity: MainActivity,
    state: HolidayComposeState,
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = PaddingValues(0.dp),
) {
    val scope = rememberCoroutineScope()
    var showYearDialog by remember { mutableStateOf(false) }
    var showClearYearDialog by remember { mutableStateOf(false) }
    var holidayEditEntry by remember { mutableStateOf<HolidayManager.HolidayEntry?>(null) }
    var holidayDraft by remember { mutableStateOf<HolidayDraft?>(null) }
    var workswapEditEntry by remember { mutableStateOf<HolidayManager.HolidayEntry?>(null) }
    var workswapDraft by remember { mutableStateOf<WorkSwapDraft?>(null) }
    var pendingDeleteHoliday by remember { mutableStateOf<HolidayManager.HolidayEntry?>(null) }
    var pendingDeleteWorkswap by remember { mutableStateOf<HolidayManager.HolidayEntry?>(null) }
    val maxWeek = remember(state.year) { activity.uiReadTotalWeekFromCourseData().coerceAtLeast(1) }

    val hints = rememberDismissibleHints(activity)
    SettingsPage(modifier = modifier, pagePadding = pagePadding) {
        dismissibleHint(
            hints = hints,
            key = "hint_holiday_overview",
            text = "节假日当天不发课前提醒；调休工作日按指定周次及星期发提醒。",
        )
        item(key = "section_0") {
            SettingsSection {
                ArrowPreference(
                    title = "年份",
                    endActions = { PreferenceValue(state.year.toString()) },
                    onClick = { showYearDialog = true },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                Toast.makeText(activity, "正在获取...", Toast.LENGTH_SHORT).show()
                                val result =
                                    withContext(Dispatchers.IO) { fetchHolidayEntries(state.year) }
                                result.error?.let {
                                    Toast.makeText(activity, "获取失败：$it", Toast.LENGTH_SHORT)
                                        .show()
                                    return@launch
                                }
                                val entries = result.entries
                                if (entries.isEmpty()) {
                                    Toast.makeText(
                                        activity,
                                        "${state.year}年暂无数据",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@launch
                                }
                                HolidayManager.mergeAndSave(state.year, entries)
                                activity.uiSyncHolidayToHook(state.year)
                                entries.forEach { e ->
                                    val endDate =
                                        if (e.endDate.isNullOrEmpty()) e.date else e.endDate
                                    activity.uiRescheduleIfCoversToday(e.date, endDate)
                                }
                                state.loadFrom(activity)
                                Toast.makeText(
                                    activity,
                                    "获取完成：节假日 ${result.holidayDays} 天，调休 ${result.workswapDays} 天",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("网络获取") }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { showClearYearDialog = true },
                    ) { Text("清除本年") }
                }
            }
        }

        item(key = "节假日") {
            HolidayEntriesSection(
                title = "节假日",
                entries = state.holidayEntries,
                addSummary = "添加节假日日期或区间",
                onEdit = { entry ->
                    holidayEditEntry = entry
                    holidayDraft = HolidayDraft(
                        date = entry.date,
                        endDate = entry.endDate ?: "",
                        name = entry.name,
                    )
                },
                onDelete = { pendingDeleteHoliday = it },
                onAdd = {
                    holidayEditEntry = null
                    holidayDraft = HolidayDraft(date = "${state.year}-01-01")
                },
            )
        }

        item(key = "调休工作日") {
            HolidayEntriesSection(
                title = "调休工作日",
                entries = state.workswapEntries,
                addSummary = "添加调休上班日与跟随周次",
                onEdit = { entry ->
                    workswapEditEntry = entry
                    workswapDraft = WorkSwapDraft(
                        date = entry.date,
                        name = entry.name,
                        followWeek = if (entry.followWeek > 0) entry.followWeek else 1,
                        followWeekday = if (entry.followWeekday > 0) entry.followWeekday else 1,
                    )
                },
                onDelete = { pendingDeleteWorkswap = it },
                onAdd = {
                    workswapEditEntry = null
                    workswapDraft = WorkSwapDraft(date = "${state.year}-01-01")
                },
            )
        }

    }

    if (showYearDialog) {
        YearPickerDialog(
            currentYear = state.year,
            onDismiss = { showYearDialog = false },
            onConfirm = {
                state.year = it
                state.loadFrom(activity)
                showYearDialog = false
            },
        )
    }

    ConfirmationDialog(
        show = showClearYearDialog,
        title = "清除本年",
        summary = "将清除 ${state.year} 年已保存的全部假期和调休数据（包括自定义条目）。确定吗？",
        confirmText = "清除",
        onDismissRequest = { showClearYearDialog = false },
        onConfirm = {
            showClearYearDialog = false
            val old = HolidayManager.loadEntries(state.year)
            HolidayManager.saveEntries(state.year, ArrayList())
            activity.uiSyncHolidayToHook(state.year)
            old.forEach { e ->
                val end = if (e.endDate.isNullOrEmpty()) e.date else e.endDate
                activity.uiRescheduleIfCoversToday(e.date, end)
            }
            state.loadFrom(activity)
            Toast.makeText(activity, "已清除 ${state.year} 年假期数据", Toast.LENGTH_SHORT).show()
        },
    )

    pendingDeleteHoliday?.let { target ->
        ConfirmationDialog(
            show = true,
            title = "删除节假日",
            summary = "确定删除“${target.name}”（${
                formatDateRange(
                    target.date,
                    target.endDate
                )
            }）吗？",
            confirmText = "删除",
            onDismissRequest = { pendingDeleteHoliday = null },
            onConfirm = {
                val all = HolidayManager.loadEntries(state.year).toMutableList()
                all.removeIf { e ->
                    e.date == target.date &&
                            (e.endDate ?: "") == (target.endDate ?: "") &&
                            e.name == target.name &&
                            e.type == target.type
                }
                HolidayManager.saveEntries(state.year, all)
                activity.uiSyncHolidayToHook(state.year)
                val targetEnd = if (target.endDate.isNullOrBlank()) target.date else target.endDate
                activity.uiRescheduleIfCoversToday(target.date, targetEnd)
                state.loadFrom(activity)
                Toast.makeText(
                    activity,
                    "已删除节假日：${target.name}（${formatDateRange(target.date, target.endDate)}）",
                    Toast.LENGTH_SHORT
                ).show()
                pendingDeleteHoliday = null
            },
        )
    }

    pendingDeleteWorkswap?.let { target ->
        ConfirmationDialog(
            show = true,
            title = "删除调休工作日",
            summary = "确定删除“${target.name}”（${formatShortDate(target.date)}）吗？",
            confirmText = "删除",
            onDismissRequest = { pendingDeleteWorkswap = null },
            onConfirm = {
                val all = HolidayManager.loadEntries(state.year).toMutableList()
                all.removeIf { e ->
                    e.date == target.date && e.name == target.name && e.type == target.type
                }
                HolidayManager.saveEntries(state.year, all)
                activity.uiSyncHolidayToHook(state.year)
                activity.uiRescheduleIfCoversToday(target.date, null)
                state.loadFrom(activity)
                Toast.makeText(
                    activity,
                    "已删除调休工作日：${target.name}（${formatShortDate(target.date)}）",
                    Toast.LENGTH_SHORT
                ).show()
                pendingDeleteWorkswap = null
            },
        )
    }

    holidayDraft?.let { draft ->
        HolidayEditDialog(
            title = if (holidayEditEntry == null) "新增节假日" else "编辑节假日",
            draft = draft,
            onDismiss = { holidayDraft = null },
            onSave = { save ->
                val isEdit = holidayEditEntry != null
                val name = save.name.trim().ifBlank { "节假日" }
                val all = HolidayManager.loadEntries(state.year).toMutableList()
                holidayEditEntry?.let { old ->
                    all.removeIf { e ->
                        e.date == old.date &&
                                (e.endDate ?: "") == (old.endDate ?: "") &&
                                e.name == old.name &&
                                e.type == old.type
                    }
                }
                all += HolidayManager.HolidayEntry(
                    save.date,
                    save.endDate,
                    name,
                    HolidayManager.TYPE_HOLIDAY,
                    true,
                )
                all.sortBy { it.date }
                HolidayManager.saveEntries(state.year, all)
                activity.uiSyncHolidayToHook(state.year)
                val endDate = if (save.endDate.isBlank()) save.date else save.endDate
                activity.uiRescheduleIfCoversToday(save.date, endDate)
                holidayEditEntry?.let { old ->
                    activity.uiRescheduleIfCoversToday(
                        old.date,
                        if (old.endDate.isNullOrEmpty()) old.date else old.endDate
                    )
                }
                state.loadFrom(activity)
                holidayDraft = null
                Toast.makeText(
                    activity,
                    if (isEdit) "已更新节假日" else "已新增节假日",
                    Toast.LENGTH_SHORT
                ).show()
            },
        )
    }

    workswapDraft?.let { draft ->
        WorkswapEditDialog(
            title = if (workswapEditEntry == null) "新增调休工作日" else "编辑调休工作日",
            draft = draft,
            maxWeek = maxWeek,
            onDismiss = { workswapDraft = null },
            onSave = { save ->
                val isEdit = workswapEditEntry != null
                val name = save.name.trim().ifBlank { "调休工作日" }
                val all = HolidayManager.loadEntries(state.year).toMutableList()
                workswapEditEntry?.let { old ->
                    all.removeIf { e -> e.date == old.date && e.name == old.name && e.type == old.type }
                }
                val entry = HolidayManager.HolidayEntry(
                    save.date,
                    "",
                    name,
                    HolidayManager.TYPE_WORKSWAP,
                    true,
                )
                entry.followWeek = save.followWeek.coerceIn(1, maxWeek)
                entry.followWeekday = save.followWeekday.coerceIn(1, 7)
                all += entry
                all.sortBy { it.date }
                HolidayManager.saveEntries(state.year, all)
                activity.uiSyncHolidayToHook(state.year)
                activity.uiRescheduleIfCoversToday(save.date, null)
                workswapEditEntry?.let { old -> activity.uiRescheduleIfCoversToday(old.date, null) }
                state.loadFrom(activity)
                workswapDraft = null
                Toast.makeText(
                    activity,
                    if (isEdit) "已更新调休工作日" else "已新增调休工作日",
                    Toast.LENGTH_SHORT
                ).show()
            },
        )
    }
}

private data class FetchHolidayResult(
    val entries: List<HolidayManager.HolidayEntry>,
    val holidayDays: Int,
    val workswapDays: Int,
    val error: String? = null,
)

private fun fetchHolidayEntries(year: Int): FetchHolidayResult {
    return try {
        val url = URL("https://unpkg.com/holiday-calendar@1.3.0/data/CN/$year.json")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "XiaoaiIsland/1.0")
        }
        val text = BufferedReader(
            InputStreamReader(
                conn.inputStream,
                Charsets.UTF_8
            )
        ).use { it.readText() }
        conn.disconnect()
        val entries = HolidayManager.parseApiResponse(text)
        var holidayDays = 0
        var workswapDays = 0
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        entries.forEach { e ->
            val days = if (e.endDate.isNullOrBlank()) {
                1
            } else {
                try {
                    val d1 = sdf.parse(e.date) ?: return@forEach
                    val d2 = sdf.parse(e.endDate) ?: return@forEach
                    ((d2.time - d1.time) / 86_400_000L).toInt() + 1
                } catch (_: Exception) {
                    1
                }
            }
            if (e.type == HolidayManager.TYPE_HOLIDAY) holidayDays += days else workswapDays += days
        }
        FetchHolidayResult(
            entries = entries,
            holidayDays = holidayDays,
            workswapDays = workswapDays
        )
    } catch (e: Exception) {
        FetchHolidayResult(
            entries = emptyList(),
            holidayDays = 0,
            workswapDays = 0,
            error = e.message ?: "未知错误"
        )
    }
}

@Composable
internal fun HolidayEntriesSection(
    title: String,
    entries: List<HolidayManager.HolidayEntry>,
    addSummary: String,
    onEdit: (HolidayManager.HolidayEntry) -> Unit,
    onDelete: (HolidayManager.HolidayEntry) -> Unit,
    onAdd: () -> Unit,
) {
    SettingsSection(title = title) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            if (entries.isEmpty()) {
                Text(
                    text = "暂无${title}数据",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                entries.forEachIndexed { index, entry ->
                    if (entry.type == HolidayManager.TYPE_HOLIDAY) {
                        HolidayRow(entry, onEdit = { onEdit(entry) }, onDelete = { onDelete(entry) })
                    } else {
                        WorkswapRow(entry, onEdit = { onEdit(entry) }, onDelete = { onDelete(entry) })
                    }
                    if (index != entries.lastIndex) Spacer(Modifier.height(12.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            val addTitle = "新增$title"
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = addTitle, onClick = onAdd)
                    .padding(vertical = 6.dp),
            ) {
                Text(addTitle, style = MiuixTheme.textStyles.main, fontWeight = FontWeight.Bold)
                Text(
                    text = addSummary,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun HolidayRow(
    entry: HolidayManager.HolidayEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateLabel = formatDateRange(entry.date, entry.endDate)
    CompactEditableEntry(onEdit = onEdit, onDelete = onDelete) {
        Text(
            "$dateLabel  ${entry.name}",
            style = MiuixTheme.textStyles.main,
            fontWeight = FontWeight.Bold,
        )
        Text(
            if (entry.isCustom) "自定义节假日" else "API 节假日",
            style = MiuixTheme.textStyles.body2,
            color = if (entry.isCustom) Color(0xFF7965AF) else Color(0xFF389E0D),
        )
    }
}

@Composable
private fun WorkswapRow(
    entry: HolidayManager.HolidayEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateLabel = formatDateRange(entry.date, entry.endDate)
    CompactEditableEntry(onEdit = onEdit, onDelete = onDelete) {
        Text(
            "$dateLabel  ${entry.name}",
            style = MiuixTheme.textStyles.main,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "替换为: ${entry.followDesc()}",
            style = MiuixTheme.textStyles.body2,
            color = Color(0xFF6750A4),
        )
        Text(
            if (entry.isCustom) "自定义调休" else "API 调休",
            style = MiuixTheme.textStyles.body2,
            color = if (entry.isCustom) Color(0xFF7965AF) else Color(0xFF389E0D),
        )
    }
}

@Composable
private fun YearPickerDialog(
    currentYear: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var year by remember(currentYear) { mutableIntStateOf(currentYear.coerceIn(2020, 2099)) }
    val pickerColors = NumberPickerDefaults.colors(
        selectedTextColor = MiuixTheme.colorScheme.primary,
        unselectedTextColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.55f),
    )
    OverlayDialog(
        show = true,
        title = "选择年份",
        onDismissRequest = onDismiss,
    ) {
        NumberPicker(
            value = year,
            onValueChange = { year = it },
            range = 2020..2099,
            label = { it.toString() },
            modifier = Modifier.fillMaxWidth(),
            colors = pickerColors,
        )
        Spacer(modifier = Modifier.height(12.dp))
        DialogActions(
            onDismiss = onDismiss,
            onConfirm = { onConfirm(year) },
        )
    }
}

private fun parseIsoDate(isoDate: String): Triple<Int, Int, Int> {
    val now = Calendar.getInstance()
    val defaultY = now.get(Calendar.YEAR)
    val defaultM = now.get(Calendar.MONTH) + 1
    val defaultD = now.get(Calendar.DAY_OF_MONTH)
    val parts = isoDate.split("-")
    val y = parts.getOrNull(0)?.toIntOrNull() ?: defaultY
    val m = (parts.getOrNull(1)?.toIntOrNull() ?: defaultM).coerceIn(1, 12)
    val d = (parts.getOrNull(2)?.toIntOrNull() ?: defaultD).coerceAtLeast(1)
    return Triple(y, m, d)
}

private fun formatIsoDate(year: Int, month: Int, day: Int): String {
    return "%04d-%02d-%02d".format(year, month, day)
}

private fun daysInMonth(year: Int, month: Int): Int {
    val cal = Calendar.getInstance()
    cal.set(Calendar.YEAR, year)
    cal.set(Calendar.MONTH, month - 1)
    cal.set(Calendar.DAY_OF_MONTH, 1)
    return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
}

@Composable
private fun MiuixDatePickerDialog(
    title: String,
    initialDate: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val parsed = remember(initialDate) { parseIsoDate(initialDate) }
    var year by remember(initialDate) { mutableIntStateOf(parsed.first.coerceIn(2020, 2099)) }
    var month by remember(initialDate) { mutableIntStateOf(parsed.second.coerceIn(1, 12)) }
    var day by remember(initialDate) { mutableIntStateOf(parsed.third) }
    val maxDay = remember(year, month) { daysInMonth(year, month) }
    val pickerColors = NumberPickerDefaults.colors(
        selectedTextColor = MiuixTheme.colorScheme.primary,
        unselectedTextColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.55f),
    )
    if (day > maxDay) day = maxDay

    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberPicker(
                value = year,
                onValueChange = { year = it },
                range = 2020..2099,
                label = { it.toString() },
                modifier = Modifier.weight(1f),
                colors = pickerColors,
            )
            NumberPicker(
                value = month,
                onValueChange = { month = it },
                range = 1..12,
                label = { "%02d".format(it) },
                modifier = Modifier.weight(1f),
                colors = pickerColors,
            )
            NumberPicker(
                value = day.coerceIn(1, maxDay),
                onValueChange = { day = it.coerceIn(1, maxDay) },
                range = 1..maxDay,
                label = { "%02d".format(it) },
                modifier = Modifier.weight(1f),
                colors = pickerColors,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        DialogActions(
            onDismiss = onDismiss,
            onConfirm = { onConfirm(formatIsoDate(year, month, day.coerceIn(1, maxDay))) },
        )
    }
}

@Composable
private fun MiuixTimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    var hour by remember(initialHour) { mutableIntStateOf(initialHour.coerceIn(0, 23)) }
    var minute by remember(initialMinute) { mutableIntStateOf(initialMinute.coerceIn(0, 59)) }
    val pickerColors = NumberPickerDefaults.colors(
        selectedTextColor = MiuixTheme.colorScheme.primary,
        unselectedTextColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.55f),
    )
    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberPicker(
                value = hour,
                onValueChange = { hour = it },
                range = 0..23,
                label = { "%02d".format(it) },
                modifier = Modifier.weight(1f),
                colors = pickerColors,
            )
            NumberPicker(
                value = minute,
                onValueChange = { minute = it },
                range = 0..59,
                label = { "%02d".format(it) },
                modifier = Modifier.weight(1f),
                colors = pickerColors,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        DialogActions(
            onDismiss = onDismiss,
            onConfirm = { onConfirm(hour, minute) },
        )
    }
}

@Composable
private fun MiuixSectionPickerDialog(
    title: String,
    initialSec: Int,
    minSec: Int = 1,
    maxSec: Int = 30,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val lower = minSec.coerceAtLeast(1)
    val upper = maxOf(maxSec, initialSec, lower)
    var section by remember(initialSec, lower, upper) {
        mutableIntStateOf(
            initialSec.coerceIn(
                lower,
                upper
            )
        )
    }
    val pickerColors = NumberPickerDefaults.colors(
        selectedTextColor = MiuixTheme.colorScheme.primary,
        unselectedTextColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.55f),
    )
    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        NumberPicker(
            value = section,
            onValueChange = { section = it },
            range = lower..upper,
            label = { "第${it}节" },
            modifier = Modifier.fillMaxWidth(),
            colors = pickerColors,
        )
        Spacer(modifier = Modifier.height(12.dp))
        DialogActions(
            onDismiss = onDismiss,
            onConfirm = { onConfirm(section) },
        )
    }
}

@Composable
private fun MiuixDurationPickerDialog(
    title: String,
    initialValue: Int,
    initialUnit: String,
    onDismiss: () -> Unit,
    onConfirm: (Int, String) -> Unit,
) {
    val unitEntries = listOf("秒" to "s", "分" to "m", "时" to "h")
    val initialUnitIndex =
        unitEntries.indexOfFirst { it.second == normalizeTimeoutUnit(initialUnit) }
            .takeIf { it >= 0 } ?: 1
    var value by remember(initialValue) { mutableIntStateOf(initialValue.coerceIn(1, 999)) }
    var unitIndex by remember(initialUnitIndex) { mutableIntStateOf(initialUnitIndex) }
    val pickerColors = NumberPickerDefaults.colors(
        selectedTextColor = MiuixTheme.colorScheme.primary,
        unselectedTextColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.55f),
    )
    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberPicker(
                value = value,
                onValueChange = { value = it.coerceIn(1, 999) },
                range = 1..999,
                label = { it.toString() },
                modifier = Modifier.weight(1f),
                colors = pickerColors,
            )
            NumberPicker(
                value = unitIndex,
                onValueChange = { unitIndex = it.coerceIn(0, unitEntries.lastIndex) },
                range = 0..unitEntries.lastIndex,
                label = { idx -> unitEntries[idx].first },
                modifier = Modifier.weight(1f),
                colors = pickerColors,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        DialogActions(
            onDismiss = onDismiss,
            onConfirm = { onConfirm(value, unitEntries[unitIndex].second) },
        )
    }
}

@Composable
private fun MiuixMinutePickerDialog(
    title: String,
    initialValue: Int,
    minValue: Int = 0,
    maxValue: Int = 60,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val lower = minValue.coerceAtLeast(0)
    val upper = maxOf(lower, maxValue)
    var minute by remember(initialValue, lower, upper) {
        mutableIntStateOf(
            initialValue.coerceIn(
                lower,
                upper
            )
        )
    }
    val pickerColors = NumberPickerDefaults.colors(
        selectedTextColor = MiuixTheme.colorScheme.primary,
        unselectedTextColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.55f),
    )
    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        NumberPicker(
            value = minute,
            onValueChange = { minute = it.coerceIn(lower, upper) },
            range = lower..upper,
            label = { "$it 分钟" },
            modifier = Modifier.fillMaxWidth(),
            colors = pickerColors,
        )
        Spacer(modifier = Modifier.height(12.dp))
        DialogActions(
            onDismiss = onDismiss,
            onConfirm = { onConfirm(minute) },
        )
    }
}

@Composable
private fun HolidayEditDialog(
    title: String,
    draft: HolidayDraft,
    onDismiss: () -> Unit,
    onSave: (HolidayDraft) -> Unit,
) {
    var form by remember(draft) { mutableStateOf(draft.copy()) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        ArrowPreference(
            title = "开始日期: ${form.date}",
            onClick = { showStartPicker = true },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        ArrowPreference(
            title = "结束日期: ${if (form.endDate.isBlank()) "仅当天" else form.endDate}",
            onClick = { showEndPicker = true },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = form.name,
            onValueChange = { form = form.copy(name = it) },
            label = "名称（如：春节、放假）",
            modifier = Modifier.fillMaxWidth(),
            textStyle = MiuixTheme.textStyles.main.copy(color = MiuixTheme.colorScheme.onSurface),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(12.dp))
        DialogActions(
            onDismiss = onDismiss,
            onConfirm = { onSave(form) },
        )
    }
    if (showStartPicker) {
        MiuixDatePickerDialog(
            title = "选择开始日期",
            initialDate = form.date,
            onDismiss = { showStartPicker = false },
            onConfirm = {
                form = form.copy(date = it)
                showStartPicker = false
            },
        )
    }
    if (showEndPicker) {
        MiuixDatePickerDialog(
            title = "选择结束日期",
            initialDate = if (form.endDate.isBlank()) form.date else form.endDate,
            onDismiss = { showEndPicker = false },
            onConfirm = {
                form = form.copy(endDate = it)
                showEndPicker = false
            },
        )
    }
}

@Composable
private fun WorkswapEditDialog(
    title: String,
    draft: WorkSwapDraft,
    maxWeek: Int,
    onDismiss: () -> Unit,
    onSave: (WorkSwapDraft) -> Unit,
) {
    var form by remember(draft) { mutableStateOf(draft.copy()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val weekEntries = remember(maxWeek) {
        (1..maxWeek.coerceAtLeast(1)).map { week ->
            "第 $week 周"
        }
    }
    val weekdayEntries = remember {
        listOf(
            "周一",
            "周二",
            "周三",
            "周四",
            "周五",
            "周六",
            "周日",
        )
    }
    OverlayDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        ArrowPreference(
            title = "选择日期: ${form.date}",
            onClick = { showDatePicker = true },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = form.name,
            onValueChange = { form = form.copy(name = it) },
            label = "名称（如：补周一课）",
            modifier = Modifier.fillMaxWidth(),
            textStyle = MiuixTheme.textStyles.main.copy(color = MiuixTheme.colorScheme.onSurface),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text("当天按以下周次/星期的课表上课：", style = MiuixTheme.textStyles.body2)
        Spacer(modifier = Modifier.height(6.dp))
        OverlayDropdownPreference(
            title = "周次",
            items = weekEntries,
            selectedIndex = form.followWeek.coerceIn(1, maxWeek.coerceAtLeast(1)) - 1,
            onSelectedIndexChange = {
                form = form.copy(followWeek = it + 1)
            },
        )
        OverlayDropdownPreference(
            title = "星期",
            items = weekdayEntries,
            selectedIndex = form.followWeekday.coerceIn(1, 7) - 1,
            onSelectedIndexChange = {
                form = form.copy(followWeekday = it + 1)
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        DialogActions(
            onDismiss = onDismiss,
            onConfirm = { onSave(form) },
        )
    }
    if (showDatePicker) {
        MiuixDatePickerDialog(
            title = "选择日期",
            initialDate = form.date,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                form = form.copy(date = it)
                showDatePicker = false
            },
        )
    }
}

private fun formatShortDate(isoDate: String?): String {
    if (isoDate.isNullOrBlank() || isoDate.length < 10) return isoDate ?: ""
    return try {
        val m = isoDate.substring(5, 7).toInt()
        val d = isoDate.substring(8, 10).toInt()
        "$m/$d"
    } catch (_: Exception) {
        isoDate
    }
}

private fun formatDateRange(startDate: String?, endDate: String?): String {
    val start = startDate?.trim().orEmpty()
    val end = endDate?.trim().orEmpty()
    if (start.isBlank()) return ""
    if (end.isBlank() || end == start) return formatShortDate(start)
    return "${formatShortDate(start)}–${formatShortDate(end)}"
}

@Composable
private fun AboutTab(
    activity: MainActivity,
    state: AboutComposeState,
    onOpen: (AppRoute) -> Unit,
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = PaddingValues(0.dp),
) {
    SettingsPage(modifier = modifier, pagePadding = pagePadding) {
        item(key = "section_0") {
            SettingsSection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.mipmap.ic_launcher),
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "课程表超级岛",
                            style = MiuixTheme.textStyles.title4,
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = state.version,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.clickable { activity.uiOpenUrl(RELEASES_URL) },
                        )
                    }
                }
            }
        }
        item(key = "section_1") {
            SettingsSection {
                ArrowPreference(
                    title = "版本",
                    endActions = { PreferenceValue(state.version) },
                    onClick = { activity.uiOpenUrl(RELEASES_URL) },
                )
                ArrowPreference(
                    title = "作者",
                    endActions = { PreferenceValue("Mercury") },
                    onClick = { activity.uiOpenAuthorPage() },
                )
                ArrowPreference(
                    title = "Fork 作者",
                    endActions = { PreferenceValue("nairain") },
                    onClick = { activity.uiOpenUrl(FORK_AUTHOR_URL) },
                )
                SwitchPreference(
                    title = "隐藏桌面图标",
                    checked = state.hideIcon,
                    onCheckedChange = {
                        state.hideIcon = it
                        activity.uiSetHideIconEnabled(it)
                    },
                )
                SwitchPreference(
                    title = "莫奈取色",
                    checked = state.monetEnabled,
                    onCheckedChange = {
                        state.monetEnabled = it
                        activity.uiSetMonetEnabled(it)
                    },
                )
                SwitchPreference(
                    title = "预测性返回",
                    checked = state.predictiveBackEnabled,
                    onCheckedChange = {
                        state.predictiveBackEnabled = it
                        activity.uiSetPredictiveBackEnabled(it)
                    },
                )
            }
        }
        item(key = "third_party_libraries") {
            SettingsSection {
                ArrowPreference(
                    title = "第三方库",
                    summary = "查看开源库及许可证",
                    onClick = { onOpen(AppRoute.ThirdPartyLibraries) },
                )
            }
        }
    }
}

@Composable
private fun ThirdPartyLibrariesPage(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = PaddingValues(0.dp),
) {
    SettingsPage(modifier = modifier, pagePadding = pagePadding) {
        item(key = "open_source_refs") {
            SettingsSection {
                OpenSourceRefs.list.forEach { ref ->
                    ArrowPreference(
                        title = ref.name,
                        summary = ref.license,
                        onClick = { activity.uiOpenUrl(ref.link) },
                    )
                }
            }
        }
    }
}

