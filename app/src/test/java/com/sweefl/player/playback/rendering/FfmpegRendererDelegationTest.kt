@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.sweefl.player.playback.rendering

import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercise the real wrapper through Media3's interface, without Android stub behavior. */
class FfmpegRendererDelegationTest {
    @Test fun pausedFirstFramePermissionReachesTheDecoder() {
        val calls = mutableListOf<String>()
        val wrapped = renderer { name, _ -> calls += name; null }
        wrapped.enableMayRenderStartOfStream()
        assertEquals(listOf("enableMayRenderStartOfStream"), calls)
    }

    @Test fun schedulerSpeedAndReleaseDefaultsReachTheDelegate() {
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val wrapped = renderer { name, args ->
            calls += name to args
            if (name == "getDurationToProgressUs") 2345L else null
        }
        wrapped.setPlaybackSpeed(1.25f, 2f)
        assertEquals(2345L, wrapped.getDurationToProgressUs(123L, 456L))
        wrapped.release()
        assertEquals(listOf(
            "setPlaybackSpeed" to listOf(1.25f, 2f),
            "getDurationToProgressUs" to listOf(123L, 456L),
            "release" to emptyList<Any?>()
        ), calls)
    }

    private fun renderer(call: (String, List<Any?>) -> Any?): Renderer {
        val capabilities = Proxy.newProxyInstance(RendererCapabilities::class.java.classLoader,
            arrayOf(RendererCapabilities::class.java)) { _, method, _ ->
            error("Unexpected capability call: ${method.name}")
        } as RendererCapabilities
        val delegate = Proxy.newProxyInstance(Renderer::class.java.classLoader,
            arrayOf(Renderer::class.java)) { _, method, args ->
            if (method.name == "getCapabilities") capabilities
            else call(method.name, args?.toList().orEmpty())
        } as Renderer
        return SdrOnlyFfmpegRenderer(delegate, FfmpegFrameEvidence(FrameEvidenceBridge()))
    }
}
