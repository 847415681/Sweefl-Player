package com.sweefl.player.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sweefl.player.core.designsystem.GlassDialog
import com.sweefl.player.core.designsystem.GlassTextButton
import com.sweefl.player.core.designsystem.GlassTextField
import com.sweefl.player.core.designsystem.Muted
import com.sweefl.player.data.preferences.PlayerPreferences
import com.sweefl.player.domain.NetworkPlaybackSettings

private class NetworkPreferences(val value: NetworkPlaybackSettings, val save: (NetworkPlaybackSettings) -> Unit)
private val LocalNetworkPreferences = staticCompositionLocalOf {
    NetworkPreferences(NetworkPlaybackSettings()) { }
}

/** Injected storage boundary: UI never opens or edits SharedPreferences itself. */
@Composable
fun NetworkPlaybackPreferencesProvider(preferences: PlayerPreferences, content: @Composable () -> Unit) {
    var value by remember(preferences) { mutableStateOf(preferences.networkPlayback) }
    DisposableEffect(preferences) {
        val unsubscribe = preferences.observeNetworkPlayback { value = it }
        onDispose { unsubscribe() }
    }
    CompositionLocalProvider(LocalNetworkPreferences provides NetworkPreferences(value) {
        preferences.networkPlayback = it
        value = it.normalized()
    }, content = content)
}

@Composable
fun NetworkPlaybackSettingsDialog(onDismiss: () -> Unit) {
    val preferences = LocalNetworkPreferences.current
    var threads by rememberSaveable { mutableStateOf(preferences.value.threads.toString()) }
    var seconds by rememberSaveable { mutableStateOf(preferences.value.bufferSeconds.toString()) }
    val validThreads = threads.trim().toIntOrNull()?.let { it in 1..32 } == true
    val validSeconds = seconds.trim().toIntOrNull()?.let { it in 5..600 } == true
    val parsed = NetworkPlaybackSettings.parse(threads, seconds)
    GlassDialog(onDismiss, title = { Text("网络播放与缓存") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassTextField(threads, { threads = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("并发线程数（1–32）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text(if (validThreads) "默认 4；服务器支持分段请求时生效" else "请输入 1 到 32 的整数",
                    color = if (validThreads) Muted else MaterialTheme.colorScheme.error) })
            GlassTextField(seconds, { seconds = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("缓存时长（秒，5–600）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text(if (validSeconds) "默认 30 秒" else "请输入 5 到 600 的整数",
                    color = if (validSeconds) Muted else MaterialTheme.colorScheme.error) })
            Text("保存后在下一次打开视频或播放器重建时生效，不中断当前播放。", color = Muted)
            Text("时长是预缓冲目标，不是离线下载；同时受 64 MiB 播放缓冲字节预算约束，高码率或直播可少于设定时长。", color = Muted)
            Text("此数值限制共享分块加速请求，不限制音视频轨道必需的普通连接。32 并发不一定更快；加速需支持 Range、已知长度和强 ETag，不支持时回退。HLS/DASH 仅符合条件的大分片可分块加速，并非同时下载32个分片。", color = Muted)
        }
    }, confirmButton = {
        GlassTextButton(enabled = parsed != null, onClick = {
            parsed?.let { preferences.save(it); onDismiss() }
        }) { Text("保存") }
    }, dismissButton = { GlassTextButton(onClick = onDismiss) { Text("取消") } })
}
