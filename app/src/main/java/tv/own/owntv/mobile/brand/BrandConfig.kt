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
