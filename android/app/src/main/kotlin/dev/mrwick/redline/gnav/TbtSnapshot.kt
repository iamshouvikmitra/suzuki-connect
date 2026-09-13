package dev.mrwick.redline.gnav

/**
 * SDK-independent copy of the fields we consume from one Navigation SDK
 * `NavInfo` update. [NavInfoReceivingService] builds it on the feed thread;
 * [TbtFrameMapper] turns it into cluster frame fields. Keeping the SDK types
 * out of the mapper makes the mapping unit-testable without Robolectric.
 */
data class TbtSnapshot(
    /** `NavState` constant: ENROUTE / REROUTING / STOPPED / UNKNOWN. */
    val navState: Int,
    /** `Maneuver` constant of the upcoming step, or null when the SDK sent no step. */
    val maneuver: Int?,
    /** `StepInfo.getRoundaboutTurnNumber()` — exit ordinal, when the step is a roundabout. */
    val roundaboutExit: Int?,
    /** `DrivingSide` constant for the route. */
    val drivingSide: Int,
    /** Remaining metres to the upcoming maneuver. */
    val distanceToStepMeters: Int?,
    /** Remaining metres to the final destination. */
    val distanceToDestinationMeters: Int?,
    /** Remaining seconds to the final destination. */
    val secondsToDestination: Int?,
    /** True when the SDK flagged a reroute on this update (informational). */
    val routeChanged: Boolean,
    /** Upcoming road name, for the in-app UI / logs only. */
    val roadName: String?,
    /** Wall-clock millis when the update was received. */
    val receivedAtMillis: Long,
)
