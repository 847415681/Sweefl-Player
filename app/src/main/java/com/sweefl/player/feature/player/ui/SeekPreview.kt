package com.sweefl.player.feature.player.ui

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sweefl.player.domain.formatDuration
import com.sweefl.player.playback.preview.PreviewSource
import com.sweefl.player.playback.preview.SeekPreviewController

/** Remember outside the conditional preview renderer to reuse a prepared decoder briefly.
 * Creation alone does not create a player. Disposal, disabling or ON_STOP releases it. */
@Stable
class SeekPreviewHost internal constructor(internal val controller: SeekPreviewController, enabled: Boolean) {
    internal var enabled by mutableStateOf(enabled)
    internal var foreground by mutableStateOf(false)
}

@Composable
fun rememberSeekPreviewHost(source: PreviewSource, enabled: Boolean = true): SeekPreviewHost {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val host = remember(source, context, owner) {
        SeekPreviewHost(SeekPreviewController(context, source, com.sweefl.player.BuildConfig.DEBUG), enabled)
    }
    SideEffect {
        host.enabled = enabled
        if (!enabled) host.controller.suspend()
    }
    DisposableEffect(host, owner) {
        host.foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> host.foreground = true
                Lifecycle.Event.ON_STOP -> { host.foreground = false; host.controller.suspend() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); host.controller.close() }
    }
    return host
}

/** Compatibility entry point. A stable external host is required for cross-gesture reuse. */
@Composable
fun SeekPreview(source: PreviewSource, targetMs: Long) {
    SeekPreview(rememberSeekPreviewHost(source), targetMs)
}

/** May be conditionally composed while scrubbing: the stable host owns the short idle grace,
 * while this renderer owns only its TextureView and invalidates cached pixels on detach. */
@Composable
fun SeekPreview(host: SeekPreviewHost, targetMs: Long, modifier: Modifier = Modifier) {
    val controller = host.controller
    val target = SeekPreviewController.bucket(targetMs)
    val eligible = host.enabled && host.foreground
    DisposableEffect(host) { onDispose { controller.hide() } }
    LaunchedEffect(host, target, eligible) {
        if (eligible) controller.show(target) else controller.suspend()
    }
    val state by controller.state.collectAsState()
    if (!eligible) return
    val ready = state.frameMs != null
    val latest = state.targetMs == target
    val shownTime = if (ready) state.frameMs!! else targetMs
    val height = LocalConfiguration.current.screenHeightDp
    val width = if (height < 500) (height - 200).coerceIn(80, 144) else 168
    Box(modifier.width(width.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))
        .background(Color.Black).clearAndSetSemantics {
            contentDescription = "进度预览 ${formatDuration(targetMs)} " +
                if (ready && latest) "画面已就绪，实际画面 ${formatDuration(shownTime)}"
                else if (ready) "更新中，当前已校验画面 ${formatDuration(shownTime)}"
                else "等待画面，${if (state.targetMs == target) state.message else "加载预览…"}"
        }) {
        key(host) {
            AndroidView(factory = { TextureView(it).also(controller::attach) }, modifier = Modifier.fillMaxSize(),
                onRelease = { controller.detach(it) })
        }
        if (!ready) Box(Modifier.fillMaxSize().background(Color(0xFF142130)), contentAlignment = Alignment.Center) {
            Text(if (state.targetMs == target) state.message else "加载预览…", modifier = Modifier.padding(horizontal = 6.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(30.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .82f)))))
        Text(formatDuration(shownTime) + if (ready && !latest) " · 更新中" else "", Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
