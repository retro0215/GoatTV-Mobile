package tv.own.owntv.mobile.ui.screens.multiview

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed

/**
 * What a tile's long press turns into (pure, unit-tested): released where it was held, the tile menu;
 * carried past [slop], a move to wherever it is dropped.
 */
internal class MultiviewTileHold(private val slop: Float) {
    enum class Release { MENU, DROP }

    private var travelled = 0f

    /** Whether the finger has carried the tile far enough to be a move. */
    var moving: Boolean = false
        private set

    /** The finger moved by [distance]; true exactly once, the moment the hold becomes a move. */
    fun move(distance: Float): Boolean {
        travelled += distance
        if (moving || travelled <= slop) return false
        moving = true
        return true
    }

    fun release(): Release = if (moving) Release.DROP else Release.MENU
}

/**
 * A tile's long press: hold to open its menu, hold and drag to move it.
 *
 * Seen on the Samsung with 1.0.10: a long press with a finger that stayed still opened no menu — on any
 * tile — and moved the sound instead. The tile underneath also listens for taps and double taps, and
 * it consumes the finger's lift; `detectDragGesturesAfterLongPress` reads a consumed lift as a
 * cancellation, so the press ended in its cancel callback (no menu) while the tile took it as a tap
 * (sound moved there). Only a finger that drifted a little after the hold — its moves consumed by the
 * drag, which cancels the tile's tap — reached the menu.
 *
 * Here, once the hold has fired, every later event of that finger is this gesture's: it is read and
 * consumed on the Initial pass, before the tile sees it, so the tile never turns the release into a
 * tap, and the release is answered by [MultiviewTileHold] however it arrives.
 */
internal suspend fun PointerInputScope.detectTileHold(
    onHold: (at: Offset) -> Unit,
    onMoveStart: () -> Unit,
    onMove: (amount: Offset) -> Unit,
    onMenu: () -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
) {
    // Armed only past twice the touch slop — a finger resting on glass still moves a little.
    val slop = viewConfiguration.touchSlop * 2f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        val hold = MultiviewTileHold(slop)
        onHold(held.position)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == held.id }
            if (change == null) {
                onCancel()
                return@awaitEachGesture
            }
            val lifted = change.changedToUpIgnoreConsumed() || !change.pressed
            val amount = change.positionChangeIgnoreConsumed()
            change.consume()
            if (lifted) {
                when (hold.release()) {
                    MultiviewTileHold.Release.MENU -> onMenu()
                    MultiviewTileHold.Release.DROP -> onDrop()
                }
                return@awaitEachGesture
            }
            if (amount != Offset.Zero) {
                if (hold.move(amount.getDistance())) onMoveStart()
                onMove(amount)
            }
        }
    }
}
