package tv.own.owntv.mobile.ui.screens.sports.hub

import tv.own.owntv.mobile.sports.live.SportsChannelOffers
import tv.own.owntv.mobile.sports.live.SportsEvent
import tv.own.owntv.mobile.sports.live.SportsEventStatus
import tv.own.owntv.mobile.sports.live.SportsLeague
import tv.own.owntv.mobile.sports.live.SportsLiveState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The Sports hub's tabs. Channels is the Sports screen the app already had; the rest are the schedule's. */
enum class SportsHubTab { LIVE_TODAY, SCHEDULE, LEAGUES, CHANNELS }

/**
 * Whether the schedule should be refreshing: only while the screen is at least STARTED (in front, app
 * not in the background) and one of the schedule's tabs is showing — they all show the store's live
 * games. The Channels tab never polls the schedule.
 */
fun sportsPollingActive(started: Boolean, tab: SportsHubTab): Boolean = started && tab != SportsHubTab.CHANNELS

/**
 * The sport selector (pure). Offers only sports the backend has enabled leagues for, in a fixed order;
 * null means All Sports. A saved choice the backend no longer offers reads as All Sports (it is kept,
 * not overwritten, so it comes back if the sport does).
 */
object SportsSportFilter {
    val ORDER = listOf("football", "basketball", "baseball", "hockey", "soccer", "mma")

    fun available(leagues: List<SportsLeague>): List<String> {
        val enabled = leagues.mapNotNull { it.sport }.toSet()
        return ORDER.filter { it in enabled }
    }

    fun effective(selected: String?, available: List<String>): String? = selected?.takeIf { it in available }

    fun filter(events: Collection<SportsEvent>, leagues: List<SportsLeague>, sport: String?): List<SportsEvent> {
        if (sport == null) return events.toList()
        val sportOf = leagues.associate { it.id to it.sport }
        return events.filter { sportOf[it.leagueId] == sport }
    }
}

/** Live & Today's three groups, always in this order. */
enum class SportsTodayGroup { LIVE, UPCOMING, RECENT }

data class SportsTodaySection(val group: SportsTodayGroup, val games: List<SportsEvent>)

/** One card's facts, resolved against the slate's leagues. */
data class SportsTodayGame(val event: SportsEvent, val league: SportsLeague?)

/**
 * What Live & Today shows from a slate (pure, unit-tested):
 *  - LIVE: live and delayed games, most important first (backend priority), then by start;
 *  - UPCOMING: scheduled (or postponed / not-yet-known) games starting within [UPCOMING_WINDOW_MS] —
 *    including one that should have started but hasn't flipped to live yet — soonest first;
 *  - RECENT: finished or canceled games the backend still returns, most recent first.
 * Empty groups are left out. Later fixtures belong to the schedule, not to "today".
 */
object SportsTodayModel {
    const val UPCOMING_WINDOW_MS = 24 * 3_600_000L

    fun sections(events: Collection<SportsEvent>, nowMs: Long): List<SportsTodaySection> {
        val live = events.filter { it.status == SportsEventStatus.LIVE || it.status == SportsEventStatus.DELAYED }
            .sortedWith(compareByDescending<SportsEvent> { it.priority }.thenBy { it.startTimeMs }.thenBy { it.id })
        val upcoming = events.filter {
            (it.status == SportsEventStatus.SCHEDULED || it.status == SportsEventStatus.POSTPONED || it.status == SportsEventStatus.UNKNOWN) &&
                it.startTimeMs <= nowMs + UPCOMING_WINDOW_MS
        }.sortedWith(compareBy<SportsEvent> { it.startTimeMs }.thenByDescending { it.priority }.thenBy { it.id })
        val recent = events.filter { it.status == SportsEventStatus.FINAL || it.status == SportsEventStatus.CANCELED }
            .sortedWith(compareByDescending<SportsEvent> { it.startTimeMs }.thenBy { it.id })
        return listOf(
            SportsTodaySection(SportsTodayGroup.LIVE, live),
            SportsTodaySection(SportsTodayGroup.UPCOMING, upcoming),
            SportsTodaySection(SportsTodayGroup.RECENT, recent),
        ).filter { it.games.isNotEmpty() }
    }
}

