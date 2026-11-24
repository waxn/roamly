package com.roamly.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The web's Squircle shape (Settings → Appearance → Shape, the default look):
 * each corner is a quarter of the superellipse |x|⁴ + |y|⁴ = 1 inside its
 * radius box, which is what CSS `corner-shape: squircle` draws
 * (`superellipse(2)`). It hugs the corner tighter than a circular arc of the
 * same radius and eases into the straight edge, so radii run larger than a
 * plain rounded rectangle's: use [SquircleRadius] to match the web's sizes.
 *
 * A [CornerBasedShape], so it slots into Material 3 `Shapes` and takes the
 * same corner sizes as [androidx.compose.foundation.shape.RoundedCornerShape]
 * (the factory functions below mirror that one's). Oversized corners are scaled
 * down to fit by [CornerBasedShape], as CSS does, so a 999dp "pill" becomes a
 * squircle-ended pill, again as on the web.
 */
class SquircleShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ) = SquircleShape(topStart, topEnd, bottomEnd, bottomStart)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        if (topStart + topEnd + bottomEnd + bottomStart == 0f) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
        val rtl = layoutDirection == LayoutDirection.Rtl
        return Outline.Generic(
            squirclePath(
                size.width, size.height,
                tl = if (rtl) topEnd else topStart,
                tr = if (rtl) topStart else topEnd,
                br = if (rtl) bottomStart else bottomEnd,
                bl = if (rtl) bottomEnd else bottomStart,
            )
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SquircleShape) return false
        return topStart == other.topStart && topEnd == other.topEnd &&
            bottomEnd == other.bottomEnd && bottomStart == other.bottomStart
    }

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        return result
    }

    override fun toString() =
        "SquircleShape(topStart=$topStart, topEnd=$topEnd, bottomEnd=$bottomEnd, bottomStart=$bottomStart)"
}

fun SquircleShape(size: Dp) = SquircleShape(CornerSize(size))
fun SquircleShape(percent: Int) = SquircleShape(CornerSize(percent))
fun SquircleShape(corner: CornerSize) = SquircleShape(corner, corner, corner, corner)
fun SquircleShape(
    topStart: Dp = 0.dp,
    topEnd: Dp = 0.dp,
    bottomEnd: Dp = 0.dp,
    bottomStart: Dp = 0.dp,
) = SquircleShape(CornerSize(topStart), CornerSize(topEnd), CornerSize(bottomEnd), CornerSize(bottomStart))

/**
 * The web Squircle shape's radius roles (`facets.css`, `html[data-shape="squircle"]`):
 * `--r-1` tags and chips, `--r-2` controls, `--r-3` panels and cards, `--r-4` dialogs
 * and sheets. Pills stay pills (any radius past half the height).
 */
object SquircleRadius {
    val tag = 10.dp
    val control = 16.dp
    val panel = 26.dp
    val dialog = 32.dp
}

/**
 * Control-point reach for one cubic per corner that tracks the quartic
 * superellipse: with points (0,1) (k,1) (1,k) (1,0), the curve's midpoint lands
 * on the superellipse's own 45° point, 2^(-1/4) ≈ 0.8409, when
 * k = (8·0.8409 − 4) / 3. Ends are tangent to the edges, as the real curve is.
 */
private const val K = 0.90909f

internal fun squirclePath(w: Float, h: Float, tl: Float, tr: Float, br: Float, bl: Float): Path = Path().apply {
    moveTo(tl, 0f)
    lineTo(w - tr, 0f)
    if (tr > 0f) cubicTo(w - tr + K * tr, 0f, w, tr - K * tr, w, tr)
    lineTo(w, h - br)
    if (br > 0f) cubicTo(w, h - br + K * br, w - br + K * br, h, w - br, h)
    lineTo(bl, h)
    if (bl > 0f) cubicTo(bl - K * bl, h, 0f, h - bl + K * bl, 0f, h - bl)
    lineTo(0f, tl)
    if (tl > 0f) cubicTo(0f, tl - K * tl, tl - K * tl, 0f, tl, 0f)
    close()
}
