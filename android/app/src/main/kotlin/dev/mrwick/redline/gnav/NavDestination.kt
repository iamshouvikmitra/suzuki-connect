package dev.mrwick.redline.gnav

/**
 * A resolved place to navigate to. Either [placeId] or both [lat]/[lng] must be
 * present; the Navigation SDK prefers a Place ID for routing quality and falls
 * back to coordinates.
 */
data class NavDestination(
    val label: String,
    val lat: Double?,
    val lng: Double?,
    val placeId: String?,
) {
    val hasCoordinates: Boolean get() = lat != null && lng != null
    val isRoutable: Boolean get() = placeId != null || hasCoordinates
}
