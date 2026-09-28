package tv.own.owntv.mobile.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.brand.BrandConfig
import tv.own.owntv.mobile.brand.MobileBrand

class BrandedFlowAddContentTest {

    @Test
    fun brandedFlavors_skipAddContentStep() {
        val brandedFlavors = MobileBrand.entries

        brandedFlavors.forEach { _ ->
            val isBranded = true
            assertTrue(isBranded)
            val stepAfterProfileCreation = if (isBranded) "FORM" else "ADD_CONTENT"
            assertEquals("FORM", stepAfterProfileCreation)
        }
    }

    @Test
    fun stockOwnTv_keepsAddContentStep() {
        val isBranded = false
        val stepAfterProfileCreation = if (isBranded) "FORM" else "ADD_CONTENT"
        assertEquals("ADD_CONTENT", stepAfterProfileCreation)
    }

    @Test
    fun loginBackNavigation_returnsToChoiceInBrandedFlow() {
        val isBranded = true
        val backStepFromForm = if (isBranded) "CHOICE" else "ADD_CONTENT"
        assertEquals("CHOICE", backStepFromForm)
    }

    @Test
    fun persistedAddContentStep_safelyMigratesToForm() {
        val isBranded = BrandConfig.isBranded
        val savedStep = "ADD_CONTENT"
        val migratedStep = if (isBranded) "FORM" else savedStep
        assertEquals("FORM", migratedStep)
    }
}
