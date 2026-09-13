package dev.mrwick.redline.gnav

import com.google.android.libraries.mapsplatform.turnbyturn.model.DrivingSide
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver

/**
 * Stage 1 (Google flavour): Navigation SDK [Maneuver] constant → Mappls
 * maneuver ID (0..75), the vocabulary the OEM translation table in
 * `ManeuverMap.mapplsIdToClusterByte` understands.
 *
 * Unlike the parked notification-scrape path this is a *structured* mapping:
 * the SDK tells us the maneuver type and, for roundabouts, the exit ordinal,
 * so nothing here is inferred from text. Roundabout exits use the per-exit
 * Mappls IDs 65..71 (exit 1..7) that Stage 2 maps to cluster bytes 20..26;
 * anything without an exit count falls back to the generic roundabout glyph 72.
 *
 * Returns `null` for "no arrow change" cases (UNKNOWN, arrival) so the caller
 * can leave the previous glyph on the cluster instead of flashing a default.
 *
 * Mappls IDs come from `docs/mappls-id-icons.md`.
 */
object GoogleManeuverMap {

    private const val MAPPLS_TURN_LEFT = 0
    private const val MAPPLS_SLIGHT_LEFT = 1
    private const val MAPPLS_SHARP_LEFT = 2
    private const val MAPPLS_TURN_RIGHT = 3
    private const val MAPPLS_SLIGHT_RIGHT = 4
    private const val MAPPLS_SHARP_RIGHT = 5
    private const val MAPPLS_UTURN_LEFT = 6
    private const val MAPPLS_STRAIGHT = 7
    private const val MAPPLS_KEEP_LEFT = 11
    private const val MAPPLS_KEEP_RIGHT = 12
    private const val MAPPLS_FORK_LEFT = 15
    private const val MAPPLS_FORK_RIGHT = 16
    private const val MAPPLS_MERGE_LEFT = 19
    private const val MAPPLS_MERGE_RIGHT = 20
    private const val MAPPLS_FERRY = 36
    private const val MAPPLS_ARRIVE = 40
    private const val MAPPLS_UTURN_RIGHT = 41
    private const val MAPPLS_ROUNDABOUT_EXIT_BASE = 64 // exit n → 64 + n, n in 1..7
    private const val MAPPLS_ROUNDABOUT_GENERIC = 72
    private const val MAPPLS_EXIT_LEFT = 73
    private const val MAPPLS_EXIT_RIGHT = 75

    /**
     * @param maneuver one of the [Maneuver] constants.
     * @param roundaboutExit exit ordinal from `StepInfo.getRoundaboutTurnNumber()`, if any.
     * @param drivingSide [DrivingSide] of the route; used only to pick a side for
     *   direction-less off-ramps (India drives on the left, so exits are usually left).
     */
    fun toMapplsId(maneuver: Int, roundaboutExit: Int? = null, drivingSide: Int = DrivingSide.NONE): Int? {
        val exitDefault = if (drivingSide == DrivingSide.RIGHT) MAPPLS_EXIT_RIGHT else MAPPLS_EXIT_LEFT
        return when (maneuver) {
            Maneuver.UNKNOWN -> null
            Maneuver.DEPART, Maneuver.STRAIGHT, Maneuver.NAME_CHANGE -> MAPPLS_STRAIGHT
            Maneuver.DESTINATION, Maneuver.DESTINATION_LEFT, Maneuver.DESTINATION_RIGHT -> MAPPLS_ARRIVE

            Maneuver.TURN_LEFT, Maneuver.ON_RAMP_LEFT -> MAPPLS_TURN_LEFT
            Maneuver.TURN_RIGHT, Maneuver.ON_RAMP_RIGHT -> MAPPLS_TURN_RIGHT
            Maneuver.TURN_KEEP_LEFT, Maneuver.ON_RAMP_KEEP_LEFT -> MAPPLS_KEEP_LEFT
            Maneuver.TURN_KEEP_RIGHT, Maneuver.ON_RAMP_KEEP_RIGHT -> MAPPLS_KEEP_RIGHT
            Maneuver.TURN_SLIGHT_LEFT, Maneuver.ON_RAMP_SLIGHT_LEFT -> MAPPLS_SLIGHT_LEFT
            Maneuver.TURN_SLIGHT_RIGHT, Maneuver.ON_RAMP_SLIGHT_RIGHT -> MAPPLS_SLIGHT_RIGHT
            Maneuver.TURN_SHARP_LEFT, Maneuver.ON_RAMP_SHARP_LEFT, Maneuver.OFF_RAMP_SHARP_LEFT -> MAPPLS_SHARP_LEFT
            Maneuver.TURN_SHARP_RIGHT, Maneuver.ON_RAMP_SHARP_RIGHT, Maneuver.OFF_RAMP_SHARP_RIGHT -> MAPPLS_SHARP_RIGHT

            // Clockwise U-turn = through the right (left-hand traffic, e.g. India).
            Maneuver.TURN_U_TURN_CLOCKWISE, Maneuver.ON_RAMP_U_TURN_CLOCKWISE,
            Maneuver.OFF_RAMP_U_TURN_CLOCKWISE -> MAPPLS_UTURN_RIGHT
            Maneuver.TURN_U_TURN_COUNTERCLOCKWISE, Maneuver.ON_RAMP_U_TURN_COUNTERCLOCKWISE,
            Maneuver.OFF_RAMP_U_TURN_COUNTERCLOCKWISE -> MAPPLS_UTURN_LEFT

            Maneuver.MERGE_LEFT -> MAPPLS_MERGE_LEFT
            Maneuver.MERGE_RIGHT, Maneuver.MERGE_UNSPECIFIED -> MAPPLS_MERGE_RIGHT
            Maneuver.FORK_LEFT -> MAPPLS_FORK_LEFT
            Maneuver.FORK_RIGHT -> MAPPLS_FORK_RIGHT
            Maneuver.ON_RAMP_UNSPECIFIED -> MAPPLS_STRAIGHT

            Maneuver.OFF_RAMP_LEFT, Maneuver.OFF_RAMP_KEEP_LEFT, Maneuver.OFF_RAMP_SLIGHT_LEFT -> MAPPLS_EXIT_LEFT
            Maneuver.OFF_RAMP_RIGHT, Maneuver.OFF_RAMP_KEEP_RIGHT, Maneuver.OFF_RAMP_SLIGHT_RIGHT -> MAPPLS_EXIT_RIGHT
            Maneuver.OFF_RAMP_UNSPECIFIED -> exitDefault

            Maneuver.FERRY_BOAT, Maneuver.FERRY_TRAIN -> MAPPLS_FERRY

            else -> if (isRoundabout(maneuver)) roundaboutId(roundaboutExit) else null
        }
    }

    /** True for every ROUNDABOUT_* constant (43..62 in the SDK's numbering). */
    fun isRoundabout(maneuver: Int): Boolean =
        maneuver in Maneuver.ROUNDABOUT_CLOCKWISE..Maneuver.ROUNDABOUT_EXIT_COUNTERCLOCKWISE

    private fun roundaboutId(exit: Int?): Int =
        if (exit != null && exit in 1..7) MAPPLS_ROUNDABOUT_EXIT_BASE + exit else MAPPLS_ROUNDABOUT_GENERIC
}
