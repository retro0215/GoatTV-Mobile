package tv.own.owntv.mobile.sports.hub

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsDetailsSurface

/**
 * Seen on the Samsung with 1.0.7: game cards read through the details sheet in portrait and landscape.
 * The details now stand on an opaque surface, capped in height so they scroll instead of falling off.
 */
class SportsDetailsSurfaceTest {

    @Test
    fun anOpaqueThemeColour_isUsedAsIs() {
        val surface = Color(0xFF2A2D31)
        assertEquals(surface, SportsDetailsSurface.fill(surface, Color(0xFF101214)))
    }

    @Test
    fun aTranslucentThemeColour_isMadeFullyOpaque() {
        val fill = SportsDetailsSurface.fill(Color(0x802A2D31), Color(0x00101214))
        assertEquals(1f, fill.alpha)
    }

    @Test
    fun theFillIsOpaqueInLightAndDarkThemes() {
        for ((surface, behind) in listOf(Color(0xFF2A2D31) to Color(0xFF0E1012), Color(0xFFE6E8EB) to Color(0xFFFFFFFF), Color(0x14FFFFFF) to Color(0xFF000000))) {
            assertEquals(1f, SportsDetailsSurface.fill(surface, behind).alpha)
        }
    }

    @Test
    fun inLandscape_theDetailsScrollWithinMostOfTheScreen() {
        // An S25 Ultra on its side: ~891 x 411 dp.
        val cap = SportsDetailsSurface.maxHeightDp(screenWidthDp = 891, screenHeightDp = 411)
        assertEquals(246, cap)
        assertTrue("leaves room for the handle and title", cap < 411 - 100)
    }

    @Test
    fun inPortrait_theDetailsMayTakeWellOverHalf() {
        assertEquals(552, SportsDetailsSurface.maxHeightDp(screenWidthDp = 411, screenHeightDp = 891))
    }
}
