package tv.own.owntv.mobile.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.ui.screens.live.LivePlaybackState
import tv.own.owntv.mobile.ui.screens.live.LivePreviewLifecycle
import tv.own.owntv.mobile.ui.screens.live.LivePreviewLifecycle.PlayerBack

/**
 * The Live TV preview after navigation: Live → Home → Live and Live → Multiview → back both left a
 * channel selected over a stopped stream and a black preview; Back from full screen started the
 * category's first channel instead of the one being watched.
 */
class LivePreviewLifecycleTest {

    /**
     * The parts of LiveTuner this is about, driven through the same [LivePlaybackState] calls in the
     * same order: tune() asks shouldOpen and sets openedId, stopPlayback() calls stopped() and keeps or
     * clears the selection, resumeSelected() asks resumeTarget and tunes it with the zap list kept.
     */
    private class Tuner {
        val playback = LivePlaybackState()
        var selected: Long? = null
        var zapList: String? = null
        var engineHasStream = false
        val opened = mutableListOf<Long>()
        var engineStops = 0

        fun tune(id: Long, zap: String? = null) {
            zapList = zap
            if (!playback.shouldOpen(id, engineHasStream)) return
            playback.openedId = id
            selected = id
            opened += id
        }

        fun streamArrived() {
            engineHasStream = true
        }

        fun stopPlayback(clearChannel: Boolean) {
            playback.stopped()
            if (clearChannel) {
                selected = null
                zapList = null
            }
            engineHasStream = false
            engineStops++
        }

        fun resumeSelected(): Boolean {
            val target = playback.resumeTarget(selected) ?: return false
            tune(target, zapList)
            return true
        }

        fun shownAgain(started: Boolean = true, multiviewRunning: Boolean = false) {
            if (LivePreviewLifecycle.shouldResume(started, multiviewRunning, selected != null, playback.active.value)) {
                resumeSelected()
            }
        }
    }

    private val espn = 10L
    private val fs1 = 30L

    @Test
    fun tabSwitch_restartsTheSameChannel() {
        val t = Tuner()
        t.tune(fs1); t.streamArrived()
        // Live → Home: LiveScreen's dispose keeps the channel and stops the engines.
        t.stopPlayback(clearChannel = false)
        assertEquals(fs1, t.selected)
        assertFalse("stopped, not playing", t.playback.active.value)
        // → Live: the preview starts the channel that was on, not the category's first.
        t.shownAgain()
        assertEquals(listOf(fs1, fs1), t.opened)
        assertTrue(t.playback.active.value)
    }

    @Test
    fun multiviewEntryAndExit_waitsForTheGridThenResumes() {
        val t = Tuner()
        t.tune(espn); t.streamArrived()
        // Multiview's start() stops the single stream and keeps the channel.
        t.stopPlayback(clearChannel = false)
        // Back from the grid: the preview reaches STARTED before the grid's dispose suspends it.
        t.shownAgain(multiviewRunning = true)
        assertEquals("no fifth decoder while the tiles still hold theirs", listOf(espn), t.opened)
        t.shownAgain(multiviewRunning = false)
        assertEquals(listOf(espn, espn), t.opened)
    }

    @Test
    fun nothingStartsInTheBackground() {
        val t = Tuner()
        t.tune(espn); t.streamArrived()
        t.stopPlayback(clearChannel = false)
        t.shownAgain(started = false)
        assertEquals(listOf(espn), t.opened)
        assertFalse(LivePreviewLifecycle.shouldResume(false, false, selected = true, playbackActive = false))
    }

    @Test
    fun noDuplicateStreamStarts() {
        val t = Tuner()
        t.tune(espn)
        // Still connecting: a second STARTED, a recomposition, a tile tap — none of them open again.
        repeat(3) { t.shownAgain() }
        assertFalse(t.resumeSelected())
        t.streamArrived()
        t.tune(espn) // the highlighted tile asked again while playing
        repeat(2) { t.shownAgain() }
        assertEquals(listOf(espn), t.opened)

        // After a stop, many returns still mean one reconnection.
        t.stopPlayback(clearChannel = false)
        repeat(3) { t.shownAgain() }
        assertEquals(listOf(espn, espn), t.opened)
    }

