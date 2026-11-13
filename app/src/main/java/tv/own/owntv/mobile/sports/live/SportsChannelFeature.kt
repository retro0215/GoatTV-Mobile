package tv.own.owntv.mobile.sports.live

import tv.own.owntv.mobile.BuildConfig

/**
 * The brand boundary for Sports channel matches, ported from the GoatTV Android TV app (tag v4.6.1).
 *
 * The build's brand (`BuildConfig.SPORTS_CHANNEL_BRAND`, set per flavour: goat, allaccess, fivestar,
 * supreme) is the ONLY identifier sent to the Sports API (`?brand=`). Without one the app never asks
 * for channel data, even if a response carried channels — and every channel the backend returns is
 * still to be verified against the active playlist on this phone before it is offered.
 */
object SportsChannelFeature {
    // Declared first: object properties initialize in order, and BRAND below uses it.
    private val BRAND_ID = Regex("^[a-z0-9-]{1,32}$")

    /** The `?brand=` value for this build, or null when this build has no event channels. */
    val BRAND: String? = brandOrNull(BuildConfig.SPORTS_CHANNEL_BRAND)

    val ENABLED: Boolean get() = BRAND != null

    /** A configured brand id, or null when empty / not a plain brand id. */
    internal fun brandOrNull(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { BRAND_ID.matches(it) }
}
