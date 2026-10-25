@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.feature.player.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.sweefl.player.core.designsystem.GlassDialog
import com.sweefl.player.core.model.ResolvedOnlineMedia
import com.sweefl.player.feature.player.PlayerHost

internal data class SupportedTrackChoice(val group: Tracks.Group, val index: Int, val label: String)

/** Resolved HLS can carry native audio tracks without separate resolver-provided audio URLs. */
internal fun usesResolvedAudioSelection(media: ResolvedOnlineMedia?): Boolean =
    media?.audios?.isNotEmpty() == true

/** Preserve distinct tracks even when their language labels match. */
internal fun supportedTrackChoices(tracks: Tracks, type: Int): List<SupportedTrackChoice> {
    val choices = tracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { index ->
            val f = group.getTrackFormat(index)
            val parts = if (type == C.TRACK_TYPE_VIDEO) listOfNotNull(
                f.height.takeIf { it > 0 }?.let { "${it}p" },
                f.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" }, f.codecs
            ) else listOfNotNull(f.label?.takeIf { it.isNotBlank() },
                f.language?.takeIf { it.isNotBlank() && it != "und" },
                f.channelCount.takeIf { it > 0 }?.let { "${it} 声道" },
                f.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" })
            SupportedTrackChoice(group, index, parts.joinToString(" · ").ifBlank { "轨道" })
        }
    }
    val counts = choices.groupingBy { it.label }.eachCount()
    return choices.mapIndexed { i, c -> if ((counts[c.label] ?: 0) > 1) c.copy(label = "${c.label} · ${i + 1}") else c }
}

@Composable
internal fun OnlineChoiceDialog(kind: String, host: PlayerHost, player: ExoPlayer, tracks: Tracks, onDismiss: () -> Unit) {
    val media = host.onlineMedia
    val title = when (kind) { "episodes" -> "选集"; "quality" -> "清晰度"; else -> "音轨" }
    GlassDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (media != null && (kind != "audio" || usesResolvedAudioSelection(media))) {
                when (kind) {
                    "episodes" -> media.episodes.distinctBy { it.pageUrl }.forEach { e ->
                        TextButton(onClick = { onDismiss(); host.selectEpisode(e.pageUrl) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${if (e.pageUrl == media.pageUrl) "✓  " else ""}${e.title}")
                        }
                    }
                    "quality" -> media.qualities.distinctBy { it.id }.forEach { q ->
                        TextButton(onClick = { onDismiss(); host.selectQuality(q.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${if (q.id == media.video.qualityId) "✓  " else ""}${q.label}")
                        }
                    }
                    else -> media.audios.distinctBy { it.id }.forEach { a ->
                        TextButton(onClick = { onDismiss(); host.selectAudio(a.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${if (a.id == media.selectedAudioId) "✓  " else ""}${a.label}")
                        }
                    }
                }
            } else {
                val type = if (kind == "quality") C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO
                val automatic = player.trackSelectionParameters.overrides.values.none { it.type == type }
                TextButton(onClick = {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(type).setTrackTypeDisabled(type, false).build()
                    onDismiss()
                }, modifier = Modifier.fillMaxWidth()) { Text("${if (automatic) "✓  " else ""}自动选择") }
                supportedTrackChoices(tracks, type).forEach { c ->
                    TextButton(onClick = {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(c.group.mediaTrackGroup, c.index))
                            .setTrackTypeDisabled(type, false).build()
                        onDismiss()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("${if (!automatic && c.group.isTrackSelected(c.index)) "✓  " else ""}${c.label}")
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } })
}
