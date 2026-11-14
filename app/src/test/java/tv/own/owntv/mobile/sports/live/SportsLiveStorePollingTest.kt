package tv.own.owntv.mobile.sports.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.sports.live.SportsFixtures.event

/**
 * The phone's polling around the TV app's store: it runs only while Sports is on screen, never twice
 * at once, never ahead of schedule, and stops — taking its request with it — the moment it is told to.
 *
 * [sleep] advances a fake clock instead of waiting, and parks for good after [sleepLimit] waits so a
 * test can look at what happened; nothing here sleeps in real time.
 */
class SportsLiveStorePollingTest {

    private var clock = SportsApiParser.parseIsoUtcMs("2026-10-02T12:00:00Z")!!
    private val sleeps = mutableListOf<Long>()
    private var sleepLimit = Int.MAX_VALUE

    private val sleep: suspend (Long) -> Unit = { ms ->
        sleeps += ms
        if (sleeps.size >= sleepLimit) awaitCancellation()
        clock += ms
        yield()
    }

    private class Api : SportsApi {
        val calls = mutableListOf<String>()

        /** Home and since fetches only: a home reload also loads the football window (`sport:football`). */
        val fetches: List<String> get() = calls.filterNot { it.startsWith("sport:") }
        val home = ArrayDeque<SportsApiResult<SportsHomePayload>>()
        val delta = ArrayDeque<SportsApiResult<SportsEventsPayload>>()
        var homeGate: CompletableDeferred<Unit>? = null
        var inFlight = 0
        var maxInFlight = 0

        private suspend fun <T> track(name: String, block: suspend () -> T): T {
            calls += name
            inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
            try { return block() } finally { inFlight-- }
        }

        override suspend fun home() = track("home") {
            homeGate?.await()
            home.removeFirstOrNull() ?: SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK)
        }

