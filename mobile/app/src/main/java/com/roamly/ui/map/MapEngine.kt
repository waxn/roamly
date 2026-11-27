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

/** Resolve a basemap name (shared with the web map's labels) to a tile source.
 *  Mapbox entries need a token; without one they fall back to OSM streets. */
internal fun basemapTileSource(name: String, mapboxToken: String, dark: Boolean): org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase {
    val token = mapboxToken.trim()
    return when (name) {
        // "Auto" and "Dark" are OpenStreetMap; dark mode tints them at night
        // (see basemapNightFilter). They were CARTO tiles, but CARTO now needs a
        // per-site API key the app has no way to get, and stamps "API KEY
        // REQUIRED" over every tile without one.
        "Satellite" -> esriTileSource()
        "Mapbox Streets" -> if (token.isNotEmpty()) mapboxTileSource(token, "streets-v12") else org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK
        "Mapbox Outdoors" -> if (token.isNotEmpty()) mapboxTileSource(token, "outdoors-v12") else org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK
        "Mapbox Satellite" -> if (token.isNotEmpty()) mapboxTileSource(token, "satellite-streets-v12") else org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK
        else -> org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK  // "Streets"
    }
}

/**
 * Inverts and cools OpenStreetMap tiles into a night map: dark land, light
 * labels, roads and water kept apart by brightness. Key-free, labelled, and
 * good to zoom 19 — the reasons it beat the alternatives.
 */
private val NIGHT_MATRIX = android.graphics.ColorMatrix(floatArrayOf(
    -0.26f, -0.50f, -0.10f, 0f, 236f,
    -0.27f, -0.53f, -0.10f, 0f, 242f,
    -0.28f, -0.55f, -0.11f, 0f, 255f,
     0f,     0f,     0f,    1f, 0f,
))

/** The tile filter for [name] in this theme, or null for none. */
internal fun basemapNightFilter(name: String, dark: Boolean): android.graphics.ColorFilter? =
    // "Dark" is the main map's default; "Auto" (mini maps) follows the theme.
    if (name == "Dark" || (name == "Auto" && dark)) android.graphics.ColorMatrixColorFilter(NIGHT_MATRIX) else null

/** Put [name]'s tiles on this map, tinted for the theme, with loading tiles in the page colour. */
internal fun MapView.applyBasemap(name: String, mapboxToken: String, dark: Boolean, loadingColor: Int) {
    val desired = basemapTileSource(name, mapboxToken, dark)
    if (tileProvider.tileSource.name() != desired.name()) setTileSource(desired)
    overlayManager.tilesOverlay.setColorFilter(basemapNightFilter(name, dark))
    overlayManager.tilesOverlay.loadingBackgroundColor = loadingColor
    overlayManager.tilesOverlay.loadingLineColor = loadingColor
    invalidate()
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

/** The heat glow is only drawn below this zoom (fading out over the last level); dots take over. */
internal const val HEAT_MAX_ZOOM = 9.0

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
            intArrayOf(Color.argb(190, 226, 150, 110), Color.argb(110, 212, 180, 140), Color.argb(0, 212, 180, 140)),
            floatArrayOf(0f, 0.5f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = grad }
        c.drawCircle(cx, cx, cx, p)
        sprite = bmp
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || points.isEmpty()) return
        val zoom = mapView.zoomLevelDouble
        // Only a glow when zoomed well out; from here down it is plain dots.
        if (zoom >= HEAT_MAX_ZOOM) return
        ensureSprite()
        val s = sprite ?: return
        val alphaFrac = 0.70f * (HEAT_MAX_ZOOM - zoom).coerceIn(0.0, 1.0).toFloat()
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

/** Dots start here; below it the heat glow is what you see. */
internal const val DOT_MIN_ZOOM = 9.0
private const val MAX_DOTS_DRAWN = 6_000

