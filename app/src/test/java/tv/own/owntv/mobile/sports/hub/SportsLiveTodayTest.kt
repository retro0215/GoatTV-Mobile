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
import tv.own.owntv.mobile.sports.live.SportsEventsPayload
import tv.own.owntv.mobile.sports.live.SportsFixtures
import tv.own.owntv.mobile.sports.live.SportsFixtures.event
import tv.own.owntv.mobile.sports.live.SportsHomePayload
import tv.own.owntv.mobile.sports.live.SportsLiveState
import tv.own.owntv.mobile.sports.live.SportsLiveStore
import tv.own.owntv.mobile.sports.live.SportsSlateLogic
import tv.own.owntv.mobile.sports.live.VisibleSportsChannel
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsChannelCounts
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsDayLabel
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsHubTab
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsTimeLabels
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsTodayGroup
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsTodayModel
import tv.own.owntv.mobile.ui.screens.sports.hub.SportsTodayUiState
import tv.own.owntv.mobile.ui.screens.sports.hub.sportsPollingActive
import java.time.ZoneId

/** Live & Today: ordering, screen states, profile-safe counts, day labels and when it polls. */
class SportsLiveTodayTest {

    private val now = SportsApiParser.parseIsoUtcMs("2026-11-13T18:00:00Z")!!

    private fun game(id: String, status: String, start: String, priority: Int = 50) =
        SportsFixtures.parsed(event(id, status = status, start = start, priority = priority))

    // ------------------------------------------------------------------ ordering

    @Test
    fun liveFirst_thenUpcoming_thenRecent() {
        val sections = SportsTodayModel.sections(
            listOf(
                game("evt_final", "FINAL", "2026-11-13T15:00:00Z"),
                game("evt_soon", "SCHEDULED", "2026-11-13T19:00:00Z"),
                game("evt_live", "LIVE", "2026-11-13T17:30:00Z"),
            ),
            now,
        )
        assertEquals(listOf(SportsTodayGroup.LIVE, SportsTodayGroup.UPCOMING, SportsTodayGroup.RECENT), sections.map { it.group })
        assertEquals(listOf("evt_live", "evt_soon", "evt_final"), sections.flatMap { it.games }.map { it.id })
    }

    @Test
    fun liveGames_byImportanceThenStart_delayedCountsAsLive() {
        val live = SportsTodayModel.sections(
            listOf(
                game("evt_low", "LIVE", "2026-11-13T16:00:00Z", priority = 10),
                game("evt_high", "LIVE", "2026-11-13T17:00:00Z", priority = 90),
                game("evt_rain", "DELAYED", "2026-11-13T16:30:00Z", priority = 10),
            ),
            now,
        ).single()
        assertEquals(SportsTodayGroup.LIVE, live.group)
        assertEquals(listOf("evt_high", "evt_low", "evt_rain"), live.games.map { it.id })
    }

    @Test
    fun upcoming_soonestFirst_withinADay_includingALateStartAndPostponed() {
        val upcoming = SportsTodayModel.sections(
            listOf(
                game("evt_tomorrow_evening", "SCHEDULED", "2026-11-14T23:00:00Z"), // > 24 h: the schedule's
                game("evt_later", "SCHEDULED", "2026-11-14T01:00:00Z"),
                game("evt_late_start", "SCHEDULED", "2026-11-13T17:50:00Z"), // should have started, not live yet
                game("evt_pp", "POSTPONED", "2026-11-13T20:00:00Z"),
            ),
            now,
        ).single()
        assertEquals(SportsTodayGroup.UPCOMING, upcoming.group)
        assertEquals(listOf("evt_late_start", "evt_pp", "evt_later"), upcoming.games.map { it.id })
    }

    @Test
    fun recentResults_mostRecentFirst_includingCanceled() {
        val recent = SportsTodayModel.sections(
            listOf(
                game("evt_morning", "FINAL", "2026-11-13T12:00:00Z"),
                game("evt_called_off", "CANCELED", "2026-11-13T14:00:00Z"),
                game("evt_last_night", "FINAL", "2026-11-13T01:00:00Z"),
            ),
            now,
        ).single()
        assertEquals(listOf("evt_called_off", "evt_morning", "evt_last_night"), recent.games.map { it.id })
    }

    @Test
    fun emptyGroupsAreLeftOut() {
        assertTrue(SportsTodayModel.sections(emptyList(), now).isEmpty())
        assertEquals(
            listOf(SportsTodayGroup.UPCOMING),
            SportsTodayModel.sections(listOf(game("evt_a", "SCHEDULED", "2026-11-13T20:00:00Z")), now).map { it.group },
        )
    }

    // ------------------------------------------------------------------ screen states

    private fun content(stale: Boolean, vararg games: org.json.JSONObject) = SportsLiveState.Content(
        SportsSlateLogic.fromHome(SportsApiParser.parseHome(SportsFixtures.home(games.toList(), emptyList()))!!, now),
        stale,
    )

    @Test
    fun loadingUnavailableEmptyAndContent() {
        assertEquals(SportsTodayUiState.Loading, SportsTodayUiState.from(SportsLiveState.Loading, now))
        assertEquals(SportsTodayUiState.Unavailable, SportsTodayUiState.from(SportsLiveState.TemporarilyUnavailable, now))
        assertEquals(SportsTodayUiState.Empty(stale = false), SportsTodayUiState.from(content(false), now))
        assertEquals("nothing within a day is still empty", SportsTodayUiState.Empty(stale = true), SportsTodayUiState.from(content(true, event("evt_far", start = "2026-11-20T18:00:00Z")), now))
        val shown = SportsTodayUiState.from(content(true, event("evt_live", status = "LIVE", start = "2026-11-13T17:00:00Z")), now) as SportsTodayUiState.Content
        assertTrue("last good data, flagged", shown.stale)
        assertEquals("NFL", shown.leagues.getValue("nfl").shortName)
    }

