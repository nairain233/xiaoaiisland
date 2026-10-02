package com.xiaoai.islandnotify

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Preview(name = "浅色 · 320dp", widthDp = 320, heightDp = 1000)
@Preview(
    name = "深色 · 320dp",
    widthDp = 320,
    heightDp = 1000,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Preview(name = "浅色 · 1.5 倍字体", widthDp = 320, heightDp = 1400, fontScale = 1.5f)
@Preview(
    name = "深色 · 1.5 倍字体",
    widthDp = 320,
    heightDp = 1400,
    fontScale = 1.5f,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Preview(name = "浅色 · 2 倍字体", widthDp = 320, heightDp = 1800, fontScale = 2f)
@Preview(
    name = "深色 · 2 倍字体",
    widthDp = 320,
    heightDp = 1800,
    fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun MiuixContainersPreview() {
    ContainersPreviewTheme { ContainerExamples() }
}

@Preview(name = "Monet 浅色 · 320dp", widthDp = 320, heightDp = 1000)
@Preview(
    name = "Monet 深色 · 320dp",
    widthDp = 320,
    heightDp = 1000,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun MiuixMonetContainersPreview() {
    ContainersPreviewTheme(monet = true) { ContainerExamples() }
}

@Preview(name = "横屏分栏 · 840dp", widthDp = 840, heightDp = 1000)
@Composable
private fun MiuixSplitContainersPreview() {
    ContainersPreviewTheme {
        Scaffold {
            Row(Modifier.fillMaxSize()) {
                ContainerExamples(modifier = Modifier.weight(1f))
                ContainerExamples(modifier = Modifier.weight(2f))
            }
        }
    }
}

@Composable
private fun ContainersPreviewTheme(monet: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val controller = remember(dark, monet) {
        ThemeController(
            colorSchemeMode = when {
                monet && dark -> ColorSchemeMode.MonetDark
                monet -> ColorSchemeMode.MonetLight
                dark -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.Light
            },
            keyColor = Color(0xFF7965AF),
        )
    }
    MiuixTheme(controller = controller, content = content)
}

@Composable
private fun ContainerExamples(modifier: Modifier = Modifier) {
    var showHint by remember { mutableStateOf(true) }
    var enabled by remember { mutableStateOf(true) }
    RouteScaffold(
        title = "容器预览",
        canBack = true,
        onBack = {},
        modifier = modifier
    ) { pageModifier, padding ->
        SettingsPage(modifier = pageModifier, pagePadding = padding) {
            if (showHint) {
                item(key = "hint") {
                    InformationCard(
                        text = "状态栏岛仅岛B支持计时变量，计时变量需放在开头，可在后面拼接文本。",
                        onClose = { showHint = false },
                    )
                }
            }
            item(key = "variables") {
                InformationCard("可用变量：{课名} {开始} {结束} {教室} {节次} {教师}")
            }
            item(key = "entry") {
                SettingsSection(title = "节假日与调休") {
                    EditableEntry(onEdit = {}, onDelete = {}) {
                        Text("10/01–10/07  国庆节与较长的自定义假期名称")
                        Text("自定义节假日", color = Color(0xFF7965AF))
                    }
                    ArrowPreference(
                        title = "新增节假日",
                        summary = "添加节假日日期或区间",
                        onClick = {})
                }
            }
            item(key = "settings") {
                SettingsSection(title = "设置分组") {
                    SwitchPreference(
                        title = "发光效果",
                        checked = enabled,
                        onCheckedChange = { enabled = it })
                    ArrowPreference(
                        title = "时长",
                        endActions = { PreferenceValue("9999 分钟") },
                        enabled = enabled,
                        onClick = {},
                    )
                }
            }
            item(key = "actions") {
                DialogActions(onDismiss = {}, onConfirm = {})
            }
        }
    }
}
