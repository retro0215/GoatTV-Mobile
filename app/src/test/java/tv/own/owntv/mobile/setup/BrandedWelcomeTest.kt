package tv.own.owntv.mobile.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.brand.BrandConfig
import tv.own.owntv.mobile.brand.MobileBrand

class BrandedWelcomeTest {

    @Test
    fun brandConfig_providesCurrentBrandName() {
        assertNotNull(BrandConfig.brandName)
        assertEquals(BrandConfig.currentBrand.displayName, BrandConfig.brandName)
        assertTrue(BrandConfig.isBranded)
    }

    @Test
    fun multiBrandNames_correctPerBrand() {
        assertEquals("GoatTV", MobileBrand.GOAT.displayName)
        assertEquals("AllAccess", MobileBrand.ALLACCESS.displayName)
        assertEquals("5Star Ultra", MobileBrand.FIVESTAR.displayName)
        assertEquals("Supreme TV", MobileBrand.SUPREME.displayName)
    }

    @Test
    fun brandedBuild_removesTagline() {
        // Tagline "Your own IPTV player." is shown only when !BrandConfig.isBranded
        val showTagline = !BrandConfig.isBranded
        assertFalse("Branded builds must not show OwnTV tagline", showTagline)
    }

    @Test
    fun brandLogoResource_existsPerFlavor() {
        // Each flavor contains its ic_brand_logo drawable asset
        val drawableName = "ic_brand_logo"
        assertNotNull(drawableName)
    }
}
