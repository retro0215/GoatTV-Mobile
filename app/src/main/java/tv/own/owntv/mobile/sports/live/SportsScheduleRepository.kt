package tv.own.owntv.mobile.sports.live

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Days and leagues beyond what [SportsLiveStore] already holds, for the Schedule and Leagues views.
 *
 * The store's slate is used first: /sports/home covers every sport from 18 h back to 48 h ahead, and
 * the store also loads the longer football / soccer / fight windows. Only what that does not cover is
 * fetched — one bounded request per sport and day (`/sports/events?sport=`), or per league over a week
 * (`/sports/events?league=`) — branded, so channels come along, and kept in memory for [TTL_MS] so
 * moving between sports, days and tabs does not ask again. Never persisted; never changes the store.
 */
class SportsScheduleRepository(
    private val api: SportsApi,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val events: List<SportsEvent>, val atMs: Long)

    private val mutex = Mutex()
    private val sportDays = HashMap<Pair<String, Long>, Entry>()
    private val leagueWeeks = HashMap<String, Entry>()

    /** One day's games: [dayStartMs, dayEndMs) in the device's zone, for [sports] (every one when null). */
    data class DayResult(
        val events: List<SportsEvent>,
        /** Sports whose games for the day could not be fetched (their cached events, if any, are kept). */
        val failedSports: Set<String>,
    )

    suspend fun day(slate: SportsSlate, dayStartMs: Long, dayEndMs: Long, sports: Set<String>?): DayResult {
        val wanted = sports ?: slate.leagues.mapNotNull { it.sport }.toSet()
        val leagueSport = slate.leagues.associate { it.id to it.sport }
        val out = LinkedHashMap<String, SportsEvent>()
        // What the store holds for the day, for the chosen sports.
        for (e in slate.eventsById.values) {
            if (e.startTimeMs in dayStartMs until dayEndMs && leagueSport[e.leagueId] in wanted) out[e.id] = e
        }
        val missing = wanted.sorted().filterNot { SportsScheduleCoverage.covers(slate, it, dayStartMs, dayEndMs) }
        // One bounded request per uncovered sport, side by side; cancelled with the caller.
        val fetched = coroutineScope { missing.map { sport -> async { sport to sportDay(sport, dayStartMs, dayEndMs) } }.awaitAll() }
        val failed = HashSet<String>()
        for ((sport, events) in fetched) {
            if (events == null) failed += sport
            events?.forEach { e -> if (e.startTimeMs in dayStartMs until dayEndMs) out.merge(e.id, e, ::newer) }
        }
        return DayResult(out.values.sortedWith(compareBy<SportsEvent> { it.startTimeMs }.thenBy { it.id }), failed)
    }

    /** A league's games from 18 h back to a week ahead: the store's, plus one bounded league request. */
    suspend fun league(slate: SportsSlate, leagueId: String): List<SportsEvent>? {
        val now = clock()
        val cached = slate.eventsById.values.filter { it.leagueId == leagueId }
        val fetched = leagueWeek(leagueId, now) ?: return cached.takeIf { it.isNotEmpty() }
        val out = LinkedHashMap<String, SportsEvent>()
        for (e in cached) out[e.id] = e
        for (e in fetched) out.merge(e.id, e, ::newer)
        return out.values.sortedWith(compareBy<SportsEvent> { it.startTimeMs }.thenBy { it.id })
    }

    private fun newer(a: SportsEvent, b: SportsEvent) = if (b.updatedAtMs >= a.updatedAtMs) b else a

    private suspend fun sportDay(sport: String, from: Long, to: Long): List<SportsEvent>? {
        val key = sport to from
        mutex.withLock { sportDays[key]?.takeIf { clock() - it.atMs < TTL_MS }?.let { return it.events } }
        val r = api.eventsForSport(sport, from, to)
        if (r !is SportsApiResult.Success) return null
        mutex.withLock { sportDays[key] = Entry(r.value.events, clock()) }
        return r.value.events
    }

    private suspend fun leagueWeek(leagueId: String, now: Long): List<SportsEvent>? {
        mutex.withLock { leagueWeeks[leagueId]?.takeIf { now - it.atMs < TTL_MS }?.let { return it.events } }
        val r = api.eventsForLeague(leagueId, now - SportsSlateLogic.HOME_PAST_MS, now + LEAGUE_AHEAD_MS)
        if (r !is SportsApiResult.Success) return null
        mutex.withLock { leagueWeeks[leagueId] = Entry(r.value.events, now) }
        return r.value.events
    }

    companion object {
        const val TTL_MS = 10 * 60_000L
        const val LEAGUE_AHEAD_MS = 7 * SportsSlateLogic.DAY_MS
    }
}

/**
 * Whether the store's slate already holds a sport's whole day (pure, unit-tested): inside the
 * /sports/home window (18 h back, 48 h ahead of its last load), or inside the sport's longer window when
 * it has one (football 7 days, soccer and fights 13 days) — so that day needs no request.
 */
object SportsScheduleCoverage {
    fun covers(slate: SportsSlate, sport: String, dayStartMs: Long, dayEndMs: Long): Boolean {
        val loaded = slate.homeLoadedAtMs
        val from = loaded - SportsSlateLogic.HOME_PAST_MS
        val homeTo = loaded + SportsSlateLogic.HOME_FUTURE_MS
        if (dayStartMs >= from && dayEndMs <= homeTo) return true
        val extended = SportsSlateLogic.EXTENDED_WINDOWS[sport] ?: return false
        return dayStartMs >= from && dayEndMs <= loaded + extended
    }
}
