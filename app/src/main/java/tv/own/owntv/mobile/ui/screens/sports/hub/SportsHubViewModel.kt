package tv.own.owntv.mobile.ui.screens.sports.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
import tv.own.owntv.mobile.sports.live.SportsLiveState
import tv.own.owntv.mobile.sports.live.SportsLiveStore

/**
 * Live & Today: the store's games, sectioned, and how many channels each game has for the ACTIVE
 * profile. Read-only — nothing here plays, previews or adds to Multiview.
 *
 * The store is app-wide; this only reads it, and [runPolling] lends the screen's lifecycle to it.
 * Channel counts go through [SportsChannelAccess] (identity, playlist isolation, the profile's Live TV
 * visibility rules) and are worked out again whenever anything that decides them changes: the games'
 * channel matches, the active profile, the default playlist, the profile's hidden channels and
 * categories, or its kids setting. Until a recount finishes the counts are pending, never stale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsHubViewModel(
    private val store: SportsLiveStore,
    private val access: SportsChannelAccess,
    private val settings: SettingsRepository,
    private val customize: CustomizationStore,
    private val profileDao: ProfileDao,
) : ViewModel() {

    val activeProfileId: StateFlow<Long> = settings.activeProfileId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1L)

    val today: StateFlow<SportsTodayUiState> = store.state
        .map { SportsTodayUiState.from(it, System.currentTimeMillis()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsTodayUiState.from(store.state.value, System.currentTimeMillis()))

    /** The games that list channels, and what they list: a recount is only needed when this changes. */
    private val matchedGames: Flow<List<SportsEvent>> = store.state
        .map { s -> (s as? SportsLiveState.Content)?.slate?.eventsById?.values?.filter { it.channels.isNotEmpty() }.orEmpty() }
        .distinctUntilChanged { a, b -> a.size == b.size && a.zip(b).all { (x, y) -> x.id == y.id && x.channels == y.channels } }

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

    val counts: StateFlow<SportsChannelCounts> = combine(matchedGames, profileRules) { games, _ -> games }
        .transformLatest { games ->
            emit(SportsChannelCounts.Pending)
            emit(SportsChannelCounts.Ready(access.offerAll(games)))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsChannelCounts.Pending)

    /** Keeps the schedule fresh while [active] (the Live & Today pane's STARTED lifecycle) is true. */
    suspend fun runPolling(active: Flow<Boolean>) = store.runWhileActive(active)

    /** One game's allowed channels for the read-only details panel, decided afresh. */
    suspend fun channelsFor(event: SportsEvent): SportsChannelOffer = access.offer(event)
}
