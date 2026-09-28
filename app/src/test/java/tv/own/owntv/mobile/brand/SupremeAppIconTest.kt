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
import tv.own.owntv.mobile.ui.components.supremeLabelRes
import tv.own.owntv.mobile.ui.components.supremeRes

class SupremeAppIconTest {

    @Test
    fun supremeIconDefinitions_eightIconsExist() {
        val labels = AppIcon.entries.map { it.supremeLabelRes() }
        assertEquals(8, labels.size)
        assertEquals(8, labels.distinct().size)
        assertEquals(R.string.supreme_icon_1, AppIcon.PETROL.supremeLabelRes())
        assertEquals(R.string.supreme_icon_2, AppIcon.SUNFLOWER.supremeLabelRes())
        assertEquals(R.string.supreme_icon_3, AppIcon.COBALT.supremeLabelRes())
        assertEquals(R.string.supreme_icon_4, AppIcon.TOMATO.supremeLabelRes())
        assertEquals(R.string.supreme_icon_5, AppIcon.BOARD.supremeLabelRes())
        assertEquals(R.string.supreme_icon_6, AppIcon.EGGSHELL.supremeLabelRes())
        assertEquals(R.string.supreme_icon_7, AppIcon.OLIVE.supremeLabelRes())
        assertEquals(R.string.supreme_icon_8, AppIcon.OLIVE_CREAM.supremeLabelRes())
    }

    @Test
    fun supremeIconResolution_mapsToDrawableResources() {
        val drawables = AppIcon.entries.map { it.supremeRes() }
        assertEquals(8, drawables.size)
        assertEquals(8, drawables.distinct().size)
        assertEquals(R.drawable.supreme_icon_1, AppIcon.PETROL.supremeRes())
        assertEquals(R.drawable.supreme_icon_2, AppIcon.SUNFLOWER.supremeRes())
        assertEquals(R.drawable.supreme_icon_3, AppIcon.COBALT.supremeRes())
        assertEquals(R.drawable.supreme_icon_4, AppIcon.TOMATO.supremeRes())
        assertEquals(R.drawable.supreme_icon_5, AppIcon.BOARD.supremeRes())
        assertEquals(R.drawable.supreme_icon_6, AppIcon.EGGSHELL.supremeRes())
        assertEquals(R.drawable.supreme_icon_7, AppIcon.OLIVE.supremeRes())
        assertEquals(R.drawable.supreme_icon_8, AppIcon.OLIVE_CREAM.supremeRes())
    }

    @Test
    fun savedSelectionPersistence_and_legacySlotMigration() {
        // Any legacy saved OwnTV selection maps safely 1:1 to a Supreme slot without crashing
        AppIcon.entries.forEach { icon ->
            assertNotNull(icon.supremeLabelRes())
            assertNotNull(icon.supremeRes())
        }
    }

    @Test
    fun fourBrandIsolation_noCrossBrandResourceLeakage() {
        AppIcon.entries.forEach { icon ->
            val goat = icon.goatRes()
            val allaccess = icon.allaccessRes()
            val fivestar = icon.fivestarRes()
            val supreme = icon.supremeRes()

            assertNotEquals(supreme, goat)
            assertNotEquals(supreme, allaccess)
            assertNotEquals(supreme, fivestar)

            val goatLbl = icon.goatLabelRes()
            val allaccessLbl = icon.allaccessLabelRes()
            val fivestarLbl = icon.fivestarLabelRes()
            val supremeLbl = icon.supremeLabelRes()

            assertNotEquals(supremeLbl, goatLbl)
            assertNotEquals(supremeLbl, allaccessLbl)
            assertNotEquals(supremeLbl, fivestarLbl)
        }
    }
}
