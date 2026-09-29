package tv.own.owntv.mobile.sync

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.sync.work.CatalogSyncScheduler

/**
 * Fires once when a catalogue sync has **finished successfully** — any playlist, whoever started it:
 * the auto-refresh, Settings' resync, Live TV's refresh or a new playlist's import. They all go
 * through [CatalogSyncScheduler], and every job it enqueues carries [CatalogSyncScheduler.TAG_CATALOG_SYNC].
 *
 * The job's end, not a table: the importer upserts in batches as it goes and stamps `lastSyncAt`
 * before its finaliser runs, so reacting to the tables would mean reacting thousands of times, and
 * part-way through. A job that fails or is cancelled fires nothing — what was on screen stays.
 *
 * Syncs that finish close together (two playlists, say) come out as one signal: [COALESCE_MS] is
 * short enough that nobody waits on it and long enough to fold them into one rebuild.
 */
class CatalogSyncCompletions(private val context: Context) {

    fun succeeded(): Flow<Unit> = WorkManager.getInstance(context)
        .getWorkInfosByTagFlow(CatalogSyncScheduler.TAG_CATALOG_SYNC)
        .map { infos -> infos.map { it.id.toString() to it.state } }
        .successes()

    companion object {
        const val COALESCE_MS = 400L
    }
}

/** One [Unit] per burst of newly succeeded jobs, from successive snapshots of every sync job. */
@OptIn(FlowPreview::class)
internal fun Flow<List<Pair<String, WorkInfo.State>>>.successes(coalesceMs: Long = CatalogSyncCompletions.COALESCE_MS): Flow<Unit> {
    val tracker = SyncCompletionTracker()
    return map { jobs -> tracker.newlySucceeded(jobs) }
        .filter { it }
        .map { }
        .debounce(coalesceMs)
}

/**
 * Which jobs have just succeeded, from successive snapshots of every catalogue-sync job.
 *
 * The first snapshot is the baseline: jobs that were already finished before anybody was watching
 * are history, not news. After that a job counts once, the first time it is seen SUCCEEDED — whether
 * it was watched running or finished between two snapshots. WorkManager reports progress many times
 * a second during a sync; none of that is a success, so none of it fires.
 */
internal class SyncCompletionTracker {
    private var baselined = false
    private val succeeded = HashSet<String>()

    @Synchronized
    fun newlySucceeded(jobs: List<Pair<String, WorkInfo.State>>): Boolean {
        val done = jobs.filter { it.second == WorkInfo.State.SUCCEEDED }.map { it.first }
        if (!baselined) {
            baselined = true
            succeeded += done
            return false
        }
        return done.count { succeeded.add(it) } > 0
    }
}
