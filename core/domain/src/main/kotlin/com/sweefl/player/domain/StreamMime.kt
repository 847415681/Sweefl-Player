package com.sweefl.player.domain

import java.util.Locale

/** Only manifest types override Media3 inference. Null/other types intentionally select Auto. */
fun normalizeStreamMime(value: String?): String? = when (value?.lowercase(Locale.ROOT)) {
    "application/x-mpegurl", "application/vnd.apple.mpegurl" -> "application/x-mpegURL"
    "application/dash+xml" -> "application/dash+xml"
    else -> null
}