    @Test
    fun playerStoppedVersusActive() {
        val state = LivePlaybackState()
        assertFalse(state.active.value)
        state.openedId = espn
        assertTrue("active from the moment a stream is asked for", state.active.value)
        assertNull("playing: nothing to resume", state.resumeTarget(espn))
        state.stopped()
        assertFalse(state.active.value)
        assertEquals("stopped with a selection: that selection", espn, state.resumeTarget(espn))
        assertNull("stopped with nothing selected: nothing", state.resumeTarget(null))
    }

    @Test
    fun selectedChannelAndItsListArePreserved() {
        val t = Tuner()
        t.tune(fs1, zap = "Sports: NFL"); t.streamArrived()
        t.stopPlayback(clearChannel = false)
        t.shownAgain()
        assertEquals(fs1, t.selected)
        assertEquals("the list it was opened from comes back with it", "Sports: NFL", t.zapList)
    }

    @Test
    fun userStoppedChannel_isNotRestarted() {
        val t = Tuner()
        t.tune(espn); t.streamArrived()
        // The mini player's swipe down, the notification's Stop: the selection goes too.
        t.stopPlayback(clearChannel = true)
        t.shownAgain()
        assertEquals(listOf(espn), t.opened)
        assertNull(t.selected)
    }

    @Test
    fun fullscreenBack_ontoThePreview_keepsTheStream() {
        assertEquals(
            PlayerBack.KEEP,
            LivePreviewLifecycle.playerBack(
                liveChannelSelected = true, filmPlaying = false, casting = false,
                backLandsOnLivePreview = true, offLiveEdge = false,
            ),
        )
        // Modelled: no stop, no tune — same channel, same connection.
        val t = Tuner()
        t.tune(fs1); t.streamArrived()
        t.shownAgain()
        assertEquals(listOf(fs1), t.opened)
        assertEquals(0, t.engineStops)
    }

    @Test
    fun fullscreenBack_fromAReplayOrRewind_returnsToLive() {
        assertEquals(
            PlayerBack.KEEP_AT_LIVE_EDGE,
            LivePreviewLifecycle.playerBack(
                liveChannelSelected = true, filmPlaying = false, casting = false,
                backLandsOnLivePreview = true, offLiveEdge = true,
            ),
        )
    }

    @Test
    fun fullscreenBack_elsewhere_stillStopsAndCleansUp() {
        fun back(selected: Boolean = true, film: Boolean = false, casting: Boolean = false, onLive: Boolean = true) =
            LivePreviewLifecycle.playerBack(selected, film, casting, onLive, offLiveEdge = false)

        assertEquals("Home, Sports, Search, a channel page: nothing would show it", PlayerBack.STOP, back(onLive = false))
        assertEquals("a film is never left running behind Live TV", PlayerBack.STOP, back(film = true))
        assertEquals("the picture is on the television", PlayerBack.STOP, back(casting = true))
        assertEquals(PlayerBack.STOP, back(selected = false))
    }

    @Test
    fun miniPlayer_neverShowsAStoppedSelection() {
        assertTrue(LivePreviewLifecycle.nothingPlaying(channelSelected = true, liveActive = false, filmPlaying = false))
        assertFalse(LivePreviewLifecycle.nothingPlaying(channelSelected = true, liveActive = true, filmPlaying = false))
        assertFalse(LivePreviewLifecycle.nothingPlaying(channelSelected = false, liveActive = false, filmPlaying = true))
        assertTrue(LivePreviewLifecycle.nothingPlaying(channelSelected = false, liveActive = false, filmPlaying = false))
    }

    @Test
    fun audioAndDecoderCleanup_stopReleasesEveryTimeAndOnlyOnce() {
        val t = Tuner()
        t.tune(espn); t.streamArrived()
        t.stopPlayback(clearChannel = false)
        assertEquals("the engines are stopped on the way out", 1, t.engineStops)
        assertFalse("and nothing counts as playing afterwards — no session, no mini player", t.playback.active.value)
        // Coming back opens exactly one stream again; going away stops it again.
        t.shownAgain(); t.streamArrived()
        t.stopPlayback(clearChannel = false)
        assertEquals(2, t.engineStops)
        assertEquals(listOf(espn, espn), t.opened)
    }
}
