package com.sweefl.player.domain

import org.junit.Assert.*
import org.junit.Test

class PlaybackRenderSettingsTest {
    @Test fun nativeIsAnOptOutEvenWithSavedColors() {
        val settings = PlaybackRenderSettings(brightness = .3f, saturation = 20f)
        assertFalse(settings.hasEffectiveColorAdjustments)
        assertTrue(settings.copy(mode = PlaybackRenderMode.GPU_SDR).hasEffectiveColorAdjustments)
    }
    @Test fun malformedStoredValuesAreSafe() {
        val settings = PlaybackRenderSettings.fromStored("future", Float.NaN, Float.POSITIVE_INFINITY, 200f)
        assertEquals(PlaybackRenderMode.NATIVE, settings.mode)
        assertEquals(0f, settings.brightness, 0f)
        assertEquals(0f, settings.contrast, 0f)
        assertEquals(100f, settings.saturation, 0f)
    }
    @Test(expected = IllegalArgumentException::class) fun nonFiniteParametersCannotReachShader() {
        PlaybackRenderSettings(brightness = Float.NaN)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidRangesAreRejected() {
        PlaybackRenderSettings(saturation = 101f)
    }
    @Test fun selectingGpuIsNotEvidenceOfOutput() {
        val state = PlaybackRenderStatus(PlaybackRenderMode.GPU_SDR).accept(PlaybackRenderEvent.GraphRequested)
        assertFalse(state.gpuSdrOperational)
        assertEquals(PlaybackRenderPhase.GRAPH_REQUESTED, state.phase)
    }
    @Test fun independentEvidenceCanArriveInDifferentOrder() {
        var state = PlaybackRenderStatus(PlaybackRenderMode.GPU_SDR)
            .accept(PlaybackRenderEvent.FirstFrameReported)
            .accept(PlaybackRenderEvent.SdrEffectConfigured(1920,1080))
            .accept(PlaybackRenderEvent.GraphRequested)
        assertFalse(state.gpuSdrOperational)
        state = state.accept(PlaybackRenderEvent.SdrShaderCreated)
        assertTrue(state.gpuSdrOperational)
    }
    @Test fun shaderCreationAndInvalidDimensionsAreNotConfiguration() {
        val state = PlaybackRenderStatus(PlaybackRenderMode.GPU_SDR)
            .accept(PlaybackRenderEvent.GraphRequested).accept(PlaybackRenderEvent.SdrShaderCreated)
            .accept(PlaybackRenderEvent.SdrEffectConfigured(0,1080))
        assertFalse(state.sdrEffectConfigured)
        assertEquals(PlaybackRenderPhase.SDR_SHADER_CREATED,state.phase)
    }
    @Test fun nativeFramesDoNotClaimSdrProcessing() {
        val state = PlaybackRenderStatus(PlaybackRenderMode.NATIVE)
            .accept(PlaybackRenderEvent.GraphRequested).accept(PlaybackRenderEvent.SdrShaderCreated)
            .accept(PlaybackRenderEvent.SdrEffectConfigured(1920,1080))
            .accept(PlaybackRenderEvent.FirstFrameReported)
        assertFalse(state.gpuSdrOperational)
        assertFalse(state.graphToneMappingRequested)
    }
    @Test fun lateCallbacksCannotEraseErrorsOrReviveReleasedPipeline() {
        val failure = PlaybackRenderFailure(PlaybackRenderFailureOrigin.GRAPH,"unsupported YUV sampling")
        val failed = PlaybackRenderStatus(PlaybackRenderMode.GPU_SDR).accept(PlaybackRenderEvent.Failed(failure))
        assertEquals(failed,failed.accept(PlaybackRenderEvent.FirstFrameReported))
        val released = failed.accept(PlaybackRenderEvent.Released)
        assertEquals(released,released.accept(PlaybackRenderEvent.SdrShaderCreated))
        assertEquals(failure,released.failure)
        assertEquals(PlaybackRenderPhase.RELEASED,released.phase)
    }
}
