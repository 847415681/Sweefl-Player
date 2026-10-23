package com.sweefl.player.domain

import org.junit.Assert.*
import org.junit.Test

class FfmpegPlaybackRulesTest {
    @Test fun ffmpegIsSeparateFromPlatformSoftwareAndOptIn() {
        for (mode in listOf(PlaybackConfig.DECODE_AUTO, PlaybackConfig.DECODE_SOFTWARE)) {
            assertFalse(FfmpegPlaybackRules.preferAudio(mode))
            assertFalse(FfmpegPlaybackRules.preferVideo(mode, PlaybackRenderMode.NATIVE))
        }
        assertTrue(FfmpegPlaybackRules.preferAudio(PlaybackConfig.DECODE_FFMPEG))
        assertTrue(FfmpegPlaybackRules.preferVideo(PlaybackConfig.DECODE_FFMPEG, PlaybackRenderMode.NATIVE))
    }

    @Test fun gpuSdrNeverUsesNativeFfmpegVideoButAllowsAudio() {
        assertFalse(FfmpegPlaybackRules.preferVideo(PlaybackConfig.DECODE_FFMPEG, PlaybackRenderMode.GPU_SDR))
        assertTrue(FfmpegPlaybackRules.preferAudio(PlaybackConfig.DECODE_FFMPEG))
    }

    @Test fun rejectsEveryAdvertisedHdrSignalIndependently() {
        assertFalse(allowed(pq = true))
        assertFalse(allowed(hlg = true))
        assertFalse(allowed(bt2020 = true))
        assertFalse(allowed(staticHdr = true))
    }

    @Test fun rejectsDolbyVisionMimeAndCodecTokens() {
        assertFalse(allowed(mime = "video/dolby-vision"))
        assertFalse(allowed(mime = "VIDEO/DOLBY-VISION"))
        for (prefix in listOf("dvav", "dva1", "dvhe", "dvh1", "dav1")) {
            assertFalse(prefix, allowed(codecs = "$prefix.08.06"))
            assertFalse(prefix, allowed(codecs = "hvc1.2.4.L153.B0, ${prefix.uppercase()}.08.06"))
        }
    }

    @Test fun allowsSdrCandidatesWithoutClaimingToDetectUnadvertisedHdr() {
        assertTrue(allowed())
        assertTrue(allowed(mime = null, codecs = null))
        assertTrue(allowed(mime = "video/av01", codecs = "av01.0.08M.08"))
        assertTrue(allowed(mime = "video/hevc", codecs = "hvc1.1.6.L93.B0"))
    }

    private fun allowed(mime: String? = "video/avc", codecs: String? = "avc1.640028",
        pq: Boolean = false, hlg: Boolean = false, bt2020: Boolean = false,
        staticHdr: Boolean = false): Boolean =
        FfmpegPlaybackRules.allowsVideo(mime, codecs, pq, hlg, bt2020, staticHdr)
}
