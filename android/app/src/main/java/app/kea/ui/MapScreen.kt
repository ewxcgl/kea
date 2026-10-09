package app.kea.ui

import android.graphics.Color as AndroidColor
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.kea.R
import app.kea.crypto.CanonicalJson
import app.kea.data.Member
import app.kea.data.Palette
import app.kea.data.toColourInt
import app.kea.data.colour
import app.kea.data.Session
import app.kea.data.Store
import app.kea.geo.Geo
import app.kea.net.WatchGate
import app.kea.service.ShareService
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** Map style URLs. Key-free, no signup, OpenStreetMap data. */
object MapStyles {
    const val DEFAULT = "https://tiles.openfreemap.org/styles/liberty"
    val ALL = mapOf(
        "liberty" to "https://tiles.openfreemap.org/styles/liberty",
        "positron" to "https://tiles.openfreemap.org/styles/positron",
        "bright" to "https://tiles.openfreemap.org/styles/bright",
        "fiord" to "https://tiles.openfreemap.org/styles/fiord",
    )
}

/**
 * The main screen.
 *
 * MapLibre is a View, so it is hosted with [AndroidView] and driven through
 * remembered callbacks rather than Compose state. Markers are re-published as
 * GeoJSON whenever the roster changes, which keeps the diffing inside MapLibre
 * where it is cheap instead of re-creating views per member.
 *
 * Tapping the map arms a new zone at that point — the coordinates come from the
 * map projection, so they are exactly where the finger landed rather than
 * snapped to something.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MapScreen(
    session: Session,
    store: Store,
    onRequestLocation: () -> Unit,
    onPlaceZone: (lat: Double, lon: Double) -> Unit,
    onOpenZones: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                map.setStyle(MapStyles.DEFAULT) { style -> installLayers(style) }
                map.addOnMapClickListener { point ->
                    // MapLibre reports the tapped coordinate directly.
                    onPlaceZone(point.latitude, point.longitude)
                    // Return false so the default camera behaviour is kept.
                    false
                }
            }
        }
    }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var pendingCamera by remember { mutableStateOf<LatLng?>(null) }
    var framed by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    val liveMembers = session.members.filter { it.lat != null && it.lon != null }

    // Frame everyone once, as soon as there is something to frame. Without this
    // the map stays at the world view while waiting for the first fix, which
    // reads as "the map is broken" rather than "nobody has moved yet".
    LaunchedEffect(map, liveMembers.size) {
        val m = map ?: return@LaunchedEffect
        if (framed || liveMembers.isEmpty()) return@LaunchedEffect
        framed = true
        runCatching {
            val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
                .apply { liveMembers.forEach { p -> include(LatLng(p.lat!!, p.lon!!)) } }
                .build()
            m.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(bounds, 120),
                800,
            )
        }
    }

    LaunchedEffect(map, liveMembers, session.zones) {
        map?.render(session, liveMembers)
    }

    LaunchedEffect(map, pendingCamera) {
        val target = pendingCamera ?: return@LaunchedEffect
        map?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(target, 15.0))
        pendingCamera = null
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            // No Modifier.clickable here on purpose: MapLibre needs the raw
            // touches for pan/zoom, and a clickable parent swallows them.
            modifier = Modifier.fillMaxSize(),
        )

        // Sharing state: the one thing the user most needs to know, because it
        // tells them whether their phone is actually transmitting.
        SharingBanner(
            session = session,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(12.dp),
        )

        FloatingActionButton(
            onClick = {
                val centre = liveMembers.firstOrNull { it.isSelf } ?: liveMembers.firstOrNull()
                pendingCamera = centre?.let { LatLng(it.lat!!, it.lon!!) }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) { Icon(Icons.Filled.MyLocation, stringResource(R.string.a11y_my_location)) }

        ExtendedFloatingActionButton(
            onClick = onOpenZones,
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text(stringResource(R.string.zone_add)) },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        )

        // Members list as a bottom sheet, so a glance gives the whole group.
        Card(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(vertical = 8.dp),
            ) {
                items(liveMembers, key = { it.deviceId }) { member ->
                    MemberRow(
                        member = member,
                        onRequestLive = {
                            ShareService.requestPull(context, member.deviceId, WatchGate.PULL_DEFAULT_MS)
                            scope.launch {
                                snackbar.showSnackbar(
                                    context.getString(
                                        R.string.acquire_live_sent,
                                        member.name,
                                        context.getString(R.string.duration_minutes, 15),
                                    ),
                                )
                            }
                        },
                    )
                }
                if (liveMembers.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.member_unknown),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---- layers ---------------------------------------------------------------

private const val SRC_MEMBERS = "kea-members"
private const val SRC_ZONES = "kea-zones"

/**
 * Layer properties.
 *
 * MapLibre 13 takes `setProperties(vararg PropertyValue<*>)`. Mixing
 * `PropertyValue<Expression>` and `PropertyValue<Float>` inline does not
 * infer, so each layer's properties go through an explicitly-typed array — a
 * layer that silently fails to build is very hard to notice at runtime.
 */
