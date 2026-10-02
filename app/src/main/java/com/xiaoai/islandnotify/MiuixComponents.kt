package com.xiaoai.islandnotify

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun LspStatusCard(
    active: Boolean,
    frameworkDesc: String,
    darkTheme: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = when {
        active && darkTheme -> Color(0xFF1A3825)
        active -> Color(0xFFDFFAE4)
        darkTheme -> Color(0xFF3A1E22)
        else -> Color(0xFFFFE4E1)
    }
    val foreground = if (darkTheme) Color(0xFFF2F2F2) else Color(0xFF202124)
    val accent = when {
        active -> Color(0xFF36D167)
        darkTheme -> Color(0xFFFF8A80)
        else -> Color(0xFFD32F2F)
    }
    val summary = if (active) {
        frameworkDesc.ifBlank { "LSPosed Service 已连接" }
    } else {
        "LSPosed Service 未连接，请检查模块启用与框架状态"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = background, contentColor = foreground),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = true,
        onClick = onRefresh,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .semantics { onClick(label = "刷新 LSP 状态", action = null) },
        ) {
            // 装饰层不参与测量，卡片高度由前景文字决定。
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomEnd) {
                Icon(
                    painter = painterResource(
                        if (active) R.drawable.ic_module_active else R.drawable.ic_module_inactive,
                    ),
                    contentDescription = null,
                    modifier = Modifier.offset(x = 27.dp, y = 31.dp).size(110.dp),
                    tint = accent,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 112.dp)
                    .padding(start = 16.dp, top = 14.dp, end = 72.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (active) "模块已激活" else "模块未激活",
                    color = foreground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = summary,
                    modifier = Modifier.padding(top = 8.dp),
                    color = foreground.copy(alpha = 0.8f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

internal data class EditDialogSpec(
    val title: String,
    val initialValue: String,
    val numberOnly: Boolean = false,
    val successToast: String? = "已保存",
    val onConfirm: (String) -> Unit,
)

@Composable
internal fun EditValueDialog(spec: EditDialogSpec, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var draft by rememberSaveable(
        spec.title,
        spec.initialValue
    ) { mutableStateOf(spec.initialValue) }
    OverlayDialog(show = true, title = spec.title, onDismissRequest = onDismiss) {
        TextField(
            value = draft,
            onValueChange = { draft = if (spec.numberOnly) it.filter(Char::isDigit) else it },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (spec.numberOnly) KeyboardType.Number else KeyboardType.Text,
            ),
        )
        Spacer(Modifier.height(12.dp))
        DialogActions(
            onDismiss = onDismiss,
            onConfirm = {
                spec.onConfirm(draft.trim())
                spec.successToast?.takeIf { it.isNotBlank() }?.let {
                    Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                }
                onDismiss()
            },
        )
    }
}

@Composable
internal fun RouteScaffold(
    title: String,
    canBack: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier, PaddingValues) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = if (!LocalInspectionMode.current && isRuntimeShaderSupported()) {
        rememberLayerBackdrop {
            drawRect(surface)
            drawContent()
        }
    } else null
    val tintAlpha = if (surface.luminance() >= 0.5f) 0.70f else 0.60f
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Box(
                modifier = if (backdrop != null) Modifier.textureBlur(
                    backdrop = backdrop,
                    shape = RectangleShape,
                    colors = BlurDefaults.blurColors(
                        blendColors = listOf(BlendColorEntry(surface.copy(alpha = tintAlpha))),
                    ),
                ) else Modifier,
            ) {
                TopAppBar(
                    color = if (backdrop != null) Color.Transparent else surface,
                    title = title,
                    navigationIcon = {
                        if (canBack) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "返回",
                                    tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
            .union(WindowInsets.ime),
    ) { innerPadding ->
        Box(
            Modifier.fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            content(
                Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)
                    .consumeWindowInsets(innerPadding),
                innerPadding,
            )
        }
    }
}

@Composable
internal fun PreferenceValue(value: String) {
    Text(
        text = value,
        modifier = Modifier.widthIn(max = 130.dp),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        textAlign = TextAlign.End,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun SettingsSection(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    title?.let { SmallTitle(it) }
    Card(modifier = Modifier.fillMaxWidth(), content = content)
}

@Composable
internal fun SettingsPage(
    modifier: Modifier = Modifier,
    pagePadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit,
) {
    val padding = withExtraPadding(pagePadding, horizontal = 16.dp, vertical = 8.dp)
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = padding.calculateStartPadding(LocalLayoutDirection.current),
            top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(LocalLayoutDirection.current),
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

internal class DismissibleHints(private val activity: MainActivity) {
    private val dismissed = mutableStateMapOf<String, Boolean>()

    fun isVisible(key: String): Boolean = !(dismissed[key] ?: activity.uiIsHintDismissed(key))

    fun dismiss(key: String) {
        activity.uiSetHintDismissed(key, true)
        dismissed[key] = true
    }
}

@Composable
internal fun rememberDismissibleHints(activity: MainActivity): DismissibleHints {
    val refreshTick by ComposeRefreshBus.tick.collectAsState()
    return remember(activity, refreshTick) { DismissibleHints(activity) }
}

internal fun LazyListScope.dismissibleHint(hints: DismissibleHints, key: String, text: String) {
    if (hints.isVisible(key)) {
        item(key = key) {
            InformationCard(text = text, onClose = { hints.dismiss(key) })
        }
    }
}

@Composable
internal fun DialogActions(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmText: String = "确定",
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton("取消", modifier = Modifier.weight(1f), minHeight = 50.dp, onClick = onDismiss)
        TextButton(
            text = confirmText,
            modifier = Modifier.weight(1f),
            minHeight = 50.dp,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = onConfirm,
        )
    }
}

@Composable
internal fun EditableEntry(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BasicComponent(
        bottomAction = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    "编辑",
                    modifier = Modifier.weight(1f),
                    minHeight = 48.dp,
                    onClick = onEdit
                )
                TextButton(
                    text = "删除",
                    modifier = Modifier.weight(1f),
                    minHeight = 48.dp,
                    colors = ButtonDefaults.textButtonColors(
                        color = Color(0xFFD32F2F),
                        disabledColor = Color(0x59D32F2F),
                        textColor = Color.White,
                        disabledTextColor = Color(0xB3FFFFFF),
                    ),
                    onClick = onDelete,
                )
            }
        },
        content = content,
    )
}

@Composable
internal fun ConfirmationDialog(
    show: Boolean,
    title: String,
    summary: String,
    confirmText: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest
    ) {
        DialogActions(
            onDismiss = onDismissRequest,
            onConfirm = onConfirm,
            confirmText = confirmText
        )
    }
}

@Composable
internal fun withExtraPadding(
    base: PaddingValues,
    horizontal: androidx.compose.ui.unit.Dp = 0.dp,
    vertical: androidx.compose.ui.unit.Dp = 0.dp,
): PaddingValues {
    return PaddingValues(
        start = base.calculateStartPadding(LocalLayoutDirection.current) + horizontal,
        top = base.calculateTopPadding() + vertical,
        end = base.calculateEndPadding(LocalLayoutDirection.current) + horizontal,
        bottom = base.calculateBottomPadding() + vertical,
    )
}

@Composable
internal fun InformationCard(text: String, onClose: (() -> Unit)? = null) {
    val foreground = colorResource(R.color.hint_foreground)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = colorResource(R.color.hint_background),
            contentColor = foreground,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp)
                .padding(
                    start = 16.dp,
                    end = if (onClose == null) 16.dp else 8.dp,
                    top = 12.dp,
                    bottom = 12.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, modifier = Modifier.weight(1f), color = foreground)
            if (onClose != null) {
                IconButton(modifier = Modifier.size(48.dp), onClick = onClose) {
                    Icon(MiuixIcons.Basic.Close, contentDescription = "关闭提示", tint = foreground)
                }
            }
        }
    }
}
