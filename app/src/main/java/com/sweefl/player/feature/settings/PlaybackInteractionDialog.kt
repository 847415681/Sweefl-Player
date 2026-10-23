package com.sweefl.player.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sweefl.player.core.designsystem.GlassDialog
import com.sweefl.player.core.designsystem.GlassSwitch
import com.sweefl.player.core.designsystem.GlassTextButton
import com.sweefl.player.core.designsystem.Muted
import com.sweefl.player.domain.DoubleTapAction
import com.sweefl.player.domain.PlaybackInteractionSettings

class InteractionPreferences(val value: PlaybackInteractionSettings, val update: (PlaybackInteractionSettings) -> Unit)
val LocalPlaybackInteractions = staticCompositionLocalOf {
    InteractionPreferences(PlaybackInteractionSettings()) { }
}

@Composable
fun PlaybackInteractionDialog(onDismiss: () -> Unit) {
    val preferences = LocalPlaybackInteractions.current
    val settings = preferences.value
    var region by remember { mutableStateOf<Int?>(null) }
    var chooseHoldSpeed by remember { mutableStateOf(false) }
    val labels = listOf("左侧区域", "中间区域", "右侧区域")
    val actions = listOf(settings.leftDoubleTap, settings.centerDoubleTap, settings.rightDoubleTap)
    GlassDialog(onDismiss, title = { Text("手势与进度预览") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("显示进度预览")
                    Text("拖动时显示目标画面；会增加解码与网络开销。", color = Muted,
                        style = MaterialTheme.typography.bodySmall)
                }
                GlassSwitch(settings.showSeekPreview,
                    { preferences.update(settings.copy(showSeekPreview = it)) },
                    Modifier.semantics { contentDescription = "显示进度预览" })
            }
            Text("横向滑动快进 / 快退，松手后跳转。左侧上下滑动调亮度，右侧调音量。", color = Muted)
            GlassTextButton(onClick = { chooseHoldSpeed = true }, modifier = Modifier.fillMaxWidth()) {
                Text("长按临时倍速", Modifier.weight(1f))
                Text("${settings.longPressSpeed}×")
            }
            Text("播放时长按空白画面临时变速，松手恢复原速度。", color = Muted,
                style = MaterialTheme.typography.bodySmall)
            Text("双击动作 · 播放区等分为左 / 中 / 右三个区域", style = MaterialTheme.typography.titleSmall)
            labels.forEachIndexed { index, label ->
                GlassTextButton(onClick = { region = index }, modifier = Modifier.fillMaxWidth()) {
                    Text(label, Modifier.weight(1f))
                    Text(actions[index].label)
                }
            }
            GlassTextButton(onClick = { preferences.update(settings.withDefaultGestures()) }) {
                Text("恢复默认手势")
            }
        }
    }, confirmButton = { GlassTextButton(onClick = onDismiss) { Text("完成") } })
    if (chooseHoldSpeed) {
        GlassDialog(onDismissRequest = { chooseHoldSpeed = false }, title = { Text("长按临时倍速") }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                com.sweefl.player.domain.LONG_PRESS_SPEED_CHOICES.forEach { value ->
                    GlassTextButton(onClick = {
                        preferences.update(settings.copy(longPressSpeed = value))
                        chooseHoldSpeed = false
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (settings.longPressSpeed == value) "✓  " else "") + "${value}×")
                    }
                }
            }
        }, confirmButton = { GlassTextButton(onClick = { chooseHoldSpeed = false }) { Text("取消") } })
    }
    region?.let { selected ->
        GlassDialog(onDismissRequest = { region = null }, title = { Text("${labels[selected]} · 双击") }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                DoubleTapAction.entries.forEach { action ->
                    GlassTextButton(onClick = {
                        preferences.update(when (selected) {
                            0 -> settings.copy(leftDoubleTap = action)
                            1 -> settings.copy(centerDoubleTap = action)
                            else -> settings.copy(rightDoubleTap = action)
                        })
                        region = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (actions[selected] == action) "✓  " else "") + action.label)
                    }
                }
            }
        }, confirmButton = { GlassTextButton(onClick = { region = null }) { Text("取消") } })
    }
}
