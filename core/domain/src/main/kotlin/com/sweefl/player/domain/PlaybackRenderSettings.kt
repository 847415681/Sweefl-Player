package com.sweefl.player.domain

/** NATIVE never enables a video effect graph, even with saved non-neutral colors. */
enum class PlaybackRenderMode { NATIVE, GPU_SDR }

data class PlaybackRenderSettings(
    val mode: PlaybackRenderMode = PlaybackRenderMode.NATIVE,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
) {
    init {
        require(brightness.isFinite() && brightness in -1f..1f)
        require(contrast.isFinite() && contrast in -1f..1f)
        require(saturation.isFinite() && saturation in -100f..100f)
    }
    val colorControlsEnabled get() = mode == PlaybackRenderMode.GPU_SDR
    val hasEffectiveColorAdjustments get() = colorControlsEnabled &&
        (brightness != 0f || contrast != 0f || saturation != 0f)
    companion object {
        fun fromStored(mode: String?, brightness: Float = 0f, contrast: Float = 0f, saturation: Float = 0f) =
            PlaybackRenderSettings(
                PlaybackRenderMode.entries.firstOrNull { it.name == mode } ?: PlaybackRenderMode.NATIVE,
                sanitize(brightness, 1f), sanitize(contrast, 1f), sanitize(saturation, 100f))
        private fun sanitize(value: Float, limit: Float) =
            if (value.isFinite()) value.coerceIn(-limit, limit) else 0f
    }
}

enum class PlaybackRenderPhase {
    REQUESTED, GRAPH_REQUESTED, SDR_SHADER_CREATED, SDR_EFFECT_CONFIGURED,
    FIRST_FRAME_REPORTED, FAILED, RELEASED
}
enum class PlaybackRenderFailureOrigin { SETUP, EFFECT, GRAPH, PLAYER }
data class PlaybackRenderFailure(val origin: PlaybackRenderFailureOrigin, val detail: String)
sealed interface PlaybackRenderEvent {
    object GraphRequested : PlaybackRenderEvent
    object SdrShaderCreated : PlaybackRenderEvent
    data class SdrEffectConfigured(val width: Int, val height: Int) : PlaybackRenderEvent
    object FirstFrameReported : PlaybackRenderEvent
    data class Failed(val failure: PlaybackRenderFailure) : PlaybackRenderEvent
    object Released : PlaybackRenderEvent
}

/** Evidence of processing and Media3 callbacks, not physical display colorimetry. */
data class PlaybackRenderStatus(
    val requestedMode: PlaybackRenderMode,
    val graphToneMappingRequested: Boolean = false,
    val sdrShaderCreated: Boolean = false,
    val configuredWidth: Int = 0,
    val configuredHeight: Int = 0,
    val firstFrameReported: Boolean = false,
    val failure: PlaybackRenderFailure? = null,
    val released: Boolean = false,
) {
    val sdrEffectConfigured get() = requestedMode == PlaybackRenderMode.GPU_SDR &&
        graphToneMappingRequested && sdrShaderCreated && configuredWidth > 0 && configuredHeight > 0 &&
        failure == null && !released
    val gpuSdrOperational get() = sdrEffectConfigured && firstFrameReported
    val phase get() = when {
        released -> PlaybackRenderPhase.RELEASED
        failure != null -> PlaybackRenderPhase.FAILED
        firstFrameReported && (requestedMode == PlaybackRenderMode.NATIVE || sdrEffectConfigured) -> PlaybackRenderPhase.FIRST_FRAME_REPORTED
        sdrEffectConfigured -> PlaybackRenderPhase.SDR_EFFECT_CONFIGURED
        graphToneMappingRequested && sdrShaderCreated -> PlaybackRenderPhase.SDR_SHADER_CREATED
        graphToneMappingRequested -> PlaybackRenderPhase.GRAPH_REQUESTED
        else -> PlaybackRenderPhase.REQUESTED
    }
    fun accept(event: PlaybackRenderEvent): PlaybackRenderStatus {
        if (event == PlaybackRenderEvent.Released) return copy(released = true)
        if (released || failure != null) return this
        return when (event) {
            PlaybackRenderEvent.GraphRequested -> if (requestedMode == PlaybackRenderMode.GPU_SDR) copy(graphToneMappingRequested = true) else this
            PlaybackRenderEvent.SdrShaderCreated -> if (requestedMode == PlaybackRenderMode.GPU_SDR) copy(sdrShaderCreated = true) else this
            is PlaybackRenderEvent.SdrEffectConfigured -> if (requestedMode == PlaybackRenderMode.GPU_SDR && event.width > 0 && event.height > 0)
                copy(configuredWidth = event.width, configuredHeight = event.height) else this
            PlaybackRenderEvent.FirstFrameReported -> copy(firstFrameReported = true)
            is PlaybackRenderEvent.Failed -> copy(failure = event.failure)
            PlaybackRenderEvent.Released -> this
        }
    }
}
