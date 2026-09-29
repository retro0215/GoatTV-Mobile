package tv.own.owntv.mobile.ui.screens.multiview

import android.content.ComponentCallbacks2
import android.util.Log
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.live.OpenStreamRegistry
import tv.own.owntv.core.live.StreamGrant
import tv.own.owntv.core.live.StreamPurpose
import tv.own.owntv.core.live.connectionBudget
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import tv.own.owntv.player.LiveEnginePool

/** Why a tile is not playing, when it is not the engine's own error. */
sealed interface TileProblem {
    /** The playlist had no connection to spare — the provider's limit, said in the tile. */
    data class Refused(val grant: StreamGrant.Refused) : TileProblem

    /** The provider allowed it and the picture stopped anyway: this phone's own limit. */
    data object DeviceLimit : TileProblem
}

/**
 * The phone's Multiview: up to five live channels at once, one of them heard.
 *
 * One per process, so a grid outlives its screen — leaving with Back, opening a tile full screen and
 * pressing Home all put it away and bring the same grid back. Only [close] ends it.
 *
 * The engines are core's [LiveEnginePool], one per player slot; core decides a channel's URL, headers
 * and connection budget exactly as it does for a full-screen tune. While the screen is not showing,
 * every tile is stopped and its provider connection given back, so nothing plays, costs a connection
 * or runs a decoder behind the user's back; [start] claims and tunes them again.
 *
 * Main thread only, like the engines.
 */
