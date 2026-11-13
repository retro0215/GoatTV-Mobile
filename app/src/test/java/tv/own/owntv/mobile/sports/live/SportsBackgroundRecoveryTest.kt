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
 * Coming back from the background: the first request can fail at the socket while Android restores the
 * app's network (seen on the Samsung S25 with 1.0.6). One quick retry, no backoff, nothing counted —
 * and only for that: 429, server errors and a second failure keep the normal rules.
 */
class SportsBackgroundRecoveryTest {

    private var clock = SportsApiParser.parseIsoUtcMs("2026-11-13T18:00:00Z")!!
    private val waits = mutableListOf<Long>()
    private var waitLimit = Int.MAX_VALUE
    private var holdOn: CompletableDeferred<Unit>? = null

    private val sleep: suspend (Long) -> Unit = { ms ->
        waits += ms
        if (waits.size >= waitLimit) awaitCancellation()
        holdOn?.await()
        clock += ms
        yield()
    }

    private class Api : SportsApi {
        val home = ArrayDeque<SportsApiResult<SportsHomePayload>>()
        val since = ArrayDeque<SportsApiResult<SportsEventsPayload>>()
        val calls = mutableListOf<String>()
        override suspend fun home() = home.removeFirst().also { calls += "home" }
        override suspend fun eventsSince(cursor: String) = since.removeFirst().also { calls += "since" }
        override suspend fun eventsForSport(sport: String, fromMs: Long, toMs: Long): SportsApiResult<SportsEventsPayload> =
            SportsApiResult.Success(SportsEventsPayload("c", emptyList()))
    }

    private val api = Api()
    private val store = SportsLiveStore(api, sleep = sleep, clock = { clock })
    private val socketFailure = SportsApiResult.Unavailable(SportsApiResult.Reason.NETWORK)
    private val live = event("evt_live", status = "LIVE", start = "2026-11-13T17:30:00Z")
    private fun homeOk() = SportsApiResult.Success(SportsApiParser.parseHome(SportsFixtures.home(listOf(live), emptyList()))!!)
    private fun sinceOk() = SportsApiResult.Success(SportsApiParser.parseEventsPage(SportsFixtures.page(listOf(live)))!!)

    /** Loads once while active, then goes "to the background" and lets the schedule fall due. */
    private suspend fun loadedThenBackgrounded(active: MutableStateFlow<Boolean>) {
        api.home += homeOk()
        store.refresh()
        active.value = false
        clock += 5 * 60_000 // overdue, within the 10-minute home reload
        waits.clear()
    }

    @Test
    fun firstConnectionFailureOnReturn_isRetriedOnceSoon_withoutBackoffOrStaleNotice() = runBlocking {
        val active = MutableStateFlow(false)
        loadedThenBackgrounded(active)
        api.since += socketFailure
        api.since += sinceOk()
        waitLimit = 2
        val job = launch { store.runWhileActive(active) }
        active.value = true
        repeat(20) { yield() }
        assertEquals("retried after 2 s, then the normal 30 s live cadence", listOf(SportsLiveStore.RECOVERY_RETRY_MS, SportsRefreshPolicy.LIVE_MS), waits)
        assertEquals(listOf("home", "since", "since"), api.calls)
        assertFalse("no 'couldn't refresh' notice for the forgiven failure", (store.state.value as SportsLiveState.Content).stale)
        job.cancel()
    }

    @Test
    fun aSecondFailure_followsTheNormalBackoff() = runBlocking {
        val active = MutableStateFlow(false)
        loadedThenBackgrounded(active)
        api.since += socketFailure
        api.since += socketFailure
        waitLimit = 2
        val job = launch { store.runWhileActive(active) }
        active.value = true
        repeat(20) { yield() }
        assertEquals("2 s, then live 30 s doubled for one counted failure", listOf(SportsLiveStore.RECOVERY_RETRY_MS, 2 * SportsRefreshPolicy.LIVE_MS), waits)
        assertTrue("the second failure is shown, last good data kept", (store.state.value as SportsLiveState.Content).stale)
        assertEquals(setOf("evt_live"), (store.state.value as SportsLiveState.Content).slate.eventsById.keys)
        job.cancel()
    }

    @Test
    fun rateLimitedOnReturn_isNeverRetriedEarly() = runBlocking {
        val active = MutableStateFlow(false)
        loadedThenBackgrounded(active)
        api.since += SportsApiResult.RateLimited(120)
        waitLimit = 1
        val job = launch { store.runWhileActive(active) }
        active.value = true
        repeat(20) { yield() }
        assertEquals("Retry-After rules, not the 2 s recovery", listOf(120_000L), waits)
        job.cancel()
    }

