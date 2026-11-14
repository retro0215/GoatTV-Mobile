package tv.own.owntv.mobile.sports.hub

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.sports.live.SportsAccessIssue
import tv.own.owntv.mobile.sports.live.SportsApi
import tv.own.owntv.mobile.sports.live.SportsApiParser
import tv.own.owntv.mobile.sports.live.SportsApiResult
import tv.own.owntv.mobile.sports.live.SportsChannelOffer
import tv.own.owntv.mobile.sports.live.SportsChannelOffers
import tv.own.owntv.mobile.sports.live.SportsChannelRef
import tv.own.owntv.mobile.sports.live.SportsEvent
import tv.own.owntv.mobile.sports.live.SportsEventsPayload
import tv.own.owntv.mobile.sports.live.SportsFixtures
import tv.own.owntv.mobile.sports.live.SportsFixtures.event
import tv.own.owntv.mobile.sports.live.SportsHomePayload
import tv.own.owntv.mobile.sports.live.SportsLeague
import tv.own.owntv.mobile.sports.live.SportsLiveState
import tv.own.owntv.mobile.sports.live.SportsLiveStore
import tv.own.owntv.mobile.sports.live.SportsSlate
import tv.own.owntv.mobile.sports.live.VisibleSportsChannel
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsChannelCounts
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsCountedGames
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsHubSelection
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsHubTab
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsLeagueGamesUiState
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsLeaguesModel
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsScheduleDays
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsScheduleModel
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsScheduleUiState
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsSportFilter
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsTodayGroup
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsTodayUiState
import tv.own.owntv.mobile.ui.screens.sports.hub.sportsPollingActive
import java.time.LocalDate
import java.time.ZoneId

/** The sport selector, Schedule, Leagues, the saved selection, counts across tabs, and polling per tab. */
class SportsScheduleAndLeaguesTest {

    private val now = SportsApiParser.parseIsoUtcMs("2026-11-13T18:00:00Z")!!
    private val ny = ZoneId.of("America/New_York")

    private val leagues = listOf(
        SportsLeague("nfl", "NFL", "NFL", 1, "football", "team"),
        SportsLeague("ncaaf", "College Football", "NCAAF", 2, "football", "team"),
        SportsLeague("nba", "NBA", "NBA", 3, "basketball", "team"),
        SportsLeague("nhl", "NHL", "NHL", 7, "hockey", "team"),
        SportsLeague("epl", "Premier League", "EPL", 9, "soccer", "team"),
        SportsLeague("pbc", "Boxing", "Boxing", 12, "boxing", "fighter"),
    )

    private fun game(id: String, league: String, start: String, status: String = "SCHEDULED") =
        SportsFixtures.parsed(event(id, league = league, start = start, status = status))

    private fun slate(vararg games: SportsEvent) = SportsSlate(leagues, emptyList(), games.associateBy { it.id }, "c", now, now)

    // ------------------------------------------------------------------ sport selector

    @Test
    fun selectorOffersOnlySportsTheBackendHas_inAFixedOrder() {
        assertEquals(listOf("football", "basketball", "hockey", "soccer"), SportsSportFilter.available(leagues))
        assertTrue("no leagues yet: All Sports only", SportsSportFilter.available(emptyList()).isEmpty())
        assertEquals("boxing is under All Sports, not a chip", false, "boxing" in SportsSportFilter.available(leagues))
    }

    @Test
    fun aSavedSportTheBackendDropped_readsAsAllSports() {
        val available = SportsSportFilter.available(leagues)
        assertEquals("hockey", SportsSportFilter.effective("hockey", available))
        assertNull("no baseball leagues enabled", SportsSportFilter.effective("baseball", available))
        assertNull(SportsSportFilter.effective(null, available))
    }

