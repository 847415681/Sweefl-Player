package com.sweefl.player.domain

import org.junit.Assert.*
import org.junit.Test

class SubtitleSyncRulesTest {
    private val abc = SubtitleTimeline.create(listOf(
        TimedSubtitleCues(1_000_000L,3_000_000L,listOf("A")),
        TimedSubtitleCues(5_000_000L,7_000_000L,listOf("B")),
        TimedSubtitleCues(9_000_000L,11_000_000L,listOf("C"))), false)
    private fun at(videoUs: Long, offsetMs: Long) = abc.cuesAt(SubtitleSyncRules.timelinePositionUs(videoUs,0,offsetMs))
    @Test fun pausedPositiveAndNegativeChangesCanLookBothDirections() {
        assertTrue(at(2_000_000L,2000).isEmpty())
        assertEquals(listOf("B"),at(2_000_000L,-4000))
        assertEquals(listOf("A"),at(6_000_000L,4000))
    }
    @Test fun firstSeekCanFindAnEarlierUnvisitedCue() {
        assertEquals(listOf("B"),at(8_000_000L,3000))
        assertTrue(at(0,1000).isEmpty())
        assertEquals(listOf("A"),at(2_000_000L,0))
    }
    @Test fun clockOffsetsAreBoundedAndCannotWrap() {
        assertEquals(60000L,SubtitleSyncRules.clampOffsetMs(Long.MAX_VALUE))
        assertEquals(-60000L,SubtitleSyncRules.clampOffsetMs(Long.MIN_VALUE))
        assertEquals(Long.MIN_VALUE,SubtitleSyncRules.timelinePositionUs(Long.MIN_VALUE,1,60000))
        assertEquals(Long.MAX_VALUE,SubtitleSyncRules.addSaturated(Long.MAX_VALUE,1))
        assertEquals(2_000_000L,SubtitleSyncRules.timelinePositionUs(17_000_000L,10_000_000L,5000))
    }
    @Test fun overlappingGroupsPreserveStyleObjectIdentityAndHalfOpenEnds() {
        val a=Any();val b=Any()
        val timeline=SubtitleTimeline.create(listOf(TimedSubtitleCues(0,10,listOf(a)),TimedSubtitleCues(5,15,listOf(b))),false)
        assertSame(a,timeline.cuesAt(5)[0]);assertSame(b,timeline.cuesAt(5)[1])
        assertEquals(listOf(b),timeline.cuesAt(10));assertTrue(timeline.cuesAt(15).isEmpty())
    }
    @Test fun replacementDoesNotResurrectOldCues() {
        val t=SubtitleTimeline.create(listOf(TimedSubtitleCues(0,100,listOf("old")),TimedSubtitleCues(10,20,listOf("new"))),true)
        assertEquals(listOf("new"),t.cuesAt(10));assertTrue(t.cuesAt(25).isEmpty())
        assertEquals(listOf("old"),t.cuesAt(5))
    }
    @Test fun equalStartAndExplicitEmptyReplacementsArePreserved() {
        val t=SubtitleTimeline.create(listOf(TimedSubtitleCues(0,100,listOf("one")),TimedSubtitleCues(0,50,listOf("two")),TimedSubtitleCues(20,20,emptyList())),true)
        assertEquals(listOf("two"),t.cuesAt(0));assertTrue(t.cuesAt(20).isEmpty())
    }
    @Test fun callerMutationCannotChangeTimeline() {
        val cues=mutableListOf("stable")
        val t=SubtitleTimeline.create(listOf(TimedSubtitleCues(0,10,cues)),false)
        cues.clear();assertEquals(listOf("stable"),t.cuesAt(5))
    }
    @Test(expected=UnsupportedOperationException::class) fun resultsAreImmutable() {
        (abc.cuesAt(2_000_000L) as MutableList<String>).add("bad")
    }
}
