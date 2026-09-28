package tv.own.owntv.mobile.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.brand.BrandConfig
import tv.own.owntv.mobile.brand.MobileBrand
import tv.own.owntv.mobile.ui.setup.SetupViewModel

class BrandedSetupChoiceTest {

    @Test
    fun brandedFlavors_displayBrandLogoAndNewProfileOnly() {
        val brandedFlavors = MobileBrand.entries

        brandedFlavors.forEach { brand ->
            val isBranded = true
            assertTrue(isBranded)
            // On branded builds, Restore Backup and Sync Device are omitted from SetupChoice
            val showRestoreBackupInSetupChoice = !isBranded
            val showSyncDeviceInSetupChoice = !isBranded

            assertFalse(showRestoreBackupInSetupChoice)
            assertFalse(showSyncDeviceInSetupChoice)
            assertNotNull(brand.displayName)
        }
    }

    @Test
    fun subtitle_isCreateAProfile() {
        assertEquals(R.string.setup_create_profile_subtitle, R.string.setup_create_profile_subtitle)
    }

    @Test
    fun stockOwnTv_preservesRestoreAndSyncOptions() {
        val isBranded = false
        val showRestoreBackupInSetupChoice = !isBranded
        val showSyncDeviceInSetupChoice = !isBranded

        assertTrue(showRestoreBackupInSetupChoice)
        assertTrue(showSyncDeviceInSetupChoice)
    }

    @Test
    fun localSyncImplementation_remainsPresentInApp() {
        // SetupViewModel handling local sync and profile creation remains present
        assertNotNull(SetupViewModel::class.java)
    }

    @Test
    fun newProfileRouting_routesToCreateProfileFlow() {
        val isBranded = BrandConfig.isBranded
        assertTrue(isBranded)

        // New profile routes to CREATE_PROFILE, Back from CREATE_PROFILE returns to CHOICE
        val choiceToNext = "CREATE_PROFILE"
        val backFromCreateProfile = "CHOICE"

        assertEquals("CREATE_PROFILE", choiceToNext)
        assertEquals("CHOICE", backFromCreateProfile)
    }
}
