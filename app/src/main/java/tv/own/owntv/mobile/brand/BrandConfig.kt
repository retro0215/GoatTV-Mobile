package tv.own.owntv.mobile.brand

enum class MobileBrand(
    val brandId: String,
    val displayName: String,
    val applicationId: String,
) {
    GOAT("goat", "GoatTV", "tv.goat.tv.mobile"),
    ALLACCESS("allaccess", "AllAccess", "tv.allaccess.app.mobile"),
    FIVESTAR("fivestar", "5Star Ultra", "tv.fivestar.ultra.mobile"),
    SUPREME("supreme", "Supreme TV", "tv.supreme.app.mobile"),
}

object BrandConfig {
    val currentBrand: MobileBrand = resolveCurrentBrand()

    private fun resolveCurrentBrand(): MobileBrand {
        val pkg = tv.own.owntv.mobile.BuildConfig.APPLICATION_ID
        return MobileBrand.entries.firstOrNull { it.applicationId == pkg } ?: MobileBrand.GOAT
    }
}
