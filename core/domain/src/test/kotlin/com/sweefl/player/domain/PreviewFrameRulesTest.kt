package com.sweefl.player.domain

import org.junit.Assert.*
import org.junit.Test

class PreviewFrameRulesTest {
    @Test fun joinsActualTextureAndConvertsPeriodTime() {
        assertEquals(10_040L, matchPreviewFrame(8_040_000, 2_000_000, 10_000, 123456789, 123456789))
    }
    @Test fun rejectsAnOlderQueuedTexture() {
        assertNull(matchPreviewFrame(10_000_000, 0, 10_000, 123456789, 123456788))
        assertNull(matchPreviewFrame(10_000_000, 0, 10_000, 0, 0))
    }
    @Test fun rejectsMatchingTextureFromWrongTargetTime() {
        assertNull(matchPreviewFrame(5_000_000, 0, 10_000, 100, 100))
        assertNull(matchPreviewFrame(11_000_000, 0, 10_000, 100, 100))
    }
    @Test fun supportsNegativePeriodOffsetsWithoutFabricatingTime() {
        assertEquals(4_020L, matchPreviewFrame(10_020_000, -6_000_000, 4_000, 100, 100))
    }
    @Test fun obsoleteTimeoutAdvancesLatestWithoutConsumingAnotherRecovery() {
        assertEquals(PreviewTimeoutAction.ADVANCE_LATEST, previewTimeoutAction(10_000, 20_000, 0, false))
        assertEquals(PreviewTimeoutAction.ADVANCE_LATEST, previewTimeoutAction(10_000, 20_000, PreviewRequestPolicy.MAX_RECOVERIES, false))
    }
    @Test fun currentTargetRecoveryIsBounded() {
        assertEquals(PreviewTimeoutAction.RETRY, previewTimeoutAction(10_000, 10_000, 0, false))
        assertEquals(PreviewTimeoutAction.UNAVAILABLE, previewTimeoutAction(10_000, 10_000, PreviewRequestPolicy.MAX_RECOVERIES, false))
    }
    @Test fun exhaustedBudgetAndHiddenTargetCannotRetry() {
        assertEquals(PreviewTimeoutAction.UNAVAILABLE, previewTimeoutAction(10_000, 20_000, 0, true))
        assertEquals(PreviewTimeoutAction.UNAVAILABLE, previewTimeoutAction(10_000, -1, 0, false))
    }
    @Test fun targetConflationStillRejectsOldPixelsAndReturnsActualTime() {
        assertNull(matchPreviewFrame(10_040_000, 0, 20_000, 500, 500))
        assertNull(matchPreviewFrame(20_040_000, 0, 20_000, 600, 500))
        assertEquals(20_040L, matchPreviewFrame(20_040_000, 0, 20_000, 600, 600))
    }
    @Test fun decoderWaitDoesNotWidenFrameTimeTolerance() {
        assertEquals(9_900L, matchPreviewFrame(9_900_000, 0, 10_000, 900_000_000, 900_000_000))
        assertEquals(10_500L, matchPreviewFrame(10_500_000, 0, 10_000, 900_000_000, 900_000_000))
        assertNull(matchPreviewFrame(9_899_999, 0, 10_000, 900_000_000, 900_000_000))
        assertNull(matchPreviewFrame(10_500_001, 0, 10_000, 900_000_000, 900_000_000))
    }
    @Test fun rejectsOverflowAndInvalidTargets() {
        assertNull(matchPreviewFrame(Long.MAX_VALUE, 10, 0, 100, 100))
        assertNull(matchPreviewFrame(0, 0, Long.MAX_VALUE, 100, 100))
        assertNull(matchPreviewFrame(0, 0, -1, 100, 100))
    }
    @Test fun endPreviewSeeksBeforeEofWithoutRelaxingTimestampChecks() {
        assertEquals(39_950L, previewDecodePosition(40_000, 40_000))
        assertEquals(10_000L, previewDecodePosition(10_000, 40_000))
        assertEquals(0L, previewDecodePosition(1, 1))
        assertEquals(10_000L, previewDecodePosition(10_000, -1))
        assertEquals(39_966L, matchPreviewFrame(39_966_000, 0, 40_000, 100, 100))
        assertNull(matchPreviewFrame(39_000_000, 0, 40_000, 100, 100))
    }
}
