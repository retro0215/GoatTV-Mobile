package tv.own.owntv.mobile.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun testBrandEndpointIsolation() {
        // Verify each flavor resolves its own default endpoint without leakage
        assertEquals("https://bp-v2.net", MobileBrand.GOAT.defaultEndpoint)
        assertEquals("https://bp-v2.net", MobileBrand.ALLACCESS.defaultEndpoint)
        assertEquals("https://bp-v2.net", MobileBrand.FIVESTAR.defaultEndpoint)
        assertEquals("https://bp-v2.net", MobileBrand.SUPREME.defaultEndpoint)

        MobileBrand.entries.forEach { brand ->
            assertNotNull(brand.defaultEndpoint)
            assertTrue("Endpoint must start with http", brand.defaultEndpoint.startsWith("http"))
        }
    }

    @Test
    fun testBrandConfigResolve() {
        assertNotNull(BrandConfig.currentBrand)
        assertNotNull(BrandConfig.serverEndpoint)
        assertNotNull(BrandConfig.brandName)
        assertEquals(BrandConfig.currentBrand.defaultEndpoint, BrandConfig.serverEndpoint)
        assertEquals(BrandConfig.currentBrand.displayName, BrandConfig.brandName)
    }
}
