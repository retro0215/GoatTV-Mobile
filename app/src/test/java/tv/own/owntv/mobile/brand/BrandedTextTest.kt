package tv.own.owntv.mobile.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Settings → App named the upstream app in every brand; each brand now reads its own name. */
class BrandedTextTest {

    // Core 1.0.67's own wording, English and three of its translations.
    private val coreStrings = listOf(
        "Color of the OwnTV icon and logo",
        "What OwnTV opens when it starts",
        "Farbe des OwnTV-Symbols und -Logos",
        "OwnTVのアイコンとロゴの色",
        "Какой экран OwnTV открывает при запуске",
    )

    @Test
    fun everyBrand_readsItsOwnName_inEveryLanguage() {
        for (brand in MobileBrand.entries) {
            for (text in coreStrings) {
                val shown = BrandedText.rebrand(text, brand.displayName)
                assertFalse("${brand.name}: $shown", shown.contains(BrandedText.UPSTREAM_BRAND))
                assertEquals(text.replace("OwnTV", brand.displayName), shown)
            }
        }
    }

    @Test
    fun theTranslationAroundTheName_isUntouched() {
        assertEquals("Color of the GoatTV icon and logo", BrandedText.rebrand(coreStrings[0], "GoatTV"))
        assertEquals("Was Supreme TV beim Start öffnet", BrandedText.rebrand("Was OwnTV beim Start öffnet", "Supreme TV"))
        assertEquals("Color of the 5Star Ultra icon and logo", BrandedText.rebrand(coreStrings[0], "5Star Ultra"))
    }

    @Test
    fun textWithoutTheUpstreamName_isReturnedAsIs() {
        assertEquals("App display language", BrandedText.rebrand("App display language", "AllAccess"))
        assertEquals(coreStrings[0], BrandedText.rebrand(coreStrings[0], BrandedText.UPSTREAM_BRAND))
    }
}
