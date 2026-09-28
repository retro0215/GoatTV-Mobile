package tv.own.owntv.mobile.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun inspectBrandLogoAssets() {
        val root = File("C:/Users/bronb/StudioProjects/GoatTV-Mobile/app/src")
        val brands = listOf("goat", "allaccess", "fivestar", "supreme")
        for (b in brands) {
            val dir = File(root, "$b/res/drawable")
            val files = dir.listFiles { _, name -> name.startsWith("ic_brand_logo") }
            assertTrue("Brand $b must have ic_brand_logo asset", files != null && files.isNotEmpty())
        }
    }

    private fun getImageDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size > 24 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()) {
            val width = ((bytes[16].toInt() and 0xFF) shl 24) or ((bytes[17].toInt() and 0xFF) shl 16) or ((bytes[18].toInt() and 0xFF) shl 8) or (bytes[19].toInt() and 0xFF)
            val height = ((bytes[20].toInt() and 0xFF) shl 24) or ((bytes[21].toInt() and 0xFF) shl 16) or ((bytes[22].toInt() and 0xFF) shl 8) or (bytes[23].toInt() and 0xFF)
            return Pair(width, height)
        }
        if (bytes.size > 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            var i = 2
            while (i < bytes.size - 8) {
                if (bytes[i] == 0xFF.toByte()) {
                    val marker = bytes[i + 1].toInt() and 0xFF
                    if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                        val h = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                        val w = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                        return Pair(w, h)
                    }
                    val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                    i += 2 + len
                } else {
                    i++
                }
            }
        }
        return null
    }
}

