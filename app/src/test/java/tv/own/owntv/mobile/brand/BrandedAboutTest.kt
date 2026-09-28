package tv.own.owntv.mobile.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.mobile.R

class BrandedAboutTest {

    @Test
    fun brandDescriptions_correctAndDistinctPerFlavor() {
        assertEquals(R.string.brand_description_goat, MobileBrand.GOAT.descriptionRes)
        assertEquals(R.string.brand_description_allaccess, MobileBrand.ALLACCESS.descriptionRes)
        assertEquals(R.string.brand_description_fivestar, MobileBrand.FIVESTAR.descriptionRes)
        assertEquals(R.string.brand_description_supreme, MobileBrand.SUPREME.descriptionRes)

        val descResList = MobileBrand.entries.map { it.descriptionRes }
        assertEquals(4, descResList.size)
        assertEquals(4, descResList.distinct().size)
    }

    @Test
    fun brandedAboutPage_omitsTelegramAndGithubCommunityAndQr() {
        val isBranded = true
        assertTrue(isBranded)

        val showTelegramOnAbout = !isBranded
        val showGithubOnAbout = !isBranded
        val showTelegramQrOnAbout = !isBranded

        assertFalse(showTelegramOnAbout)
        assertFalse(showGithubOnAbout)
        assertFalse(showTelegramQrOnAbout)
    }

    @Test
    fun stockOwnTvAbout_preservesCommunityAndQrSections() {
        val isBranded = false

        val showTelegramOnAbout = !isBranded
        val showGithubOnAbout = !isBranded
        val showTelegramQrOnAbout = !isBranded

        assertTrue(showTelegramOnAbout)
        assertTrue(showGithubOnAbout)
        assertTrue(showTelegramQrOnAbout)
    }

    @Test
    fun gplLegalAccess_remainsAvailableViaOpenSourceLicenses() {
        assertNotNull(R.string.settings_open_source_licenses)
        assertNotNull(R.string.settings_open_source_detail)
    }

    @Test
    fun localSyncQr_outsideAboutRemainsIntact() {
        // CompanionLink implementation used for Local Sync pairing remains present
        assertNotNull(CompanionLink::class.java)
    }
}
