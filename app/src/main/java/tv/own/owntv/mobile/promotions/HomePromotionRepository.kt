package tv.own.owntv.mobile.promotions

import android.annotation.SuppressLint
import android.util.Log
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tv.own.owntv.mobile.BuildConfig
import tv.own.owntv.mobile.brand.BrandConfig
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class PromotionDto(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    @SerialName("cta_text") val ctaText: String? = null,
    @SerialName("cta_action") val ctaAction: String? = "none",
    val active: Boolean = false,
    val featured: Boolean = false,
    @SerialName("publish_starts_at") val publishStartsAt: String? = null,
    @SerialName("publish_ends_at") val publishEndsAt: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("room_id") val roomId: String? = null
)

@Serializable
data class PromotionBrandDto(
    @SerialName("promotion_id") val promotionId: String,
    @SerialName("brand_id") val brandId: String
)

data class HomePromotion(
    val id: String,
    val title: String,
    val subtitle: String?,
    val bannerUrl: String?,
    val ctaText: String?,
    val ctaAction: String,
    val featured: Boolean,
    val publishStartsAt: Long?,
    val publishEndsAt: Long?,
    val sortOrder: Int,
    val eventId: String?,
    val roomId: String?
)

class MobileHomePromotionRepository {
    private val publicClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Postgrest)
        }
    }

    @SuppressLint("NewApi")
    suspend fun fetchEligiblePromotions(): List<HomePromotion> {
        val brandId = BrandConfig.currentBrand.brandId
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return emptyList()
        }
        return runCatching {
            val now = Instant.now()
            val promos = publicClient.postgrest["promotions"]
                .select {
                    filter { eq("active", true) }
                }
                .decodeList<PromotionDto>()

            if (promos.isEmpty()) return@runCatching emptyList()

            val promotionIds = promos.map { it.id }
            val promoBrands = publicClient.postgrest["promotion_brands"]
                .select {
                    filter { isIn("promotion_id", promotionIds) }
                }
                .decodeList<PromotionBrandDto>()

            eligiblePromotions(promos, promoBrands, brandId, now)
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            // The type only: a network error's message can carry the request URL.
            Log.w("MobileHomePromoRepo", "Failed to fetch promotions: ${e.javaClass.simpleName}")
            emptyList()
        }
    }
}

/**
 * The promotions Home may show for [brandId] at [now]: active, assigned to that brand, and inside
 * their publication window (either bound may be absent; both are inclusive). Featured first, then by
 * sort order. Unparseable dates are treated as absent, as before.
 */
internal fun eligiblePromotions(
    promos: List<PromotionDto>,
    promoBrands: List<PromotionBrandDto>,
    brandId: String,
    now: Instant,
): List<HomePromotion> {
    val brandMap = promoBrands.groupBy({ it.promotionId }, { it.brandId })
    return promos.mapNotNull { dto ->
        if (!dto.active) return@mapNotNull null
        val brands = brandMap[dto.id] ?: emptyList()
        if (brands.none { it.equals(brandId, ignoreCase = true) }) return@mapNotNull null

        val startInstant = dto.publishStartsAt?.let(::parseTimestamp)
        val endInstant = dto.publishEndsAt?.let(::parseTimestamp)
        if (startInstant != null && now.isBefore(startInstant)) return@mapNotNull null
        if (endInstant != null && now.isAfter(endInstant)) return@mapNotNull null

        HomePromotion(
            id = dto.id,
            title = dto.title,
            subtitle = dto.subtitle,
            bannerUrl = dto.bannerUrl,
            ctaText = dto.ctaText,
            ctaAction = dto.ctaAction?.ifBlank { "none" } ?: "none",
            featured = dto.featured,
            publishStartsAt = startInstant?.toEpochMilli(),
            publishEndsAt = endInstant?.toEpochMilli(),
            sortOrder = dto.sortOrder,
            eventId = dto.eventId,
            roomId = dto.roomId
        )
    }.sortedWith(compareByDescending<HomePromotion> { it.featured }.thenBy { it.sortOrder })
}

/** PostgREST writes timestamptz with an offset ("+00:00"), which older runtimes' Instant.parse rejects. */
private fun parseTimestamp(value: String): Instant? =
    runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()
