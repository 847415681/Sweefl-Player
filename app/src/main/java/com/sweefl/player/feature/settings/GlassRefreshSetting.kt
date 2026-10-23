package com.sweefl.player.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sweefl.player.core.designsystem.*
import com.sweefl.player.data.preferences.PlayerPreferences
import com.sweefl.player.domain.GlassRefreshMode
import com.sweefl.player.BuildConfig

@Composable
fun GlassRefreshPreferencesProvider(preferences: PlayerPreferences, content: @Composable () -> Unit) {
    var mode by remember(preferences) { mutableStateOf(preferences.glassRefreshMode) }
    val blur = remember(preferences) { mutableIntStateOf(preferences.glassBlurAmount) }
    val dirty = remember(preferences) { mutableStateOf(false) }
    val preview: (Int) -> Unit = remember(preferences) {
        { value ->
            val next = value.coerceIn(0, 100)
            if (blur.intValue != next) { blur.intValue = next; dirty.value = true }
        }
    }
    val save: () -> Unit = remember(preferences) {
        {
            if (dirty.value) {
                val value = blur.intValue
                dirty.value = false
                preferences.glassBlurAmount = value
            }
        }
    }
    DisposableEffect(preferences) {
        val unsubscribe = preferences.observeGlassRefresh { mode = it }
        val unsubscribeBlur = preferences.observeGlassBlurAmount {
            if (!dirty.value) blur.intValue = it
        }
        onDispose { save(); unsubscribe(); unsubscribeBlur() }
    }
    val policy = remember(mode) { GlassCapturePolicy(mode.name, mode::intervalNanos, BuildConfig.DEBUG) }
    CompositionLocalProvider(
        LocalSelectedRefreshMode provides mode,
        LocalGlassCapturePolicy provides policy,
        LocalUpdateGlassRefresh provides { preferences.glassRefreshMode = it },
        LocalGlassBlurAmount provides (blur.intValue / 100f),
        LocalPreviewGlassBlurAmount provides preview,
        LocalSaveGlassBlurAmount provides save,
        content = content
    )
}

private val LocalSelectedRefreshMode = staticCompositionLocalOf { GlassRefreshMode.FPS_60 }
private val LocalUpdateGlassRefresh = staticCompositionLocalOf<(GlassRefreshMode) -> Unit> { {} }

@Composable
fun GlassRefreshSetting() {
    val mode = LocalSelectedRefreshMode.current
    val update = LocalUpdateGlassRefresh.current
    var choosing by remember { mutableStateOf(false) }
    GlassTextButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) {
        Text("玻璃背景刷新率", Modifier.weight(1f))
        Text(mode.label)
    }
    if (choosing) GlassDialog(onDismissRequest = { choosing = false }, title = { Text("玻璃背景刷新率") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("设置视频玻璃的刷新上限。仅在控件可见且视频产生新画面时取样；实际更新受片源、屏幕和设备性能限制。高档位可能增加耗电。",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            GlassRefreshMode.entries.forEach { item ->
                GlassTextButton(onClick = { update(item); choosing = false }, modifier = Modifier.fillMaxWidth()) {
                    Text((if (mode == item) "✓  " else "") + item.label)
                }
            }
        }
    }, confirmButton = { GlassTextButton(onClick = { choosing = false }) { Text("取消") } })
}