private fun layer(
    layer: org.maplibre.android.style.layers.Layer,
    vararg props: org.maplibre.android.style.layers.PropertyValue<*>,
): org.maplibre.android.style.layers.Layer {
    layer.setProperties(*props)
    return layer
}

private fun MapView.installLayers(style: Style) {
    style.addSource(
        org.maplibre.android.style.sources.GeoJsonSource(SRC_MEMBERS, emptyFeatureCollection()),
    )
    style.addSource(
        org.maplibre.android.style.sources.GeoJsonSource(SRC_ZONES, emptyFeatureCollection()),
    )

    // Accuracy halo first, so it sits under the dot.
    style.addLayer(
        layer(
            org.maplibre.android.style.layers.CircleLayer("$SRC_MEMBERS-halo", SRC_MEMBERS),
            PropertyFactory.circleRadius(
                Expression.toNumber(
                    Expression.coalesce(Expression.get("acc"), Expression.literal(30.0)),
                ),
            ),
            PropertyFactory.circleColor(AndroidColor.argb(38, 94, 234, 212)),
            PropertyFactory.circleStrokeWidth(0f),
        ),
    )

    style.addLayer(
        layer(
            org.maplibre.android.style.layers.CircleLayer(SRC_MEMBERS, SRC_MEMBERS),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(Expression.get("colour") as Expression),
            PropertyFactory.circleStrokeWidth(2.5f),
            PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
        ),
    )

    style.addLayer(
        layer(
            org.maplibre.android.style.layers.SymbolLayer("$SRC_MEMBERS-label", SRC_MEMBERS),
            PropertyFactory.textField(Expression.get("name") as Expression),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor(AndroidColor.WHITE),
            PropertyFactory.textHaloColor(AndroidColor.BLACK),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textOffset(arrayOf(0f, 1.6f)),
            PropertyFactory.textAnchor("top"),
            PropertyFactory.textIgnorePlacement(true),
        ),
    )

    style.addLayer(
        layer(
            org.maplibre.android.style.layers.FillLayer("$SRC_ZONES-fill", SRC_ZONES),
            PropertyFactory.fillColor(AndroidColor.argb(30, 94, 234, 212)),
            PropertyFactory.fillOutlineColor(AndroidColor.TRANSPARENT),
        ),
    )
    style.addLayer(
        layer(
            org.maplibre.android.style.layers.LineLayer("$SRC_ZONES-line", SRC_ZONES),
            PropertyFactory.lineColor(AndroidColor.argb(140, 94, 234, 212)),
            PropertyFactory.lineWidth(2f),
        ),
    )
}

private fun emptyFeatureCollection() =
    FeatureCollection.fromFeatures(listOf<Feature>())

/**
 * Republish the overlay.
 *
 * Both collections are rebuilt wholesale. With a dozen members and a handful of
 * zones that is far cheaper than diffing, and MapLibre handles a full
 * `setGeoJson` efficiently.
 */
private fun MapLibreMap.render(session: Session, members: List<Member>) {
    getStyle { style ->
        (style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(SRC_MEMBERS))
            ?.setGeoJson(memberCollection(members))
        (style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(SRC_ZONES))
            ?.setGeoJson(zoneCollection(session))
    }
}

