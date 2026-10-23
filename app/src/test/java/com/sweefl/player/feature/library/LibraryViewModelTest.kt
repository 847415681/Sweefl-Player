@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.sweefl.player.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.sweefl.player.core.model.Video
import com.sweefl.player.data.history.PlaybackHistoryRepository
import com.sweefl.player.data.media.VideoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LibraryViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()
    private val history = object : PlaybackHistoryRepository {
        override fun position(uri: Uri) = 0L
        override fun lastUri(): Uri? = null
        override fun lastTitle() = "继续播放"
        override fun save(uri: Uri, title: String, position: Long, duration: Long) = Unit
        override fun clear() = Unit
    }

    @Before fun configureMain() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanUp() { stores.forEach { it.clear() }; Dispatchers.resetMain() }

    private fun model(query: suspend () -> List<Video>): LibraryViewModel {
        val repository = object : VideoRepository { override suspend fun videos() = query() }
        return LibraryViewModel(repository, history).also { model ->
            stores += ViewModelStore().apply { put("library", model) }
        }
    }

    @Test fun deniedPermissionDoesNotReadMediaStore() = runTest {
        var calls = 0
        val model = model { calls++; emptyList() }
        model.refresh(false)
        runCurrent()
        assertEquals(0, calls)
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.videos.isEmpty())
    }

    @Test fun revokingPermissionCancelsAnInFlightScan() = runTest {
        var cancelled = false
        val model = model { try { awaitCancellation() } finally { cancelled = true } }
        model.refresh(true)
        runCurrent()
        assertTrue(model.state.value.loading)
        model.refresh(false)
        runCurrent()
        assertTrue(cancelled)
        assertFalse(model.state.value.loading)
        assertNull(model.state.value.error)
    }

    @Test fun revokedProviderAccessProducesRecoverableState() = runTest {
        val model = model { throw SecurityException("provider permission revoked") }
        model.refresh(true)
        runCurrent()
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.videos.isEmpty())
        assertTrue(model.state.value.error.orEmpty().contains("重新授权"))
    }

    @Test fun successfulRefreshFinishesLoading() = runTest {
        var calls = 0
        val model = model { calls++; emptyList() }
        model.refresh(true)
        runCurrent()
        assertEquals(1, calls)
        assertFalse(model.state.value.loading)
        assertNull(model.state.value.error)
    }
}
