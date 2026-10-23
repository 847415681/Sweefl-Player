package com.sweefl.player.feature.network

import com.sweefl.player.core.designsystem.GlassDialog as AlertDialog
import com.sweefl.player.core.designsystem.GlassTextButton as TextButton
import com.sweefl.player.core.designsystem.GlassTextField as OutlinedTextField
import com.sweefl.player.core.designsystem.GlassSwitch as Switch
import com.sweefl.player.core.designsystem.Muted
import com.sweefl.player.domain.networkInputError

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun NetworkDialog(defaultUserAgent: String, onDismiss: () -> Unit, onPlay: (Uri, String, String, String?) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var agent by rememberSaveable { mutableStateOf(defaultUserAgent) }
    var software by rememberSaveable { mutableStateOf(false) }
    var streamType by rememberSaveable { mutableStateOf("自动") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("打开网络视频") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("支持直链、HLS（m3u8）和 DASH（mpd）。网页地址可能无法播放。", color = Muted)
            OutlinedTextField(address, { address = it; error = null }, label = { Text("视频 URL") },
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), maxLines = 3)
            OutlinedTextField(agent, { agent = it; error = null }, label = { Text("User-Agent") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { Text("此 UA 用于播放清单及分片请求，并记住为下次默认值。") })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("自动", "HLS", "DASH").forEach { type ->
                    TextButton(onClick = { streamType = type }) { Text(if (streamType == type) "✓ $type" else type) }
                }
            }
            Text("无文件扩展名的清单链接可手动选择 HLS / DASH。", color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("使用系统软件解码", modifier = Modifier.weight(1f).padding(top = 12.dp))
                Switch(checked = software, onCheckedChange = { software = it })
            }
            if (software) Text("软件解码更耗 CPU，部分格式可能没有可用的软件解码器。", color = Muted)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = {
            val cleanAddress = address.trim()
            val cleanAgent = agent.trim().ifBlank { "SweeflPlayer/0.2.0 (Android)" }
            error = networkInputError(cleanAddress, cleanAgent)
            if (error == null) {
                val mime = when (streamType) { "HLS" -> "application/vnd.apple.mpegurl"; "DASH" -> "application/dash+xml"; else -> null }
                onPlay(Uri.parse(cleanAddress), cleanAgent, if (software) "SOFTWARE" else "AUTO", mime)
            }
        }) { Text("播放") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
