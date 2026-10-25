@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.sweefl.player.playback.diagnostics


import android.content.Context
import android.media.MediaFormat
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Samples counters every two seconds; no file I/O or JSON construction on the frame callback. */
internal class PlaybackDiagnostics(
    private val context: Context,
    private val engine: ExoPlayer,
    private val mode: String,
    private val writeSnapshots: Boolean,
    private val onFrameScheduled: (Long, Long) -> Unit = { _, _ -> },
    private val renderingState: () -> Pair<com.sweefl.player.domain.PlaybackRenderSettings, com.sweefl.player.domain.PlaybackRenderStatus>? = { null },
    private val sourceState: () -> Map<String, Any?> = { emptyMap() },
    private val onSnapshot: (PlaybackDiagnosticsState) -> Unit
) : AnalyticsListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    @Volatile private var output: MediaFormat? = null

    @Volatile private var closed = false
    private val frameListener = androidx.media3.exoplayer.video.VideoFrameMetadataListener { ptsUs, releaseTimeNs, _, mediaFormat ->
        if (!closed) {
            output = mediaFormat
            onFrameScheduled(ptsUs, releaseTimeNs)
        }
    }
    init {
        engine.addAnalyticsListener(this)
        engine.setVideoFrameMetadataListener(frameListener)
        scope.launch {
            while (isActive) {
                val counters = engine.videoDecoderCounters
                counters?.ensureUpdated()
                val video = formatJson(engine.videoFormat)
                val audio = formatJson(engine.audioFormat)
                val state = PlaybackDiagnosticsState(
                    videoDecoder, audioDecoder, video?.toString().orEmpty(), audio?.toString().orEmpty(),
                    output?.toString().orEmpty(), counters?.renderedOutputBufferCount ?: 0,
                    counters?.droppedBufferCount ?: 0,
                    engine.playbackState == androidx.media3.common.Player.STATE_BUFFERING,
                    engine.totalBufferedDuration, mode, engine.currentPosition, engine.playbackState,
                    engine.isPlaying, engine.playerError?.errorCodeName
                )
                onSnapshot(state)
                if (writeSnapshots) {
                    val online = sourceState().toMap()
                    val json = JSONObject().apply {
                        put("schemaVersion", 1)
                        put("online", if (online.isEmpty()) JSONObject.NULL else JSONObject(online))
                        put("rendering", renderingState()?.let { (settings, status) -> JSONObject().apply {
                            put("requestedMode", settings.mode.name)
                            put("brightness", settings.brightness); put("contrast", settings.contrast); put("saturation", settings.saturation)
                            put("phase", status.phase.name); put("graphRequested", status.graphToneMappingRequested)
                            put("sdrShaderCreated", status.sdrShaderCreated); put("width", status.configuredWidth); put("height", status.configuredHeight)
                            put("firstFrame", status.firstFrameReported); put("operational", status.gpuSdrOperational)
                            put("failure", status.failure?.let { "${it.origin}: ${it.detail}" } ?: JSONObject.NULL)
                        } } ?: JSONObject.NULL)
                        put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                        put("decodeMode", mode)
                        put("softwareDescription", when (mode) {
                            com.sweefl.player.domain.PlaybackConfig.DECODE_FFMPEG ->
                                "Experimental FFmpeg compatibility preference with platform fallback; native video is 8-bit YV12, not color-managed; advertised HDR/DV excluded; GPU_SDR uses platform video and may use FFmpeg audio"
                            com.sweefl.player.domain.PlaybackConfig.DECODE_SOFTWARE -> "Platform software video MediaCodec; device-specific native-crash workaround may prefer FFmpeg audio"
                            else -> "Automatic platform video MediaCodec; device-specific native-crash workaround may prefer FFmpeg audio"
                        })
                        put("videoDecoder", videoDecoder ?: JSONObject.NULL)
                        put("audioDecoder", audioDecoder ?: JSONObject.NULL)
                        put("videoFormat", video ?: JSONObject.NULL)
                        put("audioFormat", audio ?: JSONObject.NULL)
                        put("outputFormat", state.outputFormat)
                        put("renderedFrames", state.renderedFrames)
                        put("droppedFrames", state.droppedFrames)
                        put("buffering", state.buffering)
                        put("bufferedDurationMs", state.bufferedDurationMs)
                        put("positionMs", state.positionMs)
                        put("playbackState", state.playbackState)
                        put("isPlaying", state.isPlaying)
                        put("playbackSpeed", engine.playbackParameters.speed)
                        put("error", state.error ?: JSONObject.NULL)
                        put("cueCount", engine.currentCues.cues.size)
                        put("cueTexts", JSONArray(engine.currentCues.cues.take(4).map { it.text?.toString().orEmpty().take(256) }))
                        put("textTracks", JSONArray(engine.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }.map { group ->
                            JSONObject().put("selected", group.isSelected).put("format", group.getTrackFormat(0).toString())
                        }))
                        put("errorDetail", engine.playerError?.let { failure ->
                            val detail = generateSequence<Throwable>(failure) { it.cause }.take(5).joinToString(" -> ") { it.toString() }
                            (if (online.isEmpty()) detail else diagnosticHttpUrl.replace(detail, "[redacted URL]")).take(2500)
                        } ?: JSONObject.NULL)
                    }.toString(2)
                    withContext(Dispatchers.IO) { runCatching { atomicWrite(File(context.filesDir, "diagnostics.json"), json) } }
                }
                delay(2_000)
            }
        }
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        videoDecoder = decoderName
    }
    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        audioDecoder = decoderName
    }
    fun close() {
        closed = true
        engine.clearVideoFrameMetadataListener(frameListener)
        scope.cancel()
        engine.removeAnalyticsListener(this)
    }
}

private val diagnosticHttpUrl = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

private fun formatJson(format: Format?): JSONObject? = format?.let {
    JSONObject().apply {
        put("id", it.id ?: JSONObject.NULL)
        put("sampleMimeType", it.sampleMimeType ?: JSONObject.NULL)
        put("codecs", it.codecs ?: JSONObject.NULL)
        put("width", it.width); put("height", it.height); put("frameRate", it.frameRate)
        put("bitrate", it.bitrate); put("channelCount", it.channelCount); put("sampleRate", it.sampleRate)
        put("color", it.colorInfo?.let { color -> JSONObject().apply {
            put("space", color.colorSpace); put("range", color.colorRange); put("transfer", color.colorTransfer)
            put("hdrStaticInfoBytes", color.hdrStaticInfo?.size ?: 0)
            put("lumaBitdepth", color.lumaBitdepth); put("chromaBitdepth", color.chromaBitdepth)
        } } ?: JSONObject.NULL)
    }
}

