package tv.own.owntv.mobile.sync

import androidx.work.WorkInfo.State
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signal Home rebuilds on: a catalogue sync job that finished successfully — once per success,
 * never for progress, never for a failure, and one rebuild for syncs that finish together.
 */
class CatalogSyncCompletionsTest {

    @Test
    fun jobsFinishedBeforeAnyoneWatched_areHistoryNotNews() {
        val tracker = SyncCompletionTracker()
        assertFalse(tracker.newlySucceeded(listOf("old" to State.SUCCEEDED)))
        assertFalse(tracker.newlySucceeded(listOf("old" to State.SUCCEEDED)))
    }

    @Test
    fun runningThenSucceeded_firesOnce() {
        val tracker = SyncCompletionTracker()
        tracker.newlySucceeded(emptyList())
        assertFalse(tracker.newlySucceeded(listOf("a" to State.ENQUEUED)))
        assertFalse(tracker.newlySucceeded(listOf("a" to State.RUNNING)))
        assertTrue(tracker.newlySucceeded(listOf("a" to State.SUCCEEDED)))
        assertFalse("the same finished job reported again", tracker.newlySucceeded(listOf("a" to State.SUCCEEDED)))
    }

    @Test
    fun progressUpdatesDuringAnImport_neverFire() {
        val tracker = SyncCompletionTracker()
        tracker.newlySucceeded(emptyList())
        // A 28,000-title import reports progress thousands of times; every snapshot is still RUNNING.
        val fired = (1..5_000).count { tracker.newlySucceeded(listOf("a" to State.RUNNING)) }
        assertEquals(0, fired)
    }

    @Test
    fun failedCancelledAndRetrying_neverFire() {
        val tracker = SyncCompletionTracker()
        tracker.newlySucceeded(emptyList())
        assertFalse(tracker.newlySucceeded(listOf("a" to State.RUNNING)))
        assertFalse("transient failure → WorkManager retry", tracker.newlySucceeded(listOf("a" to State.ENQUEUED)))
        assertFalse(tracker.newlySucceeded(listOf("a" to State.FAILED)))
        assertFalse(tracker.newlySucceeded(listOf("a" to State.FAILED, "b" to State.CANCELLED)))
    }

    @Test
    fun aJobThatFinishedBetweenTwoSnapshots_stillCounts() {
        val tracker = SyncCompletionTracker()
        tracker.newlySucceeded(emptyList())
        assertTrue(tracker.newlySucceeded(listOf("quick" to State.SUCCEEDED)))
    }

    @Test
    fun manualResyncAndAutoRefresh_areTheSameSignal() {
        // Both are CatalogSyncScheduler jobs under the same tag; only the job id differs.
        val tracker = SyncCompletionTracker()
        tracker.newlySucceeded(emptyList())
        assertTrue(tracker.newlySucceeded(listOf("manual" to State.SUCCEEDED)))
        assertTrue(tracker.newlySucceeded(listOf("manual" to State.SUCCEEDED, "auto" to State.SUCCEEDED)))
    }

    @Test
    fun twoPlaylistsFinishingTogether_rebuildHomeOnce() = runBlocking {
        val signals = flow {
            emit(emptyList())
            emit(listOf("a" to State.RUNNING, "b" to State.RUNNING))
            emit(listOf("a" to State.SUCCEEDED, "b" to State.RUNNING))
            delay(50)
            emit(listOf("a" to State.SUCCEEDED, "b" to State.SUCCEEDED))
        }.successes(coalesceMs = 200).toList()
        assertEquals(1, signals.size)
    }

    @Test
    fun syncsFinishingFarApart_eachRebuildHome() = runBlocking {
        val signals = flow {
            emit(emptyList())
            emit(listOf("a" to State.SUCCEEDED))
            delay(400)
            emit(listOf("a" to State.SUCCEEDED, "b" to State.SUCCEEDED))
        }.successes(coalesceMs = 100).toList()
        assertEquals(2, signals.size)
    }

    @Test
    fun aFailedSync_leavesHomeAlone() = runBlocking {
        val signals = flow {
            emit(emptyList())
            emit(listOf("a" to State.RUNNING))
            emit(listOf("a" to State.FAILED))
        }.successes(coalesceMs = 50).toList()
        assertTrue(signals.isEmpty())
    }
}
