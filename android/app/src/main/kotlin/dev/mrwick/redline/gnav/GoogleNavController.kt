package dev.mrwick.redline.gnav

import android.app.Activity
import android.content.Context
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.Waypoint
import dev.mrwick.redline.BuildConfig
import dev.mrwick.redline.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide owner of the Navigation SDK [Navigator]. The Navigator is a
 * singleton inside the SDK and guidance runs in the SDK's own foreground
 * service, so it deliberately outlives the Navigate screen: the rider can put
 * the phone away and the turn-by-turn feed keeps driving the cluster.
 *
 * State machine: Idle → Initializing → Ready → Routing → RoutePreview → Guiding
 * (→ Ready on stop/arrival). Errors land in [State.Error] with a human message.
 */
object GoogleNavController {
    private const val TAG = "GoogleNav"

    sealed class State {
        data object Idle : State()
        data object Initializing : State()
        data object Ready : State()
        data class Routing(val destination: NavDestination) : State()
        data class RoutePreview(val destination: NavDestination) : State()
        data class Guiding(val destination: NavDestination) : State()
        data class Error(val message: String, val recoverable: Boolean = true) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Raw text of a share / VIEW intent waiting for the Navigate screen to consume. */
    private val _pendingShare = MutableStateFlow<String?>(null)
    val pendingShare: StateFlow<String?> = _pendingShare.asStateFlow()

    @Volatile private var navigator: Navigator? = null
    private var arrivalListener: Navigator.ArrivalListener? = null
    private var routeChangedListener: Navigator.RouteChangedListener? = null

    val hasApiKey: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()
    val isGuiding: Boolean get() = _state.value is State.Guiding

    fun submitShare(text: String?) {
        if (text.isNullOrBlank()) return
        AppLog.i(TAG, "share received: ${text.take(120)}")
        _pendingShare.value = text
    }

    fun consumePendingShare(): String? = _pendingShare.value.also { _pendingShare.value = null }

    /**
     * Idempotent. Shows the SDK's terms-of-service dialog on first use, then
     * registers the turn-by-turn feed service. Needs ACCESS_FINE_LOCATION
     * already granted (the SDK errors otherwise).
     */
    fun init(activity: Activity) {
        if (navigator != null || _state.value is State.Initializing) return
        if (!hasApiKey) {
            _state.value = State.Error("No Google Maps API key in this build.", recoverable = false)
            return
        }
        _state.value = State.Initializing
        NavigationApi.getNavigator(activity, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(nav: Navigator) {
                navigator = nav
                val appCtx = activity.applicationContext
                val ok = nav.registerServiceForNavUpdates(appCtx.packageName, NavInfoReceivingService::class.java.name, 1)
                AppLog.i(TAG, "navigator ready; feed registered=$ok")
                nav.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)
                // Keep guidance (and the cluster feed) alive if the app is swiped away.
                nav.setTaskRemovedBehavior(Navigator.TaskRemovedBehavior.CONTINUE_SERVICE)
                arrivalListener = Navigator.ArrivalListener {
                    AppLog.i(TAG, "arrived")
                    stopGuidance()
                }.also { nav.addArrivalListener(it) }
                routeChangedListener = Navigator.RouteChangedListener {
                    AppLog.i(TAG, "route changed (reroute)")
                }.also { nav.addRouteChangedListener(it) }
                // If guidance survived an app restart, reflect it.
                _state.value = if (nav.isGuidanceRunning) State.Guiding(NavDestination("Current route", null, null, null)) else State.Ready
            }

            override fun onError(errorCode: Int) {
                val msg = when (errorCode) {
                    NavigationApi.ErrorCode.NOT_AUTHORIZED -> "API key rejected: enable the Navigation SDK for this key in Google Cloud."
                    NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> "Navigation terms were not accepted."
                    NavigationApi.ErrorCode.NETWORK_ERROR -> "No network while starting navigation."
                    NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING -> "Location permission is required."
                    else -> "Navigation failed to start (code $errorCode)."
                }
                AppLog.w(TAG, "getNavigator error $errorCode")
                navigator = null
                _state.value = State.Error(msg)
            }
        })
    }

    /** Computes a route and lands in [State.RoutePreview]; the rider taps Start. */
    fun setDestination(dest: NavDestination) {
        val nav = navigator ?: run { _state.value = State.Error("Navigation not ready yet."); return }
        if (!dest.isRoutable) { _state.value = State.Error("That place has no usable location."); return }
        val waypoint = try {
            val b = Waypoint.builder().setTitle(dest.label)
            if (dest.placeId != null) b.setPlaceIdString(dest.placeId)
            else b.setLatLng(dest.lat!!, dest.lng!!)
            b.build()
        } catch (e: Waypoint.UnsupportedPlaceIdException) {
            if (dest.hasCoordinates) Waypoint.builder().setTitle(dest.label).setLatLng(dest.lat!!, dest.lng!!).build()
            else { _state.value = State.Error("Google can't route to that place."); return }
        }
        _state.value = State.Routing(dest)
        if (nav.isGuidanceRunning) nav.stopGuidance()
        nav.setDestination(waypoint).setOnResultListener { code ->
            _state.value = when (code) {
                Navigator.RouteStatus.OK -> State.RoutePreview(dest)
                Navigator.RouteStatus.NO_ROUTE_FOUND -> State.Error("No route found to ${dest.label}.")
                Navigator.RouteStatus.NETWORK_ERROR -> State.Error("Network error while routing.")
                Navigator.RouteStatus.LOCATION_DISABLED, Navigator.RouteStatus.LOCATION_UNKNOWN -> State.Error("Turn on GPS and try again.")
                Navigator.RouteStatus.QUOTA_CHECK_FAILED -> State.Error("Maps quota exceeded for this key.")
                Navigator.RouteStatus.ROUTE_CANCELED -> State.Ready
                else -> State.Error("Routing failed ($code).")
            }
            AppLog.i(TAG, "route status $code for ${dest.label}")
        }
    }

    fun startGuidance() {
        val nav = navigator ?: return
        val dest = (state.value as? State.RoutePreview)?.destination ?: return
        nav.startGuidance()
        _state.value = State.Guiding(dest)
        AppLog.i(TAG, "guidance started -> ${dest.label}")
    }

    fun stopGuidance() {
        navigator?.let {
            if (it.isGuidanceRunning) it.stopGuidance()
            it.clearDestinations()
        }
        GoogleNavSource.clear("guidance stopped")
        if (navigator != null) _state.value = State.Ready
        AppLog.i(TAG, "guidance stopped")
    }

    /** Drop the route preview without starting. */
    fun cancelRoute() {
        navigator?.clearDestinations()
        if (navigator != null) _state.value = State.Ready
    }

    fun clearError() { if (_state.value is State.Error) _state.value = if (navigator != null) State.Ready else State.Idle }

    /** Last known device location via the SDK's road-snapped provider is not needed; UI passes GPS separately. */
    @Suppress("unused")
    fun appContext(c: Context): Context = c.applicationContext
}
