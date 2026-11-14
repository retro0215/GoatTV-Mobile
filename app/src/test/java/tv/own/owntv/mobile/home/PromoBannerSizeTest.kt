package tv.own.owntv.mobile.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.ui.screens.home.PromoBannerAspect
import tv.own.owntv.mobile.ui.screens.home.PromoBannerMaxHeightFraction
import tv.own.owntv.mobile.ui.screens.home.promoBannerWidth

/** The Home promotion banner keeps its 16:9 shape and never fills a landscape screen. */
class PromoBannerSizeTest {

    @Test
    fun portraitPhone_keepsTheFullWidthBanner() {
        // Galaxy S25 Ultra upright: 384 dp wide window, 832 dp tall; ~336 dp left inside the card.
        assertEquals(336.dp, promoBannerWidth(availableWidth = 336.dp, windowHeight = 832.dp))
        // A small phone too.
        assertEquals(296.dp, promoBannerWidth(availableWidth = 296.dp, windowHeight = 640.dp))
    }

    @Test
    fun landscapePhone_capsTheBannerHeight() {
        // The same phone sideways: 832 dp of width beside the rail, only 384 dp of height.
        val windowHeight = 384.dp
        val width = promoBannerWidth(availableWidth = 600.dp, windowHeight = windowHeight)
        val height = width / PromoBannerAspect
        assertTrue("narrowed, not full width: $width", width < 600.dp)
        assertTrue("at most the cap: $height", height.value <= windowHeight.value * PromoBannerMaxHeightFraction + 0.01f)
        // Proportional: the cap is reached exactly, so the artwork is as large as allowed.
        assertEquals(windowHeight.value * PromoBannerMaxHeightFraction, height.value, 0.01f)
    }

    @Test
    fun tabletLandscape_staysProportionalAndBounded() {
        val windowHeight = 800.dp
        val width = promoBannerWidth(availableWidth = 1100.dp, windowHeight = windowHeight)
        assertEquals(windowHeight.value * PromoBannerMaxHeightFraction * PromoBannerAspect, width.value, 0.01f)
    }

    @Test
    fun narrowSpace_neverWidensPastWhatIsAvailable() {
        assertEquals(120.dp, promoBannerWidth(availableWidth = 120.dp, windowHeight = 2000.dp))
    }
}
