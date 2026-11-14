package tv.own.owntv.mobile.sports.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.sports.live.SportsApiParser
import tv.own.owntv.mobile.sports.live.SportsFixtures
import tv.own.owntv.mobile.sports.live.SportsFixtures.event
import tv.own.owntv.mobile.sports.live.SportsLiveState
import tv.own.owntv.mobile.sports.live.SportsSlateLogic
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsLiveReveal
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsTodayGroup
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsTodayUiState

/**
 * Seen on the Samsung with 1.0.7: games went live while Live & Today was at its top, and "Live now"
 * appeared above what was showing. A list at its top is now held there when games go live; nobody
 * further down is moved, and ordinary score and clock updates move nothing.
 */
class SportsLiveRevealTest {

    private val now = SportsApiParser.parseIsoUtcMs("2026-11-13T22:05:00Z")!!

    private fun screen(vararg games: org.json.JSONObject): SportsTodayUiState.Content {
        val slate = SportsSlateLogic.fromHome(SportsApiParser.parseHome(SportsFixtures.home(games.toList(), emptyList()))!!, now)
        return SportsTodayUiState.from(SportsLiveState.Content(slate, stale = false), now) as SportsTodayUiState.Content
    }

    private fun g(id: String, status: String, detail: String? = null, start: String = "2026-11-13T22:00:00Z") =
        event(id, status = status, start = start, statusDetail = detail)

    private val before = screen(g("evt_a", "SCHEDULED"), g("evt_b", "SCHEDULED"), g("evt_c", "SCHEDULED", start = "2026-11-13T23:00:00Z"))
    private val wentLive = screen(g("evt_a", "LIVE", "19:58 - 1st"), g("evt_b", "LIVE", "19:40 - 1st"), g("evt_c", "SCHEDULED", start = "2026-11-13T23:00:00Z"))

    @Test
    fun gamesGoingLive_addALiveSectionAboveComingUp() {
        assertEquals(listOf(SportsTodayGroup.UPCOMING), before.sections.map { it.group })
        assertEquals(listOf(SportsTodayGroup.LIVE, SportsTodayGroup.UPCOMING), wentLive.sections.map { it.group })
        assertEquals(setOf("evt_a", "evt_b"), SportsLiveReveal.liveIds(wentLive.sections))
        assertTrue(SportsLiveReveal.liveIds(before.sections).isEmpty())
    }

    @Test
    fun atTheTop_aNewLiveSectionIsKeptInView() {
        val reveal = SportsLiveReveal()
        assertFalse("first content shown: nothing to do", reveal.shouldPinTop(SportsLiveReveal.liveIds(before.sections), wasAtTop = true))
        assertTrue(reveal.shouldPinTop(SportsLiveReveal.liveIds(wentLive.sections), wasAtTop = true))
    }

    @Test
    fun furtherDown_theListIsNeverMoved() {
        val reveal = SportsLiveReveal()
        reveal.shouldPinTop(SportsLiveReveal.liveIds(before.sections), wasAtTop = false)
        assertFalse(reveal.shouldPinTop(SportsLiveReveal.liveIds(wentLive.sections), wasAtTop = false))
    }

    @Test
    fun anotherGameGoingLive_whenSomeAlreadyAre_isAlsoKeptInView() {
        val reveal = SportsLiveReveal()
        reveal.shouldPinTop(setOf("evt_a"), wasAtTop = true)
        assertTrue(reveal.shouldPinTop(setOf("evt_a", "evt_b"), wasAtTop = true))
    }

    @Test
    fun scoreAndClockUpdates_neverMoveTheList() {
        val reveal = SportsLiveReveal()
        reveal.shouldPinTop(SportsLiveReveal.liveIds(wentLive.sections), wasAtTop = true)
        val clockMoved = screen(g("evt_a", "LIVE", "17:02 - 1st"), g("evt_b", "LIVE", "16:51 - 1st"), g("evt_c", "SCHEDULED", start = "2026-11-13T23:00:00Z"))
        repeat(3) { assertFalse(reveal.shouldPinTop(SportsLiveReveal.liveIds(clockMoved.sections), wasAtTop = true)) }
    }

    @Test
    fun gamesLeavingLive_neverMoveTheList() {
        val reveal = SportsLiveReveal()
        reveal.shouldPinTop(setOf("evt_a", "evt_b"), wasAtTop = true)
        assertFalse("one finished", reveal.shouldPinTop(setOf("evt_b"), wasAtTop = true))
        assertFalse("none live", reveal.shouldPinTop(emptySet(), wasAtTop = true))
        assertTrue("a later one going live counts again", reveal.shouldPinTop(setOf("evt_c"), wasAtTop = true))
    }

    @Test
    fun theLiveOrderIsUnchanged() {
        val ordered = screen(
            g("evt_low", "LIVE", priority = 10),
            g("evt_high", "LIVE", priority = 90),
        )
        assertEquals(listOf("evt_high", "evt_low"), ordered.sections.first().games.map { it.id })
        assertEquals(listOf("evt_high", "evt_low"), SportsLiveReveal.liveIds(ordered.sections).toList())
    }

    @Test
    fun atTop_meansTheFirstItemIsTheFirstShowing() {
        assertTrue(SportsLiveReveal.atTop(0))
        assertFalse(SportsLiveReveal.atTop(1))
        assertFalse(SportsLiveReveal.atTop(12))
    }

    private fun g(id: String, status: String, priority: Int) =
        event(id, status = status, start = "2026-11-13T22:00:00Z", priority = priority)
}
