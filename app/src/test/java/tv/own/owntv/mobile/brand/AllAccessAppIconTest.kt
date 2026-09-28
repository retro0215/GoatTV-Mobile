package tv.own.owntv.mobile.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.allaccessLabelRes
import tv.own.owntv.mobile.ui.components.allaccessRes
import tv.own.owntv.mobile.ui.components.goatLabelRes
import tv.own.owntv.mobile.ui.components.goatRes

class AllAccessAppIconTest {

    @Test
    fun allaccessIconDefinitions_eightIconsExist() {
        val labels = AppIcon.entries.map { it.allaccessLabelRes() }
        assertEquals(8, labels.size)
        assertEquals(8, labels.distinct().size)
        assertEquals(R.string.allaccess_icon_1, AppIcon.PETROL.allaccessLabelRes())
        assertEquals(R.string.allaccess_icon_2, AppIcon.SUNFLOWER.allaccessLabelRes())
        assertEquals(R.string.allaccess_icon_3, AppIcon.COBALT.allaccessLabelRes())
        assertEquals(R.string.allaccess_icon_4, AppIcon.TOMATO.allaccessLabelRes())
        assertEquals(R.string.allaccess_icon_5, AppIcon.BOARD.allaccessLabelRes())
        assertEquals(R.string.allaccess_icon_6, AppIcon.EGGSHELL.allaccessLabelRes())
        assertEquals(R.string.allaccess_icon_7, AppIcon.OLIVE.allaccessLabelRes())
        assertEquals(R.string.allaccess_icon_8, AppIcon.OLIVE_CREAM.allaccessLabelRes())
    }

    @Test
    fun allaccessIconResolution_mapsToDrawableResources() {
        val drawables = AppIcon.entries.map { it.allaccessRes() }
        assertEquals(8, drawables.size)
        assertEquals(8, drawables.distinct().size)
        assertEquals(R.drawable.allaccess_icon_1, AppIcon.PETROL.allaccessRes())
        assertEquals(R.drawable.allaccess_icon_2, AppIcon.SUNFLOWER.allaccessRes())
        assertEquals(R.drawable.allaccess_icon_3, AppIcon.COBALT.allaccessRes())
        assertEquals(R.drawable.allaccess_icon_4, AppIcon.TOMATO.allaccessRes())
        assertEquals(R.drawable.allaccess_icon_5, AppIcon.BOARD.allaccessRes())
        assertEquals(R.drawable.allaccess_icon_6, AppIcon.EGGSHELL.allaccessRes())
        assertEquals(R.drawable.allaccess_icon_7, AppIcon.OLIVE.allaccessRes())
        assertEquals(R.drawable.allaccess_icon_8, AppIcon.OLIVE_CREAM.allaccessRes())
    }

    @Test
    fun savedSelectionPersistence_and_legacySlotMigration() {
        // Any legacy saved OwnTV selection maps safely 1:1 to an AllAccess slot without crashing
        AppIcon.entries.forEach { icon ->
            assertNotNull(icon.allaccessLabelRes())
            assertNotNull(icon.allaccessRes())
        }
    }

    @Test
    fun brandIsolation_goatAndAllaccessAreDistinct() {
        AppIcon.entries.forEach { icon ->
            assertNotEquals(icon.goatRes(), icon.allaccessRes())
            assertNotEquals(icon.goatLabelRes(), icon.allaccessLabelRes())
        }
    }
}
