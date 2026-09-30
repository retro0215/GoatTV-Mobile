package tv.own.owntv.mobile.brand

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BrandSplashResourceTest {

    // Module-relative: Gradle runs unit tests from the app module directory, locally and on CI.
    private val projectRoot = File("src")
    private val brands = listOf("goat", "allaccess", "fivestar", "supreme")
    private val requiredStyles = listOf(
        "Theme.OwnTVMobile.Starting.Petrol",
        "Theme.OwnTVMobile.Starting.Sunflower",
        "Theme.OwnTVMobile.Starting.Cobalt",
        "Theme.OwnTVMobile.Starting.Tomato",
        "Theme.OwnTVMobile.Starting.Board",
        "Theme.OwnTVMobile.Starting.Eggshell",
        "Theme.OwnTVMobile.Starting.Olive",
        "Theme.OwnTVMobile.Starting.OliveCream",
    )

    @Test
    fun brandSplashDrawable_existsForEveryBrandedFlavor() {
        for (brand in brands) {
            val drawableFile = File(projectRoot, "$brand/res/drawable/brand_splash_icon.xml")
            assertTrue("Flavor $brand must contain brand_splash_icon.xml", drawableFile.exists())
            val content = drawableFile.readText()
            assertTrue("brand_splash_icon.xml for $brand must reference @drawable/ic_brand_logo", content.contains("@drawable/ic_brand_logo"))
        }
    }

    @Test
    fun brandThemeOverrides_coverAllLauncherIcons() {
        for (brand in brands) {
            val valuesTheme = File(projectRoot, "$brand/res/values/themes.xml")
            assertTrue("Flavor $brand must contain values/themes.xml", valuesTheme.exists())
            val themeContent = valuesTheme.readText()

            for (style in requiredStyles) {
                assertTrue(
                    "Flavor $brand values/themes.xml must override $style with brand_splash_icon",
                    themeContent.contains("""name="$style"""") && themeContent.contains("@drawable/brand_splash_icon"),
                )
            }
        }
    }

    @Test
    fun brandV31Themes_removeStockOwnTvBrandingImage() {
        for (brand in brands) {
            val v31Theme = File(projectRoot, "$brand/res/values-v31/themes.xml")
            assertTrue("Flavor $brand must contain values-v31/themes.xml", v31Theme.exists())
            val content = v31Theme.readText()

            assertFalse("Flavor $brand must not contain stock OwnTV splash name image", content.contains("owntv_splash_name"))
            assertTrue("Flavor $brand values-v31/themes.xml must set windowSplashScreenBrandingImage to @null", content.contains("""name="android:windowSplashScreenBrandingImage">@null"""))

            for (style in requiredStyles) {
                assertTrue("Flavor $brand values-v31/themes.xml must contain $style", content.contains("""name="$style""""))
            }
        }
    }

    @Test
    fun stockMainThemes_preserveStockOwnTvSplash() {
        val mainV31Theme = File(projectRoot, "main/res/values-v31/themes.xml")
        assertTrue("Stock main must contain values-v31/themes.xml", mainV31Theme.exists())
        val content = mainV31Theme.readText()

        assertTrue("Stock main must retain owntv_splash animated icons", content.contains("@drawable/owntv_splash_"))
        assertTrue("Stock main must retain owntv_splash_name branding image", content.contains("@drawable/owntv_splash_name_"))
    }

    @Test
    fun flavorAppNameResolution_matchesDisplayNames() {
        val expectedNames = mapOf(
            "goat" to "GoatTV",
            "allaccess" to "AllAccess",
            "fivestar" to "5Star Ultra",
            "supreme" to "Supreme TV",
        )

        for ((brand, expectedName) in expectedNames) {
            val stringsFile = File(projectRoot, "$brand/res/values/strings.xml")
            assertTrue("Flavor $brand must contain values/strings.xml", stringsFile.exists())
            val content = stringsFile.readText()
            assertTrue("Flavor $brand app_name must be $expectedName", content.contains("""<string name="app_name">$expectedName</string>"""))
        }
    }
}
