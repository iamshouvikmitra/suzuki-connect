package dev.mrwick.redline.gnav

import com.google.android.gms.maps.model.LatLng
import dev.mrwick.redline.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Turns a [LinkTarget] from [SharedLinkParser] into a routable [NavDestination]:
 * expands short links over HTTP, fetches place details for Place IDs, and runs
 * a text search for name-only links. Coordinates pass straight through.
 */
class SharedLinkResolver(
    private val search: DestinationSearch?,
    private val client: OkHttpClient = defaultClient(),
) {
    sealed class Result {
        data class Ok(val destination: NavDestination) : Result()
        data class Failed(val reason: String) : Result()
    }

    suspend fun resolve(target: LinkTarget, near: LatLng?, depth: Int = 0): Result = when (target) {
        is LinkTarget.Coordinates -> {
            // Prefer place details when we have an ID (better label + routing);
            // fall back to the raw coordinates on any failure.
            val viaPlace = target.placeId?.let { search?.fetch(it, target.label) }?.takeIf { it.hasCoordinates }
            Result.Ok(viaPlace ?: NavDestination(target.label ?: "Shared location", target.lat, target.lng, target.placeId))
        }
        is LinkTarget.PlaceId -> {
            val d = search?.fetch(target.placeId, target.label) ?: NavDestination(target.label ?: "Shared place", null, null, target.placeId)
            Result.Ok(d)
        }
        is LinkTarget.Query -> {
            val d = search?.searchText(target.query, near)
            if (d != null) Result.Ok(d) else Result.Failed("Couldn't find \"${target.query}\"")
        }
        is LinkTarget.ShortLink -> {
            if (depth > 2) Result.Failed("Link redirected too many times")
            else when (val expanded = expand(target.url)) {
                null -> Result.Failed("Couldn't open the shared link (no network?)")
                else -> {
                    val next = SharedLinkParser.parseUrl(expanded)
                    if (next == null || next is LinkTarget.ShortLink) Result.Failed("Link didn't contain a location")
                    else resolve(next, near, depth + 1)
                }
            }
        }
    }

    /** Follows redirects and returns the final URL, or null on network failure. */
    suspend fun expand(shortUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(shortUrl)
                // Android/mobile UA: maps.app.goo.gl only 302s to the full
                // /maps/place/... URL for mobile clients; a desktop UA gets a 200
                // interstitial with no redirect, which left shares unresolvable.
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
                .get().build()
            client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                AppLog.i(TAG, "expanded short link (${resp.code}) -> ${finalUrl.take(160)}")
                if (finalUrl.substringBefore('?') == shortUrl.substringBefore('?')) null else finalUrl
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "expand failed: ${t.message}")
            null
        }
    }

    private companion object {
        const val TAG = "SharedLinkResolver"
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(true).followSslRedirects(true)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
