package com.xiaoai.islandnotify

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Serializable
internal sealed interface AppRoute : NavKey {
    @Serializable data object Home : AppRoute
    @Serializable data object Empty : AppRoute
    @Serializable data object TestNotify : AppRoute
    @Serializable data object StatusCustom : AppRoute
    @Serializable data object ExpandedCustom : AppRoute
    @Serializable data object Timeout : AppRoute
    @Serializable data object Reminder : AppRoute
    @Serializable data object Mute : AppRoute
    @Serializable data object Wakeup : AppRoute
    @Serializable data object Holiday : AppRoute
    @Serializable data object About : AppRoute
    @Serializable data object ThirdPartyLibraries : AppRoute
}

internal fun openRoute(backStack: MutableList<NavKey>, route: AppRoute) {
    val existingIndex = backStack.indexOf(route)
    if (existingIndex >= 0) {
        while (backStack.lastIndex > existingIndex) backStack.removeAt(backStack.lastIndex)
    } else {
        backStack.add(route)
    }
}

internal fun useSplitLayout(width: Float, height: Float, landscape: Boolean): Boolean =
    landscape || (width >= 840f && height >= 480f)

internal fun setNavigationRoot(backStack: MutableList<NavKey>, split: Boolean) {
    backStack[0] = if (split) AppRoute.Empty else AppRoute.Home
}

@Composable
internal fun MiuixAppShell(
    themeController: ThemeController,
    content: @Composable (AppRoute, (AppRoute) -> Unit, () -> Unit) -> Unit,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val width = with(density) { windowSize.width.toDp() }
    val height = with(density) { windowSize.height.toDp() }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val split = useSplitLayout(width.value, height.value, landscape)
    val backStack = rememberNavBackStack<AppRoute>(AppRoute.Home)
    val onOpen: (AppRoute) -> Unit = { openRoute(backStack, it) }
    val onBack: () -> Unit = {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    // 只切换根节点的呈现方式，详情栈在旋转和窗口缩放时保持不变。
    SideEffect {
        setNavigationRoot(backStack, split)
    }

    MiuixTheme(controller = themeController) {
        // 根宿主负责全窗口弹层；页面宿主分别处理标题栏与系统边距。
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { rootPadding ->
            Row(Modifier.fillMaxSize().padding(rootPadding)) {
                if (split) {
                    Box(Modifier.weight(1f)) {
                        content(AppRoute.Home, onOpen, onBack)
                    }
                    Box(Modifier.fillMaxHeight().width(0.75.dp).background(MiuixTheme.colorScheme.dividerLine))
                }
                Box(
                    Modifier.weight(if (split && landscape && width >= 840.dp && height >= 480.dp) 2f else 1f),
                ) {
                    NavDisplay(
                        backStack = backStack,
                        onBack = onBack,
                        effects = NavDisplayEffects(enableCornerClip = !split),
                    ) {
                        entry<AppRoute.Home> {
                            if (split) EmptyDetailPage() else content(AppRoute.Home, onOpen, onBack)
                        }
                        entry<AppRoute.Empty> {
                            if (split) EmptyDetailPage() else content(AppRoute.Home, onOpen, onBack)
                        }
                        entry<AppRoute.TestNotify> { content(it, onOpen, onBack) }
                        entry<AppRoute.StatusCustom> { content(it, onOpen, onBack) }
                        entry<AppRoute.ExpandedCustom> { content(it, onOpen, onBack) }
                        entry<AppRoute.Timeout> { content(it, onOpen, onBack) }
                        entry<AppRoute.Reminder> { content(it, onOpen, onBack) }
                        entry<AppRoute.Mute> { content(it, onOpen, onBack) }
                        entry<AppRoute.Wakeup> { content(it, onOpen, onBack) }
                        entry<AppRoute.Holiday> { content(it, onOpen, onBack) }
                        entry<AppRoute.About> { content(it, onOpen, onBack) }
                        entry<AppRoute.ThirdPartyLibraries> { content(it, onOpen, onBack) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDetailPage() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("请选择设置项", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
