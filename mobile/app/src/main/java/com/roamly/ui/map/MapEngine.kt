package com.roamly.ui.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.roamly.data.api.LocationPoint
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay

// The map's rendering engine: tile sources, the retained MapView holder and
// the track overlays. Split out of MapScreen so the screen file is only UI.

/** An osmdroid tile source backed by Mapbox raster tiles for the given style,
 *  authenticated with the user's public token. Mirrors the web map's Mapbox
 *  basemaps. A distinct name per style keeps their tile caches separate. */
private fun mapboxTileSource(token: String, styleId: String): org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase =
    object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
        "Mapbox-$styleId", 0, 22, 256, "",
        arrayOf("https://api.mapbox.com/styles/v1/mapbox/$styleId/tiles/256/"),
        "© Mapbox © OpenStreetMap",
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
            return baseUrl + z + "/" + x + "/" + y + "?access_token=" + token
        }
    }

/** Esri World Imagery satellite tiles. Esri serves tiles in z/y/x order, so it
 *  needs a custom URL builder rather than the standard XYZ XYTileSource. */
private fun esriTileSource(): org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase =
    object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
        "EsriWorldImagery", 0, 19, 256, "",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "© Esri",
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
            return baseUrl + z + "/" + y + "/" + x
        }
    }

/** CARTO dark-matter basemap — standard XYZ, so a plain XYTileSource works. */
private fun cartoDarkTileSource(): org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase =
    org.osmdroid.tileprovider.tilesource.XYTileSource(
        "CartoDark", 0, 20, 256, ".png",
        arrayOf("https://basemaps.cartocdn.com/dark_all/"),
        "© CARTO © OpenStreetMap",
    )

/** CARTO Voyager — the light basemap "Auto" uses in light mode. */
private fun cartoVoyagerTileSource(): org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase =
    org.osmdroid.tileprovider.tilesource.XYTileSource(
        "CartoVoyager", 0, 20, 256, ".png",
        arrayOf("https://basemaps.cartocdn.com/rastertiles/voyager/"),
        "© CARTO © OpenStreetMap",
    )

/** Resolve a basemap name (shared with the web map's labels) to a tile source.
 *  Mapbox entries need a token; without one they fall back to OSM streets. */
internal fun basemapTileSource(name: String, mapboxToken: String, dark: Boolean): org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase {
    val token = mapboxToken.trim()
    return when (name) {
        "Auto" -> if (dark) cartoDarkTileSource() else cartoVoyagerTileSource()
        "Satellite" -> esriTileSource()
        "Dark" -> cartoDarkTileSource()
        "Mapbox Streets" -> if (token.isNotEmpty()) mapboxTileSource(token, "streets-v12") else org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK
        "Mapbox Outdoors" -> if (token.isNotEmpty()) mapboxTileSource(token, "outdoors-v12") else org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK
        "Mapbox Satellite" -> if (token.isNotEmpty()) mapboxTileSource(token, "satellite-streets-v12") else org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK
        else -> org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK  // "Streets"
    }
}

// ---- Heatmap overlay ----

/** Holds the single reusable MapView and its overlays. */
internal class MapHolder(
    val mapView: MapView,
    val heatmap: HeatmapOverlay,
    val points: PointsOverlay,
    val fog: FogOverlay,
    val inferred: InferredOverlay,
    val places: PlacesOverlay,
) {
    fun detach() { runCatching { mapView.onDetach() } }
}

/**
 * Per-frame culling in geographic space, recomputed once per draw.
 *
 * Both overlays are drawn on every pan/zoom frame over the *whole* accumulated
 * point set — [MapViewModel] keeps adding viewport detail without ever pruning,
 * so after a bit of panning that list is tens of thousands of rows. Projecting
 * every one of them and *then* discarding the off-screen ones meant ~50k
 * throwaway [GeoPoint] allocations plus ~50k pixel transforms per frame; the GC
 * churn alone is what made the map feel like it was dragging.
 *
 * Two double comparisons per point up front is orders of magnitude cheaper than
 * a projection, and allocates nothing — so the per-frame cost now scales with
 * what is actually on screen rather than with everything ever loaded.
 */
internal class ViewportCull(mapView: MapView, padDegrees: Double) {
    private val minLat: Double
    private val maxLat: Double
    private val minLng: Double
    private val maxLng: Double
    /** True when the viewport straddles the antimeridian (lonWest > lonEast). */
    private val wraps: Boolean
    val valid: Boolean

    init {
        val bbox = runCatching { mapView.boundingBox }.getOrNull()
        if (bbox == null) {
            minLat = 0.0; maxLat = 0.0; minLng = 0.0; maxLng = 0.0
            wraps = false; valid = false
        } else {
            minLat = bbox.latSouth - padDegrees
            maxLat = bbox.latNorth + padDegrees
            minLng = bbox.lonWest - padDegrees
            maxLng = bbox.lonEast + padDegrees
            wraps = bbox.lonWest > bbox.lonEast
            valid = true
        }
    }

    /**
     * Whether the point is on (or just off) screen. Answers `true` for
     * everything when the viewport could not be read, so a bad frame falls back
     * to the old draw-it-all behaviour rather than to a blank map.
     */
    fun contains(lat: Double, lng: Double): Boolean {
        if (!valid) return true
        if (lat < minLat || lat > maxLat) return false
        // Straddling the antimeridian, the visible span is the OUTSIDE of
        // [minLng, maxLng] — hence the `or` rather than an `and`.
        return if (wraps) lng >= minLng || lng <= maxLng else lng >= minLng && lng <= maxLng
    }
}

