package com.sweefl.player.core.model

import android.net.Uri

data class Video(
    val uri: Uri,
    val title: String,
    val folder: String,
    val durationMs: Long,
    val size: Long,
    val added: Long
)
