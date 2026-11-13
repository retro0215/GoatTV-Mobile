package tv.own.owntv.mobile.sports.live

import androidx.compose.runtime.Immutable

/**
 * One Where to Watch entry as it will be shown: channel name, the network / category where useful and
 * a 4K/UHD tag. Deliberately carries NO implementation details (remote id, EPG id, confidence, match
 * reason, Sports channel id). Ported from the GoatTV Android TV app (tag v4.6.1).
 *
 * Phase 2A only shapes the rows; nothing shows or acts on them until the adult and hidden-channel rules
 * are in place (Phase 2B) and the Where to Watch screen exists.
 */
@Immutable
data class SportsWatchRow(
    /** Local channel id: used only to act on the row, never displayed. */
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

    /** Rows in backend rank order (the resolver preserved it; never re-sorted here). */
    fun rows(event: SportsEvent, channels: List<ResolvedSportsChannel>, selectedChannelId: Long?): List<SportsWatchRow> =
        channels.map { r ->
            val broadcast = r.ref.networkKey?.let { key ->
                event.broadcasts.firstOrNull { it.networkKey.equals(key, ignoreCase = true) }?.name
            }
            SportsWatchRow(
                localChannelId = r.channel.id,
                name = r.channel.name,
                network = broadcast,
                category = r.ref.category?.takeIf { it.isNotBlank() },
                uhd = isUhd(r.channel.name) || isUhd(r.ref.name),
                selected = r.channel.id == selectedChannelId,
            )
        }
}
