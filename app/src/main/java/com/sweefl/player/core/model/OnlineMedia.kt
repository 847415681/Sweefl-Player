package com.sweefl.player.core.model

/** Stable page identities are persisted; expiring CDN URLs and account cookies are not. */
data class OnlineEpisode(val id: String, val title: String, val pageUrl: String)
data class OnlineQuality(val id: Int, val label: String)
data class OnlineVideo(
    val id: String,
    val qualityId: Int,
    val label: String,
    val url: String,
    val mimeType: String,
    val separateAudio: Boolean = false,
    val codec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Int = 0,
    val backupUrls: List<String> = emptyList()
)
data class OnlineAudio(
    val id: String,
    val label: String,
    val url: String,
    val mimeType: String = "audio/mp4",
    val codec: String? = null,
    val language: String? = null,
    val backupUrls: List<String> = emptyList()
)

/** DVR is a server window, distinct from a short sliding HLS playlist. */
data class OnlineLiveInfo(
    val roomId: String,
    val startedAtMs: Long,
    val dvrWindowSeconds: Int = 0,
    val serverClockOffsetMs: Long = 0L
) {
    fun nowMs(): Long = System.currentTimeMillis() + serverClockOffsetMs
    fun earliestMs(nowMs: Long = nowMs()): Long = maxOf(startedAtMs, nowMs - dvrWindowSeconds * 1000L)
}

data class ResolvedOnlineMedia(
    val pageUrl: String,
    val title: String,
    val episodes: List<OnlineEpisode>,
    val videos: List<OnlineVideo>,
    val audios: List<OnlineAudio> = emptyList(),
    val selectedVideoId: String,
    val selectedAudioId: String? = null,
    val qualities: List<OnlineQuality> = videos.map { OnlineQuality(it.qualityId, it.label) }.distinctBy { it.id },
    val requestHeaders: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val live: OnlineLiveInfo? = null,
    val notice: String? = null
) {
    init {
        require(videos.isNotEmpty() && videos.any { it.id == selectedVideoId })
        require(selectedAudioId == null || audios.any { it.id == selectedAudioId })
        require(requestHeaders.keys.none { it.equals("Cookie", true) })
    }
    val video: OnlineVideo get() = videos.first { it.id == selectedVideoId }
    val audio: OnlineAudio? get() = audios.firstOrNull { it.id == selectedAudioId }
}

interface OnlineMediaResolver {
    fun supports(pageUrl: String): Boolean
    suspend fun resolve(pageUrl: String, qualityId: Int? = null): ResolvedOnlineMedia
}
