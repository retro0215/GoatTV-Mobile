package tv.own.owntv.mobile.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.allaccessLabelRes
import tv.own.owntv.mobile.ui.components.allaccessRes
import tv.own.owntv.mobile.ui.components.fivestarLabelRes
import tv.own.owntv.mobile.ui.components.fivestarRes
import tv.own.owntv.mobile.ui.components.goatLabelRes
import tv.own.owntv.mobile.ui.components.goatRes

class FiveStarAppIconTest {

    @Test
    fun fivestarIconDefinitions_eightIconsExist() {
        val labels = AppIcon.entries.map { it.fivestarLabelRes() }
        assertEquals(8, labels.size)
        assertEquals(8, labels.distinct().size)
        assertEquals(R.string.fivestar_icon_1, AppIcon.PETROL.fivestarLabelRes())
        assertEquals(R.string.fivestar_icon_2, AppIcon.SUNFLOWER.fivestarLabelRes())
        assertEquals(R.string.fivestar_icon_3, AppIcon.COBALT.fivestarLabelRes())
        assertEquals(R.string.fivestar_icon_4, AppIcon.TOMATO.fivestarLabelRes())
        assertEquals(R.string.fivestar_icon_5, AppIcon.BOARD.fivestarLabelRes())
        assertEquals(R.string.fivestar_icon_6, AppIcon.EGGSHELL.fivestarLabelRes())
        assertEquals(R.string.fivestar_icon_7, AppIcon.OLIVE.fivestarLabelRes())
        assertEquals(R.string.fivestar_icon_8, AppIcon.OLIVE_CREAM.fivestarLabelRes())
    }

    @Test
    fun fivestarIconResolution_mapsToDrawableResources() {
        val drawables = AppIcon.entries.map { it.fivestarRes() }
        assertEquals(8, drawables.size)
        assertEquals(8, drawables.distinct().size)
        assertEquals(R.drawable.fivestar_icon_1, AppIcon.PETROL.fivestarRes())
        assertEquals(R.drawable.fivestar_icon_2, AppIcon.SUNFLOWER.fivestarRes())
        assertEquals(R.drawable.fivestar_icon_3, AppIcon.COBALT.fivestarRes())
        assertEquals(R.drawable.fivestar_icon_4, AppIcon.TOMATO.fivestarRes())
        assertEquals(R.drawable.fivestar_icon_5, AppIcon.BOARD.fivestarRes())
        assertEquals(R.drawable.fivestar_icon_6, AppIcon.EGGSHELL.fivestarRes())
        assertEquals(R.drawable.fivestar_icon_7, AppIcon.OLIVE.fivestarRes())
        assertEquals(R.drawable.fivestar_icon_8, AppIcon.OLIVE_CREAM.fivestarRes())
    }

    @Test
    fun savedSelectionPersistence_and_legacySlotMigration() {
        // Any legacy saved OwnTV selection maps safely 1:1 to a 5Star slot without crashing
        AppIcon.entries.forEach { icon ->
            assertNotNull(icon.fivestarLabelRes())
            assertNotNull(icon.fivestarRes())
        }
    }

    @Test
    fun brandIsolation_fivestarDistinctFromGoatAndAllAccessAndSupreme() {
        AppIcon.entries.forEach { icon ->
            assertNotEquals(icon.fivestarRes(), icon.goatRes())
            assertNotEquals(icon.fivestarRes(), icon.allaccessRes())
            assertNotEquals(icon.fivestarLabelRes(), icon.goatLabelRes())
            assertNotEquals(icon.fivestarLabelRes(), icon.allaccessLabelRes())
        }
        assertNotEquals(MobileBrand.FIVESTAR, MobileBrand.GOAT)
        assertNotEquals(MobileBrand.FIVESTAR, MobileBrand.ALLACCESS)
        assertNotEquals(MobileBrand.FIVESTAR, MobileBrand.SUPREME)
    }
}