    // ------------------------------------------------------------------ channel counts

    private fun offers(profileId: Long?, vararg counts: Pair<String, Int>, issue: SportsAccessIssue? = null) = SportsChannelOffers(
        profileId,
        counts.associate { (id, n) -> id to SportsChannelOffer(List(n) { fakeVisible(it) }, profileId) },
        issue,
    )

    private fun fakeVisible(i: Int) = VisibleSportsChannel(i.toLong(), "Channel $i", null, null, false, backendChannelId = "sch_$i")

    @Test
    fun countsAreShownOnlyForTheProfileTheyWereWorkedOutFor() {
        val ready = SportsChannelCounts.Ready(offers(1, "evt_a" to 2, "evt_b" to 0))
        assertEquals(2, SportsChannelCounts.shown(ready, "evt_a", activeProfileId = 1))
        assertEquals("every channel filtered: zero, not unknown", 0, SportsChannelCounts.shown(ready, "evt_b", activeProfileId = 1))
        assertNull("another profile is active now: no stale number", SportsChannelCounts.shown(ready, "evt_a", activeProfileId = 2))
        assertNull("still counting", SportsChannelCounts.shown(SportsChannelCounts.Pending, "evt_a", activeProfileId = 1))
        assertNull(
            "no usable playlist: no number at all",
            SportsChannelCounts.shown(SportsChannelCounts.Ready(offers(1, issue = SportsAccessIssue.SEVERAL_PLAYLISTS)), "evt_a", 1),
        )
    }

    // ------------------------------------------------------------------ day labels

    @Test
    fun startTimesAreLabelledByTheDevicesOwnDay() {
        val ny = ZoneId.of("America/New_York") // now = 1:00 PM on the 13th there
        assertEquals(SportsDayLabel.TODAY, SportsTimeLabels.day(SportsApiParser.parseIsoUtcMs("2026-11-14T03:00:00Z")!!, now, ny)) // 10 PM the 13th
        assertEquals(SportsDayLabel.TOMORROW, SportsTimeLabels.day(SportsApiParser.parseIsoUtcMs("2026-11-14T06:00:00Z")!!, now, ny))
        assertEquals(SportsDayLabel.YESTERDAY, SportsTimeLabels.day(SportsApiParser.parseIsoUtcMs("2026-11-12T20:00:00Z")!!, now, ny))
        assertEquals(SportsDayLabel.OTHER, SportsTimeLabels.day(SportsApiParser.parseIsoUtcMs("2026-11-16T20:00:00Z")!!, now, ny))
        val tokyo = ZoneId.of("Asia/Tokyo") // already the 14th there
        assertEquals(SportsDayLabel.TODAY, SportsTimeLabels.day(SportsApiParser.parseIsoUtcMs("2026-11-14T03:00:00Z")!!, now, tokyo))
    }

    // ------------------------------------------------------------------ tabs and lifecycle

    @Test
    fun pollsOnlyWhileStarted_andOnLiveAndToday() {
        assertTrue(sportsPollingActive(started = true, tab = SportsHubTab.LIVE_TODAY))
        assertFalse("app in the background / screen stopped", sportsPollingActive(started = false, tab = SportsHubTab.LIVE_TODAY))
        assertFalse("the Channels tab never polls the schedule", sportsPollingActive(started = true, tab = SportsHubTab.CHANNELS))
        assertFalse(sportsPollingActive(started = false, tab = SportsHubTab.CHANNELS))
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

    @Test
    fun lifecycleStartStop_andTabSwitching_driveOnePoller() = runBlocking {
        val api = CountingApi()
        var clock = now
        val waits = mutableListOf<Long>()
        val store = SportsLiveStore(api, sleep = { ms -> waits += ms; awaitCancellation() }, clock = { clock })
        val started = MutableStateFlow(false)
        val tab = MutableStateFlow(SportsHubTab.LIVE_TODAY)
        val active = combine(started, tab) { s, t -> sportsPollingActive(s, t) }
        // The pane and a second observer of the same screen (e.g. recomposition) — still one fetch.
        val a = launch { store.runWhileActive(active) }
        val b = launch { store.runWhileActive(active) }
        repeat(10) { yield() }
        assertEquals("created and stopped: no request", 0, api.fetches)
        started.value = true
        repeat(20) { yield() }
        assertEquals("STARTED on Live & Today: exactly one fetch for both", 1, api.fetches)
        tab.value = SportsHubTab.CHANNELS
        clock += 60 * 60_000 // an hour on the Channels tab
        repeat(10) { yield() }
        assertEquals("Channels never polls", 1, api.fetches)
        started.value = false
        tab.value = SportsHubTab.LIVE_TODAY
        repeat(10) { yield() }
        assertEquals("back on Live & Today but stopped (background): nothing", 1, api.fetches)
        started.value = true
        repeat(20) { yield() }
        assertEquals("started again, overdue: one fetch, not two", 2, api.fetches)
        assertTrue(store.state.value is SportsLiveState.Content)
        a.cancel(); b.cancel()
    }
}
