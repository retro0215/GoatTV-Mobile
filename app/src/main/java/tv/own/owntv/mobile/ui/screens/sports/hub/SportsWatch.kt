package tv.own.owntv.mobile.ui.screens.sports.hub

import tv.own.owntv.mobile.sports.live.SportsEvent
import tv.own.owntv.mobile.sports.live.SportsWatchCheck
import tv.own.owntv.mobile.sports.live.VisibleSportsChannel
import tv.own.owntv.mobile.ui.screens.live.LiveTuner
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watch, from a game's Where to Watch list (unit-tested; the view model wires it to the real pieces).
 *
 *  1. [check] decides afresh — [tv.own.owntv.mobile.sports.live.SportsChannelAccess.checkWatch]: the
 *     profile the channels were offered to is still the active one, its playlist and rules still allow
 *     the chosen feed, and the game (its newest copy) still lists it. The row it hands back is the
 *     channel as the database has it now, never anything the backend sent.
 *  2. Only if allowed, and only if the screen is still in front ([canStart]), the app's one tuner is
 *     given that channel with the game's allowed channels as the list to zap through. The tuner does
 *     not reopen a channel that is already playing, so Watch on what is on never reconnects.
 *  3. A rejected Watch tunes nothing.
 *
 * A second Watch while one is being checked is ignored (null): two quick taps open one stream.
 */
class SportsWatchLauncher(
    private val check: suspend (event: SportsEvent, chosen: VisibleSportsChannel, offeredTo: Long?) -> SportsWatchCheck,
    private val tune: (channelId: Long, zapList: LiveTuner.ZapList) -> Unit,
) {
    private val busy = AtomicBoolean(false)

    suspend fun watch(
        event: SportsEvent,
        chosen: VisibleSportsChannel,
        offeredTo: Long?,
        title: String,
        canStart: () -> Boolean,
    ): SportsWatchCheck? {
        if (!busy.compareAndSet(false, true)) return null
        try {
            val result = check(event, chosen, offeredTo)
            if (result is SportsWatchCheck.Allowed) {
                if (!canStart()) return null
                tune(result.channel.id, LiveTuner.ZapList(title, result.zapChannels))
            }
            return result
        } finally {
            busy.set(false)
        }
    }
}

/**
 * The newest copy of a game the hub knows (pure): the panel may have been open across refreshes, and
 * Watch must judge the game as the backend lists it now — a feed it has dropped is not playable.
 */
object SportsLatestEvent {
    fun pick(opened: SportsEvent, vararg known: SportsEvent?): SportsEvent =
        known.filterNotNull().filter { it.id == opened.id }.fold(opened) { best, e -> if (e.updatedAtMs >= best.updatedAtMs) e else best }
}

/** What the details panel does once a Watch has been decided (pure). */
enum class SportsWatchNext {
    /** Allowed and tuned: open the full screen player (Back from it returns to this panel). */
    OPEN_PLAYER,

    /** Refused: say why, and show the channels as they are allowed now. Nothing plays. */
    SHOW_REFUSAL,

    /** Nothing happened (a Watch was already being checked, or the screen left): stay as is. */
    NOTHING;

    companion object {
        fun after(result: SportsWatchCheck?): SportsWatchNext = when (result) {
            is SportsWatchCheck.Allowed -> OPEN_PLAYER
            is SportsWatchCheck.Rejected -> SHOW_REFUSAL
            null -> NOTHING
        }
    }
}
