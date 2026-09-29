package tv.own.owntv.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.ui.screens.library.AUTOPLAY_CHECK_MS
import tv.own.owntv.mobile.ui.screens.library.extractYouTubeVideoId
import tv.own.owntv.mobile.ui.screens.library.shouldShowTrailer
import tv.own.owntv.mobile.ui.screens.library.trailerPlayerHtml

/**
 * The trailer player page and the rule for when it shows.
 *
 * On the phone the trailer was heard and never seen: YouTube's iframe was laid out 0 px tall, because
 * the WebView starts out 0 px tall inside the detail list and CSS heights resolved against that. The
 * page now sizes the player in pixels from JavaScript, and these tests hold it to that.
 */
class VodTrailerHeroTest {

    private val html = trailerPlayerHtml("KeEHsuZ4Ja4", startMuted = false)

    @Test
    fun playerPage_sizesThePlayerInPixelsNotByCss() {
        assertTrue(html.contains("player.setSize(w, h)"))
        assertTrue(html.contains("window.addEventListener('resize', fitPlayer)"))
        assertTrue("sized once it is ready", html.contains("fitPlayer();"))
        // Neither of the two CSS heights that left the iframe 0 px tall on the device.
        assertFalse(html.contains("height:100%"))
        assertFalse(html.contains("100vh"))
        assertFalse(html.contains("height: '100%'"))
    }

    @Test
    fun playerPage_startsWithSoundByDefault() {
        assertTrue(html.contains("var START_MUTED = false;"))
        assertTrue(html.contains("'autoplay': 1"))
        assertTrue(html.contains("'playsinline': 1"))
        assertTrue(html.contains("videoId: 'KeEHsuZ4Ja4'"))
    }

    @Test
    fun playerPage_startsMutedOnlyWhenAsked() {
        assertTrue(trailerPlayerHtml("KeEHsuZ4Ja4", startMuted = true).contains("var START_MUTED = true;"))
    }

    @Test
    fun playerPage_reportsRefusedAutoplayInsteadOfFallingBackToMuted() {
        assertTrue(html.contains("TrailerBridge.blocked()"))
        assertTrue(html.contains("}, $AUTOPLAY_CHECK_MS);"))
        // The Play Trailer button's call unmutes; nothing in the page mutes on its own.
        assertTrue(html.contains("function playWithSound() { if (player) { player.unMute(); player.playVideo(); } }"))
    }

    @Test
    fun playerPage_wiresEveryPlayerEvent() {
        assertTrue(html.contains("'onReady': onPlayerReady"))
        assertTrue(html.contains("'onStateChange': onPlayerStateChange"))
        assertTrue(html.contains("'onError': onPlayerError"))
        assertTrue(html.contains("TrailerBridge.error(event.data)"))
        assertTrue(html.contains("TrailerBridge.playing()"))
    }

    @Test
    fun trailerShows_onlyWithAKey_noError_andNothingPlaying() {
        assertTrue(shouldShowTrailer("KeEHsuZ4Ja4", hasError = false, isPlayingVod = false))
        assertFalse("no trailer: artwork", shouldShowTrailer(null, hasError = false, isPlayingVod = false))
        assertFalse("blank trailer: artwork", shouldShowTrailer(" ", hasError = false, isPlayingVod = false))
        assertFalse("embed refused: artwork", shouldShowTrailer("KeEHsuZ4Ja4", hasError = true, isPlayingVod = false))
    }

    @Test
    fun playingTheFilmOrAnEpisode_removesTheTrailer() {
        assertFalse(shouldShowTrailer("KeEHsuZ4Ja4", hasError = false, isPlayingVod = true))
    }

    @Test
    fun extractYouTubeVideoId_handlesRawKeysAndUrls() {
        assertEquals("YoHD9XE21Z4", extractYouTubeVideoId("YoHD9XE21Z4"))
        assertEquals("YoHD9XE21Z4", extractYouTubeVideoId(" YoHD9XE21Z4 "))
        assertEquals("YoHD9XE21Z4", extractYouTubeVideoId("https://www.youtube.com/watch?v=YoHD9XE21Z4"))
        assertEquals("YoHD9XE21Z4", extractYouTubeVideoId("https://youtu.be/YoHD9XE21Z4"))
        assertEquals("YoHD9XE21Z4", extractYouTubeVideoId("https://www.youtube.com/embed/YoHD9XE21Z4"))
        assertEquals("Ub7j_SMktmE", extractYouTubeVideoId("Ub7j_SMktmE"))
        assertNull(extractYouTubeVideoId(null))
        assertNull(extractYouTubeVideoId(""))
        assertNull(extractYouTubeVideoId("not a trailer"))
    }
}
