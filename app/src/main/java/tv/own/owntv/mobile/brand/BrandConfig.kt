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

    private fun resolveCurrentBrand(): MobileBrand {
        val pkg = BuildConfig.APPLICATION_ID
        return MobileBrand.entries.firstOrNull { it.applicationId == pkg } ?: MobileBrand.GOAT
    }
}
