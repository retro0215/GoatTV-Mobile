package tv.own.owntv.mobile.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.home.TrendingHomeItem
import tv.own.owntv.core.metadata.MetadataRepository
import tv.own.owntv.core.home.HomeFeed
import tv.own.owntv.core.home.HomeFeedReader
import tv.own.owntv.core.model.HomeLiveRowMode
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.weather.WeatherInfo
import tv.own.owntv.core.weather.WeatherRepository
import tv.own.owntv.mobile.brand.BrandConfig
import tv.own.owntv.mobile.weather.TemperatureUnit
import tv.own.owntv.mobile.weather.WeatherTemperature
import tv.own.owntv.mobile.ui.screens.ContentActions
import tv.own.owntv.mobile.ui.screens.library.VodTuner
import tv.own.owntv.mobile.sync.CatalogSyncCompletions

/**
 * Home, for the phone.
 *
 * The rails themselves are core's — the same reader the television's Home uses, so the rows are the
 * ones the user arranged there, in that order, minus the ones they hid. What is here is only the
 * phone's part: when to rebuild them (this screen is left and returned to rather than lived on), and
 * playing what was tapped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val reader: HomeFeedReader,
    private val settings: SettingsRepository,
    private val profileDao: ProfileDao,
    private val tuner: VodTuner,
    private val actions: ContentActions,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val sourceDao: tv.own.owntv.core.database.dao.SourceDao,
    private val metadata: MetadataRepository,
    private val syncCompletions: CatalogSyncCompletions,
    weatherRepository: WeatherRepository,
    connectivity: ConnectivityObserver,
) : ViewModel() {

    private val profileId: StateFlow<Long> = settings.activeProfileId
        .map { stored -> if (stored >= 0) profileDao.resolveExistingProfileId(stored) ?: -1L else -1L }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, -1L)

    /**
     * One conflated request to rebuild. The screen asks on every entry, because coming back from a
     * film is exactly when the continue rows are wrong, and a profile switch asks on its own —
     * `collectLatest` drops a load already running rather than letting the two queue up.
     */
    private val reloads = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _feed = MutableStateFlow<HomeFeed?>(null)

    /** Null until the first load finishes — the screen shows nothing rather than a false empty state. */
    val feed: StateFlow<HomeFeed?> = _feed

    private val promotionRepository = tv.own.owntv.mobile.promotions.MobileHomePromotionRepository()
    private val _promotions = MutableStateFlow<List<tv.own.owntv.mobile.promotions.HomePromotion>>(emptyList())
    val promotions: StateFlow<List<tv.own.owntv.mobile.promotions.HomePromotion>> = _promotions.asStateFlow()

    private val _topMovies = MutableStateFlow<List<MovieEntity>>(emptyList())
    val topMovies: StateFlow<List<MovieEntity>> = _topMovies.asStateFlow()

    private val _topSeries = MutableStateFlow<List<SeriesEntity>>(emptyList())
    val topSeries: StateFlow<List<SeriesEntity>> = _topSeries.asStateFlow()

    private val _recentMovies = MutableStateFlow<List<MovieEntity>>(emptyList())
    val recentMovies: StateFlow<List<MovieEntity>> = _recentMovies.asStateFlow()

    private val _recentSeries = MutableStateFlow<List<SeriesEntity>>(emptyList())
    val recentSeries: StateFlow<List<SeriesEntity>> = _recentSeries.asStateFlow()

    init {
        reloads.tryEmit(Unit)
        viewModelScope.launch {
            combine(profileId, reloads) { pid, _ -> pid }.collectLatest { pid ->
                if (pid >= 0) {
                    val currentConfig = settings.homeConfig(pid).first()
                    if (currentConfig.order.isEmpty()) {
                        settings.updateHomeConfig(pid) {
                            it.copy(
                                order = listOf(
                                    HomeRow.CONTINUE_MOVIES,
                                    HomeRow.CONTINUE_SERIES,
                                    HomeRow.RECENT_CHANNELS,
                                    HomeRow.FAVORITE_CHANNELS
                                )
                            )
                        }
                    }
                    val sourceIds = tv.own.owntv.core.repository.activeSourceIds(settings, sourceDao, pid)
                    if (sourceIds.isNotEmpty()) {
                        // Computed in full before anything is published, and a read that fails leaves
                        // the rails as they were rather than half-updated or empty.
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val now = System.currentTimeMillis()
                                HomeDiscovery.movies(movieDao, pid, sourceIds, now) to
                                    HomeDiscovery.series(seriesDao, pid, sourceIds, now)
                            }
                        }.onSuccess { (movies, series) ->
                            _topMovies.value = movies.top
                            _topSeries.value = series.top
                            _recentMovies.value = movies.recent
                            _recentSeries.value = series.recent
                        }.onFailure { if (it is CancellationException) throw it }
                    } else {
                        _topMovies.value = emptyList()
                        _topSeries.value = emptyList()
                        _recentMovies.value = emptyList()
                        _recentSeries.value = emptyList()
                    }
                } else {
                    _topMovies.value = emptyList()
                    _topSeries.value = emptyList()
                    _recentMovies.value = emptyList()
                    _recentSeries.value = emptyList()
                }
                _feed.value = if (pid < 0) HomeFeed() else reader.load(pid)
            }
        }
        viewModelScope.launch {
            _promotions.value = promotionRepository.fetchEligiblePromotions()
        }
        // A catalogue sync that finished — automatic or asked for — changes what the discovery rails
        // should hold, so they are rebuilt while Home is on screen, not only the next time it opens.
        // Promotions come from their own service and are not fetched again for it.
        viewModelScope.launch {
            syncCompletions.succeeded().collect { refresh() }
        }
    }

    fun refresh() {
        reloads.tryEmit(Unit)
    }

    /** The weather chip, when the setting is on and there is a network to ask over. */
    val weather: StateFlow<WeatherInfo?> =
        combine(connectivity.isOnline, settings.weatherEnabled, settings.weatherLocation) { online, enabled, location ->
            Triple(online, enabled, location)
        }.flatMapLatest { (online, enabled, location) ->
            if (!online || !enabled) flowOf<WeatherInfo?>(null) else flow { emit(weatherRepository.get(location)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The chip's unit: the brand's (Fahrenheit, for every reseller app), else the user's setting. */
    val temperatureUnit: StateFlow<TemperatureUnit> = settings.weatherFahrenheit
        .map { WeatherTemperature.unitFor(BrandConfig.currentBrand, it) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            WeatherTemperature.unitFor(BrandConfig.currentBrand, userPrefersFahrenheit = false),
        )

    /** Switch a live row between logo cards and what-is-on-now. Stored where the TV app reads it. */
    fun toggleLiveMode(row: HomeRow) {
        val pid = profileId.value.takeIf { it >= 0 } ?: return
        viewModelScope.launch {
            settings.updateHomeConfig(pid) { config ->
                when (row) {
                    HomeRow.RECENT_CHANNELS -> config.copy(recentLiveMode = config.recentLiveMode.toggled())
                    HomeRow.FAVORITE_CHANNELS -> config.copy(favoriteLiveMode = config.favoriteLiveMode.toggled())
                    else -> config
                }
            }
            // The guide slice is only read for a row that is in "on now" mode, so the feed has to be
            // rebuilt for the row that just switched into it.
            refresh()
        }
    }

    fun modeOf(row: HomeRow, state: HomeFeed): HomeLiveRowMode = when (row) {
        HomeRow.FAVORITE_CHANNELS -> state.config.favoriteLiveMode
        else -> state.config.recentLiveMode
    }

    /** Play a film, or an episode, from where it was left. [onStarted] runs only if the picture is
     *  opening here rather than in another app — the same rule the detail screen plays by. */
    fun playMovie(movieId: Long, positionMs: Long, onStarted: () -> Unit) {
        viewModelScope.launch { if (tuner.playMovie(movieId, positionMs)) onStarted() }
    }

    fun playEpisode(episodeId: Long, positionMs: Long, onStarted: () -> Unit) {
        viewModelScope.launch { if (tuner.playEpisode(episodeId, positionMs)) onStarted() }
    }

    // --- Now Trending ---------------------------------------------------------------------------

    /**
     * Act on the trending item: play the film, or open the show's episodes.
     *
     * The saved row is re-read first, because the hero is built from a snapshot the worker took and a
     * sync since then may have replaced or dropped that exact version. [onUnavailable] is the case the
     * television shows the same message for, and the feed is rebuilt so the dead item goes.
     */
    fun activateTrending(
        item: TrendingHomeItem,
        onPlayerOpened: () -> Unit,
        onOpenSeries: (Long) -> Unit,
        onUnavailable: () -> Unit,
    ) {
        viewModelScope.launch {
            when (val current = revalidateTrendingItem(item)) {
                is TrendingHomeItem.Movie ->
                    if (tuner.playMovie(current.movie.id, 0L)) onPlayerOpened() else gone(onUnavailable)
                is TrendingHomeItem.Series -> onOpenSeries(current.series.id)
                null -> gone(onUnavailable)
            }
        }
    }

    private fun gone(onUnavailable: () -> Unit) {
        onUnavailable()
        refresh()
    }

    /** Re-resolve the exact saved provider row, in case a sync replaced it since the snapshot. */
    private suspend fun revalidateTrendingItem(item: TrendingHomeItem): TrendingHomeItem? =
        withContext(Dispatchers.IO) {
            when (item) {
                is TrendingHomeItem.Movie -> movieDao.getById(item.movie.id)
                    ?.takeIf { it.sourceId == item.snapshot.sourceId }
                    ?.let { item.copy(movie = it) }
                is TrendingHomeItem.Series -> seriesDao.getSeriesById(item.series.id)
                    ?.takeIf { it.sourceId == item.snapshot.sourceId }
                    ?.let { item.copy(series = it) }
            }
        }

    /** The full cached TMDB payload the details sheet shows, fetched under Trending's own TMDB id. */
    suspend fun resolveTrendingDetails(item: TrendingHomeItem): TrendingDetails =
        withContext(Dispatchers.IO) {
            val cache = when (item) {
                is TrendingHomeItem.Movie -> metadata.resolveKnownMovie(item.movie, item.snapshot.tmdbId)
                is TrendingHomeItem.Series -> metadata.resolveKnownSeries(item.series, item.snapshot.tmdbId)
            }
            TrendingDetails(cache, settings.metadataConfig().mode.tmdbWins)
        }

    data class TrendingDetails(val cache: MetadataCacheEntity?, val tmdbWins: Boolean)

    // --- The long-press menu ------------------------------------------------------------------------

    val favoriteChannels: StateFlow<Set<Long>> = favoriteIds(MediaType.LIVE)
    val favoriteMovies: StateFlow<Set<Long>> = favoriteIds(MediaType.MOVIE)
    val favoriteSeries: StateFlow<Set<Long>> = favoriteIds(MediaType.SERIES)

    private fun favoriteIds(type: MediaType): StateFlow<Set<Long>> = actions.favoriteIds(type)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Favouriting or hiding changes what the rails hold, so the feed is rebuilt after either. */
    fun toggleFavorite(type: MediaType, itemId: Long) {
        viewModelScope.launch {
            actions.toggleFavorite(type, itemId)
            refresh()
        }
    }

    fun hide(type: MediaType, itemId: Long) {
        viewModelScope.launch {
            actions.hide(type, itemId)
            refresh()
        }
    }

    fun download(type: MediaType, itemId: Long) {
        viewModelScope.launch { actions.download(type, itemId) }
    }
}
