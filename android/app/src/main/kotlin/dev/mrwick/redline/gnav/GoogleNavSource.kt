package dev.mrwick.redline.gnav

import dev.mrwick.redline.nav.ManeuverMap
import dev.mrwick.redline.nav.NavSource
import dev.mrwick.redline.nav.toNavFrame
import dev.mrwick.redline.protocol.NavFrame
import dev.mrwick.redline.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide handoff between the Navigation SDK's turn-by-turn feed
 * ([NavInfoReceivingService] writes) and the BLE heartbeat ([dev.mrwick.redline.nav.NavMux]
 * observes). Non-null [frame] means "guidance is active, show this on the
 * cluster"; null lets the mux fall through to the idle clock.
 *
 * The feed arrives once per second while guidance runs. If it goes quiet for
 * [STALE_AFTER_MS] (SDK stopped without a STOPPED state, process hiccup) the
 * frame is cleared so the cluster never holds a frozen arrow for long.
 */
object GoogleNavSource : NavSource {
    private const val TAG = "GoogleNavSource"
    const val STALE_AFTER_MS: Long = 15_000

    private val _frame = MutableStateFlow<NavFrame?>(null)
    override val frame: StateFlow<NavFrame?> = _frame.asStateFlow()

    /** Latest snapshot for the in-app UI (road name, distances). */
    private val _latest = MutableStateFlow<TbtSnapshot?>(null)
    val latest: StateFlow<TbtSnapshot?> = _latest.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var staleJob: Job? = null
    @Volatile private var lastManeuverByte: Int = ManeuverMap.DEFAULT_CLUSTER_BYTE

    fun update(snapshot: TbtSnapshot) {
        _latest.value = snapshot
        val parsed = TbtFrameMapper.toParsedNavData(snapshot, lastManeuverByte)
        if (parsed == null) {
            clear("nav state ${snapshot.navState}")
            return
        }
        lastManeuverByte = parsed.maneuverId
        _frame.value = parsed.toNavFrame()
        staleJob?.cancel()
        staleJob = scope.launch {
            delay(STALE_AFTER_MS)
            if (_frame.value != null) clear("no feed update in ${STALE_AFTER_MS / 1000}s")
        }
    }

    fun clear(reason: String = "cleared") {
        if (_frame.value != null) AppLog.i(TAG, "frame cleared: $reason")
        staleJob?.cancel()
        _frame.value = null
        lastManeuverByte = ManeuverMap.DEFAULT_CLUSTER_BYTE
    }
}
