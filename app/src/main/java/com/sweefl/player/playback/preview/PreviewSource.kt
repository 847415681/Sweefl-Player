package com.sweefl.player.playback.preview

/** Carries the exact request policy used for the primary video, including extensionless streams. */
data class PreviewSource(val uri: String, val mimeType: String?, val userAgent: String?)
