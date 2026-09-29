package tv.own.owntv.mobile.multiview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewGridMath
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewLayoutMode
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewModel

/**
 * AerioTV's grid geometry for one to five tiles, in both orientations: the exact rectangles, so the
 * phone draws the same shapes AerioTV does.
 */
class MultiviewGridMathTest {

    // A 16:9 landscape area and the Samsung's portrait content area, in dp.
    private val lw = 1600f
    private val lh = 900f
    private val pw = 384f
    private val ph = 832f

    private fun auto(n: Int, w: Float, h: Float) = MultiviewGridMath.rects(MultiviewLayoutMode.Auto, n, w, h)

    private fun assertRect(expected: Rect, actual: Rect) {
        assertEquals("left", expected.left, actual.left, 0.01f)
        assertEquals("top", expected.top, actual.top, 0.01f)
        assertEquals("right", expected.right, actual.right, 0.01f)
        assertEquals("bottom", expected.bottom, actual.bottom, 0.01f)
    }

    private fun r(x: Float, y: Float, w: Float, h: Float) = Rect(x, y, x + w, y + h)

    // --- Landscape ---------------------------------------------------------------------------------

    @Test
    fun landscape1_fillsTheArea() {
        val rects = auto(1, lw, lh)
        assertEquals(1, rects.size)
        assertRect(r(0f, 0f, lw, lh), rects[0])
    }

    @Test
    fun landscape2_sideBySide() {
        val rects = auto(2, lw, lh)
        assertRect(r(0f, 0f, 800f, 900f), rects[0])
        assertRect(r(800f, 0f, 800f, 900f), rects[1])
    }

    @Test
    fun landscape3_bigLeftTwoThirds_twoStackedRight() {
        val rects = auto(3, lw, lh)
        val bigW = lw * 2 / 3
        assertRect(r(0f, 0f, bigW, lh), rects[0])
        assertRect(r(bigW, 0f, lw - bigW, lh / 2), rects[1])
        assertRect(r(bigW, lh / 2, lw - bigW, lh / 2), rects[2])
    }

    @Test
    fun landscape4_twoByTwo() {
        val rects = auto(4, lw, lh)
        assertRect(r(0f, 0f, 800f, 450f), rects[0])
        assertRect(r(800f, 0f, 800f, 450f), rects[1])
        assertRect(r(0f, 450f, 800f, 450f), rects[2])
        assertRect(r(800f, 450f, 800f, 450f), rects[3])
    }

    @Test
    fun landscape5_twoByTwoHeroTopLeft_twoRight_twoBottom_cornerEmpty() {
        val rects = auto(5, lw, lh)
        val cw = lw / 3
        val ch = lh / 3
        assertRect(r(0f, 0f, 2 * cw, 2 * ch), rects[0])
        assertRect(r(2 * cw, 0f, cw, ch), rects[1])
        assertRect(r(2 * cw, ch, cw, ch), rects[2])
        assertRect(r(0f, 2 * ch, cw, ch), rects[3])
        assertRect(r(cw, 2 * ch, cw, ch), rects[4])
        // Bottom-right cell stays empty.
        val corner = Offset(2.5f * cw, 2.5f * ch)
        assertTrue(rects.none { it.contains(corner) })
    }

    // --- Portrait (the Samsung) -------------------------------------------------------------------

    @Test
    fun portrait1_fillsTheArea() {
        assertRect(r(0f, 0f, pw, ph), auto(1, pw, ph)[0])
    }

    @Test
    fun portrait2_splitsTopAndBottom() {
        val rects = auto(2, pw, ph)
        assertRect(r(0f, 0f, pw, ph / 2), rects[0])
        assertRect(r(0f, ph / 2, pw, ph / 2), rects[1])
    }

    @Test
    fun portrait3_bigOnTop_twoSideBySideBelow() {
        val rects = auto(3, pw, ph)
        val bigH = ph * 2 / 3
        assertRect(r(0f, 0f, pw, bigH), rects[0])
        assertRect(r(0f, bigH, pw / 2, ph - bigH), rects[1])
        assertRect(r(pw / 2, bigH, pw / 2, ph - bigH), rects[2])
    }

    @Test
    fun portrait4_oneColumnOfFour() {
        val rects = auto(4, pw, ph)
        rects.forEachIndexed { i, rect -> assertRect(r(0f, i * ph / 4, pw, ph / 4), rect) }
    }

    @Test
    fun portrait5_heroTransposed_readingOrder() {
        val rects = auto(5, pw, ph)
        val cw = pw / 3
        val ch = ph / 3
        // Hero: the top two-thirds' left two columns, transposed into the top-left 2x2.
        assertRect(r(0f, 0f, 2 * cw, 2 * ch), rects[0])
        // Reading order after the transpose: right of the hero, then down.
        assertTrue(rects[1].top <= rects[2].top)
        assertTrue(rects.drop(1).all { it.width <= cw + 0.01f && it.height <= ch + 0.01f })
        assertEquals(5, rects.size)
    }

