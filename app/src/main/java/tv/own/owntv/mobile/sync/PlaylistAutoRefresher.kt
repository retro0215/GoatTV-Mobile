package tv.own.owntv.mobile.sync

import android.os.SystemClock
import android.util.Log
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.dao.resolveExistingProfileId
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.work.CatalogSyncScheduler

/**
 * The playlist auto-refresh the Setup and Settings pickers have always saved, and nothing on the
 * phone ever acted on — so a catalogue here only changed when somebody asked for a resync.
 *
 * The television's `ShellViewModel.checkAutoRefresh` is the reference, and the semantics are its:
 * - **Off** never refreshes.
 * - **On startup** refreshes once per process start, and never on a resume.
 * - **6 h / 12 h / every N days** refresh when the source's last successful sync is at least that
 *   old, checked at process start and on every return to the foreground. A source that has never
 *   synced counts as infinitely old.
 *
 * One difference, deliberately: "every N days" uses [PlaylistRefresh.thresholdMs], which knows the day
 * count. The television reads the enum's own threshold, which is null for that mode, so there it
 * never fires.
 *
 * Checking is cheap and runs on every foreground; *syncing* happens only for a source that is due.
 * Each due source is enqueued on its own unique work with KEEP, so a source already syncing or queued
 * is left alone and a burst of resumes cannot start a second sync of it.
 */
class PlaylistAutoRefresher(
    private val dueSources: suspend (coldStart: Boolean, nowMs: Long) -> List<SourceEntity>,
    private val enqueue: (SourceEntity) -> Unit,
    private val scope: CoroutineScope,
    private val wallClock: () -> Long = System::currentTimeMillis,
    elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val gate = AutoRefreshGate(elapsedClock)

    /** The app came to the foreground. The first call in a process is the cold start. */
    fun onForeground() {
        val kind = gate.claim() ?: return
        scope.launch {
            runCatching {
                dueSources(kind == AutoRefreshGate.Kind.COLD_START, wallClock()).forEach(enqueue)
            }.onFailure { Log.w(TAG, "auto-refresh check failed: ${it.javaClass.simpleName}") }
        }
    }

    companion object {
        private const val TAG = "PlaylistAutoRefresher"

        /** Wired to the real settings, database and scheduler — the one instance the app uses. */
        fun create(
            settings: SettingsRepository,
            profileDao: ProfileDao,
            sourceDao: SourceDao,
            scheduler: CatalogSyncScheduler,
        ) = PlaylistAutoRefresher(
            dueSources = { coldStart, nowMs ->
                val stored = settings.activeProfileId.first()
                val profileId = if (stored >= 0) profileDao.resolveExistingProfileId(stored) else null
                if (profileId == null) {
                    emptyList()
                } else {
                    val modes = settings.playlistAutoRefresh.first()
                    sourceDao.observeForProfile(profileId).first().filter { source ->
                        isPlaylistRefreshDue(modes[source.id] ?: PlaylistRefresh.OFF, source.lastSyncAt, nowMs, coldStart)                    }
                }
            },
            enqueue = { source ->
                scheduler.enqueueSync(
                    source.id,
                    reason = REASON,
                    contentTypes = SyncContentTypes.enabledOf(source),
                    policy = POLICY,
                )
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )

        /** What the worker logs as the reason — the television's word for the same thing. */
        const val REASON = "auto_refresh"

        /**
         * Leave a sync that is already queued or running for that playlist alone: a second resume, or
         * a manual resync the user already started, is never restarted or doubled by the automatic one.
         */
        val POLICY = ExistingWorkPolicy.KEEP
    }
}

/**
 * Whether one playlist is due. [coldStart] is true only for the first check in a process: that is
 * the one time an "on startup" playlist refreshes.
 */
internal fun isPlaylistRefreshDue(refresh: PlaylistRefresh, lastSyncAt: Long?, nowMs: Long, coldStart: Boolean): Boolean =
    when (refresh.mode) {
        PlaylistAutoRefresh.OFF -> false
        PlaylistAutoRefresh.STARTUP -> coldStart
        else -> nowMs - (lastSyncAt ?: 0L) >= (refresh.thresholdMs ?: Long.MAX_VALUE)
    }

/**
 * When a foreground is worth checking: always the first in a process, then at most once a minute.
 * Rotation, a notification shade pulled down and a quick app switch all restart the activity, and
 * none of them is a reason to read the sources again.
 */
internal class AutoRefreshGate(private val elapsedClock: () -> Long) {
    enum class Kind { COLD_START, RESUME }

    private var coldStartDone = false
    private var lastCheckAt = 0L

    @Synchronized
    fun claim(): Kind? {
        val now = elapsedClock()
        if (!coldStartDone) {
            coldStartDone = true
            lastCheckAt = now
            return Kind.COLD_START
        }
        if (now - lastCheckAt < RESUME_THROTTLE_MS) return null
        lastCheckAt = now
        return Kind.RESUME
    }

    companion object {
        /** The television's figure. */
        const val RESUME_THROTTLE_MS = 60_000L
    }
}
