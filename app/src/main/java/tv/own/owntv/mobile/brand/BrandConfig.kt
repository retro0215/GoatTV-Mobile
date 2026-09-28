package tv.own.owntv.mobile.brand

enum class MobileBrand(
    val brandId: String,
    val displayName: String,
    val applicationId: String,
    val defaultEndpoint: String,
) {
    GOAT("goat", "GoatTV", "tv.goat.tv.mobile", "https://bp-v2.net"),
    ALLACCESS("allaccess", "AllAccess", "tv.allaccess.app.mobile", "https://bp-v2.net"),
    FIVESTAR("fivestar", "5Star Ultra", "tv.fivestar.ultra.mobile", "https://bp-v2.net"),
    SUPREME("supreme", "Supreme TV", "tv.supreme.app.mobile", "https://bp-v2.net"),
}

object BrandConfig {
    val currentBrand: MobileBrand = resolveCurrentBrand()

    val serverEndpoint: String get() = currentBrand.defaultEndpoint
    val brandName: String get() = currentBrand.displayName
    val brandId: String get() = currentBrand.brandId
    val isBranded: Boolean = true

    private fun resolveCurrentBrand(): MobileBrand {
        val pkg = tv.own.owntv.mobile.BuildConfig.APPLICATION_ID
        return MobileBrand.entries.firstOrNull { it.applicationId == pkg } ?: MobileBrand.GOAT
    }
}

