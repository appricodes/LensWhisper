package com.appricodes.lens_whisper.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * One name per distinct glyph a [com.appricodes.lens_whisper.ui.components.GridCell] can show.
 * Several buttons across Basic Tasks/VLM models are close enough in meaning to deliberately share
 * a glyph rather than each getting a bespoke one - e.g. [EXPLORE] covers both "Explore Photo by
 * Touch" and "Explore and Describe", which share the same touch-to-explore interaction even
 * though one names objects and the other asks a VLM to describe them. TalkBack's
 * contentDescription already disambiguates precisely for anyone who needs it - see
 * [GridIconGlyph]'s own doc for why sighted-only precision beyond that isn't the goal here.
 */
enum class GridIcon {
    DETECT_OBJECTS, IDENTIFY_OBJECTS_COLORS, EXPLORE,
    LIGHT_METER, SIMPLE_COLOR, SCAN_BARCODE,
    BRIEF_NARRATION, DETAILED_SCENE, PEOPLE_OUTFIT, DESCRIBE_TV, OBJECTS, CUSTOM_PROMPT,
    VOLUME_KEYS, SWITCH_CAMERA, FLASH_ON, FLASH_OFF, MICROPHONE,
    REWIND, PLAY, PAUSE, FAST_FORWARD
}

/** Every icon's own stroke width, as a fraction of the drawn glyph's shorter side - one constant
 *  so every glyph in the set reads as the same visual "weight" regardless of which one it is. */
private const val STROKE_FRACTION = 1.7f / 24f

/**
 * Draws [icon] in [tint] - hand-drawn rather than pulled from a Material icon library,
 * deliberately: this app has a history of trimming APK weight (see the removed ML Kit and
 * play-services-auth dependencies in app/build.gradle.kts), material-icons-core turns out to cover
 * almost none of this button set's vocabulary (TV, palette, touch-ripple, sun, teardrop, ...), and
 * material-icons-extended - the only Material set broad enough to - is several MB for the couple
 * dozen glyphs actually needed here, working against exactly what this app already prunes for.
 * Every glyph shares one 24x24 conceptual grid, one relative stroke width ([STROKE_FRACTION]),
 * round caps/joins, and a single-color [tint] - normally [Color.White]/[Color.Gray], matching
 * [com.appricodes.lens_whisper.ui.components.GridCell]'s own enabled/disabled label-color
 * convention, so a disabled cell's icon dims exactly the way its (no-longer-drawn, but still
 * TalkBack-read) label used to.
 *
 * [modifier] should give this an explicit square size (e.g. `Modifier.size(36.dp)`) - an unsized
 * Canvas has no intrinsic size of its own.
 */
@Composable
fun GridIconGlyph(icon: GridIcon, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawGridIcon(icon, tint)
    }
}

// --- coordinate helpers: every glyph below is authored on a 24x24 conceptual grid, mapped onto
// whatever actual pixel size the Canvas is given. -----------------------------------------------

private fun DrawScope.pt(x: Float, y: Float): Offset = Offset(x / 24f * size.width, y / 24f * size.height)
private fun DrawScope.len(v: Float): Float = v / 24f * size.minDimension

private fun DrawScope.strokeLine(tint: Color, strokeWidth: Float, x1: Float, y1: Float, x2: Float, y2: Float, alpha: Float = 1f) {
    drawLine(tint, pt(x1, y1), pt(x2, y2), strokeWidth = strokeWidth, cap = StrokeCap.Round, alpha = alpha)
}

/** An open (unfilled) polyline through [points] (x,y pairs), e.g. a viewfinder corner bracket. */
private fun DrawScope.strokePolyline(tint: Color, stroke: Stroke, vararg points: Float) {
    val path = Path().apply {
        moveTo(pt(points[0], points[1]).x, pt(points[0], points[1]).y)
        var i = 2
        while (i < points.size) {
            lineTo(pt(points[i], points[i + 1]).x, pt(points[i], points[i + 1]).y)
            i += 2
        }
    }
    drawPath(path, tint, style = stroke)
}

