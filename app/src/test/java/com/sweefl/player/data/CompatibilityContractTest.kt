package com.sweefl.player.data

import androidx.media3.common.MimeTypes
import com.sweefl.player.data.common.uriStorageKey
import com.sweefl.player.domain.normalizeStreamMime
import org.junit.Assert.*
import org.junit.Test

class CompatibilityContractTest {
    @Test fun historyKeysRemainCompatibleWithExistingInstallation() {
        assertEquals("4ed443c1619dc1dee4c4e721b7ce69d036a66506c508dd335b3ef9e25d8c2108",
            uriStorageKey("content://media/external/video/media/529"))
        assertNotEquals(uriStorageKey("https://example.org/v?a=1"), uriStorageKey("https://example.org/v?a=2"))
        assertNotEquals(uriStorageKey("https://example.org/V"), uriStorageKey("https://example.org/v"))
    }

    @Test fun domainMimeValuesMatchTheActualPlaybackLibrary() {
        assertEquals(MimeTypes.APPLICATION_M3U8, normalizeStreamMime("application/vnd.apple.mpegurl"))
        assertEquals(MimeTypes.APPLICATION_MPD, normalizeStreamMime("application/dash+xml"))
    }
}
