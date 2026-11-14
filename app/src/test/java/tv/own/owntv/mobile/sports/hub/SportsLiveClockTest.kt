package tv.own.owntv.mobile.sports.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tv.own.owntv.mobile.sports.live.SportsFixtures
import tv.own.owntv.mobile.sports.live.SportsFixtures.event
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsLiveClock

/** A live card's clock is the backend's own status detail, or nothing — never one the app makes up. */
class SportsLiveClockTest {

    private fun game(status: String, detail: String?, id: String = "evt_g") =
        SportsFixtures.parsed(event(id, status = status, start = "2026-11-13T17:30:00Z", statusDetail = detail))

    @Test
    fun aLiveGame_showsTheBackendsClockAsIs() {
        assertEquals("7:16 - 1st", SportsLiveClock.text(game("LIVE", "7:16 - 1st")))
        assertEquals("Halftime", SportsLiveClock.text(game("LIVE", "  Halftime ")))
    }

    @Test
    fun noDetail_meansNoClock() {
        assertNull(SportsLiveClock.text(game("LIVE", null)))
        assertNull(SportsLiveClock.text(game("LIVE", "   ")))
    }

    @Test
    fun onlyLiveGamesShowAClock() {
        assertNull("a final's 'Final/OT' is its status, not a clock", SportsLiveClock.text(game("FINAL", "Final/OT")))
        assertNull(SportsLiveClock.text(game("SCHEDULED", "Sat, November 14th at 7:00 PM EST")))
        assertNull("delayed shows its own status text", SportsLiveClock.text(game("DELAYED", "Rain delay")))
    }

    @Test
    fun anOpenDetailsPanel_followsTheGamesRefreshes() {
        val opened = game("LIVE", "7:16 - 1st")
        val refreshed = game("LIVE", "2:03 - 2nd")
        assertEquals("2:03 - 2nd", SportsLiveClock.text(SportsLiveClock.latest(opened, listOf(game("LIVE", "x", id = "evt_other"), refreshed))))
        assertSame("no longer listed (e.g. filtered out): keeps what it opened with", opened, SportsLiveClock.latest(opened, emptyList()))
    }
}
