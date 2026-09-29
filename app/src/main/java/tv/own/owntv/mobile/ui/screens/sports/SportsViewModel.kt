package tv.own.owntv.mobile.ui.screens.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.own.owntv.core.content.AdultCategoryClassifier
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.sync.CatalogSyncCompletions
import tv.own.owntv.mobile.ui.screens.live.LiveTuner

/** What the Sports page has to show. */
sealed interface SportsUiState {
    data object Loading : SportsUiState

    /** The playlist has no category that matches a section. */
    data object Empty : SportsUiState

    /** The database could not be read. What was on screen before is not kept. */
    data object Failed : SportsUiState

    data class Ready(val sections: List<SportsSectionData>) : SportsUiState
}

/** The card the user picked, and the section it was picked in — a channel can be in two. */
data class SportsSelection(val section: SportsSection, val channelId: Long)

/**
 * Everything a rebuild depends on. Equal inputs mean an equal page, which is what lets a sync that
 * rewrites the categories table a thousand times without changing a sports category cost nothing.
 */
internal data class SportsInputs(
    val profileId: Long,
    val matched: List<SportsCategory>,
    val hiddenChannels: Set<String>,
    val channelNames: Map<String, String>,
    /** Bumped by every successful catalogue sync: channel names and line-ups may have changed. */
    val generation: Int,
)

/**
 * Reads the channels of matched sports categories, and only those, one bounded query per category.
 *
 * Results are kept per category until the next successful sync, so a rename or a hidden channel —
 * or a category appearing — does not re-read the categories that did not change. On a 40,000-channel
 * playlist the page costs a few dozen small queries, never a scan of the whole table.
 */
internal class SportsSectionLoader(
    private val load: suspend (profileId: Long, category: CategoryEntity) -> List<ChannelEntity>,
) {
    private val cache = HashMap<Long, List<ChannelEntity>>()
    private var cachedGeneration = Int.MIN_VALUE
    private var cachedProfile = Long.MIN_VALUE

    /** Category reads actually made — for tests. */
    var reads = 0
        private set

    suspend fun sections(inputs: SportsInputs): List<SportsSectionData> {
        if (inputs.generation != cachedGeneration || inputs.profileId != cachedProfile) {
            cache.clear()
            cachedGeneration = inputs.generation
            cachedProfile = inputs.profileId
        }
        val wanted = inputs.matched.map { it.category.id }.toSet()
        cache.keys.retainAll(wanted)
        val loaded = inputs.matched.map { (section, category) ->
            val channels = cache.getOrPut(category.id) {
                reads++
                load(inputs.profileId, category)
            }
            section to channels.mapNotNull { ch -> ch.visibleAs(inputs) }
        }
        return assembleSportsSections(loaded)
    }

    private fun ChannelEntity.visibleAs(inputs: SportsInputs): ChannelEntity? {
        val key = CustomizeKeys.channel(this)
        if (key in inputs.hiddenChannels) return null
        return inputs.channelNames[key]?.let { copy(name = it) } ?: this
    }
}

/**
 * Inputs to page state: rebuilt only when something it depends on changed, newest wins. Null inputs
 * (no profile yet) are still loading.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<SportsInputs?>.toSportsState(loader: SportsSectionLoader): Flow<SportsUiState> =
    distinctUntilChanged()
        .mapLatest { inputs ->
            if (inputs == null) return@mapLatest SportsUiState.Loading
            val sections = loader.sections(inputs)
            if (sections.isEmpty()) SportsUiState.Empty else SportsUiState.Ready(sections)
        }
        .catch { e ->
            if (e is CancellationException) throw e
            emit(SportsUiState.Failed)
        }

/**
 * One preview start per pause: every [request] restarts the wait, [cancel] drops it. Tapping along a
 * row therefore opens the card the finger stopped on, once — never a stream per card, which a
 * one-connection panel answers by locking the account.
 */
