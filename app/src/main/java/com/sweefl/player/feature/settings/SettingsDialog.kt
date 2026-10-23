package com.sweefl.player.feature.settings

import com.sweefl.player.core.designsystem.GlassDialog as AlertDialog
import com.sweefl.player.core.designsystem.GlassTextButton as TextButton
import com.sweefl.player.core.designsystem.GlassSwitch as Switch
import com.sweefl.player.BuildConfig
import com.sweefl.player.core.designsystem.Mint
import com.sweefl.player.core.designsystem.Muted

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsDialog(glassEnabled: Boolean, onGlassChanged: (Boolean) -> Unit,
    onClearHistory: () -> Unit, onPermission: () -> Unit, onDismiss: () -> Unit) {
    var gestures by remember { mutableStateOf(false) }
    var network by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Sweefl Player") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${BuildConfig.VERSION_NAME} · 移动影院", color = Mint)
                Text("本地与网络视频 · 自动保存进度\n横向滑动调整进度；左侧上下调亮度，右侧调音量。\n支持自定义双击、外置字幕与画中画。")
                TextButton(onClick = { gestures = true }, modifier = Modifier.fillMaxWidth()) { Text("手势与进度预览") }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("实时玻璃折射")
                        Text("Android 13+ · 可关闭以减少绘制开销", color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = glassEnabled, onCheckedChange = onGlassChanged, enabled = Build.VERSION.SDK_INT >= 33)
                }
                TextButton(onClick = { network = true }, modifier = Modifier.fillMaxWidth()) { Text("网络播放与缓存") }
                GlassBlurSetting()
                GlassRefreshSetting()
                Text("HDR 与格式能力依赖手机解码器和屏幕；播放页可查看实际解码信息。", color = Muted)
                TextButton(onClick = { onClearHistory(); onDismiss() }) { Text("清除播放记录") }
                TextButton(onClick = { onDismiss(); onPermission() }) { Text("管理视频访问权限") }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
    if (gestures) PlaybackInteractionDialog { gestures = false }
    if (network) NetworkPlaybackSettingsDialog { network = false }
}
