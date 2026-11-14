package tv.own.owntv.mobile.brand

import tv.own.owntv.mobile.BuildConfig
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.update.MobileReleaseTrack
import tv.own.owntv.mobile.weather.TemperatureUnit

enum class MobileBrand(
    val brandId: String,
    val displayName: String,
    val applicationId: String,
    val defaultEndpoint: String,
    val descriptionRes: Int,
    /** This brand's own releases in the shared GoatTV-Mobile repository — never another brand's. */
    val releaseTrack: MobileReleaseTrack,
    /** Every reseller app shows weather in Fahrenheit. */
    val temperatureUnit: TemperatureUnit = TemperatureUnit.FAHRENHEIT,
) {
    GOAT(
        "goat", "GoatTV", "tv.goat.tv.mobile", "https://bp-v2.net", R.string.brand_description_goat,
        MobileReleaseTrack(tagPrefix = "goattv-mobile-v", assetBaseName = "GoatTV-Mobile"),
    ),
    ALLACCESS(
        "allaccess", "AllAccess", "tv.allaccess.app.mobile", "https://bp-v2.net", R.string.brand_description_allaccess,
        MobileReleaseTrack(tagPrefix = "allaccess-mobile-v", assetBaseName = "AllAccess-Mobile"),
    ),
    FIVESTAR(
        "fivestar", "5Star Ultra", "tv.fivestar.ultra.mobile", "https://bp-v2.net", R.string.brand_description_fivestar,
        MobileReleaseTrack(tagPrefix = "5star-ultra-mobile-v", assetBaseName = "5Star-Ultra-Mobile"),
    ),
    SUPREME(
        "supreme", "Supreme TV", "tv.supreme.app.mobile", "https://bp-v2.net", R.string.brand_description_supreme,
        MobileReleaseTrack(tagPrefix = "supreme-tv-mobile-v", assetBaseName = "Supreme-TV-Mobile"),
    ),
}

object BrandConfig {
    val currentBrand: MobileBrand = resolveCurrentBrand()

    val serverEndpoint: String get() = currentBrand.defaultEndpoint
    val brandName: String get() = currentBrand.displayName
    val brandId: String get() = currentBrand.brandId
    val brandDescriptionRes: Int get() = currentBrand.descriptionRes
    val isBranded: Boolean = true

    /**
     * By the brand flavor, not the package: a debug build is `<package>.debug`, and matching on the
     * package sent every debug brand to GoatTV. A flavor with no brand here fails at startup, not as a
     * silent GoatTV.
     */
    private fun resolveCurrentBrand(): MobileBrand =
        MobileBrand.entries.first { it.brandId == BuildConfig.FLAVOR_brand }
}

/**
 * Core's strings name the upstream app — "Color of the OwnTV icon and logo", "What OwnTV opens when
 * it starts" — in every one of its translations, and a reseller build showed them as written.
 *
 * Every translation keeps the brand as the literal word, so it is swapped for this brand's name at
 * display time rather than copied into new strings: the thirty translations stay exactly as core
 * ships them, and each of the four brands reads its own name.
 */
object BrandedText {
    /** The upstream brand as core's strings spell it. */
    const val UPSTREAM_BRAND = "OwnTV"

    fun rebrand(text: String, brandName: String = BrandConfig.brandName): String =
        if (brandName == UPSTREAM_BRAND) text else text.replace(UPSTREAM_BRAND, brandName)
}

/** A core string with the upstream brand name replaced by this brand's — see [BrandedText]. */
@androidx.compose.runtime.Composable
fun brandedStringResource(@androidx.annotation.StringRes id: Int): String =
    BrandedText.rebrand(androidx.compose.ui.res.stringResource(id))
