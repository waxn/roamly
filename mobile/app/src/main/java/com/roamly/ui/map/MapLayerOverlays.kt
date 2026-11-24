package com.roamly.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.roamly.data.api.InferredBatch
import com.roamly.data.api.MapPlace
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs
import kotlin.math.floor

/** Pixels per [meters] at the map centre: two projected points, so it follows
 *  whatever tile scaling osmdroid is using rather than assuming 256 px tiles. */
private fun metersToPx(mapView: MapView, meters: Double, lat: Double, lng: Double, a: android.graphics.Point, b: android.graphics.Point): Float {
    val proj = mapView.projection
    proj.toPixels(GeoPoint(lat, lng), a)
    proj.toPixels(GeoPoint(lat + meters / 111_320.0, lng), b)
    return abs(b.y - a.y).toFloat()
}

// ---- Fog of war: dims everywhere you have never been ----

/**
 * Same mask as the web's fog of war: the whole map dimmed, with a soft 200 m
 * hole punched around every ~110 m cell ever visited (`/api/fog/`, whole
 * history). Drawn below the track so the dots stay readable. Cells are bucketed
 * by whole degree so a frame only walks the buckets on screen.
 */
internal class FogOverlay : Overlay() {
    private var cells: IntArray = IntArray(0)
    private var perDeg = 1000
    private val buckets = HashMap<Long, IntArray>()

    private val fogPaint = Paint().apply { color = Color.argb((0.75f * 255).toInt(), 10, 14, 20) }
    private val holePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private var sprite: Bitmap? = null
    private val src = Rect()
    private val dst = RectF()
    private val out = android.graphics.Point()
    private val out2 = android.graphics.Point()
    private val scratch = GeoPoint(0.0, 0.0)

    fun setCells(flat: IntArray?, perDegree: Int) {
        cells = flat ?: IntArray(0)
        perDeg = perDegree.coerceAtLeast(1)
        buckets.clear()
        val tmp = HashMap<Long, MutableList<Int>>()
        var i = 0
        while (i + 1 < cells.size) {
            tmp.getOrPut(key(floor(cells[i].toDouble() / perDeg).toInt(), floor(cells[i + 1].toDouble() / perDeg).toInt())) { ArrayList() }.add(i)
            i += 2
        }
        tmp.forEach { (k, v) -> buckets[k] = v.toIntArray() }
    }

    private fun key(dy: Int, dx: Int): Long = (dy.toLong() shl 32) or (dx.toLong() and 0xffffffffL)

    private fun ensureSprite() {
        if (sprite != null) return
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        // Solid to 60% of the radius, then a soft edge, as on the web.
        val grad = RadialGradient(r, r, r, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = grad })
        sprite = bmp
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !isEnabled) return
        ensureSprite()
        val s = sprite ?: return
        val w = canvas.width.toFloat(); val h = canvas.height.toFloat()
        val bbox = runCatching { mapView.boundingBox }.getOrNull() ?: return
        val centre = mapView.mapCenter
        val rPx = metersToPx(mapView, 200.0, centre.latitude, centre.longitude, out, out2).coerceAtLeast(1.2f)

        // The holes must cut the fog only, not the tiles under it: draw on a layer.
        val layer = canvas.saveLayer(null, null)
        canvas.drawRect(0f, 0f, w, h, fogPaint)
        src.set(0, 0, s.width, s.height)
        val pad = pixelsToDegrees(rPx, mapView.zoomLevelDouble)
        val cull = ViewportCull(mapView, pad)
        val proj = mapView.projection

        val y0 = floor(bbox.latSouth - pad).toInt() - 1
        val y1 = floor(bbox.latNorth + pad).toInt() + 1
        var x0 = floor(bbox.lonWest - pad).toInt() - 1
        var x1 = floor(bbox.lonEast + pad).toInt() + 1
        if (x1 < x0) x1 += 360
        val span = (y1 - y0 + 1).toLong() * (x1 - x0 + 1)
        val lists: Collection<IntArray> = if (span > buckets.size) buckets.values else buildList {
            for (dy in y0..y1) for (dx in x0..x1) {
                val wrapped = ((dx + 180) % 360 + 360) % 360 - 180
                buckets[key(dy, wrapped)]?.let { add(it) }
            }
        }
        for (list in lists) {
            for (i in list) {
                val lat = cells[i].toDouble() / perDeg
                val lng = cells[i + 1].toDouble() / perDeg
                if (!cull.contains(lat, lng)) continue
                scratch.setCoords(lat, lng)
                proj.toPixels(scratch, out)
                dst.set(out.x - rPx, out.y - rPx, out.x + rPx, out.y + rPx)
                canvas.drawBitmap(s, src, dst, holePaint)
            }
        }
        canvas.restoreToCount(layer)
    }
}

// ---- Filled gaps: the map editor's inferred points, one dashed line per edit ----

internal class InferredOverlay(density: Float) : Overlay() {
    private var batches: List<InferredBatch> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(230, 148, 163, 184)
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    private val path = Path()
    private val out = android.graphics.Point()
    private val scratch = GeoPoint(0.0, 0.0)

    fun setBatches(b: List<InferredBatch>) { batches = b }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !isEnabled || batches.isEmpty()) return
        val proj = mapView.projection
        val cull = ViewportCull(mapView, 0.05)
        for (batch in batches) {
            val coords = batch.coords
            if (coords.size < 2 || coords.none { it.size >= 2 && cull.contains(it[1], it[0]) }) continue
            path.reset()
            var started = false
            for (c in coords) {
                if (c.size < 2) continue
                scratch.setCoords(c[1], c[0])
                proj.toPixels(scratch, out)
                if (!started) { path.moveTo(out.x.toFloat(), out.y.toFloat()); started = true }
                else path.lineTo(out.x.toFloat(), out.y.toFloat())
            }
            canvas.drawPath(path, paint)
        }
    }
}

// ---- My places: each custom place's geofence, tinted with its colour ----

internal class PlacesOverlay(private val density: Float) : Overlay() {
    private var places: List<MapPlace> = emptyList()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
        setShadowLayer(3f * density, 0f, 0f, Color.argb(220, 0, 0, 0))
    }
    private val out = android.graphics.Point()
    private val out2 = android.graphics.Point()

    fun setPlaces(p: List<MapPlace>) { places = p }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || !isEnabled || places.isEmpty()) return
        val zoom = mapView.zoomLevelDouble
        val cull = ViewportCull(mapView, 0.05)
        for (p in places) {
            if (!cull.contains(p.lat, p.lng)) continue
            val r = metersToPx(mapView, p.radiusM, p.lat, p.lng, out, out2).coerceAtLeast(4f * density)
            val base = parseColor(p.color)
            fill.color = Color.argb(46, Color.red(base), Color.green(base), Color.blue(base))
            ring.color = Color.argb(220, Color.red(base), Color.green(base), Color.blue(base))
            val x = out.x.toFloat(); val y = out.y.toFloat()
            canvas.drawCircle(x, y, r, fill)
            canvas.drawCircle(x, y, r, ring)
            if (zoom >= 12 && p.name.isNotBlank()) canvas.drawText(p.name, x, y - r - 4f * density, label)
        }
    }

    private fun parseColor(hex: String): Int =
        runCatching { Color.parseColor(hex.trim()) }.getOrDefault(Color.rgb(217, 119, 87))
}
