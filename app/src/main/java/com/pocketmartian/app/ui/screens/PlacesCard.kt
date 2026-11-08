package com.pocketmartian.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.pocketmartian.app.data.places.ATTRIBUTION
import com.pocketmartian.app.data.places.MapSnapshot
import com.pocketmartian.app.data.places.Place
import com.pocketmartian.app.data.places.PlacesRepository
import com.pocketmartian.app.data.places.PlacesResult
import com.pocketmartian.app.data.places.WalkingRoute
import com.pocketmartian.app.data.places.routeLabel
import com.pocketmartian.app.data.places.formatDistance
import com.pocketmartian.app.data.places.hoursLabel
import com.pocketmartian.app.data.places.walkLabel
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.TileDownloader
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import android.graphics.drawable.Drawable
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale

/** The pins' red, so the numbers in the list match the map at a glance. */
private val PinRed = Color(0xFFD32F2F)

/** The live map to open: [focus] is a place's number, and [route] draws the walk to it. */
internal data class PlacesMapRequest(val result: PlacesResult, val focus: Int? = null, val route: Boolean = false)

/**
 * Places Grok found, under its answer: a picture of the map, then one row per place with
 * a button for directions. Tapping the map or a row opens the live map.
 */
@Composable
internal fun PlacesCard(result: PlacesResult, mapUri: Uri?, onOpenMap: (PlacesMapRequest) -> Unit) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column {
            if (mapUri != null) {
                AsyncImage(
                    model = mapUri,
                    contentDescription = "Map of ${result.places.size} places near " +
                        (result.origin.label ?: "your location"),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(MapSnapshot.WIDTH.toFloat() / MapSnapshot.HEIGHT)
                        .clickable { onOpenMap(PlacesMapRequest(result)) }
                )
            } else {
                // Offline when the answer came: the live map can still try.
                TextButton(onClick = { onOpenMap(PlacesMapRequest(result)) }, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Show on map", modifier = Modifier.padding(start = 8.dp))
                }
            }
            result.places.forEach { place ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                PlaceRow(
                    place = place,
                    onClick = { onOpenMap(PlacesMapRequest(result, place.number)) },
                    onDirections = { via ->
                        if (via == DirectionsVia.ROUTE) onOpenMap(PlacesMapRequest(result, place.number, route = true))
                        else openDirections(context, via, place)
                    }
                )
            }
            Text(
                // The map picture carries its own credit; without one, the card does.
                text = "Distances are in a straight line." + if (mapUri == null) " $ATTRIBUTION" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun PlaceRow(place: Place, onClick: () -> Unit, onDirections: (DirectionsVia) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .background(PinRed, CircleShape)
        ) {
            Text(
                text = place.number.toString(),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = place.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // The address on a line of its own, so it can't push out distance and hours.
            place.address?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = placeFacts(place),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        DirectionsButton(onChoose = onDirections)
    }
}

/** "300 m · 4 min walk · Open 24 hours", as much as is known. */
internal fun placeFacts(place: Place): String =
    listOfNotNull(
        formatDistance(place.distanceM),
        walkLabel(place.distanceM),
        hoursLabel(place.openingHours)
    ).joinToString(" · ")

/** Where Directions can take a place. */
internal enum class DirectionsVia(val label: String) {
    /**
     * Drawn on the app's own map. openstreetmap.org's directions page, offered first, gave
     * a phone only half the screen for the map, under the list of turns.
     */
    ROUTE("Walking route"),
    GOOGLE_MAPS("Google Maps"),
    OTHER_APP("Another map app")
}

/**
 * The link that opens [place] elsewhere: walking directions in Google Maps, or a geo: link,
 * which OsmAnd, Organic Maps and other OpenStreetMap apps open at the place, with their
 * own routing. Null for [DirectionsVia.ROUTE], which the app draws itself.
 */
internal fun directionsUri(via: DirectionsVia, place: Place): String? {
    val to = String.format(Locale.US, "%.6f,%.6f", place.lat, place.lon)
    return when (via) {
        DirectionsVia.ROUTE -> null
        DirectionsVia.GOOGLE_MAPS -> "https://www.google.com/maps/dir/?api=1&travelmode=walking&destination=$to"
        DirectionsVia.OTHER_APP -> "geo:$to?q=$to(${java.net.URLEncoder.encode(place.name, "UTF-8").replace("+", "%20")})"
    }
}

private fun openDirections(context: Context, via: DirectionsVia, place: Place) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(directionsUri(via, place) ?: return))
    // A list of map apps every time, rather than whichever one Android last remembered.
    val intent = if (via == DirectionsVia.OTHER_APP) Intent.createChooser(view, "Open with") else view
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this phone can open it", Toast.LENGTH_SHORT).show()
    }
}

