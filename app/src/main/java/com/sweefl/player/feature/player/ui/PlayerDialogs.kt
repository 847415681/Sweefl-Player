@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.feature.player.ui

import com.sweefl.player.core.designsystem.GlassDialog as AlertDialog
import com.sweefl.player.core.designsystem.GlassTextButton as TextButton
import com.sweefl.player.core.designsystem.GlassSwitch as Switch
import com.sweefl.player.core.designsystem.LocalGlassEnabled
import com.sweefl.player.core.designsystem.Mint
import com.sweefl.player.core.designsystem.Muted
import com.sweefl.player.core.designsystem.glass
import com.sweefl.player.feature.player.PlayerHost

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer

@Composable
internal fun PlaybackDetailsDialog(host: PlayerHost, onGlassChanged: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var editingRendering by remember { mutableStateOf(false) }
    if (editingRendering) { RenderingSettingsDialog(host) { editingRendering = false }; return }
    val info = host.diagnosticsState
    val glass = LocalGlassEnabled.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("播放信息与解码") }, text = {
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("视频：${info.videoDecoder ?: "尚未初始化"}\n音频：${info.audioDecoder ?: "无音轨 / 尚未初始化"}")
            Text("已呈现 ${info.renderedFrames} 帧 · 丢帧 ${info.droppedFrames}\n缓冲 ${info.bufferedDurationMs / 1000} 秒", color = Mint)
            Text(info.videoFormat.ifBlank { "暂未取得视频格式" }, style = MaterialTheme.typography.bodySmall)
            Text("HDR/DV 元数据不等同于屏幕输出已验证。", color = Muted)
            RenderingStatusText(host)
            TextButton(onClick = { editingRendering = true }) { Text("画面与渲染设置") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("实时折射", Modifier.weight(1f))
                Switch(checked = glass, onCheckedChange = onGlassChanged, enabled = android.os.Build.VERSION.SDK_INT >= 33)
            }
            com.sweefl.player.feature.settings.GlassBlurSetting()
            com.sweefl.player.feature.settings.GlassRefreshSetting()
            TextButton(onClick = { onDismiss(); host.changeDecodeMode("AUTO") }) { Text(if (host.decodeMode == "AUTO") "✓ 自动解码（优先硬件）" else "自动解码（优先硬件）") }
            TextButton(onClick = { onDismiss(); host.changeDecodeMode("SOFTWARE") }) { Text(if (host.decodeMode == "SOFTWARE") "✓ 系统软件解码" else "系统软件解码") }
            TextButton(onClick = { onDismiss(); host.changeDecodeMode("FFMPEG") }) { Text(if (host.decodeMode == "FFMPEG") "✓ FFmpeg（实验性兼容模式）" else "FFmpeg（实验性兼容模式）") }
            Text("系统软件模式仅使用设备的软件 MediaCodec。FFmpeg 模式优先尝试兼容的 SDR 视频与音频，不支持的格式保留平台解码路径。FFmpeg 原生视频为 8 位 YV12，不遵循完整色彩元数据，并非色准渲染器；已标记的 HDR / 杜比视界交给平台。GPU SDR 模式仅允许 FFmpeg 音频，视频仍走平台 GPU 管线。", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}

@Composable
internal fun TrackDialog(player: ExoPlayer, tracks: Tracks, host: PlayerHost, onDismiss: () -> Unit) {
    var synchronizing by remember { mutableStateOf(false) }
    if (synchronizing) { SubtitleSyncDialog(host) { synchronizing = false }; return }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("字幕与音轨") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = { onDismiss(); host.pickSubtitle() }) { Text("加载外置字幕（SRT / VTT / ASS）") }
            TextButton(onClick = { synchronizing = true }, enabled = host.subtitleName != null) { Text("字幕同步（仅外挂字幕）") }
            host.subtitleName?.let { name ->
                Text(name, color = Mint)
                TextButton(onClick = { host.removeSubtitle(); onDismiss() }) { Text("移除外置字幕") }
            }
            TextButton(onClick = {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                onDismiss()
            }) { Text("关闭字幕") }
            TextButton(onClick = {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
                onDismiss()
            }) { Text("自动选择") }
            val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO || it.type == C.TRACK_TYPE_TEXT }
            if (groups.isEmpty()) Text("当前视频没有可选音轨或字幕。", color = Muted)
            groups.forEach { group ->
                for (index in 0 until group.length) {
                    val format = group.getTrackFormat(index)
                    val type = if (group.type == C.TRACK_TYPE_TEXT) "字幕" else "音轨"
                    val label = format.label ?: format.language ?: "轨道 ${index + 1}"
                    TextButton(enabled = group.isTrackSupported(index), onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                            .setTrackTypeDisabled(group.type, false).build()
                        onDismiss()
                    }) { Text("${if (group.isTrackSelected(index)) "✓ " else ""}$type · $label") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
