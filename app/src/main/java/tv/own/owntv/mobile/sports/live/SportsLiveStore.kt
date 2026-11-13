package tv.own.owntv.mobile.sports.live

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The read side of the Sports API the store needs (a seam for tests). */
interface SportsApi {
    suspend fun home(): SportsApiResult<SportsHomePayload>
    suspend fun eventsSince(cursor: String): SportsApiResult<SportsEventsPayload>
    suspend fun eventsForSport(sport: String, fromMs: Long, toMs: Long): SportsApiResult<SportsEventsPayload>
}

fun SportsApiClient.asSportsApi(): SportsApi = object : SportsApi {
    override suspend fun home() = this@asSportsApi.home()
    override suspend fun eventsSince(cursor: String) = this@asSportsApi.eventsSince(cursor)
    override suspend fun eventsForSport(sport: String, fromMs: Long, toMs: Long) =
        this@asSportsApi.eventsForSport(sport, fromMs, toMs)
}

sealed interface SportsLiveState {
    /** First load in progress, nothing cached yet. */
    data object Loading : SportsLiveState

    /** Nothing cached and the API is unreachable / not returning JSON. */
    data object TemporarilyUnavailable : SportsLiveState

    /**
     * Last-known-good data. [stale] = the most recent refresh failed (data kept, small notice shown).
     * An empty slate (no games in the window) is still Content — "nothing on", not an error.
     */
    data class Content(val slate: SportsSlate, val stale: Boolean) : SportsLiveState
}

/**
 * App-scoped (Koin single) in-memory Sports cache and its refresh schedule, ported from the GoatTV
 * Android TV app (tag v4.6.1). Survives navigation, so returning to Sports renders the last slate
 * immediately; never persisted (cheap to rebuild, and nothing of it belongs in Room).
 *
 *  - /sports/home on first load and every [HOME_RELOAD_MS] (popular[] and the window move with time);
 *  - /sports/events?since=<cursor> otherwise (overlapping cursor, merged by event id);
 *  - a 400 on since (cursor older than the backend allows) falls back to /sports/home;
 *  - a failure never replaces existing content.
 *
 * Polling is the phone's addition: nothing starts it but a screen that is showing Sports, through
 * [runWhileActive], and it stops when that screen stops — no polling at launch, in the background or
 * while Sports is not on screen. Every fetch, forced or scheduled, takes the same lock, and a scheduled
 * one re-checks under that lock that it is still due, so two observers, or a screen coming back from
 * the background, never fetch twice or ahead of the schedule.
 */
