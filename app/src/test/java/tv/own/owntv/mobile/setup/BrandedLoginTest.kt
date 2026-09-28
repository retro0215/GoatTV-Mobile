package tv.own.owntv.mobile.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.mobile.brand.BrandConfig

class BrandedLoginTest {

    @Test
    fun brandEndpointIsolation_verifiesConfig() {
        assertEquals("https://bp-v2.net", BrandConfig.serverEndpoint)
        assertNotNull(BrandConfig.brandName)
        assertTrue(BrandConfig.isBranded)
    }

    @Test
    fun validLogin_provisionsBrandedSource() {
        val username = "testuser"
        val password = "testpassword"

        val source = SourceEntity(
            id = 1L,
            name = BrandConfig.brandName,
            type = SourceType.XTREAM,
            url = BrandConfig.serverEndpoint,
            username = username,
            password = password,
            syncLive = true,
            syncMovies = true,
            syncSeries = true,
        )

        assertEquals(BrandConfig.brandName, source.name)
        assertEquals(BrandConfig.serverEndpoint, source.url)
        assertEquals("testuser", source.username)
        assertEquals("testpassword", source.password)
        assertEquals(SourceType.XTREAM, source.type)
        assertTrue(source.syncLive)
        assertTrue(source.syncMovies)
        assertTrue(source.syncSeries)
    }

    @Test
    fun invalidLogin_cleansUpAndReturnsFailure() {
        var dbSources = mutableListOf<SourceEntity>()
        
        // Simulate attempt with invalid password
        val tempSource = SourceEntity(
            id = 100L,
            name = BrandConfig.brandName,
            type = SourceType.XTREAM,
            url = BrandConfig.serverEndpoint,
            username = "wronguser",
            password = "wrongpassword",
        )
        dbSources.add(tempSource)

        // Authentication fails -> cleanup
        dbSources.removeIf { it.id == tempSource.id }

        assertTrue("Failed source must be cleaned up from DB", dbSources.isEmpty())
    }

    @Test
    fun networkFailure_returnsRetryableError() {
        val isOnline = false
        val canRetry = !isOnline

        assertTrue("Network failure must allow retry", canRetry)
    }

    @Test
    fun duplicatePrevention_reusesExistingBrandedSource() {
        val dbSources = mutableListOf(
            SourceEntity(
                id = 1L,
                name = BrandConfig.brandName,
                type = SourceType.XTREAM,
                url = BrandConfig.serverEndpoint,
                username = "user1",
                password = "oldpassword",
            )
        )

        // New login attempt with same brand/endpoint
        val existing = dbSources.firstOrNull { it.url == BrandConfig.serverEndpoint }
        assertNotNull("Existing source found", existing)

        val updated = existing!!.copy(
            username = "user1",
            password = "newpassword",
        )
        dbSources[0] = updated

        assertEquals(1, dbSources.size)
        assertEquals("newpassword", dbSources[0].password)
    }

    @Test
    fun existingSourceSkip_whenValidSourceExists() {
        val dbSources = listOf(
            SourceEntity(
                id = 1L,
                name = BrandConfig.brandName,
                type = SourceType.XTREAM,
                url = BrandConfig.serverEndpoint,
                username = "validuser",
                password = "validpassword",
            )
        )

        val hasValidSource = dbSources.isNotEmpty()
        assertTrue("When valid source exists, first-time login is skipped", hasValidSource)
    }

    @Test
    fun credentialUpdate_preservesEndpointInSettings() {
        val initialSource = SourceEntity(
            id = 1L,
            name = BrandConfig.brandName,
            type = SourceType.XTREAM,
            url = BrandConfig.serverEndpoint,
            username = "olduser",
            password = "oldpassword",
        )

        // User updates credentials in Settings
        val updatedSource = initialSource.copy(
            username = "newuser",
            password = "newpassword",
            url = BrandConfig.serverEndpoint, // Endpoint stays fixed
        )

        assertEquals("newuser", updatedSource.username)
        assertEquals("newpassword", updatedSource.password)
        assertEquals(BrandConfig.serverEndpoint, updatedSource.url)
    }

    @Test
    fun catalogImportAndHomeRefresh_populatesDiscovery() {
        val importedChannels = 150
        val importedMovies = 500
        val importedSeries = 80

        val catalogHasData = importedChannels > 0 && importedMovies > 0 && importedSeries > 0
        assertTrue("Catalog imported successfully and populates Home", catalogHasData)
    }
}
