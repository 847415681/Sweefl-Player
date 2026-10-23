package com.sweefl.player.feature.player.ui

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sweefl.player.core.designsystem.*
import com.sweefl.player.domain.*
import com.sweefl.player.feature.player.PlayerHost
import kotlin.math.roundToInt

@Composable
internal fun RenderingStatusText(host: PlayerHost) {
    val status = host.renderingStatus?.collectAsStateWithLifecycle()?.value
    val text = when {
        status?.failure != null -> "当前处理链未能完成，可切回原生输出后重试。"
        host.renderSettings.mode == PlaybackRenderMode.NATIVE -> "原生输出 · 保留系统 HDR 路径"
        status?.gpuSdrOperational == true -> "GPU 增强渲染运行中 · SDR 输出"
        else -> "GPU 增强渲染 · 等待实际画面"
    }
    Text(text, color = if (status?.gpuSdrOperational == true) Mint else Muted,
        style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun RenderingSettingsDialog(host: PlayerHost, onDismiss: () -> Unit) {
    var draft by remember(host.renderSettings) { mutableStateOf(host.renderSettings) }
    GlassDialog(onDismissRequest = onDismiss, title = { Text("画面与渲染设置") }, text = {
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RenderingStatusText(host)
            GlassTextButton(onClick = { draft = draft.copy(mode = PlaybackRenderMode.NATIVE) }) {
                Text("${if (draft.mode == PlaybackRenderMode.NATIVE) "✓ " else ""}原生输出（保留 HDR）")
            }
            GlassTextButton(onClick = { draft = draft.copy(mode = PlaybackRenderMode.GPU_SDR) },
                enabled = Build.VERSION.SDK_INT >= 29) {
                Text("${if (draft.mode == PlaybackRenderMode.GPU_SDR) "✓ " else ""}GPU 增强（输出 SDR）")
            }
            Text("增强模式可将支持的 PQ / HLG HDR 片源映射为 SDR，并应用下方画面调节。效果受设备和片源支持限制。",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            if (Build.VERSION.SDK_INT < 29) Text("增强渲染需要 Android 10 或更新版本。", color = Muted)
            RenderAdjustment("画面亮度", draft.brightness*100f, draft.colorControlsEnabled) {
                draft = draft.copy(brightness = it/100f)
            }
            RenderAdjustment("画面对比度", draft.contrast*100f, draft.colorControlsEnabled) {
                draft = draft.copy(contrast = it/100f)
            }
            RenderAdjustment("画面饱和度", draft.saturation, draft.colorControlsEnabled) {
                draft = draft.copy(saturation = it)
            }
            if (!draft.colorControlsEnabled) Text("画面调节仅在 GPU 增强模式生效。", color = Muted)
            GlassTextButton(onClick = { draft = draft.copy(brightness = 0f, contrast = 0f, saturation = 0f) }) {
                Text("画面参数归零")
            }
            Text("点击应用后生效，并记住当前视频的设置；保留播放位置、状态、速度与字幕。",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }, confirmButton = {
        GlassTextButton(onClick = { onDismiss(); host.applyRenderSettings(draft) }) { Text("应用") }
    }, dismissButton = { GlassTextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun RenderAdjustment(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label); Text("${if (value > 0) "+" else ""}${value.roundToInt()}%", color = Mint)
    }
    GlassSlider(value, { onChange(it.roundToInt().toFloat()) }, Modifier.fillMaxWidth().semantics { contentDescription = label },
        enabled = enabled, valueRange = -100f..100f)
}
