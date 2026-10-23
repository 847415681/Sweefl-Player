package com.sweefl.player.feature.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sweefl.player.core.designsystem.GlassDialog
import com.sweefl.player.core.designsystem.GlassTextButton
import com.sweefl.player.core.designsystem.Mint
import com.sweefl.player.core.designsystem.Muted
import com.sweefl.player.domain.SubtitleSyncRules
import com.sweefl.player.feature.player.PlayerHost

@Composable
internal fun SubtitleSyncDialog(host: PlayerHost, onDismiss: () -> Unit) {
    val offset = host.subtitleOffsetMs
    val available = host.subtitleName != null
    val limit = SubtitleSyncRules.MAX_OFFSET_MS
    GlassDialog(onDismissRequest = onDismiss, title = { Text("外挂字幕同步") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(host.subtitleName ?: "请先加载外挂字幕", color = Mint)
            Text("${if (offset > 0) "+" else ""}$offset ms", style = MaterialTheme.typography.headlineMedium)
            Text("正值让字幕晚出现，负值让字幕提前。范围为 ±60 秒。")
            Text("仅对外挂字幕生效，内嵌字幕保持原始时间。", color = Muted)
            for (direction in listOf(-1L, 1L)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (step in listOf(1000L, 100L)) {
                        GlassTextButton(onClick = { host.setSubtitleOffsetMs(host.subtitleOffsetMs + step*direction) },
                            modifier = Modifier.weight(1f),
                            enabled = available && if (direction < 0) offset > -limit else offset < limit) {
                            Text("${if (direction < 0) "提前" else "延后"} ${if (step == 1000L) "1" else "0.1"} 秒")
                        }
                    }
                }
            }
            GlassTextButton(onClick = { host.setSubtitleOffsetMs(0L) }, enabled = available && offset != 0L) { Text("归零") }
            Text("自动记住当前视频的偏移，调整时音视频进度保持不变。", color = Muted,
                style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { GlassTextButton(onClick = onDismiss) { Text("完成") } })
}
