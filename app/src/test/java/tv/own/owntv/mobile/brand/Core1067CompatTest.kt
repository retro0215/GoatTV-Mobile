package tv.own.owntv.mobile.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.theme.AppFontFamily
import tv.own.owntv.mobile.ui.components.BrandIconChoices
import tv.own.owntv.mobile.ui.components.allaccessLabelRes
import tv.own.owntv.mobile.ui.components.allaccessRes
import tv.own.owntv.mobile.ui.components.fivestarLabelRes
import tv.own.owntv.mobile.ui.components.fivestarRes
import tv.own.owntv.mobile.ui.components.goatLabelRes
import tv.own.owntv.mobile.ui.components.goatRes
import tv.own.owntv.mobile.ui.components.supremeLabelRes
import tv.own.owntv.mobile.ui.components.supremeRes
import tv.own.owntv.mobile.ui.screens.home.shownOnMobile
import tv.own.owntv.mobile.ui.theme.MobileFontChoices
import tv.own.owntv.mobile.ui.theme.onMobile
import java.io.File

/** What core 1.0.67 added that the current Mobile UI deliberately does not adopt. */
class Core1067CompatTest {

    @Test
    fun pixelIcon_isNotOffered_andDrawsAsEachBrandsEggshellSlot() {
        assertFalse(AppIcon.PIXEL in BrandIconChoices)
        assertEquals(8, BrandIconChoices.size)
        assertEquals(AppIcon.EGGSHELL.goatRes(), AppIcon.PIXEL.goatRes())
        assertEquals(AppIcon.EGGSHELL.goatLabelRes(), AppIcon.PIXEL.goatLabelRes())
        assertEquals(AppIcon.EGGSHELL.allaccessRes(), AppIcon.PIXEL.allaccessRes())
        assertEquals(AppIcon.EGGSHELL.allaccessLabelRes(), AppIcon.PIXEL.allaccessLabelRes())
        assertEquals(AppIcon.EGGSHELL.fivestarRes(), AppIcon.PIXEL.fivestarRes())
        assertEquals(AppIcon.EGGSHELL.fivestarLabelRes(), AppIcon.PIXEL.fivestarLabelRes())
        assertEquals(AppIcon.EGGSHELL.supremeRes(), AppIcon.PIXEL.supremeRes())
        assertEquals(AppIcon.EGGSHELL.supremeLabelRes(), AppIcon.PIXEL.supremeLabelRes())
    }

    @Test
    fun everyCoreIcon_hasALauncherComponent() {
        // AppIconSwitcher enables/disables MainActivity + suffix for EVERY AppIcon; a missing
        // component would make PackageManager throw on the first icon change.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        AppIcon.entries.forEach { icon ->
            assertTrue(
                "Manifest must declare .MainActivity${icon.activitySuffix}",
                manifest.contains("android:name=\".MainActivity${icon.activitySuffix}\""),
            )
        }
    }

    @Test
    fun favouriteFilmAndSeriesRows_areNotShownOnMobile() {
        assertFalse(HomeRow.FAVORITE_MOVIES.shownOnMobile)
        assertFalse(HomeRow.FAVORITE_SERIES.shownOnMobile)
        HomeRow.entries.filter { it != HomeRow.FAVORITE_MOVIES && it != HomeRow.FAVORITE_SERIES }
            .forEach { assertTrue(it.shownOnMobile) }
    }

    @Test
    fun plusJakartaSans_drawsAndSelectsAsSystemSans() {
        assertEquals(AppFontFamily.SYSTEM_SANS, AppFontFamily.PLUS_JAKARTA_SANS.onMobile)
        assertFalse(AppFontFamily.PLUS_JAKARTA_SANS in MobileFontChoices)
        AppFontFamily.entries.filter { it != AppFontFamily.PLUS_JAKARTA_SANS }
            .forEach { assertEquals(it, it.onMobile) }
    }
}
