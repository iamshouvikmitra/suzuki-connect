package dev.mrwick.redline.ui.navigate

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.navigation.ForceNightMode
import com.google.android.libraries.navigation.NavigationView
import dev.mrwick.redline.gnav.DestinationSearch
import dev.mrwick.redline.gnav.GoogleNavController
import dev.mrwick.redline.gnav.GoogleNavController.State
import dev.mrwick.redline.gnav.GoogleNavSource
import dev.mrwick.redline.gnav.NavDestination
import dev.mrwick.redline.gnav.SharedLinkParser
import dev.mrwick.redline.gnav.SharedLinkResolver
import dev.mrwick.redline.ui.theme.GixxerTokens
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-app Google navigation. Hosts the Navigation SDK's [NavigationView] (map,
 * route, voice guidance, reroutes) and layers REDLINE's own destination search
 * and Start/Stop controls on top. While guidance runs the SDK's turn-by-turn
 * feed drives the cluster via [GoogleNavSource] regardless of whether this
 * screen is visible.
 *
 * Also the landing point for shared Google Maps links: the pending share text
 * from [GoogleNavController.pendingShare] is resolved to a destination and
 * routed automatically, leaving the rider one tap ("Start") from guidance.
 */
@OptIn(FlowPreview::class)
@Composable
fun NavigateScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val scope = rememberCoroutineScope()
    val state by GoogleNavController.state.collectAsStateWithLifecycle()
    val pendingShare by GoogleNavController.pendingShare.collectAsStateWithLifecycle()
    val latestTbt by GoogleNavSource.latest.collectAsStateWithLifecycle()

    if (!GoogleNavController.hasApiKey) {
        MessagePane(
            title = "Navigation isn't configured",
            body = "This build has no Google Maps API key. Add MAPS_API_KEY to android/local.properties and rebuild.",
            onBack = onBack,
        )
        return
    }

    // --- Location permission gate (the SDK refuses to start without it) ---
    var hasLocation by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        hasLocation = res[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }
    if (!hasLocation) {
        MessagePane(
            title = "Location needed",
            body = "Turn-by-turn navigation needs precise location while riding.",
            onBack = onBack,
            action = "Allow location" to {
                permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
        )
        return
    }

    // --- Navigator init (idempotent) + keep screen on while here ---
    LaunchedEffect(activity) { activity?.let { GoogleNavController.init(it) } }
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val search = remember(ctx) { DestinationSearch(ctx.applicationContext) }
    val resolver = remember(search) { SharedLinkResolver(search) }
    val resolvingShare by GoogleNavController.resolving.collectAsStateWithLifecycle()
    val shareError by GoogleNavController.shareError.collectAsStateWithLifecycle()

    // --- Hand a shared link to the controller once the navigator is usable ---
    // Keyed on a stable Boolean (not the whole state) and doing no async work
    // here, so consuming the share can't cancel its own resolution.
    val navUsable = state is State.Ready || state is State.RoutePreview || state is State.Guiding || state is State.Error
    LaunchedEffect(pendingShare, navUsable) {
        if (pendingShare != null && navUsable) {
            GoogleNavController.consumePendingShare()?.let { text ->
                GoogleNavController.resolveShare(text, resolver, lastKnownLatLng(ctx))
            }
        }
    }

    // --- Search box state ---
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<DestinationSearch.Suggestion>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.length < 3) { suggestions = emptyList(); return@LaunchedEffect }
        delay(300) // debounce keystrokes → fewer autocomplete requests
        searching = true
        suggestions = search.suggest(query, lastKnownLatLng(ctx))
        searching = false
    }
    val showSearch = state is State.Ready || state is State.Error || state is State.Initializing || state is State.Idle
    val searchFocus = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(state, pendingShare) {
        // Opened from the Home "Where to?" bar with nothing shared → put the cursor in the box.
        if (!focusedOnce && state is State.Ready && pendingShare == null) {
            focusedOnce = true
            runCatching { searchFocus.requestFocus() }
        }
    }

    val guiding = state is State.Guiding
    val submitTypedQuery: () -> Unit = {
        val q = query.trim()
        if (q.isNotEmpty()) {
            query = ""; suggestions = emptyList()
            GoogleNavController.submitShare(q) // plain text → Places text search via the share path
        }
    }

    Box(Modifier.fillMaxSize().background(GixxerTokens.bg)) {
        NavigationMap(Modifier.fillMaxSize())

        // Top bar: back + search. Hidden while guiding so it never sits over the
        // SDK's own instruction header; Back moves down beside Stop.
        if (!guiding) Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = GixxerTokens.surface.copy(alpha = 0.92f)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = GixxerTokens.textPrimary) }
                }
                Spacer(Modifier.width(8.dp))
                if (showSearch) {
                    Surface(shape = RoundedCornerShape(14.dp), color = GixxerTokens.surface.copy(alpha = 0.92f), modifier = Modifier.weight(1f).height(48.dp)) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, tint = GixxerTokens.textMuted, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.weight(1f)) {
                                if (query.isEmpty()) Text(
                                    "Where to?", color = GixxerTokens.textMuted, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                BasicTextField(
                                    value = query,
                                    onValueChange = { query = it.replace("\n", " ") },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = GixxerTokens.textPrimary),
                                    cursorBrush = SolidColor(GixxerTokens.accent),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { submitTypedQuery() }),
                                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                                )
                            }
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, "Clear", tint = GixxerTokens.textMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            // Pasted link → treat like a share.
            LaunchedEffect(query) {
                if (SharedLinkParser.extractUrl(query) != null || query.startsWith("geo:")) {
                    val q = query; query = ""
                    GoogleNavController.submitShare(q)
                }
            }
            if (showSearch && (suggestions.isNotEmpty() || searching)) {
                Spacer(Modifier.height(6.dp))
                Surface(shape = RoundedCornerShape(14.dp), color = GixxerTokens.surface.copy(alpha = 0.96f)) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                        if (searching && suggestions.isEmpty()) item {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = GixxerTokens.accent)
                                Spacer(Modifier.width(10.dp)); Text("Searching…", color = GixxerTokens.textMuted)
                            }
                        }
                        items(suggestions, key = { it.placeId }) { s ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    query = ""; suggestions = emptyList()
                                    scope.launch {
                                        val d = search.fetch(s.placeId, s.primary)
                                        if (d != null) GoogleNavController.setDestination(d) else GoogleNavController.reportError("Couldn't load that place.")
                                    }
                                }.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Place, null, tint = GixxerTokens.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(s.primary, color = GixxerTokens.textPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(s.secondary, color = GixxerTokens.textMuted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom card: state + controls
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            color = GixxerTokens.surface.copy(alpha = 0.96f),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val s = state) {
                    State.Idle, State.Initializing -> StatusLine("Starting Google navigation…", spinner = true)
                    is State.Error -> {
                        Text(s.message, color = GixxerTokens.danger, style = MaterialTheme.typography.bodyMedium)
                        if (s.recoverable) OutlinedButton(onClick = { GoogleNavController.clearError(); activity?.let { GoogleNavController.init(it) } }) { Text("Retry") }
                    }
                    State.Ready -> {
                        if (resolvingShare != null) StatusLine("Finding $resolvingShare…", spinner = true)
                        else Text("Search above, or share a place from Google Maps to REDLINE.", color = GixxerTokens.textMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                    is State.Routing -> StatusLine("Routing to ${s.destination.label}…", spinner = true)
                    is State.RoutePreview -> {
                        DestinationLine(s.destination)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { GoogleNavController.startGuidance() },
                                colors = ButtonDefaults.buttonColors(containerColor = GixxerTokens.accent, contentColor = GixxerTokens.inkBlack),
                                modifier = Modifier.weight(1f),
                            ) { Text("START", fontWeight = FontWeight.Bold) }
                            OutlinedButton(onClick = { GoogleNavController.cancelRoute() }) { Text("Cancel") }
                        }
                    }
                    is State.Guiding -> {
                        DestinationLine(s.destination)
                        latestTbt?.let { t ->
                            val road = t.roadName?.let { " · $it" } ?: ""
                            val toStep = t.distanceToStepMeters?.let { "${it} m" } ?: "—"
                            val toDest = t.distanceToDestinationMeters?.let { "%.1f km".format(it / 1000.0) } ?: "—"
                            val mins = t.secondsToDestination?.let { "${(it + 30) / 60} min" } ?: "—"
                            Text("Next in $toStep$road", color = GixxerTokens.textPrimary, style = MaterialTheme.typography.bodyMedium)
                            Text("$toDest · $mins remaining · cluster ${if (GoogleNavSource.frame.value != null) "live" else "idle"}", color = GixxerTokens.textMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = onBack, modifier = Modifier.height(44.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = GixxerTokens.textPrimary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp)); Text("BACK", color = GixxerTokens.textPrimary)
                            }
                            Button(
                                onClick = { GoogleNavController.stopGuidance() },
                                colors = ButtonDefaults.buttonColors(containerColor = GixxerTokens.danger, contentColor = Color.White),
                                modifier = Modifier.weight(1f).height(44.dp),
                            ) { Text("STOP NAVIGATION", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                shareError?.let { Text(it, color = GixxerTokens.warning, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** Hosts the SDK view and forwards the Compose lifecycle to it. */
@Composable
private fun NavigationMap(modifier: Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val ctx = LocalContext.current
    val navView = remember {
        NavigationView(ctx).apply {
            onCreate(null)
            setForceNightMode(ForceNightMode.FORCE_NIGHT) // matches REDLINE's dark cockpit
            setEtaCardEnabled(true)
            setHeaderEnabled(true)
            setRecenterButtonEnabled(true)
        }
    }
    DisposableEffect(lifecycleOwner, navView) {
        var reached = Lifecycle.State.CREATED
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { navView.onStart(); reached = Lifecycle.State.STARTED }
                Lifecycle.Event.ON_RESUME -> { navView.onResume(); reached = Lifecycle.State.RESUMED }
                Lifecycle.Event.ON_PAUSE -> { navView.onPause(); reached = Lifecycle.State.STARTED }
                Lifecycle.Event.ON_STOP -> { navView.onStop(); reached = Lifecycle.State.CREATED }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Wind the view down through the states it is still in before destroying it.
            if (reached == Lifecycle.State.RESUMED) navView.onPause()
            if (reached >= Lifecycle.State.STARTED) navView.onStop()
            navView.onDestroy()
        }
    }
    AndroidView(factory = { navView }, modifier = modifier)
}

@Composable
private fun StatusLine(text: String, spinner: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (spinner) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = GixxerTokens.accent); Spacer(Modifier.width(10.dp)) }
        Text(text, color = GixxerTokens.textPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DestinationLine(d: NavDestination) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Place, null, tint = GixxerTokens.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(d.label, color = GixxerTokens.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}

@Composable
private fun MessagePane(title: String, body: String, onBack: () -> Unit, action: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxSize().background(GixxerTokens.bg).statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = GixxerTokens.textPrimary) }
        Text(title, color = GixxerTokens.textPrimary, style = MaterialTheme.typography.headlineSmall)
        Text(body, color = GixxerTokens.textMuted, style = MaterialTheme.typography.bodyMedium)
        action?.let { (label, run) -> Button(onClick = run) { Text(label) } }
    }
}

/** Best-effort last GPS fix for biasing place search; null when unavailable. */
private fun lastKnownLatLng(ctx: android.content.Context): LatLng? {
    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val lm = ctx.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager ?: return null
    val loc = try {
        lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
    } catch (_: SecurityException) { null }
    return loc?.let { LatLng(it.latitude, it.longitude) }
}