/** Live & Today's screen state, from the store's (pure). */
sealed interface SportsTodayUiState {
    data object Loading : SportsTodayUiState

    /** Nothing loaded yet and the service can't be reached right now. */
    data object Unavailable : SportsTodayUiState

    /** Loaded, but nothing live, upcoming or recent. [stale]: the last refresh failed; [filtered]: for the chosen sport. */
    data class Empty(val stale: Boolean, val filtered: Boolean = false) : SportsTodayUiState

    data class Content(
        val sections: List<SportsTodaySection>,
        val leagues: Map<String, SportsLeague>,
        /** The last refresh failed; this is the last good data. */
        val stale: Boolean,
    ) : SportsTodayUiState

    companion object {
        fun from(state: SportsLiveState, nowMs: Long, sport: String? = null): SportsTodayUiState = when (state) {
            SportsLiveState.Loading -> Loading
            SportsLiveState.TemporarilyUnavailable -> Unavailable
            is SportsLiveState.Content -> {
                val games = SportsSportFilter.filter(state.slate.eventsById.values, state.slate.leagues, sport)
                val sections = SportsTodayModel.sections(games, nowMs)
                if (sections.isEmpty()) Empty(state.stale, filtered = sport != null)
                else Content(sections, state.slate.leagues.associateBy { it.id }, state.stale)
            }
        }
    }
}

/**
 * Channel counts as a card may show them: only ever for the profile that is active NOW. While the
 * counts are being worked out for a newly active profile — or after any change that can alter them —
 * they are [Pending] and no card shows a number, so a count can never belong to another profile.
 */
sealed interface SportsChannelCounts {
    data object Pending : SportsChannelCounts

    data class Ready(val offers: SportsChannelOffers) : SportsChannelCounts

    companion object {
        /** The count to show for [eventId] while [activeProfileId] is active, or null to show none. */
        fun shown(counts: SportsChannelCounts, eventId: String, activeProfileId: Long): Int? {
            val ready = counts as? Ready ?: return null
            if (ready.offers.profileId != activeProfileId || ready.offers.issue != null) return null
            return ready.offers.countFor(eventId)
        }
    }
}

/** How a game's start is labelled, relative to the device's own day (pure). */
enum class SportsDayLabel { TODAY, TOMORROW, YESTERDAY, OTHER }

object SportsTimeLabels {
    fun day(startMs: Long, nowMs: Long, zone: ZoneId): SportsDayLabel {
        val start = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
        val today: LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return when (start) {
            today -> SportsDayLabel.TODAY
            today.plusDays(1) -> SportsDayLabel.TOMORROW
            today.minusDays(1) -> SportsDayLabel.YESTERDAY
            else -> SportsDayLabel.OTHER
        }
    }
}

// ---------------------------------------------------------------------------------------- schedule

/** The Schedule's days: yesterday, today and the six after, in the device's zone (pure). */
object SportsScheduleDays {
    const val DAYS_BACK = 1L
    const val DAYS_AHEAD = 6L

    fun days(nowMs: Long, zone: ZoneId): List<LocalDate> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return (-DAYS_BACK..DAYS_AHEAD).map { today.plusDays(it) }
    }

    /** The chosen day, or today when none was chosen or the saved one has dropped out of the range. */
    fun effective(selectedEpochDay: Long?, days: List<LocalDate>): LocalDate =
        days.firstOrNull { it.toEpochDay() == selectedEpochDay } ?: days[DAYS_BACK.toInt()]

    /** [start, end) of [day] in [zone], as epoch millis — a 23- or 25-hour day across a clock change. */
    fun bounds(day: LocalDate, zone: ZoneId): Pair<Long, Long> =
        day.atStartOfDay(zone).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

/** A league's games within a day. */
data class SportsLeagueSection(val leagueId: String, val league: SportsLeague?, val games: List<SportsEvent>)