/** A closed, filled polygon through [points] (x,y pairs). */
private fun DrawScope.fillPolygon(tint: Color, vararg points: Float) {
    val path = Path().apply {
        moveTo(pt(points[0], points[1]).x, pt(points[0], points[1]).y)
        var i = 2
        while (i < points.size) {
            lineTo(pt(points[i], points[i + 1]).x, pt(points[i], points[i + 1]).y)
            i += 2
        }
        close()
    }
    drawPath(path, tint)
}

/** The four-corner "viewfinder" frame shared by [GridIcon.DETECT_OBJECTS] and
 *  [GridIcon.IDENTIFY_OBJECTS_COLORS] - both are the segmentation detector pointed at whatever's
 *  in view, differing only in what they report once something's found. */
private fun DrawScope.viewfinderCorners(tint: Color, stroke: Stroke) {
    strokePolyline(tint, stroke, 4f, 8f, 4f, 4f, 8f, 4f)
    strokePolyline(tint, stroke, 16f, 4f, 20f, 4f, 20f, 8f)
    strokePolyline(tint, stroke, 20f, 16f, 20f, 20f, 16f, 20f)
    strokePolyline(tint, stroke, 8f, 20f, 4f, 20f, 4f, 16f)
}

/** A simple teardrop (paint-drop) silhouette - a filled circle for the round base plus an
 *  overlapping filled triangle for the point, rather than an exact bezier drop; at icon size the
 *  two blend into one continuous shape. Shared by [GridIcon.SIMPLE_COLOR] (full-size) and
 *  [GridIcon.IDENTIFY_OBJECTS_COLORS] (small, inside the viewfinder corners) so the two "this
 *  button is about color" glyphs read as the same family. */
private fun DrawScope.teardrop(tint: Color, cx: Float, apexY: Float, baseCy: Float, r: Float) {
    drawCircle(tint, radius = len(r), center = pt(cx, baseCy))
    fillPolygon(tint, cx, apexY, cx - r * 0.95f, baseCy - r * 0.25f, cx + r * 0.95f, baseCy - r * 0.25f)
}

/** The center dot + two pairs of flanking partial-ring arcs shared by [GridIcon.EXPLORE] - a
 *  finger touching a point, with sound/ripple arcs fanning out on both sides. */
private fun DrawScope.rippleGlyph(tint: Color, strokeWidth: Float) {
    drawCircle(tint, radius = len(2f), center = pt(12f, 12f))
    val ringStroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    for ((radius, alpha) in listOf(5f to 0.9f, 8f to 0.55f)) {
        val topLeft = pt(12f - radius, 12f - radius)
        val ringSize = androidx.compose.ui.geometry.Size(len(radius * 2), len(radius * 2))
        drawArc(tint, startAngle = 115f, sweepAngle = 130f, useCenter = false, topLeft = topLeft, size = ringSize, style = ringStroke, alpha = alpha)
        drawArc(tint, startAngle = -65f, sweepAngle = 130f, useCenter = false, topLeft = topLeft, size = ringSize, style = ringStroke, alpha = alpha)
    }
}

/** [GridIcon.SCAN_BARCODE]: the bars of a 1D barcode inside the same four-corner viewfinder frame
 *  the detector glyphs use ([viewfinderCorners]) — "point the camera at this and it will be read"
 *  is exactly the shared idea, and the bars are what distinguishes it from them. Bar x-positions
 *  and widths are deliberately uneven, the way a real barcode's are; a set of evenly spaced
 *  identical lines reads as a grid or a menu icon instead. */
private fun DrawScope.barcodeBars(tint: Color, strokeWidth: Float) {
    val topY = 8f
    val bottomY = 16f
    // (x, thickness multiplier) — a thick bar is drawn as two adjacent strokes rather than with a
    // per-bar stroke width, keeping every line in this icon set at the one shared weight.
    for ((x, wide) in listOf(7f to false, 9f to true, 12f to false, 14.5f to true, 17f to false)) {
        strokeLine(tint, strokeWidth, x, topY, x, bottomY)
        if (wide) strokeLine(tint, strokeWidth, x + 0.9f, topY, x + 0.9f, bottomY)
    }
}

