package com.sweefl.player.feature.network

import com.sweefl.player.core.designsystem.GlassDialog as AlertDialog
import com.sweefl.player.core.designsystem.GlassTextButton as TextButton
import com.sweefl.player.core.designsystem.GlassTextField as OutlinedTextField
import com.sweefl.player.core.designsystem.GlassSwitch as Switch
import com.sweefl.player.core.designsystem.Muted
import com.sweefl.player.domain.networkInputError

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    var address by rememberSaveable { mutableStateOf("") }
    var agent by rememberSaveable { mutableStateOf(defaultUserAgent) }
    var software by rememberSaveable { mutableStateOf(false) }
    var streamType by rememberSaveable { mutableStateOf("自动") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("打开网络视频") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("支持直链、HLS（m3u8）、DASH（mpd）和 B 站视频 / 直播链接。可粘贴 B 站分享文本或 BV / AV 编号。", color = Muted)
            TextButton(onClick = {
                context.startActivity(Intent(context, BilibiliLoginActivity::class.java))
            }) { Text("B站官方登录（可选）") }
            Text("登录后可使用账号已开通的清晰度与内容。", color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(address, { address = it; error = null }, label = { Text("视频 URL / B站分享链接") },
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), maxLines = 3)
            OutlinedTextField(agent, { agent = it; error = null }, label = { Text("User-Agent") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { Text("用于普通网络流，并记住为下次默认值；B站链接自动使用兼容的浏览器 UA。") })
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
            val cleanAddress = normalizeNetworkAddress(address)
            val cleanAgent = agent.trim().ifBlank { "SweeflPlayer/0.2.0 (Android)" }
            error = networkInputError(cleanAddress, cleanAgent)
            if (error == null) {
                val mime = when (streamType) { "HLS" -> "application/vnd.apple.mpegurl"; "DASH" -> "application/dash+xml"; else -> null }
                onPlay(Uri.parse(cleanAddress), cleanAgent, if (software) "SOFTWARE" else "AUTO", mime)
            }
        }) { Text("播放") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
