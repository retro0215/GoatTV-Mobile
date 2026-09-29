package tv.own.owntv.mobile.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.ui.screens.multiview.channelRowsToPeek

/**
 * The crash on opening Multiview from Live TV: the picker opened before its channel pager had
 * loaded, its only row was the "no channels here" line at index 0, and that row was peeked as a
 * channel — `IndexOutOfBoundsException: index 0 in ItemSnapshotList of size 0`.
 */
class AddToMultiviewPeekTest {

    @Test
    fun theEmptyStateRowIsNeverPeekedAsAChannel() {
        assertTrue(channelRowsToPeek(visibleIndices = listOf(0), channelCount = 0).isEmpty())
    }

    @Test
    fun loadedChannelRowsAreAllPeeked() {
        assertEquals(listOf(0, 1, 2, 3), channelRowsToPeek(listOf(0, 1, 2, 3), channelCount = 40))
    }

    @Test
    fun rowsPastTheLoadedCountAreSkipped() {
        // The list shrank (a search narrowed it) before the visible rows were re-read.
        assertEquals(listOf(4, 5), channelRowsToPeek(listOf(4, 5, 6, 7), channelCount = 6))
    }

    @Test
    fun negativeIndicesAreSkipped() {
        assertTrue(channelRowsToPeek(listOf(-1), channelCount = 10).isEmpty())
    }
}
