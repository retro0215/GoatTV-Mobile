package tv.own.owntv.mobile.sports.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.sports.live.SportsFixtures.event

/**
 * Schedule days and league weeks: the store's slate first, then bounded requests only for what it
 * does not cover, kept in memory so switching sports, days and tabs does not ask again.
 */
class SportsScheduleRepositoryTest {

    private var clock = SportsApiParser.parseIsoUtcMs("2026-11-13T18:00:00Z")!!
    private val day = SportsSlateLogic.DAY_MS
    private fun at(iso: String) = SportsApiParser.parseIsoUtcMs(iso)!!

    private val leagues = listOf(
        SportsLeague("nfl", "NFL", "NFL", 1, "football", "team"),
        SportsLeague("nba", "NBA", "NBA", 3, "basketball", "team"),
        SportsLeague("nhl", "NHL", "NHL", 7, "hockey", "team"),
    )

    private class Api : SportsApi {
        val sportCalls = mutableListOf<Triple<String, Long, Long>>()
        val leagueCalls = mutableListOf<Triple<String, Long, Long>>()
        val bySport = HashMap<String, SportsApiResult<SportsEventsPayload>>()
        var league: SportsApiResult<SportsEventsPayload> = SportsApiResult.Success(SportsEventsPayload("c", emptyList()))
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun home(): SportsApiResult<SportsHomePayload> = error("not used")
        override suspend fun eventsSince(cursor: String): SportsApiResult<SportsEventsPayload> = error("not used")
        override suspend fun eventsForSport(sport: String, fromMs: Long, toMs: Long): SportsApiResult<SportsEventsPayload> {
            sportCalls += Triple(sport, fromMs, toMs)
            gate?.await()
            return bySport[sport] ?: SportsApiResult.Success(SportsEventsPayload("c", emptyList()))
        }
        override suspend fun eventsForLeague(leagueId: String, fromMs: Long, toMs: Long): SportsApiResult<SportsEventsPayload> {
            leagueCalls += Triple(leagueId, fromMs, toMs)
            return league
        }
    }

    private val api = Api()
    private val repo = SportsScheduleRepository(api) { clock }

    private fun game(id: String, league: String, start: String, updated: String = "2026-11-13T12:00:00.000Z") =
        SportsFixtures.parsed(event(id, league = league, start = start, updated = updated))

    private fun slate(vararg games: SportsEvent) = SportsSlate(
        leagues = leagues, popularIds = emptyList(), eventsById = games.associateBy { it.id },
        cursor = "c", homeLoadedAtMs = clock, lastSuccessMs = clock,
    )

    private fun page(vararg games: SportsEvent) = SportsApiResult.Success(SportsEventsPayload("c", games.toList()))

    @Test
    fun aDayInsideTheHomeWindow_needsNoRequest() = runBlocking {
        val s = slate(game("evt_nba", "nba", "2026-11-14T00:30:00Z"), game("evt_nhl", "nhl", "2026-11-14T01:00:00Z"))
        val r = repo.day(s, at("2026-11-14T00:00:00Z"), at("2026-11-15T00:00:00Z"), sports = null)
        assertEquals(listOf("evt_nba", "evt_nhl"), r.events.map { it.id })
        assertTrue("covered by /sports/home: no request", api.sportCalls.isEmpty())
    }

    @Test
    fun aDayBeyondTheWindow_asksOncePerSport_boundedToThatDay() = runBlocking {
        val from = at("2026-11-17T05:00:00Z")
        val to = from + day
        api.bySport["basketball"] = page(game("evt_far", "nba", "2026-11-17T23:00:00Z"))
        val r = repo.day(slate(), from, to, sports = setOf("basketball"))
        assertEquals(listOf("evt_far"), r.events.map { it.id })
        assertEquals(listOf(Triple("basketball", from, to)), api.sportCalls)
    }

    @Test
    fun footballDaysInsideItsWeek_areCoveredByTheStoresLongerWindow() = runBlocking {
        val from = at("2026-11-17T05:00:00Z")
        assertTrue(SportsScheduleCoverage.covers(slate(), "football", from, from + day))
        assertTrue("hockey has no longer window", !SportsScheduleCoverage.covers(slate(), "hockey", from, from + day))
        repo.day(slate(), from, from + day, sports = setOf("football"))
        assertTrue(api.sportCalls.isEmpty())
    }

    @Test
    fun switchingSportsAndComingBack_reusesWhatWasLoaded() = runBlocking {
        val from = at("2026-11-17T05:00:00Z")
        repo.day(slate(), from, from + day, sports = null)                  // All Sports: basketball + hockey
        assertEquals(setOf("basketball", "hockey"), api.sportCalls.map { it.first }.toSet())
        repo.day(slate(), from, from + day, sports = setOf("hockey"))       // filter switch
        repo.day(slate(), from, from + day, sports = null)                  // and back
        assertEquals("nothing asked twice", 2, api.sportCalls.size)
        clock += SportsScheduleRepository.TTL_MS
        repo.day(slate(), from, from + day, sports = setOf("hockey"))
        assertEquals("after the cache lifetime, asked again", 3, api.sportCalls.size)
    }

