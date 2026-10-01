package com.xiaoai.islandnotify

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Preview(name = "浅色 · 窄屏", widthDp = 320, showBackground = true)
@Preview(name = "深色 · 窄屏", widthDp = 320, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "浅色 · 1.5 倍字体", widthDp = 320, fontScale = 1.5f, showBackground = true)
@Preview(name = "深色 · 1.5 倍字体", widthDp = 320, fontScale = 1.5f, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "浅色 · 2 倍字体", widthDp = 320, fontScale = 2f, showBackground = true)
@Preview(name = "深色 · 2 倍字体", widthDp = 320, fontScale = 2f, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LspStatusCardPreview() {
    val darkTheme = isSystemInDarkTheme()
    val controller = remember(darkTheme) {
        ThemeController(colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light)
    }
    MiuixTheme(controller = controller) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LspStatusCard(
                active = true,
                frameworkDesc = "Framework: LSPosed\nAPI: 101  Version: 7386",
                darkTheme = darkTheme,
                onRefresh = {},
            )
            LspStatusCard(
                active = true,
                frameworkDesc = "",
                darkTheme = darkTheme,
                onRefresh = {},
            )
            LspStatusCard(
                active = true,
                frameworkDesc = "Framework: LSPosed 长框架名称与版本信息\nAPI: 101  Version: 1234567890",
                darkTheme = darkTheme,
                onRefresh = {},
            )
            LspStatusCard(
                active = false,
                frameworkDesc = "",
                darkTheme = darkTheme,
                onRefresh = {},
            )
        }
    }
}
