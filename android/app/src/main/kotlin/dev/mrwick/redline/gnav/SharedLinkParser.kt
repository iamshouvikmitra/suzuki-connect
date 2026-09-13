package dev.mrwick.redline.gnav

import java.net.URI
import java.net.URLDecoder

/**
 * What a shared Google Maps link / geo URI is asking us to navigate to.
 * Pure data: no network, no SDK types, fully unit-testable.
 */
sealed class LinkTarget {
    /** Exact coordinates, optionally with a Place ID (preferred for routing) and a label. */
    data class Coordinates(val lat: Double, val lng: Double, val placeId: String? = null, val label: String? = null) : LinkTarget()
    /** A Place ID with no coordinates in the URL. */
    data class PlaceId(val placeId: String, val label: String? = null) : LinkTarget()
    /** Free text that must go through a place search ("Cafe Coffee Day Koramangala"). */
    data class Query(val query: String) : LinkTarget()
    /** A short link (maps.app.goo.gl, goo.gl/maps) that must be expanded over HTTP first. */
    data class ShortLink(val url: String) : LinkTarget()
}

/**
 * Parses the text of a share intent (or a plain URL) into a [LinkTarget].
 *
 * Handles the URL shapes Google Maps actually produces:
 *  - `https://maps.app.goo.gl/<id>` (share sheet) → [LinkTarget.ShortLink]
 *  - `https://www.google.com/maps/place/<name>/@lat,lng,17z/data=…!3dLAT!4dLNG…!19sChIJ…`
 *  - `https://www.google.com/maps/search/<q>/`, `/maps/dir/<from>/<to>/`
 *  - `https://maps.google.com/?q=lat,lng`, `?q=<text>`, `?daddr=`, `?destination=`, `?ll=`
 *  - `https://www.google.com/maps/search/?api=1&query=…&query_place_id=…`
 *  - `geo:lat,lng?q=…`
 */
object SharedLinkParser {

    private val URL_REGEX = Regex("""(?i)\b((?:https?://|geo:)[^\s<>"']+)""")
    private val LAT_LNG = Regex("""^\s*(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")
    private val DATA_3D4D = Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""")
    private val DATA_PLACE_ID = Regex("""!19s(ChIJ[A-Za-z0-9_-]+)""")
    private val AT_COORDS = Regex("""/@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
    private val SHORT_HOSTS = setOf("maps.app.goo.gl", "goo.gl", "g.co", "maps.google.com/maps")

    /** Pulls the first URL-ish token out of arbitrary shared text. */
    fun extractUrl(text: String?): String? =
        text?.let { URL_REGEX.find(it)?.groupValues?.get(1)?.trimEnd('.', ',', ')') }

    /** Parses shared text; returns null when it contains nothing navigable. */
    fun parseSharedText(text: String?): LinkTarget? {
        val url = extractUrl(text)
        if (url != null) return parseUrl(url)
        // No URL: treat non-trivial text as a place query ("MG Road Metro").
        val t = text?.trim().orEmpty()
        return if (t.length in 3..120 && !t.contains('\n')) LinkTarget.Query(t) else null
    }

    fun parseUrl(raw: String): LinkTarget? {
        val url = raw.trim()
        if (url.startsWith("geo:", ignoreCase = true)) return parseGeo(url)
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.rawPath.orEmpty()
        val q = queryMap(uri.rawQuery)

        if (host == "maps.app.goo.gl" || host == "g.co" || (host == "goo.gl" && path.startsWith("/maps"))) {
            return LinkTarget.ShortLink(url)
        }
        if (!host.contains("google.")) return null

        // Explicit place-ID params (Maps URLs API).
        val placeId = q["query_place_id"] ?: q["destination_place_id"] ?: q["place_id"]

        // /maps/place/<name>/@lat,lng,…/data=…!3dLAT!4dLNG…!19sChIJ…
        val label = pathSegmentAfter(path, "/maps/place/") ?: pathSegmentAfter(path, "/maps/search/")
        val dataMatch = DATA_3D4D.findAll(url).lastOrNull()
        val dataPlaceId = DATA_PLACE_ID.find(url)?.groupValues?.get(1)
        if (dataMatch != null) {
            return LinkTarget.Coordinates(
                dataMatch.groupValues[1].toDouble(), dataMatch.groupValues[2].toDouble(),
                placeId = dataPlaceId ?: placeId, label = label,
            )
        }

        // Coordinate-bearing query params.
        for (key in listOf("daddr", "destination", "q", "query", "ll", "center")) {
            val v = q[key] ?: continue
            LAT_LNG.find(v)?.let { m ->
                return LinkTarget.Coordinates(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), placeId = placeId, label = label)
            }
        }
        if (placeId != null) return LinkTarget.PlaceId(placeId, label = label ?: q["query"] ?: q["destination"])

        // /maps/dir/<origin>/<dest>/… → last meaningful segment is the destination.
        pathSegmentAfter(path, "/maps/dir/")?.let { dirSegment ->
            val dest = dirSegment.split('/').map { decode(it) }.lastOrNull { it.isNotBlank() && !it.startsWith("@") && !it.startsWith("data=") }
            if (dest != null) {
                LAT_LNG.find(dest)?.let { m -> return LinkTarget.Coordinates(m.groupValues[1].toDouble(), m.groupValues[2].toDouble()) }
                return LinkTarget.Query(dest)
            }
        }

        // Viewport centre as a last-resort coordinate when the URL names a place.
        AT_COORDS.find(url)?.let { m ->
            if (label != null || dataPlaceId != null) {
                return LinkTarget.Coordinates(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), placeId = dataPlaceId, label = label)
            }
        }

        // Text-only queries.
        for (key in listOf("daddr", "destination", "q", "query")) {
            q[key]?.takeIf { it.isNotBlank() }?.let { return LinkTarget.Query(it) }
        }
        if (label != null) return LinkTarget.Query(label)
        return null
    }

    private fun parseGeo(url: String): LinkTarget? {
        // geo:lat,lng?q=lat,lng(label)  |  geo:0,0?q=text
        val body = url.substring(4)
        val qIdx = body.indexOf('?')
        val coordsPart = if (qIdx >= 0) body.substring(0, qIdx) else body
        val q = queryMap(if (qIdx >= 0) body.substring(qIdx + 1) else null)["q"]
        q?.let { qv ->
            val labelMatch = Regex("""^(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)(?:\((.*)\))?$""").find(qv)
            if (labelMatch != null) {
                return LinkTarget.Coordinates(labelMatch.groupValues[1].toDouble(), labelMatch.groupValues[2].toDouble(), label = labelMatch.groupValues[3].ifBlank { null })
            }
            if (qv.isNotBlank()) return LinkTarget.Query(qv)
        }
        LAT_LNG.find(coordsPart.substringBefore(';'))?.let { m ->
            val lat = m.groupValues[1].toDouble(); val lng = m.groupValues[2].toDouble()
            if (lat != 0.0 || lng != 0.0) return LinkTarget.Coordinates(lat, lng)
        }
        return null
    }

    private fun pathSegmentAfter(path: String, prefix: String): String? {
        val i = path.indexOf(prefix)
        if (i < 0) return null
        val rest = path.substring(i + prefix.length)
        val seg = if (prefix.endsWith("dir/")) rest else rest.substringBefore('/')
        return decode(seg).takeIf { it.isNotBlank() }
    }

    private fun queryMap(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.associate { kv ->
            val i = kv.indexOf('=')
            if (i < 0) decode(kv) to "" else decode(kv.substring(0, i)) to decode(kv.substring(i + 1))
        }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%20"), "UTF-8") }.getOrDefault(s).trim()
}
