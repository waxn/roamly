package com.roamly.ui.kit

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.roamly.ui.map.applyBasemap
import com.roamly.ui.theme.Atlas
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/** osmdroid's global config, idempotent; every MapView in the app goes through it. */
fun configureOsmdroid(context: Context) {
    val app = context.applicationContext
    val cfg = Configuration.getInstance()
    if (cfg.userAgentValue == "Roamly/1.0") return
    cfg.load(app, app.getSharedPreferences("osmdroid", 0))
    cfg.userAgentValue = "Roamly/1.0"
    cfg.osmdroidBasePath = app.cacheDir
    cfg.osmdroidTileCache = java.io.File(app.cacheDir, "osmdroid_tiles").apply { mkdirs() }
}

/**
 * A MapView for a card or a screen section, on the theme-following basemap,
 * with osmdroid's own zoom buttons hidden. [interactive] false makes it a
 * static picture (scrolling the page past it doesn't grab the map).
 */
@Composable
fun rememberAtlasMapView(interactive: Boolean = false): MapView {
    val context = LocalContext.current
    val dark = Atlas.colors.isDark
    val loading = Atlas.colors.surface.toArgb()
    val mapView = remember {
        configureOsmdroid(context)
        MapView(context).apply {
            setMultiTouchControls(interactive)
            isClickable = interactive
            setDestroyMode(false)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            if (!interactive) setOnTouchListener { _, _ -> true }
            controller.setZoom(12.0)
        }
    }
    LaunchedEffect(dark) { mapView.applyBasemap("Dark", "", dark, loading) }
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }
    return mapView
}

/** Show [mapView] in composition (detaching it from a previous parent first). */
@Composable
fun MapViewHost(mapView: MapView, modifier: Modifier = Modifier) {
    AndroidView(factory = { (mapView.parent as? android.view.ViewGroup)?.removeView(mapView); mapView }, modifier = modifier)
}

/** A glowing polyline: a wide translucent halo under a crisp core. */
fun MapView.addGlowLine(points: List<GeoPoint>, color: Color, width: Float = 7f, geodesic: Boolean = false) {
    val halo = Polyline(this).apply {
        setPoints(points)
        outlinePaint.color = color.copy(alpha = 0.28f).toArgb()
        outlinePaint.strokeWidth = width * 2.6f
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        outlinePaint.isAntiAlias = true
        isGeodesic = geodesic
    }
    val core = Polyline(this).apply {
        setPoints(points)
        outlinePaint.color = color.toArgb()
        outlinePaint.strokeWidth = width
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        outlinePaint.isAntiAlias = true
        isGeodesic = geodesic
    }
    overlays.add(halo)
    overlays.add(core)
}

/** Frame [points] once the view has a size. */
fun MapView.fitTo(points: List<GeoPoint>, paddingPx: Int = 64) {
    if (points.isEmpty()) return
    val run = {
        runCatching {
            if (points.size == 1) { controller.setZoom(14.0); controller.setCenter(points[0]) }
            else zoomToBoundingBox(BoundingBox.fromGeoPoints(points), false, paddingPx)
        }
        invalidate()
    }
    if (width > 0 && height > 0) run()
    else {
        // Not measured yet (a map swapped in after its data arrived): framing now would
        // fit to a 0×0 box and leave the map on world zoom, so wait for the first layout.
        addOnFirstLayoutListener { _, _, _, _, _ -> run() }
        post { if (width > 0 && height > 0) run() }
    }
}

/** A single track on a static map: the journal day card, a recording summary. */
@Composable
fun TrackMap(points: List<GeoPoint>, modifier: Modifier = Modifier, color: Color = Atlas.colors.accent) {
    val mapView = rememberAtlasMapView(interactive = false)
    LaunchedEffect(points, color) {
        mapView.overlays.clear()
        if (points.size >= 2) mapView.addGlowLine(points, color)
        mapView.fitTo(points)
    }
    MapViewHost(mapView, modifier)
}

/** Per-member track colours — mirrors the web's adventure_public.html MEMBER_COLORS
 *  (owner = index 0, other members cycle through the rest by join order). */
val MEMBER_TRACK_COLORS = listOf(
    android.graphics.Color.rgb(0xE8, 0x76, 0x3D),
    android.graphics.Color.rgb(0x7B, 0xA3, 0xB8),
    android.graphics.Color.rgb(0x7F, 0x9B, 0x6D),
    android.graphics.Color.rgb(0xCF, 0x9A, 0x44),
    android.graphics.Color.rgb(0xA8, 0x88, 0xA0),
    android.graphics.Color.rgb(0x6B, 0x7F, 0xA3),
)

/**
 * A round map marker: a coloured disc with a white ring and, when given, a
 * letter in the middle — a person on the family map, a numbered stop.
 */
fun avatarMarker(context: Context, color: Int, label: String?, sizeDp: Float = 34f): android.graphics.drawable.BitmapDrawable {
    val d = context.resources.displayMetrics.density
    val px = (sizeDp * d).toInt().coerceAtLeast(8)
    val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val r = px / 2f
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.argb(70, 0, 0, 0)
    canvas.drawCircle(r, r + d, r - d, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(r, r, r - d, paint)
    paint.color = color
    canvas.drawCircle(r, r, r - 3.5f * d, paint)
    if (!label.isNullOrBlank()) {
        paint.color = android.graphics.Color.WHITE
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = px * (if (label.length > 1) 0.34f else 0.44f)
        val y = r - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(label, r, y, paint)
    }
    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}
