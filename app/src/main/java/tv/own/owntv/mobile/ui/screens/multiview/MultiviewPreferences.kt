package tv.own.owntv.mobile.ui.screens.multiview

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the grid marks the tile with the sound — AerioTV's three styles. */
enum class AudioFocusStyle(val key: String) {
    /** A speaker in the middle of the tile, fading with the controls. The default. */
    CenterIcon("centerIcon"),

    /** A quiet grey outline, always there. */
    GrayOutline("grayPersistent"),

    /** An accent outline that appears when the sound moves and fades after five seconds. */
    AccentFading("themeFading");

    companion object {
        fun from(key: String?): AudioFocusStyle = entries.firstOrNull { it.key == key } ?: CenterIcon
    }
}

/**
 * The phone's Multiview settings, with AerioTV's defaults: centre-icon indicator, padding on, square
 * corners, the default layout. Kept here rather than in core's settings because none of them exists
 * on the television, and each app's own preferences file keeps each brand's choices its own.
 *
 * The performance warning's "shown recently" throttle is deliberately in memory, as AerioTV has it:
 * a restart, or two hours, brings the warning back; "Don't show again" is what persists.
 */
class MultiviewPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _audioFocusStyle = MutableStateFlow(AudioFocusStyle.from(prefs.getString(KEY_STYLE, null)))
    val audioFocusStyle: StateFlow<AudioFocusStyle> = _audioFocusStyle.asStateFlow()

    private val _tilePadding = MutableStateFlow(prefs.getBoolean(KEY_PADDING, true))
    val tilePadding: StateFlow<Boolean> = _tilePadding.asStateFlow()

    private val _roundedCorners = MutableStateFlow(prefs.getBoolean(KEY_ROUNDED, false))
    val roundedCorners: StateFlow<Boolean> = _roundedCorners.asStateFlow()

    private val _layoutMode = MutableStateFlow(MultiviewLayoutMode.from(prefs.getString(KEY_LAYOUT, null)))
    val layoutMode: StateFlow<MultiviewLayoutMode> = _layoutMode.asStateFlow()

    private val _warningSuppressed = MutableStateFlow(prefs.getBoolean(KEY_WARNING_SUPPRESSED, false))
    val warningSuppressed: StateFlow<Boolean> = _warningSuppressed.asStateFlow()

    private var warningLastShownAt: Long? = null

    fun setAudioFocusStyle(value: AudioFocusStyle) {
        _audioFocusStyle.value = value
        prefs.edit().putString(KEY_STYLE, value.key).apply()
    }

    fun setTilePadding(value: Boolean) {
        _tilePadding.value = value
        prefs.edit().putBoolean(KEY_PADDING, value).apply()
    }

    fun setRoundedCorners(value: Boolean) {
        _roundedCorners.value = value
        prefs.edit().putBoolean(KEY_ROUNDED, value).apply()
    }

    fun setLayoutMode(value: MultiviewLayoutMode) {
        _layoutMode.value = value
        prefs.edit().putString(KEY_LAYOUT, value.key).apply()
    }

    fun suppressWarning() {
        _warningSuppressed.value = true
        prefs.edit().putBoolean(KEY_WARNING_SUPPRESSED, true).apply()
    }

    /** Whether adding one more tile to a grid of [currentSize] should ask first, and if so notes it. */
    fun shouldWarnBeforeAdding(currentSize: Int, now: Long = System.currentTimeMillis()): Boolean {
        if (!needsWarning(currentSize, _warningSuppressed.value, warningLastShownAt, now)) return false
        warningLastShownAt = now
        return true
    }

    private companion object {
        const val FILE = "multiview"
        const val KEY_STYLE = "audio_focus_style"
        const val KEY_PADDING = "tile_padding"
        const val KEY_ROUNDED = "tile_corners_rounded"
        const val KEY_LAYOUT = "layout_mode"
        const val KEY_WARNING_SUPPRESSED = "perf_warning_suppressed"
    }
}

/** How long a shown warning keeps quiet (AerioTV: two hours). */
internal const val WARNING_THROTTLE_MS = 2 * 60 * 60 * 1000L

/**
 * The fifth tile asks first: adding to a grid already at [MultiviewModel.SOFT_LIMIT], unless the user
 * said not to, or was asked within [WARNING_THROTTLE_MS]. There is no sixth tile to warn about.
 */
internal fun needsWarning(currentSize: Int, suppressed: Boolean, lastShownAt: Long?, now: Long): Boolean =
    currentSize >= MultiviewModel.SOFT_LIMIT &&
        currentSize < MultiviewModel.MAX_TILES &&
        !suppressed &&
        (lastShownAt == null || now - lastShownAt >= WARNING_THROTTLE_MS)
