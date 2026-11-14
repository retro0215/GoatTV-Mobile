package tv.own.owntv.mobile.sports.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsPreviewReveal
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsPreviewReveal.Reveal

/**
 * Seen on the Samsung with 1.0.10: a Preview started from a lower row played out of sight at the top of
 * the details. A Preview that starts is now brought into view; nothing else scrolls the panel.
 */
class SportsPreviewRevealTest {

    @Test
    fun aPreviewStarting_scrollsToItInPortrait() {
        assertEquals(Reveal(scrollToTop = true, expandSheet = false), SportsPreviewReveal.onChange(null, 7, landscape = false))
    }

    @Test
    fun aPreviewStarting_inLandscape_alsoOpensTheSheetAllTheWay() {
        assertEquals(Reveal(scrollToTop = true, expandSheet = true), SportsPreviewReveal.onChange(null, 7, landscape = true))
    }

    @Test
    fun previewingAnotherChannel_isANewPreview() {
        assertEquals(Reveal(scrollToTop = true, expandSheet = false), SportsPreviewReveal.onChange(7, 9, landscape = false))
    }

    @Test
    fun theSamePreviewCarryingOn_neverScrolls() {
        // Score, clock and playback updates recompose the panel with the same channel previewing.
        repeat(3) { assertNull(SportsPreviewReveal.onChange(7, 7, landscape = true)) }
    }

    @Test
    fun aStoppedPreview_movesNothing() {
        assertNull(SportsPreviewReveal.onChange(7, null, landscape = false))
        assertNull(SportsPreviewReveal.onChange(null, null, landscape = true))
    }
}