internal class SportsPreviewDebounce(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val start: () -> Unit,
) {
    private var job: Job? = null

    fun request() {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            start()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/** What coming back to the page does to the preview. */
enum class SportsReturn {
    /** The selected channel is still playing (the mini player kept it): it becomes the preview again. */
    KEEP_PLAYING,

    /** Nothing is playing: the selected channel previews again, after the usual pause. */
    PREVIEW_AGAIN,

    /** Something else is playing — the user's, not the page's — and is not replaced. */
    LEAVE_ALONE,
}

fun sportsReturnAction(selectedId: Long, playingId: Long?, hasStream: Boolean): SportsReturn = when {
    !hasStream -> SportsReturn.PREVIEW_AGAIN
    playingId == selectedId -> SportsReturn.KEEP_PLAYING
    else -> SportsReturn.LEAVE_ALONE
}

/** What a lifecycle change of the Sports page does to its preview. */
enum class SportsPageEvent { SHOW, LEAVE, NOTHING }

/**
 * The page came to the front ([resumed]) or stopped being seen. Stopping — another tab, Multiview,
 * the Android Home button — ends the page's preview, so no Sports sound goes on behind anything else.
 * Two stops are not leaving: the player, which carries the same stream on, and a rotation, which
 * only rebuilds the screen around it.
 */
fun sportsPageEvent(resumed: Boolean, toFullscreen: Boolean, changingConfiguration: Boolean): SportsPageEvent = when {
    resumed -> SportsPageEvent.SHOW
    toFullscreen || changingConfiguration -> SportsPageEvent.NOTHING
    else -> SportsPageEvent.LEAVE
}

/**
 * The phone's Sports page: the television's section, reading this app's own database.
 *
 * The categories come from the active profile's live playlists (narrowed to the chosen playlist, as
 * every browse screen is), hidden and adult categories dropped as Live TV drops them, and grouped by
 * [matchSportsSection]. Nothing here goes to the network: a playlist refresh writes the database, the
 * category flow and [CatalogSyncCompletions] notice, and the page is rebuilt from what was written.
 *
 * The preview is [LiveTuner]'s one stream, the same as Live TV's, with its sound on — the television
 * keeps its pane silent, the phone does not. Being the tuner's stream is what keeps it the only
 * sound: a new tune replaces the old one, the tuner holds the audio focus, and full screen carries the
 * same connection on rather than opening a second.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SportsViewModel(
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val profileDao: ProfileDao,
    private val sourceDao: SourceDao,
    private val settings: SettingsRepository,
    private val customize: CustomizationStore,
    private val tuner: LiveTuner,
    syncCompletions: CatalogSyncCompletions,
) : ViewModel() {

    private val loader = SportsSectionLoader { profileId, category ->
        withContext(Dispatchers.IO) {
            channelDao.snapshotByCategoryManual(
                categoryId = category.id,
                profileId = profileId,
                contextKey = CustomizeKeys.category(category),
                limit = CATEGORY_LIMIT,
            )
        }
    }

    private val generation: Flow<Int> = syncCompletions.succeeded()
        .scan(0) { n, _ -> n + 1 }

    private val inputs: Flow<SportsInputs?> = activeProfileSources(settings, sourceDao)
        .flatMapLatest { ctx ->
            if (ctx.profileId < 0) {
                flowOf(null)
            } else {
                combine(
                    categoryDao.observe(ctx.liveSourceIds.ifEmpty { listOf(-1L) }, MediaType.LIVE),
                    customize.observe(ctx.profileId, MediaType.LIVE),
                    profileDao.observeById(ctx.profileId),
                    generation,
                ) { categories, cust, profile, gen ->
                    inputsOf(ctx.profileId, categories, cust, profile?.isKids == true, gen)
                }
            }
        }

    val state: StateFlow<SportsUiState> = inputs
        // An import writes the categories table in batches; the page waits for it to settle.
        .debounce(INPUT_SETTLE_MS)
        .toSportsState(loader)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsUiState.Loading)

    private fun inputsOf(
        profileId: Long,
        categories: List<CategoryEntity>,
        cust: SectionCustomizations,
        isKids: Boolean,
        gen: Int,
    ): SportsInputs {
        val hidden = AdultCategoryClassifier.hiddenCategoryIds(categories, cust.hiddenCategories, isKids)
        return SportsInputs(
            profileId = profileId,
            matched = matchSportsCategories(categories.filter { it.id !in hidden }),
            hiddenChannels = cust.hiddenItems.keys,
            channelNames = cust.itemNames,
            generation = gen,
        )
    }

    // --- The preview --------------------------------------------------------------------------------

    private val _selected = MutableStateFlow<SportsSelection?>(null)

    /** The card last tapped. Kept for as long as the page is on the back stack. */
    val selected: StateFlow<SportsSelection?> = _selected.asStateFlow()

    /** The zap list the selection was made with, for the tune that follows it. */
    private var selectedZap: LiveTuner.ZapList? = null

    /** The channel this page put into the tuner — the one it may stop when the user leaves. */
    private var ownedChannelId: Long? = null

    private val debounce = SportsPreviewDebounce(viewModelScope, PREVIEW_DEBOUNCE_MS) { startPreview() }

    /**
     * A card was tapped. The first tap selects it and, once the selection has held for
     * [PREVIEW_DEBOUNCE_MS], tunes the preview; running a finger along a row therefore opens one
     * stream, not one per card. A tap on the card already selected answers [SportsTap.FULLSCREEN] and
     * the caller opens the player.
     */
    fun onChannelTapped(section: SportsSection, channel: ChannelEntity, zapList: LiveTuner.ZapList): SportsTap {
        val action = sportsTapAction(channel.id, _selected.value?.channelId)
        _selected.value = SportsSelection(section, channel.id)
        selectedZap = zapList
        if (action == SportsTap.PREVIEW) debounce.request()
        return action
    }

    private fun startPreview() {
        val selection = _selected.value ?: return
        tuner.tune(selection.channelId, selectedZap)
        ownedChannelId = selection.channelId
    }

    /**
     * The page is on screen again (after full screen, Multiview or another tab). If the tuner is free,
     * the selected channel previews again after the same pause; a stream the user left playing in the
     * mini player is somebody else's and is not replaced.
     */
    fun onPageShown() {
        val selection = _selected.value ?: return
        when (sportsReturnAction(selection.channelId, tuner.channel.value?.id, tuner.hasStream)) {
            SportsReturn.KEEP_PLAYING -> ownedChannelId = selection.channelId
            SportsReturn.PREVIEW_AGAIN -> debounce.request()
            SportsReturn.LEAVE_ALONE -> Unit
        }
    }

    /** Full screen: the same stream carried on, and the section as the list to zap through. */
    fun prepareFullscreen() {
        debounce.cancel()
        val selection = _selected.value ?: return
        tuner.tune(selection.channelId, selectedZap)
        ownedChannelId = null
    }

    /**
     * Leaving for anywhere but full screen: a pending preview is dropped and the page's own stream
     * stopped, so nothing plays behind another tab. A stream this page did not start is left alone.
     */
    fun onPageLeft() {
        debounce.cancel()
        val owned = ownedChannelId ?: return
        ownedChannelId = null
        if (tuner.channel.value?.id == owned) tuner.stopPlayback(clearChannel = true)
    }

    override fun onCleared() {
        debounce.cancel()
    }

    companion object {
        /** The television's focus debounce, used here for taps. */
        const val PREVIEW_DEBOUNCE_MS = 400L

        /** How long the categories must hold still before the page is rebuilt. */
        const val INPUT_SETTLE_MS = 250L

        /** At most this many channels are read from one category — the same bound Live TV's lists use. */
        const val CATEGORY_LIMIT = 2_000
    }
}
