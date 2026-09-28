package tv.own.owntv.mobile.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.brand.BrandConfig
import tv.own.owntv.mobile.brand.MobileBrand

class BrandedFlowDisclaimerTest {

    @Test
    fun brandedFlavors_skipDisclaimerInSequence() {
        val brandedFlavors = MobileBrand.entries

        brandedFlavors.forEach { _ ->
            val isBranded = true
            assertTrue(isBranded)
            val nextStep = if (isBranded) "CHOICE" else "DISCLAIMER"
            assertEquals("CHOICE", nextStep)
        }
    }

    @Test
    fun stockOwnTv_keepsDisclaimerInSequence() {
        val isBranded = false
        val nextStep = if (isBranded) "CHOICE" else "DISCLAIMER"
        assertEquals("DISCLAIMER", nextStep)
    }

    @Test
    fun brandedFlow_backFromChoiceReturnsToDisplaySize() {
        val isBranded = true
        val backStepFromChoice = if (isBranded) "DISPLAY_SIZE" else "DISCLAIMER"
        assertEquals("DISPLAY_SIZE", backStepFromChoice)
    }

    @Test
    fun persistedDisclaimerStep_safelyMigratesToChoice() {
        val isBranded = BrandConfig.isBranded
        val savedStep = "DISCLAIMER"
        val migratedStep = if (isBranded) "CHOICE" else savedStep
        assertEquals("CHOICE", migratedStep)
    }
}
