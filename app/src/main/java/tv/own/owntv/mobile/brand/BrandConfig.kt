package tv.own.owntv.mobile.brand

import tv.own.owntv.mobile.BuildConfig
import tv.own.owntv.mobile.R

enum class MobileBrand(
    val brandId: String,
    val displayName: String,
    val applicationId: String,
    val defaultEndpoint: String,
    val descriptionRes: Int,
) {
    GOAT("goat", "GoatTV", "tv.goat.tv.mobile", "https://bp-v2.net", R.string.brand_description_goat),
    ALLACCESS("allaccess", "AllAccess", "tv.allaccess.app.mobile", "https://bp-v2.net", R.string.brand_description_allaccess),
    FIVESTAR("fivestar", "5Star Ultra", "tv.fivestar.ultra.mobile", "https://bp-v2.net", R.string.brand_description_fivestar),
    SUPREME("supreme", "Supreme TV", "tv.supreme.app.mobile", "https://bp-v2.net", R.string.brand_description_supreme),
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
