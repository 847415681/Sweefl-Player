package com.sweefl.player.playback.engine

import com.sweefl.player.core.model.OnlineAudio
import com.sweefl.player.core.model.OnlineVideo
import com.sweefl.player.core.model.ResolvedOnlineMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedOnlineSourceSnapshotTest {
    @Test fun selectionSurvivesResolverListMutation() {
        val videos = mutableListOf(OnlineVideo("v", 80, "video", "https://cdn/video", "video/mp4", separateAudio = true))
        val audios = mutableListOf(OnlineAudio("a", "audio", "https://cdn/audio"))
        val media = ResolvedOnlineMedia("https://page", "title", emptyList(), videos, audios, "v", "a")
        val snapshot = SelectedOnlineSourceSnapshot(media)
        videos[0] = videos[0].copy(url = "https://cdn/new-video")
        audios.clear()
        assertEquals("https://cdn/video", snapshot.videoUrl)
        assertEquals(SelectedOnlineSourceSnapshot.Audio("https://cdn/audio", "audio/mp4"), snapshot.audio)
    }

    @Test fun muxedVideoAndUnselectedAudioDoNotMerge() {
        val video = OnlineVideo("v", 80, "video", "https://cdn/video", "video/mp4")
        val media = ResolvedOnlineMedia("https://page", "title", emptyList(), listOf(video),
            listOf(OnlineAudio("a", "audio", "https://cdn/audio")), "v", "a")
        assertNull(SelectedOnlineSourceSnapshot(media).audio)
        assertNull(SelectedOnlineSourceSnapshot(media.copy(
            videos = listOf(video.copy(separateAudio = true)), selectedAudioId = null)).audio)
    }
}
