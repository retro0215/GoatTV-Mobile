package tv.own.owntv.mobile.ui.screens.sports.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.sports.live.SportsChannelAccess
import tv.own.owntv.mobile.sports.live.SportsChannelOffer
import tv.own.owntv.mobile.sports.live.SportsEvent
import tv.own.owntv.mobile.sports.live.SportsLeague
import tv.own.owntv.mobile.sports.live.SportsLiveState
import tv.own.owntv.mobile.sports.live.SportsLiveStore
import tv.own.owntv.mobile.sports.live.SportsScheduleRepository
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Sports hub's schedule side: Live & Today, Schedule and Leagues, all for the chosen sport, and
 * how many channels each game has for the ACTIVE profile. Read-only — nothing here plays, previews or
 * adds to Multiview.
 *
 * The store is app-wide; this only reads it, and [runPolling] lends the screen's lifecycle to it. The
 * screen says what it shows through [show]; a tab that is not showing loads nothing (its last result is
 * kept for when it comes back), and switching sports filters what is already loaded — Live & Today
 * never asks the network for it, Schedule and Leagues only for a day or league not loaded yet
 * ([SportsScheduleRepository] keeps those in memory for a while).
 *
 * Channel counts go through [SportsChannelAccess] (identity, playlist isolation, the profile's Live TV
 * visibility rules) for every game on screen, and are worked out again whenever anything that decides
 * them changes: the games' channel matches, the active profile, the default playlist, the profile's
 * hidden channels and categories, or its kids setting. Until a recount finishes the counts are pending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsHubViewModel(
    private val store: SportsLiveStore,
    private val schedules: SportsScheduleRepository,
    private val access: SportsChannelAccess,
    private val settings: SettingsRepository,
    private val customize: CustomizationStore,
    private val profileDao: ProfileDao,
) : ViewModel() {

    private val query = MutableStateFlow<SportsHubQuery?>(null)

    /** What the hub is showing now; the screen calls this whenever the tab, sport, day or league changes. */
    fun show(q: SportsHubQuery) {
        query.value = q
    }

    val activeProfileId: StateFlow<Long> = settings.activeProfileId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1L)

    private val slateLeagues: Flow<List<SportsLeague>> = store.state
        .map { (it as? SportsLiveState.Content)?.slate?.leagues.orEmpty() }
        .distinctUntilChanged()

    /** The backend's enabled leagues (for the Leagues tab). */
    val leagues: StateFlow<List<SportsLeague>> = slateLeagues
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The sports the selector offers, besides All Sports. */
    val sports: StateFlow<List<String>> = slateLeagues.map { SportsSportFilter.available(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsSportFilter.available(currentLeagues()))

    private fun currentLeagues() = (store.state.value as? SportsLiveState.Content)?.slate?.leagues.orEmpty()

    val today: StateFlow<SportsTodayUiState> = combine(store.state, query.map { it?.sport }.distinctUntilChanged()) { s, sport ->
        SportsTodayUiState.from(s, System.currentTimeMillis(), sport)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsTodayUiState.from(store.state.value, System.currentTimeMillis()))

    private var scheduleShown: Pair<LocalDate, String?>? = null

    /** One day's games for the chosen sport. Each store refresh re-reads it (fresh live scores), from memory. */
    val schedule: StateFlow<SportsScheduleUiState> = combine(query.filterNotNull(), store.state) { q, s -> q to s }
        .transformLatest { (q, s) ->
            val day = q.day
            if (q.tab != SportsHubTab.SCHEDULE || day == null) return@transformLatest // keep the last result
            val slate = (s as? SportsLiveState.Content)?.slate
            if (slate == null) {
                emit(if (s is SportsLiveState.TemporarilyUnavailable) SportsScheduleUiState.Unavailable else SportsScheduleUiState.Loading)
                return@transformLatest
            }
            val key = day to q.sport
            if (key != scheduleShown) emit(SportsScheduleUiState.Loading)
            scheduleShown = key
            val zone = ZoneId.systemDefault()
            val (from, to) = SportsScheduleDays.bounds(day, zone)
            val result = schedules.day(slate, from, to, q.sport?.let(::setOf))
            emit(SportsScheduleUiState.from(result.events, result.failedSports, slate.leagues, q.sport))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsScheduleUiState.Loading)

    private var leagueShown: String? = null

    /** The open league's games, yesterday to a week ahead. */
    val leagueGames: StateFlow<SportsLeagueGamesUiState> = combine(query.filterNotNull(), store.state) { q, s -> q to s }
        .transformLatest { (q, s) ->
            val id = q.leagueId
            if (q.tab != SportsHubTab.LEAGUES || id == null) return@transformLatest
            val slate = (s as? SportsLiveState.Content)?.slate
            if (slate == null) {
                emit(if (s is SportsLiveState.TemporarilyUnavailable) SportsLeagueGamesUiState.Unavailable else SportsLeagueGamesUiState.Loading)
                return@transformLatest
            }
            if (id != leagueShown) emit(SportsLeagueGamesUiState.Loading)
            leagueShown = id
            emit(SportsLeagueGamesUiState.from(schedules.league(slate, id), ZoneId.systemDefault()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsLeagueGamesUiState.Loading)

    /** Every game on screen that lists channels: a recount is only needed when this changes. */
    private val countedGames: Flow<List<SportsEvent>> = combine(
        store.state.map { (it as? SportsLiveState.Content)?.slate?.eventsById?.values?.toList().orEmpty() },
        schedule.map { (it as? SportsScheduleUiState.Content)?.games.orEmpty() },
        leagueGames.map { (it as? SportsLeagueGamesUiState.Content)?.games.orEmpty() },
    ) { stored, day, league -> SportsCountedGames.of(stored, day, league) }
        .distinctUntilChanged(SportsCountedGames::same)

    /** Everything about the active profile that can change what it may see. */
    private val profileRules: Flow<Any?> = settings.activeProfileId.flatMapLatest { pid ->
        if (pid < 0) {
            flowOf(pid)
        } else {
            combine(
                settings.defaultSourceId,
                customize.observe(pid, MediaType.LIVE).map { it.hiddenItems.keys to it.hiddenCategories },
                profileDao.observeById(pid).map { it?.isKids },
            ) { defaultId, hidden, kids -> listOf(pid, defaultId, hidden, kids) }
        }
    }.distinctUntilChanged()

    val counts: StateFlow<SportsChannelCounts> = combine(countedGames, profileRules) { games, _ -> games }
        .transformLatest { games ->
            emit(SportsChannelCounts.Pending)
            emit(SportsChannelCounts.Ready(access.offerAll(games)))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsChannelCounts.Pending)

    /** Keeps the schedule fresh while [active] (the hub's STARTED lifecycle, on a schedule tab) is true. */
    suspend fun runPolling(active: Flow<Boolean>) = store.runWhileActive(active)

    /** One game's allowed channels for the read-only details panel, decided afresh. */
    suspend fun channelsFor(event: SportsEvent): SportsChannelOffer = access.offer(event)
}
