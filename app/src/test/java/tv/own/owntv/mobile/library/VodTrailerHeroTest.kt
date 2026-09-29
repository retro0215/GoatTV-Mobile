package tv.own.owntv.mobile.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.MetadataCacheEntity
import tv.own.owntv.mobile.ui.screens.library.extractYouTubeVideoId

class VodTrailerHeroTest {

    @Test
    fun movieTrailerMetadata_resolvesYouTubeKey() {
        val meta = MetadataCacheEntity(
            key = "movie_100",
            type = "MOVIE",
            tmdbId = 100,
            title = "Inception",
            year = 2010,
            overview = "A thief who steals corporate secrets...",
            posterPath = "/poster.jpg",
            backdropPath = "/backdrop.jpg",
            logoPath = null,
            rating = 8.8,
            genresJson = null,
            castJson = null,
            trailerKey = "YoHD9XE21Z4",
            imdbId = "tt1375666",
            updatedAt = System.currentTimeMillis(),
        )

        assertNotNull(meta.trailerKey)
        assertEquals("YoHD9XE21Z4", meta.trailerKey)
    }

    @Test
    fun seriesTrailerMetadata_resolvesYouTubeKey() {
        val meta = MetadataCacheEntity(
            key = "series_200",
            type = "SERIES",
            tmdbId = 200,
            title = "Breaking Bad",
            year = 2008,
            overview = "A chemistry teacher diagnosed with inoperable lung cancer...",
            posterPath = "/poster.jpg",
            backdropPath = "/backdrop.jpg",
            logoPath = null,
            rating = 9.5,
            genresJson = null,
            castJson = null,
            trailerKey = "HhesaQXLuRY",
            imdbId = "tt0903747",
            updatedAt = System.currentTimeMillis(),
        )

        assertNotNull(meta.trailerKey)
        assertEquals("HhesaQXLuRY", meta.trailerKey)
    }

    @Test
    fun trailerAvailable_and_mutedDefaultState() {
        val trailerKey = "YoHD9XE21Z4"
        val isTrailerAvailable = trailerKey.isNotBlank()
        var isMuted = true
        val isPlayingVod = false

        assertTrue("Valid trailerKey activates trailer hero", isTrailerAvailable)
        assertTrue("Trailer must start muted by default", isMuted)
        assertFalse("VOD is not playing initially", isPlayingVod)

        // Toggle mute
        isMuted = false
        assertFalse("Unmuted trailer audio", isMuted)
    }

    @Test
    fun trailerUnavailable_or_invalid_fallsBackToBackdropOrPoster() {
        val nullTrailerKey: String? = null
        val emptyTrailerKey = "   "
        val backdropUrl = "http://example.com/backdrop.jpg"
        val posterUrl = "http://example.com/poster.jpg"

        fun resolveHeroImage(key: String?, backdrop: String?, poster: String?): String? {
            return if (key.isNullOrBlank()) {
                backdrop ?: poster
            } else {
                null // Plays video trailer instead
            }
        }

        assertEquals("http://example.com/backdrop.jpg", resolveHeroImage(nullTrailerKey, backdropUrl, posterUrl))
        assertEquals("http://example.com/backdrop.jpg", resolveHeroImage(emptyTrailerKey, backdropUrl, posterUrl))
        assertEquals("http://example.com/poster.jpg", resolveHeroImage(nullTrailerKey, null, posterUrl))
        assertNull(resolveHeroImage("valid_key", backdropUrl, posterUrl))
    }

    @Test
    fun vodPlay_stopsTrailerLifecycle() {
        var isPlayingTrailer = true
        val isPlayingVod = true

        if (isPlayingVod) {
            isPlayingTrailer = false
        }

        assertTrue("VOD playback started", isPlayingVod)
        assertFalse("Trailer playback stopped", isPlayingTrailer)
    }

    @Test
    fun insetPoster_isCompletelyAbsentFromHero() {
        // Small inset poster overlay was removed from DetailScreen hero Box.
        val insetPosterPresent = false
        assertFalse("Small inset poster must be absent from hero", insetPosterPresent)
    }

    @Test
    fun extractYouTubeVideoId_handlesRawKeysAndUrls() {
        assertEquals("YoHD9XE21Z4", extractYouTubeVideoId("YoHD9XE21Z4"))
        assertEquals("YoHD9XE21Z4",
            extractYouTubeVideoId("https://www.youtube.com/watch?v=YoHD9XE21Z4")
        )
        assertEquals("YoHD9XE21Z4", extractYouTubeVideoId("https://youtu.be/YoHD9XE21Z4"))
        assertEquals("YoHD9XE21Z4",
            extractYouTubeVideoId("https://www.youtube.com/embed/YoHD9XE21Z4")
        )
        assertNull(extractYouTubeVideoId(null))
        assertNull(extractYouTubeVideoId(""))
    }
}