    @Test
    fun liveAndToday_filtersEveryGroup_byTheChosenSport_withItsOwnEmptyState() {
        val state = SportsLiveState.Content(
            slate(
                game("evt_nba_live", "nba", "2026-11-13T17:00:00Z", "LIVE"),
                game("evt_nhl_live", "nhl", "2026-11-13T17:30:00Z", "LIVE"),
                game("evt_nhl_soon", "nhl", "2026-11-13T20:00:00Z"),
                game("evt_nba_final", "nba", "2026-11-13T03:00:00Z", "FINAL"),
            ),
            stale = false,
        )
        val all = SportsTodayUiState.from(state, now) as SportsTodayUiState.Content
        assertEquals(4, all.sections.sumOf { it.games.size })
        val hockey = SportsTodayUiState.from(state, now, "hockey") as SportsTodayUiState.Content
        assertEquals(listOf(SportsTodayGroup.LIVE, SportsTodayGroup.UPCOMING), hockey.sections.map { it.group })
        assertEquals(listOf("evt_nhl_live", "evt_nhl_soon"), hockey.sections.flatMap { it.games }.map { it.id })
        assertEquals(SportsTodayUiState.Empty(stale = false, filtered = true), SportsTodayUiState.from(state, now, "soccer"))
    }

    @Test
    fun switchingSports_onLiveAndToday_makesNoRequest() = runBlocking {
        val api = CountingApi()
        val store = SportsLiveStore(api, sleep = { awaitCancellation() }) { now }
        store.refresh()
        val before = api.fetches
        for (sport in listOf("hockey", null, "basketball", "soccer", null)) SportsTodayUiState.from(store.state.value, now, sport)
        assertEquals("filtering is in memory", before, api.fetches)
    }

    // ------------------------------------------------------------------ polling per tab

    @Test
    fun theScheduleTabsPoll_channelsNever() {
        for (tab in listOf(SportsHubTab.LIVE_TODAY, SportsHubTab.SCHEDULE, SportsHubTab.LEAGUES)) {
            assertTrue(tab.name, sportsPollingActive(started = true, tab = tab))
            assertFalse(tab.name, sportsPollingActive(started = false, tab = tab))
        }
        assertFalse(sportsPollingActive(started = true, tab = SportsHubTab.CHANNELS))
    }

    @Test
    fun movingBetweenScheduleTabs_keepsOnePoller_withNoExtraFetch() = runBlocking {
        val api = CountingApi()
        val store = SportsLiveStore(api, sleep = { awaitCancellation() }) { now }
        val started = MutableStateFlow(true)
        val tab = MutableStateFlow(SportsHubTab.LIVE_TODAY)
        val job = launch { store.runWhileActive(combine(started, tab) { s, t -> sportsPollingActive(s, t) }) }
        repeat(20) { yield() }
        assertEquals(1, api.fetches)
        tab.value = SportsHubTab.SCHEDULE; repeat(10) { yield() }
        tab.value = SportsHubTab.LEAGUES; repeat(10) { yield() }
        tab.value = SportsHubTab.LIVE_TODAY; repeat(10) { yield() }
        assertEquals("still active throughout: nothing restarts, nothing due", 1, api.fetches)
        job.cancel()
    }

    private class CountingApi : SportsApi {
        var fetches = 0
        override suspend fun home(): SportsApiResult<SportsHomePayload> {
            fetches++
            return SportsApiResult.Success(SportsApiParser.parseHome(SportsFixtures.home(emptyList(), emptyList()))!!)
        }
        override suspend fun eventsSince(cursor: String): SportsApiResult<SportsEventsPayload> { fetches++; return SportsApiResult.Success(SportsEventsPayload(cursor, emptyList())) }
        override suspend fun eventsForSport(sport: String, fromMs: Long, toMs: Long): SportsApiResult<SportsEventsPayload> =
            SportsApiResult.Success(SportsEventsPayload("c", emptyList()))
    }

    // ------------------------------------------------------------------ schedule days

    @Test
    fun scheduleDays_areYesterdayToSixDaysAhead_inTheDevicesZone() {
        val days = SportsScheduleDays.days(now, ny) // 1 PM on the 13th in New York
        assertEquals(LocalDate.of(2026, 11, 12), days.first())
        assertEquals(LocalDate.of(2026, 11, 19), days.last())
        assertEquals(8, days.size)
        val tokyo = SportsScheduleDays.days(now, ZoneId.of("Asia/Tokyo")) // already the 14th there
        assertEquals(LocalDate.of(2026, 11, 13), tokyo.first())
    }

