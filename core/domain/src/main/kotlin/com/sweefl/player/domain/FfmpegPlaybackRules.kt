package com.sweefl.player.domain

import java.util.Locale

/** NextLib's native video path is experimental 8-bit YV12, not a color-managed renderer. */
object FfmpegPlaybackRules {
    fun preferAudio(decodeMode: String): Boolean = decodeMode == PlaybackConfig.DECODE_FFMPEG

    fun preferVideo(decodeMode: String, renderMode: PlaybackRenderMode): Boolean =
        preferAudio(decodeMode) && renderMode == PlaybackRenderMode.NATIVE

    /** Reject advertised HDR/wide gamut even if the decoder reports that the codec is supported.
     * Missing metadata is not proof of SDR; this gate cannot identify unadvertised HDR.
     */
    fun allowsVideo(
        sampleMimeType: String?, codecs: String?, isPq: Boolean, isHlg: Boolean,
        isBt2020: Boolean, hasStaticHdrInfo: Boolean,
    ): Boolean {
        if (isPq || isHlg || isBt2020 || hasStaticHdrInfo) return false
        if (sampleMimeType.equals("video/dolby-vision", ignoreCase = true)) return false
        return codecs.orEmpty().split(',').none { codec ->
            codec.trim().lowercase(Locale.ROOT).substringBefore('.') in setOf("dvav", "dva1", "dvhe", "dvh1", "dav1")
        }
    }
}
