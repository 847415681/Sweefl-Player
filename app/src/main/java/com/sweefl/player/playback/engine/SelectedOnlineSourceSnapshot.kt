package com.sweefl.player.playback.engine

import com.sweefl.player.core.model.ResolvedOnlineMedia

/** Only selected scalar values survive; resolver-owned lists may later be replaced or mutated. */
internal class SelectedOnlineSourceSnapshot(online: ResolvedOnlineMedia) {
    data class Audio(val url: String, val mimeType: String)
    val videoUrl: String
    val audio: Audio?

    init {
        val video = online.video
        videoUrl = video.url
        audio = if (video.separateAudio) online.audio?.let { Audio(it.url, it.mimeType) } else null
    }
}