    @Test
    fun aSavedDayOutsideTheRange_fallsBackToToday() {
        val days = SportsScheduleDays.days(now, ny)
        assertEquals(LocalDate.of(2026, 11, 15), SportsScheduleDays.effective(LocalDate.of(2026, 11, 15).toEpochDay(), days))
        assertEquals("a day restored after midnight passed", LocalDate.of(2026, 11, 13), SportsScheduleDays.effective(LocalDate.of(2026, 11, 2).toEpochDay(), days))
        assertEquals(LocalDate.of(2026, 11, 13), SportsScheduleDays.effective(null, days))
    }

    @Test
    fun dayBounds_followTheLocalClock_includingAClockChange() {
        val (from, to) = SportsScheduleDays.bounds(LocalDate.of(2026, 11, 13), ny)
        assertEquals(SportsApiParser.parseIsoUtcMs("2026-11-13T05:00:00Z"), from)
        assertEquals(24 * 3_600_000L, to - from)
        val (dstFrom, dstTo) = SportsScheduleDays.bounds(LocalDate.of(2026, 11, 1), ny) // clocks go back
        assertEquals(25 * 3_600_000L, dstTo - dstFrom)
    }

    // ------------------------------------------------------------------ schedule grouping and states

    @Test
    fun aDaysGames_areGroupedByLeague_inLeagueOrder_thenByStart() {
        val sections = SportsScheduleModel.byLeague(
            listOf(
                game("evt_nhl", "nhl", "2026-11-14T00:00:00Z"),
                game("evt_nfl_late", "nfl", "2026-11-14T01:00:00Z"),
                game("evt_nfl_early", "nfl", "2026-11-13T18:00:00Z"),
                game("evt_new", "xfl", "2026-11-13T19:00:00Z"),
            ),
            leagues,
        )
        assertEquals(listOf("nfl", "nhl", "xfl"), sections.map { it.leagueId })
        assertEquals(listOf("evt_nfl_early", "evt_nfl_late"), sections.first().games.map { it.id })
        assertNull("an unknown league still shows, last", sections.last().league)
    }

    @Test
    fun scheduleStates_emptyFilteredPartialAndUnavailable() {
        val nba = game("evt_nba", "nba", "2026-11-14T00:00:00Z")
        assertEquals(SportsScheduleUiState.Empty(filtered = false), SportsScheduleUiState.from(emptyList(), emptySet(), leagues, null))
        assertEquals(SportsScheduleUiState.Empty(filtered = true), SportsScheduleUiState.from(listOf(nba), emptySet(), leagues, "hockey"))
        assertEquals(SportsScheduleUiState.Unavailable, SportsScheduleUiState.from(emptyList(), setOf("hockey"), leagues, "hockey"))
        val partial = SportsScheduleUiState.from(listOf(nba), setOf("hockey"), leagues, null) as SportsScheduleUiState.Content
        assertTrue("shown, with a notice", partial.partial)
        assertEquals(listOf("evt_nba"), partial.games.map { it.id })
    }

    // ------------------------------------------------------------------ leagues

    @Test
    fun leaguesAreGroupedBySport_inSelectorOrder_otherSportsAfter() {
        val groups = SportsLeaguesModel.groups(leagues, sport = null)
        assertEquals(listOf("football", "basketball", "hockey", "soccer", "boxing"), groups.map { it.sport })
        assertEquals(listOf("nfl", "ncaaf"), groups.first().leagues.map { it.id })
        assertEquals(listOf("hockey"), SportsLeaguesModel.groups(leagues, "hockey").map { it.sport })
        assertTrue(SportsLeaguesModel.groups(leagues, "baseball").isEmpty())
    }

    @Test
    fun theOpenLeague_respectsTheChosenSport() {
        assertEquals("nhl", SportsLeaguesModel.effective("nhl", leagues, null))
        assertEquals("nhl", SportsLeaguesModel.effective("nhl", leagues, "hockey"))
        assertNull("switched to football: back to the list", SportsLeaguesModel.effective("nhl", leagues, "football"))
        assertNull("league no longer offered", SportsLeaguesModel.effective("wnba", leagues, null))
    }