private fun memberCollection(members: List<Member>): FeatureCollection {
    val features = members.mapNotNull { m ->
        val lat = m.lat ?: return@mapNotNull null
        val lon = m.lon ?: return@mapNotNull null
        // MapLibre 6 takes feature properties as a Gson JsonObject, not a Map.
        val props = com.google.gson.JsonObject().apply {
            addProperty("name", m.name.ifBlank { m.deviceId })
            addProperty("colour", m.colour().toColourInt())
            addProperty("acc", m.accuracy ?: 0.0)
            addProperty("self", m.isSelf)
        }
        Feature.fromGeometry(Point.fromLngLat(lon, lat), props)
    }
    return FeatureCollection.fromFeatures(features)
}

private fun zoneCollection(session: Session): FeatureCollection {
    val features = mutableListOf<Feature>()
    for (zone in session.zones) {
        val props = com.google.gson.JsonObject().apply { addProperty("name", zone.name) }
        if (zone.isPolygon) {
            val ring = zone.ringPairs()
            if (ring.size >= 3) {
                // GeoJSON wants [lon, lat] and a closed ring.
                val points = ring.map { Point.fromLngLat(it.second, it.first) }.toMutableList()
                points += points.first()
                features += Feature.fromGeometry(Polygon.fromLngLats(listOf(points)), props)
            }
        } else {
            features += Feature.fromGeometry(
                circlePolygon(zone.lat, zone.lon, zone.radiusM.toDouble()), props,
            )
        }
    }
    return FeatureCollection.fromFeatures(features)
}

/**
 * A circle as a 48-gon.
 *
 * MapLibre has no circle *fill* geometry for data sources, so a polygon is the
 * honest way to draw a geofence that other clients must render identically.
 * 48 segments puts the chord error under a millimetre even at 20 km.
 */
private fun circlePolygon(lat: Double, lon: Double, radiusM: Double): Polygon {
    val ring = ArrayList<Point>(49)
    val steps = 48
    for (i in 0..steps) {
        val bearing = Math.toRadians(i * 360.0 / steps)
        val dLat = (radiusM * Math.cos(bearing)) / Geo.EARTH_RADIUS_M
        val cosLat = Math.cos(Math.toRadians(lat))
        val dLon = if (Math.abs(cosLat) < 1e-12) 0.0
        else (radiusM * Math.sin(bearing)) / (Geo.EARTH_RADIUS_M * cosLat)
        ring += Point.fromLngLat(
            lon + Math.toDegrees(dLon),
            lat + Math.toDegrees(dLat),
        )
    }
    return Polygon.fromLngLats(listOf(ring))
}


// ---- pieces ---------------------------------------------------------------

@Composable
private fun SharingBanner(session: Session, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val watchers = session.members.filter { !it.isSelf && it.watching }
    val (bg, text) = when {
        watchers.isEmpty() -> MaterialTheme.colorScheme.surfaceVariant to
            context.getString(R.string.sharing_idle)
        watchers.size == 1 -> MaterialTheme.colorScheme.primaryContainer to
            context.getString(R.string.sharing_watched_by, watchers.first().name)
        else -> MaterialTheme.colorScheme.primaryContainer to
            context.getString(R.string.sharing_watched_by_n, watchers.size)
    }

    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    if (watchers.isEmpty()) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.primary,
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun MemberRow(member: Member, onRequestLive: () -> Unit) {
    val context = LocalContext.current
    val fresh = System.currentTimeMillis() - member.fixAt < STALE_AFTER_MS

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !member.isSelf, onClick = onRequestLive)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .background(androidx.compose.ui.graphics.Color(member.colour().toColourInt()), CircleShape),
        )
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                buildString {
                    append(member.name.ifBlank { member.deviceId })
                    if (member.isSelf) append(" · ").append(stringResource(R.string.member_you))
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                buildString {
                    append(
                        if (fresh) context.getString(R.string.member_fresh)
                        else context.getString(R.string.member_stale, formatAge(context, member.fixAt)),
                    )
                    member.accuracy?.let {
                        append(" · ")
                        append(context.getString(R.string.member_accuracy, it.toInt()))
                    }
                    member.speed?.let {
                        append(" · ")
                        append(formatSpeed(context, it))
                    }
                    member.battery?.let {
                        append(" · ")
                        append(context.getString(R.string.member_battery, (it * 100).toInt()))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (member.watching) {
            Icon(
                Icons.Filled.MyLocation,
                contentDescription = stringResource(R.string.member_watching),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Marker greys out past this; the last fix is still kept and drawn. */
private const val STALE_AFTER_MS = 30_000L