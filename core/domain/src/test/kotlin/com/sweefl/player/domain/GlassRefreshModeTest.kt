package com.sweefl.player.domain

import org.junit.Assert.*
import org.junit.Test

class GlassRefreshModeTest {
    @Test fun requestedModesRespectTheDisplayLimit() {
        assertEquals(30f, GlassRefreshMode.FPS_30.targetHz(144f), 0f)
        assertEquals(60f, GlassRefreshMode.FPS_60.targetHz(120f), 0f)
        assertEquals(60f, GlassRefreshMode.FPS_90.targetHz(60f), 0f)
        assertEquals(90f, GlassRefreshMode.FPS_90.targetHz(144f), 0f)
    }
    @Test fun followingDisplayRetainsFractionalAndHighRefreshRates() {
        assertEquals(59.94f, GlassRefreshMode.DISPLAY.targetHz(59.94f), .001f)
        assertEquals(144f, GlassRefreshMode.DISPLAY.targetHz(144f), 0f)
        assertEquals(6_944_444L, GlassRefreshMode.DISPLAY.intervalNanos(144f))
    }
    @Test fun missingOrUnknownPreferenceUsesSixtyWithoutBreakingSavedModes() {
        assertEquals(GlassRefreshMode.FPS_60, GlassRefreshMode.decode(null))
        assertEquals(GlassRefreshMode.FPS_60, GlassRefreshMode.decode("future-value"))
        GlassRefreshMode.entries.forEach { assertEquals(it, GlassRefreshMode.decode(it.name)) }
    }
    @Test fun unavailableDisplayRateHasFiniteSafeTiming() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertEquals(60f, GlassRefreshMode.DISPLAY.targetHz(it), 0f)
            assertEquals(16_666_666L, GlassRefreshMode.FPS_90.intervalNanos(it))
        }
    }
}
