package dev.mrwick.redline.gnav

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.RectangularBounds
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.SearchByTextRequest
import dev.mrwick.redline.util.AppLog
import kotlinx.coroutines.tasks.await

/**
 * Thin coroutine wrapper over the Places SDK (New): autocomplete for the
 * in-app search box, place-details fetch for a chosen prediction, and text
 * search for shared links that only carry a place name.
 *
 * Requires `Places.initializeWithNewPlacesApiEnabled` to have run (GixxerApp
 * does this when an API key is configured); [isAvailable] is false otherwise.
 */
class DestinationSearch(context: Context) {

    data class Suggestion(val placeId: String, val primary: String, val secondary: String)

    private val client: PlacesClient? = if (Places.isInitialized()) Places.createClient(context) else null
    val isAvailable: Boolean get() = client != null

    /** Fresh token per search session; billed as one session until [fetch] is called. */
    private var sessionToken: AutocompleteSessionToken = AutocompleteSessionToken.newInstance()

    private val fields = listOf(Place.Field.ID, Place.Field.DISPLAY_NAME, Place.Field.LOCATION, Place.Field.FORMATTED_ADDRESS)

    suspend fun suggest(query: String, near: LatLng?): List<Suggestion> {
        val c = client ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val req = FindAutocompletePredictionsRequest.builder()
            .setQuery(query)
            .setSessionToken(sessionToken)
            .apply {
                if (near != null) {
                    setOrigin(near)
                    // ~50 km box around the rider — bias, not restrict.
                    setLocationBias(RectangularBounds.newInstance(
                        LatLng(near.latitude - 0.45, near.longitude - 0.45),
                        LatLng(near.latitude + 0.45, near.longitude + 0.45),
                    ))
                }
            }
            .build()
        return try {
            c.findAutocompletePredictions(req).await().autocompletePredictions.map {
                Suggestion(it.placeId, it.getPrimaryText(null).toString(), it.getSecondaryText(null).toString())
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "autocomplete failed: ${t.message}")
            emptyList()
        }
    }

    /** Resolve a prediction (or any Place ID) to a routable destination. Ends the autocomplete session. */
    suspend fun fetch(placeId: String, fallbackLabel: String? = null): NavDestination? {
        val c = client ?: return fallbackLabel?.let { NavDestination(it, null, null, placeId) }
        val req = FetchPlaceRequest.builder(placeId, fields).setSessionToken(sessionToken).build()
        sessionToken = AutocompleteSessionToken.newInstance()
        return try {
            c.fetchPlace(req).await().place.toDestination(placeId, fallbackLabel)
        } catch (t: Throwable) {
            AppLog.w(TAG, "fetchPlace failed: ${t.message}")
            fallbackLabel?.let { NavDestination(it, null, null, placeId) }
        }
    }

    /** Free-text lookup ("Cafe Coffee Day Koramangala") → best single match. */
    suspend fun searchText(query: String, near: LatLng?): NavDestination? {
        val c = client ?: return null
        val req = SearchByTextRequest.builder(query, fields)
            .setMaxResultCount(1)
            .apply {
                if (near != null) setLocationBias(RectangularBounds.newInstance(
                    LatLng(near.latitude - 0.9, near.longitude - 0.9),
                    LatLng(near.latitude + 0.9, near.longitude + 0.9),
                ))
            }
            .build()
        return try {
            c.searchByText(req).await().places.firstOrNull()?.let { it.toDestination(it.id, query) }
        } catch (t: Throwable) {
            AppLog.w(TAG, "searchByText failed: ${t.message}")
            null
        }
    }

    private fun Place.toDestination(placeId: String?, fallbackLabel: String?): NavDestination? {
        val loc = location
        val id = id ?: placeId
        if (loc == null && id == null) return null
        return NavDestination(
            label = displayName?.takeIf { it.isNotBlank() } ?: fallbackLabel ?: formattedAddress ?: "Destination",
            lat = loc?.latitude,
            lng = loc?.longitude,
            placeId = id,
        )
    }

    private companion object { const val TAG = "DestinationSearch" }
}
