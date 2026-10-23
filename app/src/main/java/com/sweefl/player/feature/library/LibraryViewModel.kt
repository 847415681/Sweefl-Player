package com.sweefl.player.feature.library

import com.sweefl.player.data.history.PlaybackHistoryRepository
import com.sweefl.player.data.media.VideoRepository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repository: VideoRepository,
    private val history: PlaybackHistoryRepository
) : ViewModel() {
    private val mutable = MutableStateFlow(LibraryState())
    val state = mutable.asStateFlow()
    private var scan: Job? = null

    private fun bookmark() = history.lastUri()?.let { PlaybackBookmark(it, history.lastTitle(), history.position(it)) }

    fun clearHistory() {
        history.clear()
        mutable.value = mutable.value.copy(bookmark = null)
    }

    fun refresh(hasPermission: Boolean) {
        scan?.cancel()
        val last = bookmark()
        if (!hasPermission) {
            mutable.value = LibraryState(bookmark = last)
            return
        }
        scan = viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null, bookmark = last)
            try {
                mutable.value = LibraryState(videos = repository.videos(), bookmark = bookmark())
            } catch (e: SecurityException) {
                mutable.value = LibraryState(error = "视频访问权限已变更，请重新授权或使用“打开文件”。", bookmark = bookmark())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(loading = false, error = "无法读取媒体库，请稍后刷新。")
            }
        }
    }
}