/** Directions, with a choice of where to open them. */
@Composable
private fun DirectionsButton(onChoose: (DirectionsVia) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.heightIn(min = 36.dp)
        ) {
            Icon(Icons.Outlined.Directions, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("Directions", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DirectionsVia.entries.forEach { via ->
                DropdownMenuItem(
                    text = { Text(via.label) },
                    onClick = {
                        open = false
                        onChoose(via)
                    }
                )
            }
        }
    }
}

/** Where the walking route is: on its way, drawn, or not to be had. */
private sealed class RouteState {
    object Loading : RouteState()
    class Ready(val route: WalkingRoute) : RouteState()
    object Failed : RouteState()
}

/**
 * The live map, full screen: pinch, pan, and tap a pin for its name. A [request] with a
 * focus opens with that place's label showing, or with [PlacesMapRequest.route], with the
 * walking route to it drawn and its distance and time along the top.
 */
@Composable
internal fun PlacesMapOverlay(request: PlacesMapRequest, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val result = request.result
    val target = result.places.firstOrNull { it.number == request.focus }?.takeIf { request.route }
    val mapView = remember(request) { placesMapView(context, result, request.focus, target != null, density) }
    var routeState by remember(request) { mutableStateOf<RouteState>(RouteState.Loading) }

    if (target != null) {
        LaunchedEffect(request) {
            val places = EntryPointAccessors.fromApplication(context.applicationContext, MapHttpEntryPoint::class.java)
                .placesRepository()
            val route = places.walkingRoute(result.origin, target.lat, target.lon)
            routeState = if (route != null) {
                showRoute(mapView, route, density)
                RouteState.Ready(route)
            } else {
                RouteState.Failed
            }
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp)
        ) {
            IconButton(
                onClick = onDismiss,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Close map")
            }
            if (target != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .weight(1f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = when (val state = routeState) {
                                RouteState.Loading -> "Finding a walking route\u2026"
                                is RouteState.Ready -> routeLabel(state.route)
                                RouteState.Failed -> "Couldn't get a walking route"
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (routeState == RouteState.Failed) {
                                "Try Directions \u2192 Google Maps"
                            } else {
                                "to ${target.number}. ${target.name}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Draw [route] under the pins and fit the map to it. */
private fun showRoute(map: MapView, route: WalkingRoute, density: Float) {
    val points = route.points.map { (lat, lon) -> GeoPoint(lat, lon) }
    // A white edge keeps the line readable over roads of the same blue.
    val casing = Polyline(map).apply {
        setPoints(points)
        outlinePaint.color = AndroidColor.WHITE
        outlinePaint.strokeWidth = 9f * density
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val line = Polyline(map).apply {
        setPoints(points)
        outlinePaint.color = AndroidColor.rgb(0x19, 0x76, 0xD2)
        outlinePaint.strokeWidth = 5f * density
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
    }
    map.overlays.add(0, line)
    map.overlays.add(0, casing)
    map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, (72 * density).toInt())
    map.invalidate()
}

private fun placesMapView(context: Context, result: PlacesResult, focus: Int?, route: Boolean, density: Float): MapView {
    // osmdroid keeps its tiles in the app's own cache, so it needs no storage permission;
    // the tile servers refuse requests that don't name the app.
    Configuration.getInstance().apply {
        userAgentValue = PlacesRepository.USER_AGENT
        osmdroidBasePath = File(context.cacheDir, "osmdroid")
        osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
    }
    val http = EntryPointAccessors.fromApplication(context.applicationContext, MapHttpEntryPoint::class.java).okHttpClient()
    val map = MapView(context, OkHttpTileProvider(context.applicationContext, http))
    map.setMultiTouchControls(true)
    map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
    map.isTilesScaledToDpi = true

    val origin = GeoPoint(result.origin.lat, result.origin.lon)
    // With a route on its way, straight lines would only be in its way.
    if (!route) result.places.forEach { place ->
        map.overlays.add(Polyline(map).apply {
            setPoints(listOf(origin, GeoPoint(place.lat, place.lon)))
            outlinePaint.color = AndroidColor.argb(200, 0x19, 0x76, 0xD2)
            outlinePaint.strokeWidth = 2.5f * density
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
        })
    }
    map.overlays.add(Marker(map).apply {
        position = origin
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = BitmapDrawable(context.resources, MapSnapshot.originBitmap(result.origin.isUser, density))
        title = result.origin.label ?: "You"
    })
    var focused: Marker? = null
    // Added last to first so pin 1 is drawn on top.
    result.places.reversed().forEach { place ->
        val marker = Marker(map).apply {
            position = GeoPoint(place.lat, place.lon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = BitmapDrawable(context.resources, MapSnapshot.pinBitmap(place.number, density))
            title = "${place.number}. ${place.name}"
            snippet = listOfNotNull(place.address, placeFacts(place)).joinToString("<br>")
        }
        map.overlays.add(marker)
        if (place.number == focus) focused = marker
    }
    map.overlays.add(CopyrightOverlay(context))

    // Start where the places are, not at 0°, 0° until the first layout zooms to them.
    map.controller.setZoom(15.0)
    map.controller.setCenter(origin)
    // A route frames just the walk; otherwise every place.
    val framed = if (route) result.places.filter { it.number == focus } else result.places
    val points = listOf(origin) + framed.map { GeoPoint(it.lat, it.lon) }
    map.addOnFirstLayoutListener { _, _, _, _, _ ->
        val box = BoundingBox.fromGeoPoints(points)
        if (box.latitudeSpan < 0.0005 && box.longitudeSpanWithDateLine < 0.0005) {
            map.controller.setZoom(17.0)
            map.controller.setCenter(box.centerWithDateLine)
        } else {
            map.zoomToBoundingBox(box, false, (56 * density).toInt())
        }
        // With a route, the bar along the top names the place instead.
        if (!route) focused?.let {
            map.controller.setCenter(it.position)
            it.showInfoWindow()
        }
    }
    return map
}

/** The app's own HTTP client, for the map's tiles. */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface MapHttpEntryPoint {
    fun okHttpClient(): OkHttpClient
    fun placesRepository(): PlacesRepository
}

/**
 * OpenStreetMap tiles fetched with the app's HTTP client rather than osmdroid's own.
 *
 * osmdroid looks hosts up with the phone's resolver alone. On the reporting phone (on a
 * VPN) that resolver could not find tile.openstreetmap.org at all, so the map would stay
 * blank; the app's client falls back to DNS over HTTPS (see ResilientDns) and shares
 * what it found with the rest of the app.
 */
private class OkHttpTileProvider(context: Context, http: OkHttpClient) :
    MapTileProviderBasic(context, TileSourceFactory.MAPNIK.also { pendingHttp = http }) {

    // Called from the superclass constructor, before this class's fields exist; the
    // client reaches it through [pendingHttp], set just before.
    override fun createDownloaderProvider(check: INetworkAvailablityCheck?, source: ITileSource?): MapTileDownloader =
        super.createDownloaderProvider(check, source).apply {
            pendingHttp?.let { setTileDownloader(OkHttpTileDownloader(it)) }
        }

    private companion object {
        @Volatile var pendingHttp: OkHttpClient? = null
    }
}

private class OkHttpTileDownloader(private val http: OkHttpClient) : TileDownloader() {
    override fun downloadTile(
        index: Long,
        redirectCount: Int,
        url: String?,
        cache: IFilesystemCache?,
        source: OnlineTileSourceBase?
    ): Drawable? {
        if (url == null || source == null) return null
        return try {
            val request = Request.Builder().url(url).header("User-Agent", PlacesRepository.USER_AGENT).build()
            http.newCall(request).execute().use { resp ->
                val bytes = resp.body?.bytes()?.takeIf { resp.isSuccessful } ?: return null
                // Saved without an expiry, a tile counts as stale at once, and the map
                // fetched the same few tiles again several times a second.
                val expires = computeExpirationTime(resp.header("Expires"), resp.header("Cache-Control"), System.currentTimeMillis())
                cache?.saveFile(source, index, ByteArrayInputStream(bytes), expires)
                source.getDrawable(ByteArrayInputStream(bytes))
            }
        } catch (e: Exception) {
            null
        }
    }
}
