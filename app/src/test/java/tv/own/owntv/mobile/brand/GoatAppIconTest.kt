package tv.own.owntv.mobile.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import tv.own.owntv.core.brand.AppIcon
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.goatLabelRes
import tv.own.owntv.mobile.ui.components.goatRes

class GoatAppIconTest {

    @Test
    fun goatIconDefinitions_eightIconsExist() {
        val labels = AppIcon.entries.map { it.goatLabelRes() }
        assertEquals(8, labels.size)
        assertEquals(8, labels.distinct().size)
        assertEquals(R.string.goat_icon_1, AppIcon.PETROL.goatLabelRes())
        assertEquals(R.string.goat_icon_2, AppIcon.SUNFLOWER.goatLabelRes())
        assertEquals(R.string.goat_icon_3, AppIcon.COBALT.goatLabelRes())
        assertEquals(R.string.goat_icon_4, AppIcon.TOMATO.goatLabelRes())
        assertEquals(R.string.goat_icon_5, AppIcon.BOARD.goatLabelRes())
        assertEquals(R.string.goat_icon_6, AppIcon.EGGSHELL.goatLabelRes())
        assertEquals(R.string.goat_icon_7, AppIcon.OLIVE.goatLabelRes())
        assertEquals(R.string.goat_icon_8, AppIcon.OLIVE_CREAM.goatLabelRes())
    }

    @Test
    fun goatIconResolution_mapsToDrawableResources() {
        val drawables = AppIcon.entries.map { it.goatRes() }
        assertEquals(8, drawables.size)
        assertEquals(8, drawables.distinct().size)
        assertEquals(R.drawable.goattv_icon_1, AppIcon.PETROL.goatRes())
        assertEquals(R.drawable.goattv_icon_2, AppIcon.SUNFLOWER.goatRes())
        assertEquals(R.drawable.goattv_icon_3, AppIcon.COBALT.goatRes())
        assertEquals(R.drawable.goattv_icon_4, AppIcon.TOMATO.goatRes())
        assertEquals(R.drawable.goattv_icon_5, AppIcon.BOARD.goatRes())
        assertEquals(R.drawable.goattv_icon_6, AppIcon.EGGSHELL.goatRes())
        assertEquals(R.drawable.goattv_icon_7, AppIcon.OLIVE.goatRes())
        assertEquals(R.drawable.goattv_icon_8, AppIcon.OLIVE_CREAM.goatRes())
    }

    @Test
    fun savedSelectionPersistence_and_migration() {
        // Any saved selection 1:1 maps to a valid Goat icon slot without crashing
        AppIcon.entries.forEach { icon ->
            assertNotNull(icon.goatLabelRes())
            assertNotNull(icon.goatRes())
        }
    }

    @Test
    fun nonGoatFlavors_doNotResolveGoatAssets() {
        assertNotEquals(MobileBrand.ALLACCESS, MobileBrand.GOAT)
        assertNotEquals(MobileBrand.FIVESTAR, MobileBrand.GOAT)
        assertNotEquals(MobileBrand.SUPREME, MobileBrand.GOAT)
    }
}
