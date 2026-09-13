package dev.mrwick.redline.gnav

import dev.mrwick.redline.nav.ManeuverMap
import dev.mrwick.redline.nav.ParsedNavData
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Pure mapping from a [TbtSnapshot] to the a531 frame fields.
 *
 * The cluster's frame has room for exactly one maneuver glyph, one
 * "distance to next" (4 ASCII chars + unit), one ETA (6 chars) and one total
 * distance. Everything richer the SDK offers (lanes, next-step preview,
 * instruction text) has nowhere to land, so it is deliberately ignored here.
 */
object TbtFrameMapper {

    /**
     * @param previousManeuverByte the cluster byte currently displayed; reused
     *   when this update carries no glyph change (UNKNOWN maneuver, arrival,
     *   untranslatable Mappls ID) so the cluster does not flash a default arrow.
     * @return frame fields, or null when navigation is not en route.
     */
    fun toParsedNavData(
        s: TbtSnapshot,
        previousManeuverByte: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ParsedNavData? {
        if (!isActive(s.navState)) return null

        val mapplsId = s.maneuver?.let { GoogleManeuverMap.toMapplsId(it, s.roundaboutExit, s.drivingSide) }
        val clusterByte = mapplsId?.let { ManeuverMap.mapplsIdToClusterByte(it, null) } ?: previousManeuverByte

        val (distNext, distNextUnit) = formatMeters(s.distanceToStepMeters ?: 0)
        val (distTotal, distTotalUnit) = formatMeters(s.distanceToDestinationMeters ?: 0)
        val eta = formatEta(s.receivedAtMillis + (s.secondsToDestination ?: 0) * 1_000L, zone)

        return ParsedNavData(
            maneuverId = clusterByte,
            distNext = distNext,
            distNextUnit = distNextUnit,
            eta = eta,
            distTotal = distTotal,
            distTotalUnit = distTotalUnit,
            streetName = s.roadName,
        )
    }

    /** ENROUTE and REROUTING both keep the last known guidance on the cluster. */
    fun isActive(navState: Int): Boolean =
        navState == com.google.android.libraries.mapsplatform.turnbyturn.model.NavState.ENROUTE ||
            navState == com.google.android.libraries.mapsplatform.turnbyturn.model.NavState.REROUTING

    /**
     * Metres → (4-char ASCII, unit) exactly as the OEM app formats it:
     * `< 1000 m` → "0220"/M; `< 100 km` → "01.2"/K (one decimal);
     * otherwise whole kilometres "0123"/K, clamped to 9999.
     */
    fun formatMeters(meters: Int): Pair<String, String> {
        val m = meters.coerceAtLeast(0)
        return when {
            m < 1000 -> "%04d".format(Locale.ROOT, m) to "M"
            m < 100_000 -> {
                val tenths = (m + 50) / 100 // round to 0.1 km
                "%02d.%d".format(Locale.ROOT, tenths / 10, tenths % 10) to "K"
            }
            else -> "%04d".format(Locale.ROOT, (m / 1000).coerceAtMost(9999)) to "K"
        }
    }

    /** Arrival wall-clock as the 6-char 12-hour field the cluster expects, e.g. "0432PM". */
    fun formatEta(arrivalEpochMillis: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(arrivalEpochMillis).atZone(zone).toLocalTime()
        val hour12 = ((t.hour + 11) % 12) + 1
        val ampm = if (t.hour < 12) "AM" else "PM"
        return "%02d%02d%s".format(Locale.ROOT, hour12, t.minute, ampm)
    }
}