        override suspend fun eventsSince(cursor: String) = track("since") {
            delta.removeFirstOrNull() ?: SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK)
        }

        override suspend fun eventsForSport(sport: String, fromMs: Long, toMs: Long): SportsApiResult<SportsEventsPayload> =
            track("sport:$sport") { SportsApiResult.Success(SportsEventsPayload("c", emptyList())) }
    }

    private val api = Api()
    private fun store() = SportsLiveStore(api, clock = { clock }, sleep = sleep)

    private fun homeOk(vararg events: org.json.JSONObject) =
        SportsApiResult.Success(SportsApiParser.parseHome(SportsFixtures.home(events.toList(), emptyList()))!!)

    private fun deltaOk(vararg events: org.json.JSONObject) =
        SportsApiResult.Success(SportsApiParser.parseEventsPage(SportsFixtures.page(events.toList()))!!)

    private val idleGame get() = event("evt_idle", start = "2026-10-04T17:00:00Z") // days away → 5-minute cadence
    private val liveGame get() = event("evt_live", status = "LIVE", start = "2026-10-02T11:30:00Z")

    // --------------------------------------------------------------------------- lifecycle

    @Test
    fun nothingIsFetched_untilAScreenIsActive() = runBlocking {
        val store = store()
        val active = MutableStateFlow(false)
        val job = launch { store.runWhileActive(active) }
        repeat(5) { yield() }
        assertTrue("created and observed while inactive: no request", api.fetches.isEmpty())
        assertEquals(SportsLiveState.Loading, store.state.value)
        job.cancel()
    }

    @Test
    fun pollsOnTheRefreshCadence_whileActive() = runBlocking {
        sleepLimit = 3
        api.home += homeOk(liveGame)
        api.delta += deltaOk(liveGame)
        api.delta += deltaOk(liveGame)
        val job = launch { store().runWhileActive(MutableStateFlow(true)) }
        repeat(20) { yield() }
        assertEquals(listOf("home", "since", "since"), api.fetches)
        assertEquals("30 s while a game is live", listOf(30_000L, 30_000L, 30_000L), sleeps)
        job.cancel()
    }

    @Test
    fun goingToTheBackground_stopsPolling_andCancelsTheRequestInFlight() = runBlocking {
        val store = store()
        val active = MutableStateFlow(true)
        api.homeGate = CompletableDeferred() // the first request never answers
        val job = launch { store.runWhileActive(active) }
        repeat(5) { yield() }
        assertEquals(1, api.inFlight)
        active.value = false // app stopped / Sports left
        repeat(5) { yield() }
        assertEquals("the hanging request was cancelled, not left running", 0, api.inFlight)
        assertEquals(listOf("home"), api.fetches)
        assertEquals(SportsLiveState.Loading, store.state.value)
        job.cancel()
    }

    @Test
    fun comingBackWithinTheInterval_makesNoRequest() = runBlocking {
        sleepLimit = 1
        val store = store()
        val active = MutableStateFlow(true)
        api.home += homeOk(idleGame)
        val job = launch { store.runWhileActive(active) }
        repeat(10) { yield() }
        assertEquals(listOf("home"), api.fetches)
        active.value = false
        repeat(5) { yield() }
        clock += 60_000 // one minute in the background; the 5-minute refresh is not due
        sleeps.clear()
        active.value = true
        repeat(10) { yield() }
        assertEquals("no burst on return", listOf("home"), api.fetches)
        assertEquals("waits out the rest of the schedule", listOf(4 * 60_000L), sleeps)
        job.cancel()
    }

    @Test
    fun comingBackAfterTheIntervalPassed_makesExactlyOneRequest() = runBlocking {
        sleepLimit = 1
        val store = store()
        val active = MutableStateFlow(true)
        api.home += homeOk(idleGame)
        api.delta += deltaOk()
        val job = launch { store.runWhileActive(active) }
        repeat(10) { yield() }
        active.value = false
        repeat(5) { yield() }
        clock += 7 * 60_000 // past the 5-minute schedule, inside the 10-minute home reload
        sleeps.clear()
        active.value = true
        repeat(10) { yield() }
        assertEquals(listOf("home", "since"), api.fetches)
        job.cancel()
    }

    @Test
    fun cancellingTheCaller_endsTheLoop() = runBlocking {
        api.home += homeOk(idleGame)
        val job = launch { store().runWhileActive(MutableStateFlow(true)) }
        repeat(10) { yield() }
        job.cancel()
        job.join()
        val before = api.fetches.size
        repeat(10) { yield() }
        assertTrue(job.isCancelled)
        assertEquals(before, api.fetches.size)
    }

    // --------------------------------------------------------------------------- no overlap

    @Test
    fun twoObservers_neverOverlap_andNeverDoubleFetch() = runBlocking {
        sleepLimit = 1 // both loops park on their first wait: the clock stays put
        val store = store()
        api.homeGate = CompletableDeferred()
        api.home += homeOk(idleGame)
        val a = launch { store.runWhileActive(MutableStateFlow(true)) }
        val b = launch { store.runWhileActive(MutableStateFlow(true)) }
        repeat(10) { yield() }
        assertEquals("the second observer waits for the first fetch", 1, api.fetches.size)
        api.homeGate!!.complete(Unit)
        repeat(20) { yield() }
        assertEquals(1, api.maxInFlight)
        assertEquals("one fetch for two observers", listOf("home"), api.fetches)
        assertEquals("both wait for the same next refresh", listOf(SportsRefreshPolicy.IDLE_MS, SportsRefreshPolicy.IDLE_MS), sleeps)
        a.cancel(); b.cancel()
    }

    @Test
    fun aForcedRefresh_andTheSchedule_shareOneLock() = runBlocking {
        val store = store()
        api.homeGate = CompletableDeferred()
        api.home += homeOk(idleGame)
        api.delta += deltaOk()
        val forced = launch { store.refresh() }
        val scheduled = launch { store.refreshIfDue() }
        repeat(10) { yield() }
        assertEquals(1, api.fetches.size)
        api.homeGate!!.complete(Unit)
        forced.join(); scheduled.join()
        assertEquals(1, api.maxInFlight)
        assertEquals("the scheduled one saw it was no longer due", listOf("home"), api.fetches)
    }

    // --------------------------------------------------------------------------- 429 and backoff

    @Test
    fun rateLimited_waitsAtLeastRetryAfter_andTheBackoffIsBounded() = runBlocking {
        val store = store()
        api.home += SportsApiResult.RateLimited(600)
        assertEquals("Retry-After wins over the 60 s first-load cadence", 600_000L, store.refresh())
        assertEquals(SportsLiveState.TemporarilyUnavailable, store.state.value)
        // Failure after failure: doubling, capped at 10 minutes, never under 30 s.
        val delays = (1..6).map { clock += 600_000; store.refresh() }
        assertTrue(delays.all { it in SportsRefreshPolicy.LIVE_MS..SportsRefreshPolicy.MAX_BACKOFF_MS })
        assertEquals(SportsRefreshPolicy.MAX_BACKOFF_MS, delays.last())
    }

    @Test
    fun rejectedCursor_whoseHomeRetryIsRateLimited_honoursRetryAfter() = runBlocking {
        val store = store()
        api.home += homeOk(liveGame) // live: a 30 s cadence, 60 s after one failure
        store.refresh()
        clock += 6 * 60_000
        api.delta += SportsApiResult.Rejected(400, "since is too old; reload /home.")
        api.home += SportsApiResult.RateLimited(240)
        assertEquals("the reload's Retry-After wins over the 60 s backoff", 240_000L, store.refresh())
        assertTrue("last-good kept", (store.state.value as SportsLiveState.Content).stale)
        assertEquals(listOf("home", "since", "home"), api.fetches)
    }

    // --------------------------------------------------------------------------- states

    @Test
    fun lastGoodData_survivesEveryKindOfFailure() = runBlocking {
        val store = store()
        api.home += homeOk(liveGame)
        store.refresh()
        for (failure in listOf(
            SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK),
            SportsApiResult.Unavailable(SportsApiResult.Reason.MALFORMED),
            SportsApiResult.RateLimited(30),
        )) {
            clock += 60_000
            api.delta += failure
            store.refresh()
            val s = store.state.value as SportsLiveState.Content
            assertTrue(s.stale)
            assertEquals(setOf("evt_live"), s.slate.eventsById.keys)
        }
        clock += 60_000
        api.delta += deltaOk(liveGame)
        store.refresh()
        assertFalse("a success clears the stale notice", (store.state.value as SportsLiveState.Content).stale)
    }

    @Test
    fun emptyWindow_isContent_notAnError() = runBlocking {
        val store = store()
        api.home += homeOk()
        val delay = store.refresh()
        val s = store.state.value as SportsLiveState.Content
        assertTrue(s.slate.eventsById.isEmpty())
        assertFalse(s.stale)
        assertEquals("nothing on: the idle cadence", SportsRefreshPolicy.IDLE_MS, delay)
    }

    @Test
    fun malformedFirstLoad_isTemporarilyUnavailable_thenRecovers() = runBlocking {
        val store = store()
        api.home += SportsApiResult.Unavailable(SportsApiResult.Reason.MALFORMED)
        store.refresh()
        assertEquals(SportsLiveState.TemporarilyUnavailable, store.state.value)
        clock += 120_000
        api.home += homeOk(idleGame)
        store.refresh()
        assertEquals(setOf("evt_idle"), (store.state.value as SportsLiveState.Content).slate.eventsById.keys)
    }

    @Test
    fun brandSpecificChannels_arriveWithTheEvents() = runBlocking {
        val store = store()
        val withChannels = event(
            "evt_ch",
            channels = org.json.JSONArray(
                listOf(org.json.JSONObject("""{"channelId":"sch_1","remoteId":"900001","epgChannelId":null,"name":"US: ESPN","category":null,"logoUrl":null,"networkKey":"espn","confidence":95,"reason":"network_exact"}""")),
            ),
        )
        api.home += homeOk(withChannels)
        store.refresh()
        val e = (store.state.value as SportsLiveState.Content).slate.eventsById.getValue("evt_ch")
        assertEquals(listOf("900001"), e.channels.map { it.remoteId })
    }
}