/** A day's games (the Leagues tab lists a league's week by day). */
data class SportsDaySection(val day: LocalDate, val games: List<SportsEvent>)

object SportsScheduleModel {
    /** By league, in the backend's league order (unknown leagues last), each by start time. */
    fun byLeague(events: Collection<SportsEvent>, leagues: List<SportsLeague>): List<SportsLeagueSection> {
        val byId = leagues.associateBy { it.id }
        return events.groupBy { it.leagueId }
            .map { (id, games) -> SportsLeagueSection(id, byId[id], games.sortedWith(START_ORDER)) }
            .sortedWith(compareBy<SportsLeagueSection> { it.league?.order ?: Int.MAX_VALUE }.thenBy { it.leagueId })
    }

    /** By the device's day, earliest first, each by start time. */
    fun byDay(events: Collection<SportsEvent>, zone: ZoneId): List<SportsDaySection> =
        events.groupBy { Instant.ofEpochMilli(it.startTimeMs).atZone(zone).toLocalDate() }
            .toSortedMap()
            .map { (day, games) -> SportsDaySection(day, games.sortedWith(START_ORDER)) }

    private val START_ORDER = compareBy<SportsEvent> { it.startTimeMs }.thenByDescending { it.priority }.thenBy { it.id }
}

/** The Schedule tab's state for one day and sport. */
sealed interface SportsScheduleUiState {
    /** Waiting for the store's first load, or for this day's games. */
    data object Loading : SportsScheduleUiState

    /** Nothing could be loaded for the day. */
    data object Unavailable : SportsScheduleUiState

    /** No games that day (for the chosen sport, when [filtered]). */
    data class Empty(val filtered: Boolean) : SportsScheduleUiState

    /** [partial]: some sports' games could not be loaded; what could is shown. */
    data class Content(val sections: List<SportsLeagueSection>, val partial: Boolean) : SportsScheduleUiState {
        val games: List<SportsEvent> get() = sections.flatMap { it.games }
    }

    companion object {
        fun from(events: List<SportsEvent>, failedSports: Set<String>, leagues: List<SportsLeague>, sport: String?): SportsScheduleUiState {
            val shown = SportsSportFilter.filter(events, leagues, sport)
            val partial = failedSports.isNotEmpty()
            return when {
                shown.isEmpty() && partial -> Unavailable
                shown.isEmpty() -> Empty(filtered = sport != null)
                else -> Content(SportsScheduleModel.byLeague(shown, leagues), partial)
            }
        }
    }
}

// ----------------------------------------------------------------------------------------- leagues

data class SportsLeagueGroup(val sport: String, val leagues: List<SportsLeague>)

object SportsLeaguesModel {
    /**
     * The backend's enabled leagues by sport — the selector's sports in its order, then any other sport
     * the backend has (e.g. boxing) — each in the backend's league order. Only [sport]'s when one is chosen.
     */
    fun groups(leagues: List<SportsLeague>, sport: String?): List<SportsLeagueGroup> {
        val bySport = leagues.filter { it.sport != null && (sport == null || it.sport == sport) }.groupBy { it.sport!! }
        val order = SportsSportFilter.ORDER + bySport.keys.filter { it !in SportsSportFilter.ORDER }.sorted()
        return order.mapNotNull { s ->
            bySport[s]?.let { SportsLeagueGroup(s, it.sortedWith(compareBy<SportsLeague> { l -> l.order }.thenBy { l -> l.id })) }
        }
    }

    /** A remembered league that no longer fits (another sport chosen, league gone) is let go. */
    fun effective(selectedLeagueId: String?, leagues: List<SportsLeague>, sport: String?): String? {
        val league = leagues.firstOrNull { it.id == selectedLeagueId } ?: return null
        return league.id.takeIf { sport == null || league.sport == sport }
    }
}

/** One league's games, from yesterday to a week ahead. */
sealed interface SportsLeagueGamesUiState {
    data object Loading : SportsLeagueGamesUiState
    data object Unavailable : SportsLeagueGamesUiState

    /** No games in that window. */
    data object Empty : SportsLeagueGamesUiState

