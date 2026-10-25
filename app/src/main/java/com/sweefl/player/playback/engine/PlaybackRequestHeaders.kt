package com.sweefl.player.playback.engine

/** Only these public CDN request headers may cross from online resolution into playback. */
internal fun playbackRequestHeaders(headers: Map<String, String>): Map<String, String> = buildMap {
    headers.forEach { (name, value) ->
        when {
            name.equals("User-Agent", true) -> put("User-Agent", value)
            name.equals("Referer", true) -> put("Referer", value)
        }
    }
}