internal class PointsOverlay : Overlay() {
    private var points: List<LocationPoint> = emptyList()
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val out = android.graphics.Point()
    private val scratch = GeoPoint(0.0, 0.0)
    private val dst = android.graphics.RectF()
    private val src = android.graphics.Rect()
    private val sprites = arrayOfNulls<android.graphics.Bitmap>(SPEED_COLORS.size)
    private var grid = BooleanArray(0)
    var onPointTapped: ((LocationPoint) -> Unit)? = null

    fun setPoints(p: List<LocationPoint>) { points = p }

    /** A soft dot: a solid core inside a faint halo, pre-rendered once per speed band so a frame is just blits. */
    private fun sprite(band: Int): android.graphics.Bitmap {
        sprites[band]?.let { return it }
        val size = 32
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val c = SPEED_COLORS[band]
        val mid = size / 2f
        // A crisp dot: solid fill and a thin dark rim so it holds on the night map.
        cv.drawCircle(mid, mid, size * 0.42f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 8, 12, 14) })
        cv.drawCircle(mid, mid, size * 0.34f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c })
        sprites[band] = bmp
        return bmp
    }

    override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
        if (mapView.zoomLevelDouble < DOT_MIN_ZOOM || points.isEmpty()) return false
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
        if (zoom < DOT_MIN_ZOOM) return
        // Fades in as the heat glow fades out.
        spritePaint.alpha = (255 * ((zoom - (DOT_MIN_ZOOM - 0.5)) / 1.0).coerceIn(0.0, 1.0)).toInt()
        val density = mapView.resources.displayMetrics.density
        val half = when {
            zoom < 11 -> 2.5f
            zoom < 13 -> 3f
            zoom < 15 -> 3.5f
            else -> 4.5f
        } * density
        val projection: Projection = mapView.projection
        val cull = ViewportCull(mapView, pixelsToDegrees(half + 20f, zoom))
        // Thin out in screen space: at most one dot per cell, so a spot you sit at
        // all day stays a readable few dots instead of a solid blob (and costs less).
        val cell = (half * 1.6f).coerceAtLeast(1f)
        val cols = (mapView.width / cell).toInt() + 3
        val rows = (mapView.height / cell).toInt() + 3
        if (grid.size < cols * rows) grid = BooleanArray(cols * rows) else java.util.Arrays.fill(grid, 0, cols * rows, false)
        var drawn = 0
        for (i in points.indices) {
            val p = points[i]
            if (!cull.contains(p.lat, p.lng)) continue
            scratch.setCoords(p.lat, p.lng)
            projection.toPixels(scratch, out)
            val cx = (out.x / cell).toInt() + 1
            val cy = (out.y / cell).toInt() + 1
            if (cx < 0 || cy < 0 || cx >= cols || cy >= rows) continue
            val gi = cy * cols + cx
            if (grid[gi]) continue
            grid[gi] = true
            if (++drawn > MAX_DOTS_DRAWN) break
            val sp = sprite(speedBand(p.speed))
            src.set(0, 0, sp.width, sp.height)
            dst.set(out.x - half, out.y - half, out.x + half, out.y + half)
            canvas.drawBitmap(sp, src, dst, spritePaint)
        }
    }
}

/** Speed bands (still, on foot, cycling, driving, fast): saturated enough to read on the night map. */
private val SPEED_COLORS = intArrayOf(
    Color.rgb(77, 142, 224),
    Color.rgb(52, 184, 120),
    Color.rgb(232, 186, 48),
    Color.rgb(236, 112, 52),
    Color.rgb(224, 60, 92),
)

private fun speedBand(speed: Double?): Int {
    val s = speed ?: 0.0
    return when {
        s < 1.0 -> 0
        s < 5.0 -> 1
        s < 14.0 -> 2
        s < 30.0 -> 3
        else -> 4
    }
}

/** speed in m/s: the colour of its band (kept for callers outside the overlay). */
internal fun speedColor(speed: Double?): Int = SPEED_COLORS[speedBand(speed)]