    data class Content(val days: List<SportsDaySection>) : SportsLeagueGamesUiState {
        val games: List<SportsEvent> get() = days.flatMap { it.games }
    }

    companion object {
        fun from(games: List<SportsEvent>?, zone: ZoneId): SportsLeagueGamesUiState = when {
            games == null -> Unavailable
            games.isEmpty() -> Empty
            else -> Content(SportsScheduleModel.byDay(games, zone))
        }
    }
}

// --------------------------------------------------------------------------------- shared selection

/**
 * What the hub remembers across tabs, rotation and process death: the tab, the sport (null = All
 * Sports), the Schedule's day (epoch day; null = today) and the open league. Saved as a plain list.
 */
data class SportsHubSelection(
    val tab: SportsHubTab = SportsHubTab.LIVE_TODAY,
    val sport: String? = null,
    val epochDay: Long? = null,
    val leagueId: String? = null,
) {
    fun toSaved(): List<Any?> = listOf(tab.name, sport, epochDay, leagueId)

    /**
     * A new sport closes the open league: going back to the league's sport later shows that sport's
     * league list, not the league that was open before. Choosing the sport already chosen changes nothing.
     */
    fun withSport(newSport: String?): SportsHubSelection =
        if (newSport == sport) this else copy(sport = newSport, leagueId = null)

    /**
     * Drops a remembered league that no longer fits — gone from the backend, or of another sport than
     * the one chosen — once the leagues are known (restored state, rotation). While they are still
     * loading nothing can be judged, so the selection is kept as it is.
     */
    fun normalized(leagues: List<SportsLeague>, effectiveSport: String?): SportsHubSelection {
        if (leagueId == null || leagues.isEmpty()) return this
        return if (SportsLeaguesModel.effective(leagueId, leagues, effectiveSport) == null) copy(leagueId = null) else this
    }

    companion object {
        fun fromSaved(saved: List<Any?>): SportsHubSelection = SportsHubSelection(
            tab = SportsHubTab.entries.firstOrNull { it.name == saved.getOrNull(0) } ?: SportsHubTab.LIVE_TODAY,
            sport = saved.getOrNull(1) as? String,
            epochDay = saved.getOrNull(2) as? Long,
            leagueId = saved.getOrNull(3) as? String,
        )
    }
}

/** What the view model needs to load for the hub as it stands: the tab, the effective sport, day and league. */
data class SportsHubQuery(
    val tab: SportsHubTab,
    val sport: String?,
    val day: LocalDate?,
    val leagueId: String?,
)

/**
 * The games whose channels are counted: the store's and whatever Schedule or Leagues is showing, one
 * per id (the newest copy), only those that list channels. Counted for the active profile as before.
 */
object SportsCountedGames {
    fun of(vararg lists: Collection<SportsEvent>): List<SportsEvent> {
        val byId = LinkedHashMap<String, SportsEvent>()
        for (list in lists) for (e in list) {
            if (e.channels.isEmpty()) continue
            val existing = byId[e.id]
            if (existing == null || e.updatedAtMs >= existing.updatedAtMs) byId[e.id] = e
        }
        return byId.values.toList()
    }

    /** Whether a recount is needed: the same games listing the same channels needs none. */
    fun same(a: List<SportsEvent>, b: List<SportsEvent>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) -> x.id == y.id && x.channels == y.channels }
}

/**
 * The game clock a live card shows (pure): the backend's own status detail for a live game, e.g.
 * "7:16 - 1st" or "Halftime", trimmed — or nothing. The app never makes up or runs a clock itself:
 * it changes when the backend's does, with each refresh.
 */
object SportsLiveClock {
    fun text(event: SportsEvent): String? =
        event.statusDetail?.trim()?.takeIf { event.status == SportsEventStatus.LIVE && it.isNotEmpty() }

    /** The newest copy of [opened] among [shown] (an open details panel follows refreshes), or [opened]. */
    fun latest(opened: SportsEvent, shown: List<SportsEvent>): SportsEvent =
        shown.firstOrNull { it.id == opened.id } ?: opened
}
