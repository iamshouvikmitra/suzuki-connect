package dev.mrwick.redline.gnav

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager
import com.google.android.libraries.mapsplatform.turnbyturn.model.DrivingSide
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import dev.mrwick.redline.util.AppLog

/**
 * Bound by the Navigation SDK (see `Navigator.registerServiceForNavUpdates`)
 * to receive one `NavInfo` per second while guidance runs. Each message is
 * flattened into a [TbtSnapshot] and handed to [GoogleNavSource], which the
 * BLE heartbeat turns into a531 frames for the cluster.
 *
 * Runs in our own process, so the singleton handoff is safe. The handler must
 * not hold a strong reference to the service (SDK guidance; avoids a leak).
 */
class NavInfoReceivingService : Service() {

    private lateinit var incomingMessenger: Messenger
    private lateinit var thread: HandlerThread

    private class IncomingNavStepHandler(
        looper: Looper,
        private val turnByTurnManager: TurnByTurnManager = TurnByTurnManager.createInstance(),
    ) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            if (msg.what != TurnByTurnManager.MSG_NAV_INFO) return
            val navInfo: NavInfo = try {
                turnByTurnManager.readNavInfoFromBundle(msg.data) ?: return
            } catch (t: Throwable) {
                AppLog.w(TAG, "readNavInfoFromBundle failed: ${t.message}")
                return
            }
            GoogleNavSource.update(navInfo.toSnapshot())
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("NavInfoReceivingService", Process.THREAD_PRIORITY_DEFAULT).also { it.start() }
        incomingMessenger = Messenger(IncomingNavStepHandler(thread.looper))
        AppLog.i(TAG, "created")
    }

    override fun onBind(intent: Intent?): IBinder = incomingMessenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        GoogleNavSource.clear("feed service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        GoogleNavSource.clear("feed service destroyed")
        thread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NavInfoService"

        internal fun NavInfo.toSnapshot(now: Long = System.currentTimeMillis()): TbtSnapshot {
            val step = currentStep
            return TbtSnapshot(
                navState = navState,
                maneuver = step?.maneuver,
                roundaboutExit = step?.roundaboutTurnNumber,
                drivingSide = step?.drivingSide ?: DrivingSide.NONE,
                distanceToStepMeters = distanceToCurrentStepMeters,
                distanceToDestinationMeters = distanceToFinalDestinationMeters,
                secondsToDestination = timeToFinalDestinationSeconds,
                routeChanged = routeChanged,
                roadName = step?.fullRoadName?.takeIf { it.isNotBlank() } ?: step?.simpleRoadName,
                receivedAtMillis = now,
            )
        }
    }
}