@UnstableApi
class MultiviewSession(
    private val pool: LiveEnginePool,
    private val registry: OpenStreamRegistry,
    private val tuner: LiveTuner,
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _model = MutableStateFlow(MultiviewModel())
    val model: StateFlow<MultiviewModel> = _model.asStateFlow()

    private val _problems = MutableStateFlow<Map<Int, TileProblem>>(emptyMap())
    val problems: StateFlow<Map<Int, TileProblem>> = _problems.asStateFlow()

    /** True while the Multiview screen is in front and its tiles are meant to be playing. */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val claims = HashMap<Int, OpenStreamRegistry.Claim>()
    private val tuneJobs = HashMap<Int, Job>()

    /** Budget checks one at a time: tiles opening together must not all be granted the last connection. */
    private val claimLock = Mutex()

    init {
        watchProfileAndSources()
    }

    fun engineFor(slot: Int) = pool.engineFor(slot)

    // --- Changing the grid --------------------------------------------------------------------------

    /** Add a tile at the end; false only when the grid is full. Adding a channel already there is a no-op. */
    fun add(content: MultiviewContent): Boolean {
        val result = _model.value.add(content)
        val slot = result.slot ?: return false
        val isNew = result.model !== _model.value
        _model.value = result.model
        if (isNew) startTile(slot)
        applyAudio()
        return true
    }

    /** The channel being watched becomes tile 1 with the sound, keeping any tiles already staged. */
    fun seedFront(content: MultiviewContent) {
        val before = _model.value
        val result = before.seedFront(content)
        result.dropped.forEach(::freeSlot)
        _model.value = result.model
        val slot = result.slot ?: return
        if (before.tileIn(slot) == null) startTile(slot)
        applyAudio()
    }

    fun remove(slot: Int) {
        if (_model.value.tileIn(slot) == null) return
        _model.value = _model.value.remove(slot)
        freeSlot(slot)
        applyAudio()
    }

    /** Swap Stream: the tile keeps its place and player slot and re-tunes to [content]. */
    fun replace(slot: Int, content: MultiviewContent): Boolean {
        val before = _model.value
        val result = before.replace(slot, content)
        if (result.slot == null) return false
        if (result.model != before) {
            _model.value = result.model
            startTile(slot)
        }
        return true
    }

    /**
     * The picker's Cancel: put the grid back as [snapshot] had it. Tiles added since go, tiles removed
     * since come back, and order and sound are restored. A tile that never left keeps its player.
     */
    fun restore(snapshot: MultiviewModel) {
        _model.value.tiles
            .filterNot { snapshot.contains(it.content.key) }
            .forEach { remove(it.slot) }
        snapshot.tiles
            .filterNot { _model.value.contains(it.content.key) }
            .forEach { add(it.content) }
        _model.value = _model.value.orderedLike(snapshot)
        applyAudio()
    }

    /** Reorder only: no player is touched. */
    fun move(fromIndex: Int, toIndex: Int) {
        _model.value = _model.value.move(fromIndex, toIndex)
    }

    fun swap(a: Int, b: Int) {
        _model.value = _model.value.swap(a, b)
    }

    fun setAudio(slot: Int) {
        if (_problems.value[slot] != null) return
        _model.value = _model.value.withAudio(slot)
        applyAudio()
    }

    fun setSpotlight(slot: Int?) {
        _model.value = _model.value.withSpotlight(slot)
    }

    fun setGridFullscreen(slot: Int?) {
        _model.value = _model.value.withGridFullscreen(slot)
        applyAudio()
    }

    // --- Lifecycle ------------------------------------------------------------------------------------

    /**
     * The screen is in front: stop whatever single stream is playing — the full-screen player, the
     * Live TV preview, a mini player — and tune every tile. Nothing else may be heard under the grid.
     */
    fun start() {
        stopSingleStreamPlayback()
        if (_running.value) return
        _running.value = true
        _model.value.tiles.forEach { startTile(it.slot) }
        applyAudio()
    }

    /**
     * The screen went away — Home, a tile opened full screen, Back. Every tile stops and gives its
     * connection back; the grid itself, its order, its sound and its Spotlight stay for [start].
     */
    fun suspend() {
        if (!_running.value) return
        _running.value = false
        tuneJobs.values.forEach { it.cancel() }
        tuneJobs.clear()
        pool.giveSoundTo(null)
        _model.value.tiles.forEach { tile -> pool.peek(tile.slot)?.stop() }
        releaseAllClaims()
    }

    /** The X: stop every tile, free every player, forget the grid. */
    fun close() {
        _running.value = false
        tuneJobs.values.forEach { it.cancel() }
        tuneJobs.clear()
        releaseAllClaims()
        pool.releaseAll()
        _problems.value = emptyMap()
        _model.value = MultiviewModel()
    }

    /** Try a tile that failed or was refused again. */
    fun retry(slot: Int) {
        if (_model.value.tileIn(slot) == null) return
        startTile(slot)
        applyAudio()
    }

    /**
     * A tile the provider allowed has stopped while the others play on: the phone's own limit. It
     * keeps its place and says so; the sound moves to a tile that is actually showing something.
     */
    fun markDeviceLimit(slot: Int) {
        if (_model.value.tileIn(slot) == null) return
        stopTile(slot)
        setProblem(slot, TileProblem.DeviceLimit)
        moveSoundOffFailed(slot)
    }

    /** The engine reported an error: its audio would otherwise go on under an error message. */
    fun onTileFailed(slot: Int) {
        pool.peek(slot)?.setMuted(true)
        moveSoundOffFailed(slot)
    }

    /**
     * Critical memory pressure — the system is about to kill the app. Keep the tile with the sound and
     * free the rest, which is what lets the process survive (AerioTV's rule). Milder levels are ignored.
     */
    fun onMemoryPressure(level: Int) {
        if (level != ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL &&
            level != ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) return
        val result = _model.value.keepOnlyAudible()
        if (result.dropped.isEmpty()) return
        Log.w(TAG, "memory pressure level=$level: released ${result.dropped.size} tiles")
        result.dropped.forEach(::freeSlot)
        _model.value = result.model
        applyAudio()
    }

    // --- Internals ------------------------------------------------------------------------------------

    private fun startTile(slot: Int) {
        tuneJobs.remove(slot)?.cancel()
        clearProblem(slot)
        if (!_running.value) return
        val content = _model.value.tileIn(slot)?.content ?: return
        val channel = (content as? MultiviewContent.Live)?.channel ?: return
        tuneJobs[slot] = scope.launch {
            val source = tuner.sourceOf(channel)
            val grant = claimLock.withLock {
                releaseClaim(slot)
                val grant = connectionBudget(source, registry.openOn(channel.sourceId), StreamPurpose.WATCHING)
                if (grant !is StreamGrant.Refused) claims[slot] = registry.claim(channel.sourceId, StreamPurpose.WATCHING)
                grant
            }
            // The tile may have been removed, re-pointed or put away while the budget was asked.
            if (!_running.value || _model.value.tileIn(slot)?.content != content) {
                releaseClaim(slot)
                return@launch
            }
            if (grant is StreamGrant.Refused) {
                pool.peek(slot)?.stop()
                setProblem(slot, TileProblem.Refused(grant))
                moveSoundOffFailed(slot)
                return@launch
            }
            tuner.tuneTile(pool.engineFor(slot), channel, muted = _model.value.audioSlot != slot)
            applyAudio()
        }
    }

    private fun stopTile(slot: Int) {
        tuneJobs.remove(slot)?.cancel()
        pool.peek(slot)?.stop()
        releaseClaim(slot)
    }

    /** A slot is empty again: its player goes, and so does anything said about it. */
    private fun freeSlot(slot: Int) {
        tuneJobs.remove(slot)?.cancel()
        releaseClaim(slot)
        pool.release(slot)
        clearProblem(slot)
    }

    /** Exactly one engine unmuted — the audio slot's — and only while the grid is actually playing. */
    private fun applyAudio() {
        val slot = _model.value.audioSlot
        val audible = slot?.takeIf { _running.value && _problems.value[it] == null && pool.peek(it) != null }
        pool.giveSoundTo(audible)
    }

    private fun moveSoundOffFailed(slot: Int) {
        val model = _model.value
        if (model.audioSlot == slot) {
            val next = model.tiles.firstOrNull { it.slot != slot && _problems.value[it.slot] == null }
            if (next != null) _model.value = model.withAudio(next.slot)
        }
        applyAudio()
    }

    private fun stopSingleStreamPlayback() {
        // Both live engines, the media session, Cast's hold and the foreground service, in core's order.
        tuner.stopPlayback(clearChannel = false)
        // A film in the mini player plays on the same shared player; the grid must not play over it.
        tuner.player.stop()
    }

    private fun setProblem(slot: Int, problem: TileProblem) {
        _problems.value = _problems.value + (slot to problem)
    }

    private fun clearProblem(slot: Int) {
        if (slot in _problems.value) _problems.value = _problems.value - slot
    }

    private fun releaseClaim(slot: Int) {
        claims.remove(slot)?.let { registry.release(it) }
    }

    private fun releaseAllClaims() {
        claims.values.forEach { registry.release(it) }
        claims.clear()
    }

    /**
     * A grid must not outlive what it plays from. Switching profile ends it — the next person must not
     * find the last one's channels running — and a tile whose playlist is deleted, or no longer the
     * active profile's, is taken out so no engine keeps streaming on credentials that are gone.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun watchProfileAndSources() {
        scope.launch {
            var lastProfile: Long? = null
            settings.activeProfileId.distinctUntilChanged().collect { pid ->
                if (lastProfile != null && lastProfile != pid && !_model.value.isEmpty) close()
                lastProfile = pid
            }
        }
        scope.launch {
            settings.activeProfileId
                .flatMapLatest { pid -> if (pid < 0) flowOf(emptyList()) else sourceDao.observeForProfile(pid) }
                .map { sources -> sources.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect { sourceIds ->
                    _model.value.tiles
                        .filter { (it.content as? MultiviewContent.Live)?.channel?.sourceId !in sourceIds }
                        .forEach { remove(it.slot) }
                }
        }
    }

    private companion object {
        const val TAG = "MultiviewSession"
    }
}
