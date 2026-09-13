package dev.mrwick.redline.telemetry

import dev.mrwick.redline.protocol.TelemetryFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the bike's most recent telemetry (a537).
 * Dashboard observes; RideLogger writes via the BikeBridgeService.
 */
object TelemetryRepository {
    private val _latest = MutableStateFlow<TelemetryFrame?>(null)
    val latest: StateFlow<TelemetryFrame?> = _latest.asStateFlow()

    /** Wall-clock millis of the last [update]; 0 when nothing has arrived or after [reset]. */
    @Volatile var lastUpdateMillis: Long = 0L
        private set

    /**
     * True while BikeBridgeService's DemoTelemetrySource is feeding synthetic
     * frames. Consumers that drive rider-facing behaviour off speed (the
     * active-ride overlay) must ignore demo data.
     */
    @Volatile var demoActive: Boolean = false

    /** True when the latest frame is younger than [maxAgeMs]. */
    fun isFresh(maxAgeMs: Long, now: Long = System.currentTimeMillis()): Boolean =
        _latest.value != null && now - lastUpdateMillis <= maxAgeMs

    private val _history = MutableStateFlow<List<TelemetryFrame>>(emptyList())
    /** Rolling window of the last 60 frames (~5 min at 5s cadence). */
    val history: StateFlow<List<TelemetryFrame>> = _history.asStateFlow()

    // PERF: mutate-in-place rolling buffer guarded by `this`. Prior impl
    // rebuilt the entire history list on every a537 sample
    // (`(_history.value + frame).takeLast(60)`), allocating ~60 references
    // per ~5s tick. ArrayDeque keeps the bounded window and we publish an
    // immutable snapshot for observers (audit finding 3.2).
    private val historyBuffer: ArrayDeque<TelemetryFrame> = ArrayDeque(HISTORY_SIZE)

    fun update(frame: TelemetryFrame) {
        lastUpdateMillis = System.currentTimeMillis()
        _latest.value = frame
        synchronized(this) {
            if (historyBuffer.size >= HISTORY_SIZE) historyBuffer.removeFirst()
            historyBuffer.addLast(frame)
            // R5: only publish the immutable snapshot when at least one collector is
            // active. The ArrayDeque push above always runs so that a subscriber
            // joining mid-ride immediately sees a correct rolling window; we just
            // skip the O(n) toList() copy when nobody is watching.
            if (_history.subscriptionCount.value > 0) {
                _history.value = historyBuffer.toList()
            }
        }
    }

    fun reset() {
        lastUpdateMillis = 0L
        _latest.value = null
        synchronized(this) {
            historyBuffer.clear()
            _history.value = emptyList()
        }
    }

    private const val HISTORY_SIZE = 60
}
