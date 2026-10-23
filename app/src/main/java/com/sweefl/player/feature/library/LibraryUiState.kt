package com.sweefl.player.feature.library

import com.sweefl.player.core.model.Video

import android.net.Uri

data class PlaybackBookmark(val uri: Uri, val title: String, val positionMs: Long)

data class LibraryState(
    val videos: List<Video> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val bookmark: PlaybackBookmark? = null
)
