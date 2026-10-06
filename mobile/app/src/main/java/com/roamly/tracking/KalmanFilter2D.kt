package com.roamly.tracking

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Forward constant-velocity Kalman filter over lat/lon, for the **live** recording
 * screen only.
 *
 * The stored track is always the raw accepted fixes; the server smooths them
 * properly (forward *and* backward — see `tracker/activity_track.py`), which a
 * live view cannot, since it has no future fixes to look at. This exists so the
 * line drawn while you ride, and the distance shown, aren't the raw zig-zag.
 *
 * Works in local metres around the first fix. The two axes are independent under
 * an isotropic white-acceleration model, so each is a 2-state (position,
 * velocity) filter and every matrix is 2×2 — the same structure as the server's.
 * Measurement noise comes from each fix's own reported accuracy, so a good fix
 * pulls hard and a poor one barely nudges.
 */
class KalmanFilter2D(private val processNoise: Double) {

    private class Axis(var x: Double, var v: Double, var p00: Double, var p01: Double, var p11: Double) {
        fun predict(dt: Double, q: Double) {
            x += dt * v
            p00 += 2 * dt * p01 + dt * dt * p11 + q * dt * dt * dt / 3.0
            p01 += dt * p11 + q * dt * dt / 2.0
            p11 += q * dt
        }

        fun update(z: Double, r: Double) {
            val s = p00 + r
            val k0 = p00 / s
            val k1 = p01 / s
            val y = z - x
            x += k0 * y
            v += k1 * y
            val n00 = (1 - k0) * p00
            val n01 = (1 - k0) * p01
            val n11 = p11 - k1 * p01
            p00 = n00; p01 = n01; p11 = n11
        }
    }

    private var lat0 = 0.0
    private var lon0 = 0.0
    private var kx = 0.0
    private val ky = EARTH_R * Math.PI / 180.0
    private var ex: Axis? = null
    private var ny: Axis? = null
    private var lastT = 0L

    /** Smoothed position after folding in one fix. */
    data class Estimate(val lat: Double, val lon: Double, val speedMps: Double)

    fun reset() {
        ex = null; ny = null; lastT = 0L
    }

    fun update(tMs: Long, lat: Double, lon: Double, accuracyM: Float?): Estimate {
        val acc = max((accuracyM ?: 15f).toDouble(), 3.0)
        // Android's horizontal accuracy is a 68% radius; per-axis sigma is ~that / 1.5.
        val r = (acc / 1.5) * (acc / 1.5)
        val ax = ex
        val ay = ny
        // A long gap means the old velocity says nothing — start over there.
        if (ax == null || ay == null || tMs - lastT > RESET_GAP_MS) {
            lat0 = lat; lon0 = lon
            kx = cos(Math.toRadians(lat)) * EARTH_R * Math.PI / 180.0
            ex = Axis(0.0, 0.0, r, 0.0, 25.0)
            ny = Axis(0.0, 0.0, r, 0.0, 25.0)
            lastT = tMs
            return Estimate(lat, lon, 0.0)
        }
        val dt = max((tMs - lastT) / 1000.0, 0.001)
        lastT = tMs
        ax.predict(dt, processNoise)
        ay.predict(dt, processNoise)
        ax.update((lon - lon0) * kx, r)
        ay.update((lat - lat0) * ky, r)
        return Estimate(lat0 + ay.x / ky, lon0 + ax.x / kx, hypot(ax.v, ay.v))
    }

    private companion object {
        const val EARTH_R = 6371008.8
        const val RESET_GAP_MS = 60_000L
    }
}
