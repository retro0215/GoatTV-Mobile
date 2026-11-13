package tv.own.owntv.mobile.sports.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import tv.own.owntv.mobile.sports.live.SportsLeague
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsHubSelection
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsHubTab
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsLeaguesModel

/**
 * Seen on the Samsung with 1.0.7: open NHL, pick Football, pick All Sports again — NHL reopened. A new
 * sport now closes the open league, and a remembered league that no longer fits is forgotten.
 */
class SportsLeagueSelectionTest {

    private val leagues = listOf(
        SportsLeague("nfl", "NFL", "NFL", 1, "football", "team"),
        SportsLeague("nba", "NBA", "NBA", 3, "basketball", "team"),
        SportsLeague("nhl", "NHL", "NHL", 7, "hockey", "team"),
    )

    private val nhlOpen = SportsHubSelection(SportsHubTab.LEAGUES, sport = null, leagueId = "nhl")

    @Test
    fun switchingSportAndBack_showsTheLeagueList_notTheOldLeague() {
        val football = nhlOpen.withSport("football")
        assertEquals("football", football.sport)
        assertNull("the open league is closed", football.leagueId)
        val backToAll = football.withSport(null)
        assertNull(backToAll.sport)
        assertNull("All Sports shows the league list", SportsLeaguesModel.effective(backToAll.leagueId, leagues, backToAll.sport))
        val backToHockey = SportsHubSelection(SportsHubTab.LEAGUES, "hockey", leagueId = "nhl").withSport("football").withSport("hockey")
        assertNull("the league's own sport again: still its list", backToHockey.leagueId)
    }

    @Test
    fun choosingTheSameSportAgain_keepsTheLeagueOpen() {
        val hockey = SportsHubSelection(SportsHubTab.LEAGUES, "hockey", leagueId = "nhl")
        assertSame(hockey, hockey.withSport("hockey"))
    }

    @Test
    fun aSportChange_keepsTheTabAndDay() {
        val s = SportsHubSelection(SportsHubTab.LEAGUES, "hockey", epochDay = 20_000, leagueId = "nhl").withSport("basketball")
        assertEquals(SportsHubTab.LEAGUES, s.tab)
        assertEquals(20_000L, s.epochDay)
        assertEquals("basketball", s.sport)
    }

    @Test
    fun allLeaguesAndBack_closeTheLeague_asBefore() {
        assertNull(nhlOpen.copy(leagueId = null).leagueId)
        assertEquals("nhl", SportsLeaguesModel.effective(nhlOpen.leagueId, leagues, null))
    }

    @Test
    fun aRestoredLeagueThatNoLongerFits_isForgotten() {
        assertNull("league of another sport", SportsHubSelection(SportsHubTab.LEAGUES, "football", leagueId = "nhl").normalized(leagues, "football").leagueId)
        assertNull("league gone from the backend", nhlOpen.copy(leagueId = "wnba").normalized(leagues, null).leagueId)
        assertEquals("a fitting league is kept", "nhl", nhlOpen.normalized(leagues, null).leagueId)
    }

    @Test
    fun whileLeaguesAreStillLoading_nothingIsJudged() {
        assertSame(nhlOpen, nhlOpen.normalized(emptyList(), null))
    }

    @Test
    fun aSavedAndRestoredSelection_normalizesTheSameWay() {
        val restored = SportsHubSelection.fromSaved(SportsHubSelection(SportsHubTab.LEAGUES, "football", leagueId = "nhl").toSaved())
        assertNull(restored.normalized(leagues, "football").leagueId)
    }
}