    @Test
    fun aLeaguesGames_areListedByLocalDay_withEmptyAndUnavailable() {
        val games = listOf(
            game("evt_b", "nhl", "2026-11-15T00:30:00Z"), // 7:30 PM on the 14th in New York
            game("evt_a", "nhl", "2026-11-14T17:00:00Z"),
            game("evt_c", "nhl", "2026-11-16T00:00:00Z"),
        )
        val content = SportsLeagueGamesUiState.from(games, ny) as SportsLeagueGamesUiState.Content
        assertEquals(listOf(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15)), content.days.map { it.day })
        assertEquals(listOf("evt_a", "evt_b"), content.days.first().games.map { it.id })
        assertEquals(SportsLeagueGamesUiState.Empty, SportsLeagueGamesUiState.from(emptyList(), ny))
        assertEquals(SportsLeagueGamesUiState.Unavailable, SportsLeagueGamesUiState.from(null, ny))
    }

    // ------------------------------------------------------------------ selection across tabs and rotation

    @Test
    fun theSelection_survivesSavingAndRestoring() {
        val chosen = SportsHubSelection(SportsHubTab.SCHEDULE, "hockey", LocalDate.of(2026, 11, 15).toEpochDay(), "nhl")
        assertEquals(chosen, SportsHubSelection.fromSaved(chosen.toSaved()))
        assertEquals(SportsHubSelection(), SportsHubSelection.fromSaved(SportsHubSelection().toSaved()))
    }

    @Test
    fun aDamagedOrOlderSave_restoresSafely() {
        assertEquals(SportsHubSelection(), SportsHubSelection.fromSaved(emptyList()))
        assertEquals(SportsHubTab.LIVE_TODAY, SportsHubSelection.fromSaved(listOf("RETIRED_TAB", 5, "x", null)).tab)
        assertNull(SportsHubSelection.fromSaved(listOf("LEAGUES", 5, "x", null)).sport)
    }

    @Test
    fun theSportIsSharedByEveryTab() {
        var selection = SportsHubSelection(sport = "hockey")
        for (tab in SportsHubTab.entries) {
            selection = selection.copy(tab = tab)
            assertEquals("hockey", selection.sport)
        }
    }

    // ------------------------------------------------------------------ counts across tabs

    private fun ref(id: String) = SportsChannelRef(id, id.removePrefix("sch_"), null, "Channel $id", "Sports", null, null, 90, "network")

    private fun withChannels(e: SportsEvent, vararg ids: String) = e.copy(channels = ids.map { ref(it) })

    @Test
    fun countedGames_coverEveryTab_oncePerGame_newestCopy_onlyThoseWithChannels() {
        val stored = withChannels(game("evt_a", "nba", "2026-11-14T00:00:00Z"), "sch_1")
        val newer = stored.copy(updatedAtMs = stored.updatedAtMs + 1, channels = listOf(ref("sch_1"), ref("sch_2")))
        val schedule = listOf(newer, game("evt_none", "nba", "2026-11-14T01:00:00Z"))
        val league = listOf(withChannels(game("evt_week", "nhl", "2026-11-18T00:00:00Z"), "sch_3"))
        val counted = SportsCountedGames.of(listOf(stored), schedule, league)
        assertEquals(listOf("evt_a", "evt_week"), counted.map { it.id })
        assertEquals(2, counted.first().channels.size)
        assertTrue(SportsCountedGames.same(counted, SportsCountedGames.of(listOf(stored), schedule, league)))
        assertFalse(SportsCountedGames.same(counted, SportsCountedGames.of(listOf(stored))))
    }

    @Test
    fun scheduleAndLeagueCards_showCountsOnlyForTheActiveProfile() {
        val offers = SportsChannelOffers(
            1L,
            mapOf("evt_week" to SportsChannelOffer(listOf(VisibleSportsChannel(1, "Channel", null, null, false, backendChannelId = "sch_3")), 1L)),
            null,
        )
        assertEquals(1, SportsChannelCounts.shown(SportsChannelCounts.Ready(offers), "evt_week", 1))
        assertNull(SportsChannelCounts.shown(SportsChannelCounts.Ready(offers), "evt_week", 2))
        assertNull(SportsChannelCounts.shown(SportsChannelCounts.Ready(offers.copy(issue = SportsAccessIssue.NO_PROFILE)), "evt_week", 1))
    }
}
