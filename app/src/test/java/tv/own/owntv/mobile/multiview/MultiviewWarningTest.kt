package tv.own.owntv.mobile.multiview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.ui.screens.multiview.AudioFocusStyle
import tv.own.owntv.mobile.ui.screens.multiview.MultiviewLayoutMode
import tv.own.owntv.mobile.ui.screens.multiview.WARNING_THROTTLE_MS
import tv.own.owntv.mobile.ui.screens.multiview.needsWarning

/** The fifth-tile warning and AerioTV's defaults. */
class MultiviewWarningTest {

    private val now = 1_800_000_000_000L

    @Test
    fun theFifthTileAsksFirst() {
        assertTrue(needsWarning(currentSize = 4, suppressed = false, lastShownAt = null, now = now))
    }

    @Test
    fun tilesOneToFourDoNotAsk() {
        (0..3).forEach { assertFalse(needsWarning(it, suppressed = false, lastShownAt = null, now = now)) }
    }

    @Test
    fun thereIsNoSixthTileToWarnAbout() {
        assertFalse(needsWarning(currentSize = 5, suppressed = false, lastShownAt = null, now = now))
    }

    @Test
    fun dontShowAgainIsRemembered() {
        assertFalse(needsWarning(currentSize = 4, suppressed = true, lastShownAt = null, now = now))
    }

    @Test
    fun shownRecently_staysQuietForTwoHours() {
        assertFalse(needsWarning(4, suppressed = false, lastShownAt = now - WARNING_THROTTLE_MS + 1, now = now))
        assertTrue(needsWarning(4, suppressed = false, lastShownAt = now - WARNING_THROTTLE_MS, now = now))
    }

    @Test
    fun aeriosDefaults() {
        assertTrue(AudioFocusStyle.from(null) == AudioFocusStyle.CenterIcon)
        assertTrue(AudioFocusStyle.from("unknown") == AudioFocusStyle.CenterIcon)
        assertTrue(MultiviewLayoutMode.from(null) == MultiviewLayoutMode.Auto)
        assertTrue(MultiviewLayoutMode.from("heroCorner") == MultiviewLayoutMode.Auto)
    }
}
