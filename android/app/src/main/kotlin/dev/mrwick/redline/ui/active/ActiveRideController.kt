package dev.mrwick.redline.ui.active

import dev.mrwick.redline.telemetry.TelemetryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-singleton active-ride state machine.
 *
 * Observes [TelemetryRepository.latest] and manages the active-ride overlay lifecycle:
 *
 *  - [isActive] flips to true when speed > 5 km/h for 3 consecutive seconds.
 *  - [isActive] flips to false 30 seconds after speed drops to 0, or 10 seconds
 *    after telemetry stops arriving (bike off, BLE lost, demo mode turned off).
 *  - [dismiss] hides it and keeps it hidden until the bike has been stopped for
 *    10 seconds, so a tap mid-ride does not have it spring back on the next
 *    speed reading. Demo-mode frames never activate it.
 *  - [currentSpeedKmh] mirrors the latest reported speed (null when disconnected).
 *
 * The state machine runs on a background coroutine scoped to the process — it starts
 * once (object initialisation) and runs for the lifetime of the app process.
 *
 * Thread safety: all [MutableStateFlow] mutations happen on the coroutine dispatcher;
 * Compose collectors on the main thread see consistent snapshots.
 */
object ActiveRideController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isActive = MutableStateFlow(false)
    /** True when speed has exceeded the motion threshold and dismiss hasn't been called. */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _currentSpeedKmh = MutableStateFlow<Int?>(null)
    /** Latest reported speed in km/h; null when no telemetry is available. */
    val currentSpeedKmh: StateFlow<Int?> = _currentSpeedKmh.asStateFlow()

    // Internal counters for the state machine.
    // aboveThresholdTicks: consecutive ticks where speed > SPEED_THRESHOLD_KMH
    // stoppedTicks: consecutive ticks with speed == 0 or no fresh telemetry
    // dismissed: set by dismiss(); cleared only after a genuine stop (see REARM_TICKS)
    private var aboveThresholdTicks = 0
    private var stoppedTicks = 0
    private var dismissed = false

    private const val SPEED_THRESHOLD_KMH = 5
    /** Consecutive seconds above threshold before isActive flips true. */
    private const val ACTIVATE_TICKS = 3
    /** Seconds at speed 0 (fresh telemetry) before isActive flips false. */
    private const val DEACTIVATE_TICKS = 30
    /** Seconds without fresh telemetry before isActive flips false (bike off / BLE lost / demo off). */
    private const val STALE_DEACTIVATE_TICKS = 10
    /** Seconds stopped before a dismissed overlay may auto-show again on the next ride. */
    private const val REARM_TICKS = 10
    /** A frame older than this is treated as "no telemetry" — a537 arrives every ~5 s. */
    private const val FRESH_MS = 15_000L
    /** Polling cadence for the state-machine loop (ms). */
    private const val TICK_MS = 1_000L

    init {
        scope.launch {
            // Tick loop: poll TelemetryRepository.latest every second and
            // advance the state machine.
            while (true) {
                val fresh = TelemetryRepository.isFresh(FRESH_MS)
                val frame = if (fresh) TelemetryRepository.latest.value else null
                val speed = frame?.speedKmh ?: 0
                // Demo mode synthesises a sweeping speed; it must never pop the
                // rider-facing overlay.
                val eligible = fresh && !TelemetryRepository.demoActive

                _currentSpeedKmh.value = frame?.speedKmh

                when {
                    // Moving (real, fresh telemetry): count toward activation.
                    eligible && speed > SPEED_THRESHOLD_KMH -> {
                        stoppedTicks = 0
                        aboveThresholdTicks++
                        if (aboveThresholdTicks >= ACTIVATE_TICKS && !dismissed && !_isActive.value) {
                            _isActive.value = true
                        }
                    }
                    // Stopped or no usable telemetry: count toward deactivation / re-arm.
                    else -> {
                        aboveThresholdTicks = 0
                        stoppedTicks++
                        val limit = if (fresh) DEACTIVATE_TICKS else STALE_DEACTIVATE_TICKS
                        if (_isActive.value && stoppedTicks >= limit) _isActive.value = false
                        // A dismissed overlay re-arms only after the bike has actually
                        // stopped for a while — not on the next speed blip, which is
                        // what made it reappear every few seconds mid-ride.
                        if (dismissed && stoppedTicks >= REARM_TICKS) dismissed = false
                    }
                }

                delay(TICK_MS)
            }
        }
    }

    /**
     * Forces [isActive] to false and suppresses re-activation until the next
     * motion event (speed > threshold) is detected.
     *
     * Called by the user single-tapping the active-ride overlay to dismiss it.
     */
    fun dismiss() {
        dismissed = true
        _isActive.value = false
        aboveThresholdTicks = 0
        stoppedTicks = 0
    }
}
