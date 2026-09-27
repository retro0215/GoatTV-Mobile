package tv.own.owntv.mobile.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BrandConfigTest {

    @Test
    fun testMobileBrandEnumValues() {
        assertEquals("tv.goat.tv.mobile", MobileBrand.GOAT.applicationId)
        assertEquals("tv.allaccess.app.mobile", MobileBrand.ALLACCESS.applicationId)
        assertEquals("tv.fivestar.ultra.mobile", MobileBrand.FIVESTAR.applicationId)
        assertEquals("tv.supreme.app.mobile", MobileBrand.SUPREME.applicationId)
    }

    @Test
    fun testBrandConfigResolve() {
        assertNotNull(BrandConfig.currentBrand)
    }
}
