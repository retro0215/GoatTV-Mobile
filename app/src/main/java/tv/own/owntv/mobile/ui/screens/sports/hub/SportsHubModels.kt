package tv.own.owntv.mobile.ui.screens.sports.hub

import tv.own.owntv.mobile.sports.live.SportsChannelOffers
import tv.own.owntv.mobile.sports.live.SportsEvent
import tv.own.owntv.mobile.sports.live.SportsEventStatus
import tv.own.owntv.mobile.sports.live.SportsLeague
import tv.own.owntv.mobile.sports.live.SportsLiveState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The Sports hub's tabs. Live & Today is new; Channels is the Sports screen the app already had. */
enum class SportsHubTab { LIVE_TODAY, CHANNELS }

/**
 * Whether Live & Today should be refreshing: only while the screen is at least STARTED (in front, app
 * not in the background) and that tab is the one showing. The Channels tab never polls the schedule.
 */
fun sportsPollingActive(started: Boolean, tab: SportsHubTab): Boolean = started && tab == SportsHubTab.LIVE_TODAY

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

    /** Loaded, but nothing live, upcoming or recent. [stale]: the last refresh failed. */
    data class Empty(val stale: Boolean) : SportsTodayUiState

    data class Content(
        val sections: List<SportsTodaySection>,
        val leagues: Map<String, SportsLeague>,
        /** The last refresh failed; this is the last good data. */
        val stale: Boolean,
    ) : SportsTodayUiState

    companion object {
        fun from(state: SportsLiveState, nowMs: Long): SportsTodayUiState = when (state) {
            SportsLiveState.Loading -> Loading
            SportsLiveState.TemporarilyUnavailable -> Unavailable
            is SportsLiveState.Content -> {
                val sections = SportsTodayModel.sections(state.slate.eventsById.values, nowMs)
                if (sections.isEmpty()) Empty(state.stale)
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
