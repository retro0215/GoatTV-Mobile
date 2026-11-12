package tv.own.owntv.mobile.home

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.mobile.promotions.PromotionBrandDto
import tv.own.owntv.mobile.promotions.PromotionDto
import tv.own.owntv.mobile.promotions.eligiblePromotions
import java.time.Instant

/** Home Promotions: the Supabase rows decode, and only this brand's live promotions are shown. */
class HomePromotionsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val now = Instant.parse("2026-11-12T12:00:00Z")

    private fun promo(
        id: String,
        active: Boolean = true,
        featured: Boolean = false,
        sortOrder: Int = 0,
        starts: String? = null,
        ends: String? = null,
    ) = PromotionDto(
        id = id, title = "Title $id", active = active, featured = featured, sortOrder = sortOrder,
        publishStartsAt = starts, publishEndsAt = ends,
    )

    private fun brands(vararg pairs: Pair<String, String>) =
        pairs.map { (promotionId, brandId) -> PromotionBrandDto(promotionId, brandId) }

    @Test
    fun supabaseRows_decodeWithGeneratedSerializers() {
        // Fails with "Serializer for class ... not found" when the serialization plugin is missing.
        val rows = json.decodeFromString<List<PromotionDto>>(
            """[{"id":"p1","title":"Big game","subtitle":null,"banner_url":"https://x/b.jpg",
               "cta_text":"Watch","cta_action":"","active":true,"featured":true,
               "publish_starts_at":"2026-11-01T00:00:00+00:00","publish_ends_at":null,
               "sort_order":3,"event_id":null,"room_id":null,"created_at":"2026-10-01T00:00:00Z"}]""",
        )
        assertEquals("https://x/b.jpg", rows.single().bannerUrl)
        assertEquals(3, rows.single().sortOrder)
        assertTrue(rows.single().featured)

        val links = json.decodeFromString<List<PromotionBrandDto>>(
            """[{"promotion_id":"p1","brand_id":"goat"}]""",
        )
        assertEquals(PromotionBrandDto("p1", "goat"), links.single())

        // Supabase returns timestamptz with an offset; it must still parse as a window bound.
        val shown = eligiblePromotions(rows, links, "goat", now)
        assertEquals(listOf("p1"), shown.map { it.id })
        assertEquals("none", shown.single().ctaAction)
    }

    @Test
    fun otherBrandsAndUnassignedPromotions_areExcluded() {
        val shown = eligiblePromotions(
            listOf(promo("mine"), promo("theirs"), promo("nobody")),
            brands("mine" to "GOAT", "theirs" to "allaccess"),
            "goat",
            now,
        )
        assertEquals(listOf("mine"), shown.map { it.id })
    }

    @Test
    fun inactiveExpiredAndFuturePromotions_areExcluded() {
        val shown = eligiblePromotions(
            listOf(
                promo("inactive", active = false),
                promo("expired", ends = "2026-11-12T11:59:59Z"),
                promo("future", starts = "2026-11-12T12:00:01Z"),
                promo("endsNow", ends = "2026-11-12T12:00:00Z"),
                promo("startsNow", starts = "2026-11-12T12:00:00Z"),
                promo("open", starts = "2026-11-01T00:00:00Z", ends = "2026-12-01T00:00:00Z"),
            ),
            brands(
                "inactive" to "goat", "expired" to "goat", "future" to "goat",
                "endsNow" to "goat", "startsNow" to "goat", "open" to "goat",
            ),
            "goat",
            now,
        )
        assertEquals(setOf("endsNow", "startsNow", "open"), shown.map { it.id }.toSet())
    }

    @Test
    fun featuredFirst_thenSortOrder() {
        val shown = eligiblePromotions(
            listOf(
                promo("b", sortOrder = 2),
                promo("featured2", featured = true, sortOrder = 5),
                promo("a", sortOrder = 1),
                promo("featured1", featured = true, sortOrder = 1),
            ),
            brands("a" to "goat", "b" to "goat", "featured1" to "goat", "featured2" to "goat"),
            "goat",
            now,
        )
        assertEquals(listOf("featured1", "featured2", "a", "b"), shown.map { it.id })
    }

    @Test
    fun noRows_meansNoPromotions() {
        assertTrue(eligiblePromotions(emptyList(), emptyList(), "goat", now).isEmpty())
    }
}