class SportsLiveStore(
    private val api: SportsApi,
    /** How [runWhileActive] waits between fetches; a seam for tests. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    // Last, as on the TV, so `SportsLiveStore(api) { now }` still means the clock.
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<SportsLiveState>(SportsLiveState.Loading)
    val state: StateFlow<SportsLiveState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var consecutiveFailures = 0
    private var nextDueAtMs = 0L

    /** Milliseconds until the next refresh is due (0 = now). Lets a returning screen skip a redundant fetch. */
    fun millisUntilDue(): Long = (nextDueAtMs - clock()).coerceAtLeast(0)

    /** Performs one refresh (home or delta) now and schedules the next one. Returns the delay until then. */
    suspend fun refresh(): Long = mutex.withLock { refreshLocked() }

    /**
     * Refreshes only if the schedule says so, deciding under the fetch lock: returns the wait until the
     * next refresh — the remaining time when it was not due yet, the new delay when it fetched.
     */
    suspend fun refreshIfDue(): Long = mutex.withLock {
        val wait = nextDueAtMs - clock()
        if (wait > 0) wait else refreshLocked()
    }

    /**
     * Keeps Sports fresh for as long as [active] says it is on screen, and does nothing otherwise.
     *
     * The screen passes its own lifecycle (STARTED → true), so the app going to the background or the
     * screen being left pauses polling; coming back resumes it on the existing schedule — a return
     * within the interval makes no request at all. Turning inactive, or cancelling the caller, cancels
     * the loop and any request in flight.
     */
    suspend fun runWhileActive(active: Flow<Boolean>) {
        active.distinctUntilChanged().collectLatest { on ->
            if (on) {
                while (true) sleep(refreshIfDue())
            }
        }
    }

    private suspend fun refreshLocked(): Long {
        val now = clock()
        val slate = (_state.value as? SportsLiveState.Content)?.slate
        val needsHome = slate == null || now - slate.homeLoadedAtMs >= HOME_RELOAD_MS
        var retryAfterMs = 0L

        val updated: SportsSlate? = if (needsHome) {
            when (val r = api.home()) {
                is SportsApiResult.Success -> withExtendedSports(SportsSlateLogic.fromHome(r.value, now), slate, now)
                is SportsApiResult.RateLimited -> { retryAfterMs = r.retryAfterSeconds * 1000; null }
                else -> null
            }
        } else {
            when (val r = api.eventsSince(slate.cursor)) {
                is SportsApiResult.Success -> SportsSlateLogic.mergeDelta(slate, r.value, now)
                // Cursor too old / rejected: start over from /home rather than guessing.
                is SportsApiResult.Rejected -> when (val h = api.home()) {
                    is SportsApiResult.Success -> withExtendedSports(SportsSlateLogic.fromHome(h.value, now), slate, now)
                    is SportsApiResult.RateLimited -> { retryAfterMs = h.retryAfterSeconds * 1000; null }
                    else -> null
                }
                is SportsApiResult.RateLimited -> { retryAfterMs = r.retryAfterSeconds * 1000; null }
                is SportsApiResult.Unavailable -> null
            }
        }

        if (updated != null) {
            consecutiveFailures = 0
            _state.value = SportsLiveState.Content(updated, stale = false)
        } else {
            consecutiveFailures++
            _state.value = if (slate != null) SportsLiveState.Content(slate, stale = true) else SportsLiveState.TemporarilyUnavailable
        }

        val delayMs = SportsRefreshPolicy.nextDelayMs(updated ?: slate, consecutiveFailures, retryAfterMs, now)
        nextDueAtMs = now + delayMs
        return delayMs
    }

    /**
     * Adds the longer fixture window of every extended-window sport that has an enabled league (one
     * request per sport, only alongside a /home reload — the since-feed keeps those events current in
     * between). A failed fetch keeps that sport's previously loaded events (last-known-good).
     */
    private suspend fun withExtendedSports(fresh: SportsSlate, previous: SportsSlate?, now: Long): SportsSlate {
        var result = fresh
        for (sport in SportsSlateLogic.extendedSports(fresh.leagues)) {
            val from = now - SportsSlateLogic.HOME_PAST_MS
            val to = now + (SportsSlateLogic.EXTENDED_WINDOWS[sport] ?: SportsSlateLogic.EXTENDED_FUTURE_MS)
            result = when (val r = api.eventsForSport(sport, from, to)) {
                is SportsApiResult.Success -> SportsSlateLogic.mergeExtended(result, r.value.events, now)
                else -> SportsSlateLogic.carryOverSport(result, previous, sport, now)
            }
        }
        return result
    }

    companion object {
        const val HOME_RELOAD_MS = 10 * 60_000L
    }
}

/**
 * Refresh cadence (pure, unit-tested), as on the TV: 30 s while anything is live or delayed, 60 s when
 * a game starts within 30 minutes, 5 minutes otherwise. Failures back off ×2 per failure in a row
 * (capped at ×16 and 10 minutes, never under 30 s), and never sooner than a 429's Retry-After.
 */
internal object SportsRefreshPolicy {
    const val LIVE_MS = 30_000L
    const val STARTING_SOON_MS = 60_000L
    const val IDLE_MS = 5 * 60_000L
    const val MAX_BACKOFF_MS = 10 * 60_000L
    private const val SOON_WINDOW_MS = 30 * 60_000L
    private const val LATE_START_GRACE_MS = 20 * 60_000L

    fun baseDelayMs(slate: SportsSlate?, nowMs: Long): Long {
        val events = slate?.eventsById?.values ?: return STARTING_SOON_MS
        if (events.any { it.status == SportsEventStatus.LIVE || it.status == SportsEventStatus.DELAYED }) return LIVE_MS
        val startingSoon = events.any {
            it.status == SportsEventStatus.SCHEDULED &&
                it.startTimeMs in (nowMs - LATE_START_GRACE_MS)..(nowMs + SOON_WINDOW_MS)
        }
        return if (startingSoon) STARTING_SOON_MS else IDLE_MS
    }

    fun nextDelayMs(slate: SportsSlate?, consecutiveFailures: Int, retryAfterMs: Long, nowMs: Long): Long {
        val base = baseDelayMs(slate, nowMs)
        val backoff = if (consecutiveFailures <= 0) base
        else (base * (1L shl consecutiveFailures.coerceAtMost(4))).coerceAtMost(MAX_BACKOFF_MS).coerceAtLeast(LIVE_MS)
        return maxOf(backoff, retryAfterMs)
    }
}
