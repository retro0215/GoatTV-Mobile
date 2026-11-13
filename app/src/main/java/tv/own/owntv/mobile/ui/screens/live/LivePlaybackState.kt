package tv.own.owntv.mobile.ui.screens.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which channel [LiveTuner] has actually opened a stream for, kept apart from which channel is
 * *selected* (`LiveTuner.channel`).
 *
 * The two used to be read as one. Leaving Live TV for another tab, or opening Multiview, stops the
 * stream but keeps the selection — the name, the logo and the highlighted tile stay — and every
 * check downstream asked only "is a channel selected?". So the preview came back to a stopped engine
 * and stayed black, nothing re-opened it, tapping the highlighted tile opened a full screen player
 * that was black too, and a requested mini player could appear over a stream that no longer existed.
 *
 * [openedId] is the channel a stream was asked for — set the moment a tune starts, so a tune still
 * connecting counts as active and is never asked for a second time — and null once playback stops.
 */
class LivePlaybackState {

    private val _active = MutableStateFlow(false)

    /** True from the moment a stream is asked for until playback stops; the selection may outlive it. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** The channel a stream was opened (or is opening) for; null after playback stopped. */
    var openedId: Long? = null
        set(value) {
            field = value
            _active.value = value != null
        }

    /**
     * Whether a request for [channelId] has to open a stream. Not when that channel is already open
     * and still has its stream — re-opening the channel on screen tore it down and rebuilt it.
     */
    fun shouldOpen(channelId: Long, hasStream: Boolean): Boolean = !(openedId == channelId && hasStream)

    /**
     * The selected channel to start again, or null: only when something is selected and nothing has
     * been opened since playback stopped. A stream that is playing, or still connecting, is left alone,
     * so asking twice never costs the provider a second connection.
     */
    fun resumeTarget(selectedId: Long?): Long? = if (openedId == null) selectedId else null

    /** Playback stopped; the selection is the caller's to keep or clear. */
    fun stopped() {
        openedId = null
    }
}

/**
 * The two decisions about the Live TV preview's stream that used to be made by accident.
 *
 * Pure, so they can be tested without a player: the composables only feed them what they can see.
 */
object LivePreviewLifecycle {

    /**
     * Whether Live TV, now in front, should start its selected channel again.
     *
     * - [started]: the screen is at least STARTED. Nothing starts behind the lock screen or while the
     *   app is in the background — background playback is MainActivity's to manage, and it pauses
     *   rather than stops, which leaves the stream open and this answer false anyway.
     * - [multiviewRunning]: the grid owns the decoders until it has suspended. It suspends in its own
     *   dispose, which can land after this screen has already appeared on the way back from it;
     *   starting first would ask for a fifth decoder the phone does not have.
     * - [selected] / [playbackActive]: only a selected channel whose playback has stopped. A stream
     *   that is playing or connecting is never opened twice; nothing selected is the first-channel
     *   auto-tune's business, not this.
     */
    fun shouldResume(
        started: Boolean,
        multiviewRunning: Boolean,
        selected: Boolean,
        playbackActive: Boolean,
    ): Boolean = started && !multiviewRunning && selected && !playbackActive

    /**
     * Nothing for a mini player to show: no film, and no live channel that is actually playing. A
     * channel can stay selected after its stream stopped, and that is not something playing.
     */
    fun nothingPlaying(channelSelected: Boolean, liveActive: Boolean, filmPlaying: Boolean): Boolean =
        !(channelSelected && liveActive) && !filmPlaying

    /** What Back does in the full screen player. */
    enum class PlayerBack {
        /** End the stream — Back means "finished watching" anywhere nothing would show it. */
        STOP,

        /** Leave it playing: Back lands on the Live TV preview, which shows this very stream. */
        KEEP,

        /** As [KEEP], but a replay or a rewind goes back to the live edge — the preview is live TV. */
        KEEP_AT_LIVE_EDGE,
    }

    /**
     * Back from the full screen player.
     *
     * It stopped the stream every time and cleared the channel, so the preview it returned to started
     * the category's *first* channel instead of the one being watched — a reconnection to land
     * somewhere else. When Back lands on the Live TV preview the stream simply stays: same channel,
     * same connection, the picture moves from one surface to the other exactly as the mini player
     * button already does.
     *
     * Everywhere else it still stops, and so does a film, a cast (the picture is on the television,
     * the preview would be an empty box) and a channel that is no longer selected.
     */
    fun playerBack(
        liveChannelSelected: Boolean,
        filmPlaying: Boolean,
        casting: Boolean,
        backLandsOnLivePreview: Boolean,
        offLiveEdge: Boolean,
    ): PlayerBack = when {
        !liveChannelSelected || filmPlaying || casting || !backLandsOnLivePreview -> PlayerBack.STOP
        offLiveEdge -> PlayerBack.KEEP_AT_LIVE_EDGE
        else -> PlayerBack.KEEP
    }
}
