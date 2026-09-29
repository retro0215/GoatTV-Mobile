package tv.own.owntv.mobile.ui.screens.library

import android.util.Log
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.HttpClient

/**
 * What the playlist itself says about one title, beyond what its catalogue row holds.
 *
 * The catalogue sync (`get_vod_streams` / `get_series`) carries no trailer, and for films no plot
 * either — those only come back from the per-title `get_vod_info` / `get_series_info`. The TV app
 * reads the same fields the same way; this is the phone's copy, kept in the app because the published
 * core does not have it yet.
 */
data class ProviderVodInfo(
    val plot: String?,
    val trailer: String?,
    val backdropUrl: String?,
)

/**
 * Fetches [ProviderVodInfo] for the one title that was opened — never for a list, so browsing costs
 * the provider nothing. Held in memory for the session: a title re-opened is instant, and a Room
 * table would mean a migration on every user's database for three strings.
 *
 * Xtream only; every other source type answers null and the screen falls back to TMDB and artwork.
 */
class ProviderVodInfoLoader(
    private val sourceDao: SourceDao,
    private val http: HttpClient,
) {
    private val cache = ConcurrentHashMap<String, ProviderVodInfo>()

    suspend fun movie(movie: MovieEntity): ProviderVodInfo? =
        load(movie.sourceId, movie.remoteId, ACTION_VOD, PARAM_VOD)

    suspend fun series(series: SeriesEntity): ProviderVodInfo? =
        load(series.sourceId, series.remoteId, ACTION_SERIES, PARAM_SERIES)

    private suspend fun load(sourceId: Long, remoteId: String?, action: String, param: String): ProviderVodInfo? {
        if (remoteId.isNullOrBlank()) return null
        val key = "$action:$sourceId:$remoteId"
        cache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            val source = sourceDao.getById(sourceId)?.takeIf { it.type == SourceType.XTREAM }
                ?: return@withContext null
            runCatching { http.getText(apiUrl(source, action, param, remoteId), source.userAgent) }
                // The message can carry the request URL, and with it the account's credentials.
                .onFailure { Log.w(TAG, "$action failed: ${it.javaClass.simpleName}") }
                .getOrNull()
                ?.let(::parseProviderVodInfo)
                ?.also { cache[key] = it }
        }
    }

    private fun apiUrl(s: SourceEntity, action: String, param: String, id: String): String {
        val user = URLEncoder.encode(s.username.orEmpty(), UTF_8)
        val pass = URLEncoder.encode(s.password.orEmpty(), UTF_8)
        val remote = URLEncoder.encode(id, UTF_8)
        return "${s.url.trimEnd('/')}/player_api.php?username=$user&password=$pass&action=$action&$param=$remote"
    }

    private companion object {
        const val TAG = "ProviderVodInfo"
        const val UTF_8 = "UTF-8"
        const val ACTION_VOD = "get_vod_info"
        const val ACTION_SERIES = "get_series_info"
        const val PARAM_VOD = "vod_id"
        const val PARAM_SERIES = "series_id"
    }
}

/**
 * The `info` block of a `get_vod_info` / `get_series_info` body. Panels differ in which key holds
 * the plot and whether `backdrop_path` is an array or a string, so both shapes are read. Null when
 * the body is not the object it should be.
 */
internal fun parseProviderVodInfo(body: String): ProviderVodInfo? {
    val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val info = root.optJSONObject("info") ?: return null
    val plot = PLOT_KEYS.firstNotNullOfOrNull { info.cleanString(it) }
    val backdrop = when (val raw = info.opt("backdrop_path")) {
        is JSONArray -> (0 until raw.length()).firstNotNullOfOrNull { raw.optString(it).cleanOrNull() }
        is String -> raw.cleanOrNull()
        else -> null
    }
    return ProviderVodInfo(
        plot = plot,
        trailer = info.cleanString("youtube_trailer"),
        backdropUrl = backdrop,
    )
}

private val PLOT_KEYS = listOf("plot", "description", "overview")

private fun JSONObject.cleanString(key: String): String? =
    if (isNull(key)) null else optString(key).cleanOrNull()

/** Panels write an absent field as "", "null" or whitespace; all three mean nothing. */
private fun String.cleanOrNull(): String? = trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