/** Rough degrees-of-latitude for a pixel count at the given zoom. */
internal fun pixelsToDegrees(px: Float, zoom: Double): Double {
    val worldPx = 256.0 * Math.pow(2.0, zoom)
    return (px / worldPx) * 360.0
}

internal class HeatmapOverlay : Overlay() {
    private var points: List<LocationPoint> = emptyList()
    private var sprite: android.graphics.Bitmap? = null

    // Hoisted out of draw(): these were reallocated on every frame.
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = android.graphics.Rect()
    private val dst = android.graphics.RectF()
    private val out = android.graphics.Point()
    private val scratch = GeoPoint(0.0, 0.0)

    fun setPoints(p: List<LocationPoint>) { points = p }

    private fun ensureSprite() {
        if (sprite != null) return
        val size = 64
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val grad = android.graphics.RadialGradient(
            cx, cx, cx,
            intArrayOf(Color.argb(200, 255, 80, 60), Color.argb(120, 255, 170, 0), Color.argb(0, 255, 255, 0)),
            floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = grad }
        c.drawCircle(cx, cx, cx, p)
        sprite = bmp
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return
        ensureSprite()
        val s = sprite ?: return
        val zoom = mapView.zoomLevelDouble
        val alphaFrac = when {
            zoom >= 15 -> 0.04f
            zoom >= 13 -> 0.16f
            zoom >= 11 -> 0.50f
            else -> 0.70f
        }
        val spriteSize = when {
            zoom < 6 -> 26f
            zoom < 10 -> 44f
            zoom < 13 -> 64f
            else -> 80f
        }
        val half = spriteSize / 2f
        val cull = ViewportCull(mapView, pixelsToDegrees(spriteSize, zoom))
        paint.alpha = (alphaFrac * 255).toInt()
        src.set(0, 0, s.width, s.height)
        val projection: Projection = mapView.projection
        for (i in points.indices) {
            val pt = points[i]
            if (!cull.contains(pt.lat, pt.lng)) continue
            scratch.setCoords(pt.lat, pt.lng)
            projection.toPixels(scratch, out)
            dst.set(out.x - half, out.y - half, out.x + half, out.y + half)
            canvas.drawBitmap(s, src, dst, paint)
        }
    }
}

// ---- Speed-graded coloured dots; larger and more visible at detail zoom ----

internal class PointsOverlay : Overlay() {
    private var points: List<LocationPoint> = emptyList()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 255, 255, 255)
    }
    private val out = android.graphics.Point()
    private val scratch = GeoPoint(0.0, 0.0)
    var onPointTapped: ((LocationPoint) -> Unit)? = null

    fun setPoints(p: List<LocationPoint>) { points = p }

    override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
        if (mapView.zoomLevelDouble < 11.0 || points.isEmpty()) return false
        val projection = mapView.projection
        val tapX = e.x; val tapY = e.y
        val tapRadiusPx = 40f  // generous tap target
        val cull = ViewportCull(mapView, pixelsToDegrees(tapRadiusPx, mapView.zoomLevelDouble))
        var bestDist = Float.MAX_VALUE
        var bestPt: LocationPoint? = null
        val hit = android.graphics.Point()
        for (i in points.indices) {
            val pt = points[i]
            if (!cull.contains(pt.lat, pt.lng)) continue
            scratch.setCoords(pt.lat, pt.lng)
            projection.toPixels(scratch, hit)
            val dx = hit.x - tapX; val dy = hit.y - tapY
            val dist = dx * dx + dy * dy
            if (dist < tapRadiusPx * tapRadiusPx && dist < bestDist) {
                bestDist = dist
                bestPt = pt
            }
        }
        bestPt?.let { onPointTapped?.invoke(it) }
        return bestPt != null
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return
        val zoom = mapView.zoomLevelDouble
        if (zoom < 11.0) return
        val alphaFrac = when {
            zoom >= 13 -> 1.0f
            zoom >= 12 -> 0.75f
            else -> 0.4f
        }
        val projection: Projection = mapView.projection
        val radius = when {
            zoom < 12 -> 3.5f
            zoom < 13 -> 5.0f
            zoom < 15 -> 7.0f
            else -> 9.0f
        }
        strokePaint.strokeWidth = (radius * 0.35f).coerceAtLeast(1.5f)
        val cull = ViewportCull(mapView, pixelsToDegrees(radius + 20f, zoom))
        for (i in points.indices) {
            val p = points[i]
            if (!cull.contains(p.lat, p.lng)) continue
            scratch.setCoords(p.lat, p.lng)
            projection.toPixels(scratch, out)
            val c = speedColor(p.speed)
            fillPaint.color = Color.argb((Color.alpha(c) * alphaFrac).toInt(), Color.red(c), Color.green(c), Color.blue(c))
            val x = out.x.toFloat(); val y = out.y.toFloat()
            canvas.drawCircle(x, y, radius, fillPaint)
            canvas.drawCircle(x, y, radius, strokePaint)
        }
    }
}

/** speed in m/s — matches the website's green→amber→red gradient */
internal fun speedColor(speed: Double?): Int {
    val s = speed ?: 0.0
    return when {
        s < 1.0 -> Color.rgb(59, 130, 246)
        s < 5.0 -> Color.rgb(34, 197, 94)
        s < 14.0 -> Color.rgb(234, 179, 8)
        s < 30.0 -> Color.rgb(249, 115, 22)
        else -> Color.rgb(239, 68, 68)
    }
}
