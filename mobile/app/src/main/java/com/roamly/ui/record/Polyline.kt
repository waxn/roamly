package com.roamly.ui.record

/** Decode a Google-encoded polyline (precision 5) into (lat, lng) pairs. */
internal fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
    val out = ArrayList<Pair<Double, Double>>(encoded.length / 4)
    var i = 0; var lat = 0; var lng = 0
    while (i < encoded.length) {
        var shift = 0; var result = 0
        do {
            if (i >= encoded.length) return out
            val b = encoded[i++].code - 63
            result = result or ((b and 0x1f) shl shift); shift += 5
            val more = b >= 0x20
            if (!more) break
        } while (true)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1
        shift = 0; result = 0
        do {
            if (i >= encoded.length) return out
            val b = encoded[i++].code - 63
            result = result or ((b and 0x1f) shl shift); shift += 5
            val more = b >= 0x20
            if (!more) break
        } while (true)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
        out.add(lat / 1e5 to lng / 1e5)
    }
    return out
}
