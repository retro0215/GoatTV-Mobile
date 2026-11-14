package tv.own.owntv.mobile.multiview

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewGridMath
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewLayoutMode

/** A Multiview tile shows the video in its own shape, with black bars, never stretched to the tile. */
class MultiviewVideoFitTest {

    private val wide = 16f / 9f

    private fun assertSize(expected: Size, actual: Size) {
        assertEquals("width", expected.width, actual.width, 0.01f)
        assertEquals("height", expected.height, actual.height, 0.01f)
    }

    private fun assertKeepsAspect(aspect: Float, actual: Size) =
        assertEquals("aspect", aspect, actual.width / actual.height, 0.001f)

    @Test
    fun singleTileInPortrait_letterboxesWidescreen() {
        // The reported case: one 16:9 channel alone in the Samsung's portrait area.
        val tile = MultiviewGridMath.rects(MultiviewLayoutMode.Auto, 1, 384f, 832f).single()
        val picture = MultiviewGridMath.fitVideo(tile.width, tile.height, wide)
        assertSize(Size(384f, 216f), picture)
        assertKeepsAspect(wide, picture)
    }

    @Test
    fun singleTileInLandscape_fillsA169Screen() {
        assertSize(Size(1600f, 900f), MultiviewGridMath.fitVideo(1600f, 900f, wide))
    }

    @Test
    fun widerScreenThanVideo_pillarboxes() {
        // A 20:9 phone in landscape, and a 4:3 channel in a 16:9 tile.
        assertSize(Size(1600f, 900f), MultiviewGridMath.fitVideo(2000f, 900f, wide))
        val oldTv = MultiviewGridMath.fitVideo(1600f, 900f, 4f / 3f)
        assertSize(Size(1200f, 900f), oldTv)
        assertKeepsAspect(4f / 3f, oldTv)
    }

    @Test
    fun everyLayoutAndCount_keepsTheVideoShapeInsideItsTile() {
        for ((w, h) in listOf(1600f to 900f, 384f to 832f)) {
            for (mode in MultiviewLayoutMode.entries) {
                for (count in 1..5) {
                    for (tile in MultiviewGridMath.rects(mode, count, w, h)) {
                        for (aspect in listOf(wide, 4f / 3f, 21f / 9f, 9f / 16f)) {
                            val p = MultiviewGridMath.fitVideo(tile.width, tile.height, aspect)
                            val where = "$mode n=$count ${w}x$h aspect=$aspect"
                            assertKeepsAspect(aspect, p)
                            assertTrue(where, p.width <= tile.width + 0.01f && p.height <= tile.height + 0.01f)
                            // Touches at least two opposite edges: no smaller than it has to be.
                            assertTrue(
                                where,
                                kotlin.math.abs(p.width - tile.width) < 0.01f ||
                                    kotlin.math.abs(p.height - tile.height) < 0.01f,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun anamorphicPixelAspect_isHonoured() {
        // 1440x1080 with 4:3 pixels is a 16:9 picture; the engine reports the display aspect.
        val displayAspect = 1440f * (4f / 3f) / 1080f
        assertSize(Size(384f, 216f), MultiviewGridMath.fitVideo(384f, 832f, displayAspect))
    }

    @Test
    fun unknownAspectOrEmptyTile_fillsTheTileAsBefore() {
        for (aspect in listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertSize(Size(384f, 832f), MultiviewGridMath.fitVideo(384f, 832f, aspect))
        }
        assertSize(Size(0f, 0f), MultiviewGridMath.fitVideo(0f, 0f, wide))
    }
}