    @Test
    fun aServerErrorOnReturn_isNotTheTransientCase() = runBlocking {
        val active = MutableStateFlow(false)
        loadedThenBackgrounded(active)
        api.since += SportsApiResult.Unavailable(SportsApiResult.Reason.SERVER)
        waitLimit = 1
        val job = launch { store.runWhileActive(active) }
        active.value = true
        repeat(20) { yield() }
        assertEquals(listOf(2 * SportsRefreshPolicy.LIVE_MS), waits)
        job.cancel()
    }

    @Test
    fun onlyTheFirstFetchAfterEachReturnIsForgiven() = runBlocking {
        val active = MutableStateFlow(false)
        loadedThenBackgrounded(active)
        api.since += sinceOk()          // first fetch on return succeeds — forgiveness is used up
        api.since += socketFailure      // a later failure while staying on screen backs off as usual
        waitLimit = 2
        val job = launch { store.runWhileActive(active) }
        active.value = true
        repeat(20) { yield() }
        assertEquals(listOf(SportsRefreshPolicy.LIVE_MS, 2 * SportsRefreshPolicy.LIVE_MS), waits)
        // Background again and back: the next return gets its own one forgiveness.
        active.value = false
        repeat(5) { yield() }
        clock += 5 * 60_000 // now past the 10-minute home reload: the first fetch back is /home
        waits.clear(); waitLimit = 1
        api.home += socketFailure
        active.value = true
        repeat(20) { yield() }
        assertEquals(listOf(SportsLiveStore.RECOVERY_RETRY_MS), waits)
        job.cancel()
    }

    @Test
    fun aFirstLoadThatFailsAtTheSocket_staysLoading_andRetries() = runBlocking {
        api.home += socketFailure
        api.home += homeOk()
        waitLimit = 2
        val job = launch { store.runWhileActive(MutableStateFlow(true)) }
        repeat(20) { yield() }
        assertEquals(SportsLiveStore.RECOVERY_RETRY_MS, waits.first())
        assertTrue(store.state.value is SportsLiveState.Content)
        job.cancel()
    }

    @Test
    fun goingAwayDuringTheRetryWait_cancelsIt_withNoExtraRequest() = runBlocking {
        val active = MutableStateFlow(false)
        loadedThenBackgrounded(active)
        api.since += socketFailure
        holdOn = CompletableDeferred() // the 2 s wait never finishes on its own
        val job = launch { store.runWhileActive(active) }
        active.value = true
        repeat(20) { yield() }
        assertEquals(listOf(SportsLiveStore.RECOVERY_RETRY_MS), waits)
        active.value = false // back to the background before the retry
        repeat(10) { yield() }
        holdOn!!.complete(Unit)
        repeat(10) { yield() }
        assertEquals("the retry never ran", listOf("home", "since"), api.calls)
        job.cancel()
    }

    @Test
    fun aForcedRefresh_neverForgives() = runBlocking {
        api.home += homeOk()
        store.refresh()
        clock += 60_000
        api.since += socketFailure
        val delay = store.refresh()
        assertEquals(2 * SportsRefreshPolicy.LIVE_MS, delay)
        assertTrue((store.state.value as SportsLiveState.Content).stale)
    }

    @Test
    fun twoObserversReturning_stillMakeOneRequestAtATime() = runBlocking {
        // Both loops wait on a gate the test opens, so time only moves when the test moves it.
        val gate = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val gated = SportsLiveStore(api, sleep = { ms -> waits += ms; gate.receive() }, clock = { clock })
        api.home += homeOk()
        gated.refresh()
        clock += 5 * 60_000
        api.since += socketFailure
        api.since += sinceOk()
        val active = MutableStateFlow(true)
        val a = launch { gated.runWhileActive(active) }
        val b = launch { gated.runWhileActive(active) }
        repeat(20) { yield() }
        assertEquals("one failed try; the other observer just waits for the retry", listOf("home", "since"), api.calls)
        assertEquals(listOf(SportsLiveStore.RECOVERY_RETRY_MS, SportsLiveStore.RECOVERY_RETRY_MS), waits)
        clock += SportsLiveStore.RECOVERY_RETRY_MS
        gate.send(Unit); gate.send(Unit)
        repeat(20) { yield() }
        assertEquals("one retry between them, not two", listOf("home", "since", "since"), api.calls)
        a.cancel(); b.cancel()
    }
}
