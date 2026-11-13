package tv.own.owntv.mobile.sports.live

import androidx.compose.runtime.Immutable

/**
 * One Where to Watch entry as it will be shown: channel name, the network / category where useful and
 * a 4K/UHD tag. Deliberately carries NO implementation details (remote id, EPG id, confidence, match
 * reason, Sports channel id, stream URL). Ported from the GoatTV Android TV app (tag v4.6.1).
 *
 * Built only from [VisibleSportsChannel]s — channels that already passed identity AND the profile's
 * visibility rules ([SportsChannelAccess]) — so a row can never show a hidden or blocked channel.
 * Nothing shows or acts on the rows until the Where to Watch screen exists.
 */
@Immutable
data class SportsWatchRow(
    /** Local channel id: used only to mark / act on the row, never displayed. */
    val localChannelId: Long,
    val name: String,
    /** Broadcast network for this feed when the event lists it ("ESPN"); null when unknown. */
    val network: String?,
    val category: String?,
    val uhd: Boolean,
    /** The feed the user already picked for this event this session. */
    val selected: Boolean,
)

internal object SportsWatchPresentation {

    private val UHD = Regex("""\b(?:4k|uhd|2160p)\b""", RegexOption.IGNORE_CASE)

    fun isUhd(name: String): Boolean = UHD.containsMatchIn(name)

    /** Rows in backend rank order (resolution and filtering preserved it; never re-sorted here). */
    fun rows(event: SportsEvent, channels: List<VisibleSportsChannel>, selectedChannelId: Long?): List<SportsWatchRow> =
        channels.map { c ->
            val broadcast = c.networkKey?.let { key ->
                event.broadcasts.firstOrNull { it.networkKey.equals(key, ignoreCase = true) }?.name
            }
            SportsWatchRow(
                localChannelId = c.localChannelId,
                name = c.name,
                network = broadcast,
                category = c.category,
                uhd = c.uhd,
                selected = c.localChannelId == selectedChannelId,
            )
        }
}