    @Test
    fun yesterdayBeforeTheHomeWindow_isFetched_andMergedWithTheStoresNewerCopies() = runBlocking {
        val from = at("2026-11-12T05:00:00Z") // the 12th in New York: partly older than now-18h
        val stored = game("evt_late", "nba", "2026-11-13T02:00:00Z", updated = "2026-11-13T05:00:00.000Z")
        api.bySport["basketball"] = page(
            game("evt_late", "nba", "2026-11-13T02:00:00Z", updated = "2026-11-13T01:00:00.000Z"),
            game("evt_early", "nba", "2026-11-12T17:00:00Z"),
        )
        val r = repo.day(slate(stored), from, from + day, sports = setOf("basketball"))
        assertEquals(listOf("evt_early", "evt_late"), r.events.map { it.id })
        assertEquals("the store's newer copy wins", stored.updatedAtMs, r.events.last().updatedAtMs)
    }

    @Test
    fun aFailedSport_isReported_theOthersStillShown_andNotCachedAsEmpty() = runBlocking {
        val from = at("2026-11-17T05:00:00Z")
        api.bySport["hockey"] = SportsApiResult.Unavailable(SportsApiResult.Reason.SERVER)
        api.bySport["basketball"] = page(game("evt_b", "nba", "2026-11-17T23:00:00Z"))
        val r = repo.day(slate(), from, from + day, sports = null)
        assertEquals(setOf("hockey"), r.failedSports)
        assertEquals(listOf("evt_b"), r.events.map { it.id })
        api.bySport["hockey"] = page()
        assertTrue("tried again next time", repo.day(slate(), from, from + day, sports = null).failedSports.isEmpty())
        assertEquals(3, api.sportCalls.size)
    }

    @Test
    fun aRateLimitedDay_isAFailureForThatSport_neverRetriedInALoop() = runBlocking {
        val from = at("2026-11-17T05:00:00Z")
        api.bySport["hockey"] = SportsApiResult.RateLimited(60)
        val r = repo.day(slate(), from, from + day, sports = setOf("hockey"))
        assertEquals(setOf("hockey"), r.failedSports)
        assertEquals(1, api.sportCalls.size)
    }

    @Test
    fun leavingWhileADayLoads_cancelsTheRequest() = runBlocking {
        val from = at("2026-11-17T05:00:00Z")
        api.gate = CompletableDeferred()
        val job = launch { repo.day(slate(), from, from + day, sports = setOf("hockey")) }
        repeat(5) { yield() }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        api.gate = null
        repo.day(slate(), from, from + day, sports = setOf("hockey"))
        assertEquals("nothing was cached from the cancelled load", 2, api.sportCalls.size)
    }

    @Test
    fun aLeague_isOneRequestFromYesterdayToAWeekAhead_mergedWithTheStore() = runBlocking {
        val stored = game("evt_tonight", "nhl", "2026-11-14T00:00:00Z")
        api.league = page(game("evt_next_week", "nhl", "2026-11-19T00:00:00Z"), game("evt_tonight", "nhl", "2026-11-14T00:00:00Z"))
        val games = repo.league(slate(stored, game("evt_other", "nba", "2026-11-14T00:00:00Z")), "nhl")!!
        assertEquals(listOf("evt_tonight", "evt_next_week"), games.map { it.id })
        val (id, from, to) = api.leagueCalls.single()
        assertEquals("nhl", id)
        assertEquals(clock - SportsSlateLogic.HOME_PAST_MS, from)
        assertEquals(clock + 7 * day, to)
        repo.league(slate(stored), "nhl")
        assertEquals("cached", 1, api.leagueCalls.size)
    }

    @Test
    fun aLeagueThatFails_showsWhatTheStoreHas_orNothingToShow() = runBlocking {
        api.league = SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK)
        assertEquals(listOf("evt_t"), repo.league(slate(game("evt_t", "nhl", "2026-11-14T00:00:00Z")), "nhl")!!.map { it.id })
        assertNull("unavailable, not 'no games'", repo.league(slate(), "nba"))
    }

    @Test
    fun leaguePathIsBranded_andBounded() {
        val path = SportsApiClient.eventsForLeaguePath("nhl", at("2026-11-13T00:00:00Z"), at("2026-11-20T00:00:00Z"), "goat")
        assertEquals("/sports/events?league=nhl&from=2026-11-13T00%3A00%3A00Z&to=2026-11-20T00%3A00%3A00Z&brand=goat", path)
    }
}