private fun DrawScope.drawGridIcon(icon: GridIcon, tint: Color) {
    val strokeWidth = size.minDimension * STROKE_FRACTION
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)

    when (icon) {
        GridIcon.DETECT_OBJECTS -> {
            viewfinderCorners(tint, stroke)
            drawCircle(tint, radius = len(2.2f), center = pt(12f, 12f))
        }

        GridIcon.IDENTIFY_OBJECTS_COLORS -> {
            viewfinderCorners(tint, stroke)
            teardrop(tint, cx = 12f, apexY = 9.3f, baseCy = 14.3f, r = 2.7f)
        }

        GridIcon.EXPLORE -> rippleGlyph(tint, strokeWidth)

        GridIcon.LIGHT_METER -> {
            drawCircle(tint, radius = len(4.2f), center = pt(12f, 12f), style = stroke)
            val inner = 6.4f
            val outer = 9.3f
            for (deg in 0 until 360 step 45) {
                val rad = Math.toRadians(deg.toDouble())
                val cos = kotlin.math.cos(rad).toFloat()
                val sin = kotlin.math.sin(rad).toFloat()
                strokeLine(
                    tint, strokeWidth,
                    12f + inner * cos, 12f + inner * sin,
                    12f + outer * cos, 12f + outer * sin
                )
            }
        }

        GridIcon.SIMPLE_COLOR -> teardrop(tint, cx = 12f, apexY = 3.4f, baseCy = 13.6f, r = 4.6f)

        GridIcon.SCAN_BARCODE -> {
            viewfinderCorners(tint, stroke)
            barcodeBars(tint, strokeWidth)
        }

        GridIcon.BRIEF_NARRATION -> {
            drawRoundRect(
                tint, topLeft = pt(4f, 5f),
                size = androidx.compose.ui.geometry.Size(len(16f), len(11f)),
                cornerRadius = CornerRadius(len(2.4f), len(2.4f)),
                style = stroke
            )
            fillPolygon(tint, 6f, 16f, 6f, 19.3f, 9.3f, 16f)
            strokeLine(tint, strokeWidth, 7f, 9.3f, 14f, 9.3f)
            strokeLine(tint, strokeWidth, 7f, 12.3f, 11f, 12.3f)
        }

        GridIcon.DETAILED_SCENE -> {
            val eye = Path().apply {
                moveTo(pt(2f, 12f).x, pt(2f, 12f).y)
                quadraticTo(pt(12f, 3.3f).x, pt(12f, 3.3f).y, pt(22f, 12f).x, pt(22f, 12f).y)
                quadraticTo(pt(12f, 20.7f).x, pt(12f, 20.7f).y, pt(2f, 12f).x, pt(2f, 12f).y)
                close()
            }
            drawPath(eye, tint, style = stroke)
            drawCircle(tint, radius = len(2.6f), center = pt(12f, 12f))
        }

        GridIcon.PEOPLE_OUTFIT -> {
            drawCircle(tint, radius = len(3.1f), center = pt(12f, 7f), style = stroke)
            drawArc(
                tint, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = pt(5.5f, 10.3f),
                size = androidx.compose.ui.geometry.Size(len(13f), len(13f)),
                style = stroke
            )
        }

        GridIcon.DESCRIBE_TV -> {
            drawRoundRect(
                tint, topLeft = pt(3f, 4.5f),
                size = androidx.compose.ui.geometry.Size(len(18f), len(12f)),
                cornerRadius = CornerRadius(len(1.3f), len(1.3f)),
                style = stroke
            )
            strokeLine(tint, strokeWidth, 12f, 16.5f, 12f, 19f)
            strokeLine(tint, strokeWidth, 8.5f, 19.5f, 15.5f, 19.5f)
        }

        GridIcon.OBJECTS -> {
            drawRoundRect(
                tint, topLeft = pt(4f, 4f),
                size = androidx.compose.ui.geometry.Size(len(9f), len(9f)),
                cornerRadius = CornerRadius(len(1.6f), len(1.6f)),
                style = stroke
            )
            drawCircle(tint, radius = len(4.3f), center = pt(16.5f, 16.5f), style = stroke)
        }

        GridIcon.CUSTOM_PROMPT -> {
            drawRoundRect(
                tint, topLeft = pt(4f, 5f),
                size = androidx.compose.ui.geometry.Size(len(16f), len(11f)),
                cornerRadius = CornerRadius(len(2.4f), len(2.4f)),
                style = stroke
            )
            fillPolygon(tint, 6f, 16f, 6f, 19.3f, 9.3f, 16f)
            // A "?" - a hooked arc, a short stub down, then a dot; not a typographic glyph, just
            // legible as "question" at icon size.
            drawArc(
                tint, startAngle = -40f, sweepAngle = 250f, useCenter = false,
                topLeft = pt(9.3f, 7.4f),
                size = androidx.compose.ui.geometry.Size(len(4.2f), len(4.2f)),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            strokeLine(tint, strokeWidth, 11.6f, 11.4f, 11.6f, 12.6f)
            drawCircle(tint, radius = len(0.55f), center = pt(11.6f, 14.6f))
        }

        GridIcon.VOLUME_KEYS -> {
            fillPolygon(tint, 4f, 10f, 4f, 14f, 7.2f, 14f, 12f, 17.8f, 12f, 6.2f, 7.2f, 10f)
            for ((radius, alpha) in listOf(3f to 1f, 5.3f to 0.55f)) {
                drawArc(
                    tint, startAngle = -45f, sweepAngle = 90f, useCenter = false,
                    topLeft = pt(15f - radius, 12f - radius),
                    size = androidx.compose.ui.geometry.Size(len(radius * 2), len(radius * 2)),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round), alpha = alpha
                )
            }
        }

        GridIcon.SWITCH_CAMERA -> {
            val ringStroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            val ringSize = androidx.compose.ui.geometry.Size(len(16f), len(16f))
            drawArc(tint, startAngle = 200f, sweepAngle = 150f, useCenter = false, topLeft = pt(4f, 4f), size = ringSize, style = ringStroke)
            drawArc(tint, startAngle = 20f, sweepAngle = 150f, useCenter = false, topLeft = pt(4f, 4f), size = ringSize, style = ringStroke)
            drawCircle(tint, radius = len(3.2f), center = pt(12f, 12f), style = stroke)
        }

        GridIcon.FLASH_ON, GridIcon.FLASH_OFF -> {
            fillPolygon(tint, 13f, 2f, 5f, 13.5f, 10.2f, 13.5f, 10.2f, 22f, 19f, 9.8f, 13.6f, 9.8f)
            if (icon == GridIcon.FLASH_OFF) {
                // A cutout slash (drawn in the background color first) so the crossing gap reads
                // clearly against the bolt's own fill, then the visible slash on top.
                strokeLine(Color.Black, strokeWidth * 2f, 3.2f, 3.2f, 20.8f, 20.8f)
                strokeLine(tint, strokeWidth, 3.2f, 3.2f, 20.8f, 20.8f)
            }
        }

        GridIcon.MICROPHONE -> {
            drawRoundRect(
                tint, topLeft = pt(9f, 2.5f),
                size = androidx.compose.ui.geometry.Size(len(6f), len(10.5f)),
                cornerRadius = CornerRadius(len(3f), len(3f)),
                style = stroke
            )
            drawArc(
                tint, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                topLeft = pt(6f, 8f),
                size = androidx.compose.ui.geometry.Size(len(12f), len(9f)),
                style = stroke
            )
            strokeLine(tint, strokeWidth, 12f, 17f, 12f, 20.5f)
            strokeLine(tint, strokeWidth, 8.5f, 20.5f, 15.5f, 20.5f)
        }

        GridIcon.REWIND -> {
            fillPolygon(tint, 12f, 6f, 4f, 12f, 12f, 18f)
            fillPolygon(tint, 21f, 6f, 13f, 12f, 21f, 18f)
        }

        GridIcon.PLAY -> fillPolygon(tint, 7f, 5.5f, 7f, 18.5f, 18f, 12f)

        GridIcon.PAUSE -> {
            drawRoundRect(
                tint, topLeft = pt(6.5f, 5.5f),
                size = androidx.compose.ui.geometry.Size(len(4f), len(13f)),
                cornerRadius = CornerRadius(len(1.2f), len(1.2f))
            )
            drawRoundRect(
                tint, topLeft = pt(13.5f, 5.5f),
                size = androidx.compose.ui.geometry.Size(len(4f), len(13f)),
                cornerRadius = CornerRadius(len(1.2f), len(1.2f))
            )
        }

        GridIcon.FAST_FORWARD -> {
            fillPolygon(tint, 12f, 6f, 20f, 12f, 12f, 18f)
            fillPolygon(tint, 3f, 6f, 11f, 12f, 3f, 18f)
        }
    }
}
