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
        // Turning the phone upright mid-Preview: same channel, nothing moves.
        assertNull(SportsPreviewReveal.onChange(7, 7, landscape = false))
        // On its side the sheet is asked to stay all the way open, but the details are never scrolled.
        repeat(3) { assertEquals(false, SportsPreviewReveal.onChange(7, 7, landscape = true)?.scrollToTop) }
    }

    /** Seen on the Samsung with 1.0.11: a Preview started upright stayed in a half-open sheet after turning. */
    @Test
    fun turningOnItsSideMidPreview_opensTheSheet_withoutScrolling() {
        assertEquals(Reveal(scrollToTop = false, expandSheet = true), SportsPreviewReveal.onChange(7, 7, landscape = true))
    }

    @Test
    fun turningWithNoPreview_movesNothing() {
        assertNull(SportsPreviewReveal.onChange(null, null, landscape = true))
        assertNull(SportsPreviewReveal.onChange(7, null, landscape = true))
    }

    @Test
    fun aStoppedPreview_movesNothing() {
        assertNull(SportsPreviewReveal.onChange(7, null, landscape = false))
        assertNull(SportsPreviewReveal.onChange(null, null, landscape = true))
    }
}
