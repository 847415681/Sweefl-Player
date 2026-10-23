package com.sweefl.player.playback.diagnostics

import com.sweefl.player.domain.PlaybackConfig
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Capability advertisements are evidence of advertised support, not proof of correct HDR output. */
internal object DeviceCapabilities {
    private val started = AtomicBoolean(false)
    fun collectOnce(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val codecs = JSONArray()
                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }.forEach { codec ->
                    val types = JSONArray()
                    codec.supportedTypes.forEach { mime ->
                        val entry = JSONObject().put("mime", mime)
                        runCatching {
                            val caps = codec.getCapabilitiesForType(mime)
                            entry.put("profileLevels", JSONArray().apply {
                                caps.profileLevels.forEach { put(JSONObject().put("profile", it.profile).put("level", it.level)) }
                            })
                            entry.put("maxSupportedInstances", caps.maxSupportedInstances)
                            caps.videoCapabilities?.let {
                                entry.put("widths", it.supportedWidths.toString())
                                entry.put("heights", it.supportedHeights.toString())
                                entry.put("frameRates", it.supportedFrameRates.toString())
                                entry.put("bitrateRange", it.bitrateRange.toString())
                            }
                            caps.audioCapabilities?.let {
                                entry.put("maxInputChannelCount", it.maxInputChannelCount)
                                entry.put("sampleRateRanges", JSONArray(it.supportedSampleRateRanges.map { range -> range.toString() }))
                            }
                        }.onFailure { entry.put("error", it.javaClass.simpleName) }
                        types.put(entry)
                    }
                    codecs.put(JSONObject().put("name", codec.name).put("types", types).apply {
                        put("softwareOnly", if (Build.VERSION.SDK_INT >= 29) codec.isSoftwareOnly else PlaybackConfig.isLegacySoftwareCodec(codec.name))
                        put("softwareClassification", if (Build.VERSION.SDK_INT >= 29) "platform" else "conservative-name-match")
                        if (Build.VERSION.SDK_INT >= 29) put("hardwareAccelerated", codec.isHardwareAccelerated)
                    })
                }
                val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                val hdr = display?.hdrCapabilities
                val json = JSONObject().put("schemaVersion", 1).put("sdkInt", Build.VERSION.SDK_INT)
                    .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("decoders", codecs)
                    .put("displayHdrTypes", JSONArray(hdr?.supportedHdrTypes?.toList() ?: emptyList<Int>()))
                    .put("displayHdrTypeNames", JSONArray(hdr?.supportedHdrTypes?.map { when (it) {
                        1 -> "DOLBY_VISION"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10_PLUS"; else -> "UNKNOWN_$it"
                    } } ?: emptyList<String>()))
                    .put("hdrOutputVerified", false)
                    .put("note", "Decoder MIME/profile and display HDR advertisements do not verify Dolby Vision or HDR output correctness.")
                atomicWrite(File(app.filesDir, "capabilities.json"), json.toString(2))
            }
        }
    }
}