    // --- Shared guarantees --------------------------------------------------------------------------

    @Test
    fun everyLayoutStaysInsideTheArea_withNoOverlap() {
        for ((w, h) in listOf(lw to lh, pw to ph)) {
            for (n in 1..MultiviewModel.MAX_TILES) {
                for (mode in MultiviewLayoutMode.entries) {
                    val rects = MultiviewGridMath.rects(mode, n, w, h)
                    assertEquals("$mode $n", n, rects.size)
                    rects.forEach { rect ->
                        assertTrue(rect.left >= -0.01f && rect.top >= -0.01f)
                        assertTrue(rect.right <= w + 0.01f && rect.bottom <= h + 0.01f)
                    }
                    for (i in rects.indices) for (j in i + 1 until rects.size) {
                        val overlap = rects[i].intersect(rects[j])
                        assertTrue("$mode $n $i/$j", overlap.width <= 0.01f || overlap.height <= 0.01f)
                    }
                }
            }
        }
    }

    @Test
    fun aSixthTileIsNeverLaidOut() {
        assertEquals(5, auto(6, lw, lh).size)
        assertEquals(5, MultiviewGridMath.evenGridRects(9, lw, lh, 0f).size)
    }

    // --- Spotlight ---------------------------------------------------------------------------------

    @Test
    fun spotlightLandscape_heroLeftTwoThirds_restStackedRight() {
        val rects = MultiviewGridMath.spotlightRects(4, lw, lh, 0f)
        val bigW = lw * 2 / 3
        assertRect(r(0f, 0f, bigW, lh), rects[0])
        rects.drop(1).forEachIndexed { i, rect -> assertRect(r(bigW, i * lh / 3, lw - bigW, lh / 3), rect) }
    }

    @Test
    fun spotlightPortrait_heroOnTop_restInARowBeneath() {
        val rects = MultiviewGridMath.spotlightRects(3, pw, ph, 0f)
        assertRect(r(0f, 0f, pw, ph * 2 / 3), rects[0])
        assertEquals(rects[1].top, rects[2].top, 0.01f)
        assertTrue(rects[1].left < rects[2].left)
    }

    @Test
    fun spotlightGivesTheChosenTileTheBigRect_othersKeepTheirOrder() {
        val spot = MultiviewGridMath.spotlightRects(5, lw, lh, 0f)
        val rects = MultiviewGridMath.resolvedRects(MultiviewLayoutMode.Auto, 5, spotlightIndex = 3, width = lw, height = lh)
        assertRect(spot[0], rects[3])
        assertRect(spot[1], rects[0])
        assertRect(spot[2], rects[1])
        assertRect(spot[3], rects[2])
        assertRect(spot[4], rects[4])
    }

    @Test
    fun exitingSpotlightReturnsToTheChosenLayout() {
        val rects = MultiviewGridMath.resolvedRects(MultiviewLayoutMode.Auto, 4, spotlightIndex = null, width = lw, height = lh)
        assertEquals(auto(4, lw, lh), rects)
    }

    @Test
    fun anOutOfRangeSpotlightIsIgnored() {
        val rects = MultiviewGridMath.resolvedRects(MultiviewLayoutMode.Auto, 3, spotlightIndex = 7, width = lw, height = lh)
        assertEquals(auto(3, lw, lh), rects)
    }

    // --- Other shapes -------------------------------------------------------------------------------

    @Test
    fun evenGridLandscape_rowsFromTheSquareRoot_lastRowCentred() {
        val rects = MultiviewGridMath.evenGridRects(5, lw, lh, 0f)
        // floor(sqrt 5) = 2 rows, 3 columns; the second row's two tiles are centred.
        val tw = lw / 3
        assertRect(r(0f, 0f, tw, lh / 2), rects[0])
        assertRect(r(tw / 2, lh / 2, tw, lh / 2), rects[3])
    }

    @Test
    fun stackedIsOneColumnInEitherOrientation() {
        val rects = MultiviewGridMath.stackedRects(3, lw, lh, 0f)
        rects.forEachIndexed { i, rect -> assertRect(r(0f, i * lh / 3, lw, lh / 3), rect) }
    }

    @Test
    fun layoutChoicesOnlyWhereTheyDiffer() {
        assertTrue(MultiviewLayoutMode.available(1).isEmpty())
        assertEquals(listOf(MultiviewLayoutMode.Auto, MultiviewLayoutMode.Stacked), MultiviewLayoutMode.available(2))
        assertEquals(3, MultiviewLayoutMode.available(3).size)
        assertEquals(listOf(MultiviewLayoutMode.Auto, MultiviewLayoutMode.EvenGrid), MultiviewLayoutMode.available(5))
    }

    @Test
    fun aDropInTheFiveTileHoleGoesToTheNearestTile() {
        val rects = auto(5, lw, lh)
        val hole = Offset(lw * 5 / 6, lh * 5 / 6)
        val index = MultiviewGridMath.indexAt(hole, rects)
        assertTrue(index == 2 || index == 4)
    }
}
