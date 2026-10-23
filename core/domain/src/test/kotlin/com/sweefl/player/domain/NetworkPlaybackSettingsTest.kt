package com.sweefl.player.domain

import org.junit.Assert.*
import org.junit.Test

class NetworkPlaybackSettingsTest {
    @Test fun defaultsAreConservativeAndBounded() {
        val settings = NetworkPlaybackSettings.fromStored(null, null)
        assertEquals(NetworkPlaybackSettings(4, 30), settings)
        assertEquals(30_000, settings.bufferDurationMs)
        assertEquals(67_108_864, NetworkPlaybackSettings.TARGET_BUFFER_BYTES)
    }
    @Test fun corruptPreferenceTypesFallBackWithoutNumericCoercion() {
        listOf("32", 32L, 32f, true).forEach {
            assertEquals(NetworkPlaybackSettings(), NetworkPlaybackSettings.fromStored(it, it))
        }
    }
    @Test fun storedValuesClampAtEveryBoundary() {
        assertEquals(NetworkPlaybackSettings(1, 5), NetworkPlaybackSettings.fromStored(Int.MIN_VALUE, -1))
        assertEquals(NetworkPlaybackSettings(32, 600), NetworkPlaybackSettings.fromStored(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(600_000, NetworkPlaybackSettings(Int.MAX_VALUE, Int.MAX_VALUE).bufferDurationMs)
    }
    @Test fun numericSaveAcceptsExactBounds() {
        assertEquals(NetworkPlaybackSettings(1, 5), NetworkPlaybackSettings.parse("1", "5"))
        assertEquals(NetworkPlaybackSettings(32, 600), NetworkPlaybackSettings.parse(" 32 ", "600"))
    }
    @Test fun invalidSaveDoesNotSilentlyClampOrOverflow() {
        listOf("", "0", "33", "1.5", "2147483648", "NaN").forEach {
            assertNull(NetworkPlaybackSettings.parse(it, "30"))
        }
        listOf("", "4", "601", "-5", "Infinity").forEach {
            assertNull(NetworkPlaybackSettings.parse("4", it))
        }
    }
}
