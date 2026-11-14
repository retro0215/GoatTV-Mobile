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
    ): SportsWatchCheck? = run(event, chosen, offeredTo, canStart) { allowed ->
        tune(allowed.channel.id, LiveTuner.ZapList(title, allowed.zapChannels))
    }

    /**
     * Any of the panel's actions — Watch, Preview, Add to Multiview — on the same terms: [check] first,
     * [onAllowed] only when it allows the channel and the screen is still in front, one action at a
     * time (null for a second one while the first is being checked, or when the screen left).
     */
    suspend fun run(
        event: SportsEvent,
        chosen: VisibleSportsChannel,
        offeredTo: Long?,
        canStart: () -> Boolean,
        onAllowed: (SportsWatchCheck.Allowed) -> Unit,
    ): SportsWatchCheck? {
        if (!busy.compareAndSet(false, true)) return null
        try {
            val result = check(event, chosen, offeredTo)
            if (result is SportsWatchCheck.Allowed) {
                if (!canStart()) return null
                onAllowed(result)
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

/**
 * Who owns the stream a Sports Preview started (unit-tested). There is one tuner and one stream in the
 * app; a Preview is that stream shown inside the game's panel. This remembers which channel the panel
 * put there, so it can take it back — and only that: a stream someone else started (the mini player,
 * Live TV, a Watch already handed to the player) is never stopped from here.
 *
 *  - [started]: a Preview tuned [channelId]; the panel owns it.
 *  - [handedToPlayer]: Watch took the same stream full screen (no reopen); the player owns it now.
 *  - [release]: the panel closed, Sports was left, the app went to the background, Multiview is
 *    opening: stop the stream if it is still the one this panel started.
 */
class SportsPreviewOwner(
    private val playingChannelId: () -> Long?,
    private val stop: () -> Unit,
) {
    private val _owned = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    /** The channel the panel is previewing, or null. */
    val owned: kotlinx.coroutines.flow.StateFlow<Long?> = _owned

    fun started(channelId: Long) {
        _owned.value = channelId
    }

    fun handedToPlayer() {
        _owned.value = null
    }

    fun release() {
        val mine = _owned.value ?: return
        _owned.value = null
        if (playingChannelId() == mine) stop()
    }
}

/** What Add to Multiview does with a channel the access check allowed (pure). */
enum class SportsMultiviewAdd {
    /** Add it: a new tile at the end. Its stream opens when the grid is opened, under the grid's limits. */
    ADD,

    /** The fifth-tile performance warning first (the grid's own rule), then add. */
    WARN_FIRST,

    /** It is already a tile: nothing to add (the grid never holds a channel twice). */
    ALREADY_THERE,

    /** All tiles are taken: say so, add nothing. */
    FULL;

    companion object {
        fun decide(alreadyThere: Boolean, isFull: Boolean, shouldWarn: () -> Boolean): SportsMultiviewAdd = when {
            alreadyThere -> ALREADY_THERE
            isFull -> FULL
            shouldWarn() -> WARN_FIRST
            else -> ADD
        }
    }
}

/** Where the hub's panels lead: the full screen player, and the Multiview grid. */
class SportsHubNav(val openPlayer: () -> Unit, val openMultiview: () -> Unit)

/** The three things a Where to Watch row does. */
enum class SportsPanelAction { WATCH, PREVIEW, MULTIVIEW }

/** The action being decided right now, and for which row. */
data class SportsPendingAction(val localChannelId: Long, val action: SportsPanelAction)

/**
 * A Where to Watch row as shown (pure): the channel's name as Live TV shows it, its category underneath
 * and the 4K tag. A category that is blank or only repeats the name is left out.
 *
 * [number] — the playlist's own channel number, as the player shows it — is set only on rows that
 * would otherwise read exactly alike (same name, same category; seen on the Samsung with 1.0.10: two
 * "US: ESPN" feeds, both "USA | Sports"). It is never made up: a row whose playlist gives no number, or
 * the same number as its twin, stays as it is. Nothing else of the channel is shown.
 */
data class SportsChannelLabel(val name: String, val category: String?, val uhd: Boolean, val number: Int? = null) {
    companion object {
        fun of(channel: VisibleSportsChannel): SportsChannelLabel = SportsChannelLabel(
            name = channel.name,
            category = channel.category?.trim()?.takeIf { it.isNotEmpty() && !it.equals(channel.name.trim(), ignoreCase = true) },
            uhd = channel.uhd,
        )

        /** Labels for a game's rows, in their order, with the channel number only where it tells twins apart. */
        fun forRows(channels: List<VisibleSportsChannel>): List<SportsChannelLabel> {
            val plain = channels.map(::of)
            val looks = plain.map { it.name.lowercase() to it.category?.lowercase() }
            return plain.mapIndexed { i, label ->
                val twins = looks.indices.filter { looks[it] == looks[i] }
                if (twins.size < 2) return@mapIndexed label
                val number = channels[i].number ?: return@mapIndexed label
                // A number only helps if it differs from every twin's.
                if (twins.any { it != i && channels[it].number == number }) label else label.copy(number = number)
            }
        }
    }
}

/**
 * Bringing a Preview into view (pure). Seen on the Samsung with 1.0.10: started from a lower row, the
 * Preview appeared at the top of the details — scrolled out of sight — and in landscape the half-open
 * sheet showed little besides it. When a Preview starts (or moves to another channel) the details
 * scroll back to their top, where it plays, and in landscape the sheet opens all the way — also when
 * the phone is turned on its side mid-Preview. A Preview that carries on (the same channel: score,
 * clock and playback updates never call this) never scrolls, and a stopped one moves nothing.
 */
object SportsPreviewReveal {
    data class Reveal(val scrollToTop: Boolean, val expandSheet: Boolean)

    /**
     * Called when the previewing channel or the orientation changes. Seen on the Samsung with 1.0.11: a
     * Preview started upright stayed in a half-open sheet when the phone was turned on its side, cut
     * off at the bottom. A Preview playing in landscape now always has the sheet all the way open; only
     * a new Preview scrolls.
     */
    fun onChange(before: Long?, now: Long?, landscape: Boolean): Reveal? {
        if (now == null) return null
        val fresh = now != before
        if (!fresh && !landscape) return null
        return Reveal(scrollToTop = fresh, expandSheet = landscape)
    }
}
