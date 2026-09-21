package dev.creditwatch.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/**
 * One stroke-drawn icon set, so glyphs picked out of the system font cannot decide how the
 * interface looks. Every icon is a 24x24 outline on the same 2px stroke and round caps, tinted
 * by the caller from the palette.
 *
 * Add telemetry icons here rather than reaching for a font or an icon dependency: the set is
 * meant to grow with activity, temperature, memory and idle marks as the agent lands.
 */
object CwIcons {
    val Settings = icon("settings") {
        moveTo(12f, 15f)
        arcToRelative(3f, 3f, 0f, true, true, 0f, -6f)
        arcToRelative(3f, 3f, 0f, true, true, 0f, 6f)
        close()
        moveTo(19.4f, 13.5f)
        lineToRelative(1.5f, 1.2f)
        lineToRelative(-2f, 3.4f)
        lineToRelative(-1.8f, -0.7f)
        arcToRelative(7.3f, 7.3f, 0f, false, true, -2f, 1.2f)
        lineToRelative(-0.3f, 1.9f)
        horizontalLineToRelative(-3.9f)
        lineToRelative(-0.3f, -1.9f)
        arcToRelative(7.3f, 7.3f, 0f, false, true, -2f, -1.2f)
        lineToRelative(-1.8f, 0.7f)
        lineToRelative(-2f, -3.4f)
        lineToRelative(1.5f, -1.2f)
        arcToRelative(7.3f, 7.3f, 0f, false, true, 0f, -2.3f)
        lineToRelative(-1.5f, -1.2f)
        lineToRelative(2f, -3.4f)
        lineToRelative(1.8f, 0.7f)
        arcToRelative(7.3f, 7.3f, 0f, false, true, 2f, -1.2f)
        lineToRelative(0.3f, -1.9f)
        horizontalLineToRelative(3.9f)
        lineToRelative(0.3f, 1.9f)
        arcToRelative(7.3f, 7.3f, 0f, false, true, 2f, 1.2f)
        lineToRelative(1.8f, -0.7f)
        lineToRelative(2f, 3.4f)
        lineToRelative(-1.5f, 1.2f)
        arcToRelative(7.3f, 7.3f, 0f, false, true, 0f, 2.3f)
        close()
    }

    val Close = icon("close") {
        moveTo(18f, 6f); lineTo(6f, 18f)
        moveTo(6f, 6f); lineTo(18f, 18f)
    }

    val Refresh = icon("refresh") {
        moveTo(21f, 12f)
        arcToRelative(9f, 9f, 0f, true, true, -2.64f, -6.36f)
        moveTo(21f, 3f)
        verticalLineToRelative(6f)
        horizontalLineToRelative(-6f)
    }

    val ChevronLeft = icon("chevron-left") {
        moveTo(15f, 18f); lineTo(9f, 12f); lineTo(15f, 6f)
    }

    val Alert = icon("alert") {
        moveTo(12f, 3.6f)
        lineTo(22f, 20.4f)
        horizontalLineTo(2f)
        close()
        moveTo(12f, 10f); verticalLineToRelative(4.2f)
        moveTo(12f, 17.4f); verticalLineToRelative(0.2f)
    }

    val ExternalLink = icon("external-link") {
        moveTo(14f, 4f); horizontalLineToRelative(6f); verticalLineToRelative(6f)
        moveTo(20f, 4f); lineTo(11f, 13f)
        moveTo(17f, 14.5f)
        verticalLineTo(19f)
        arcToRelative(1.6f, 1.6f, 0f, false, true, -1.6f, 1.6f)
        horizontalLineTo(5f)
        arcTo(1.6f, 1.6f, 0f, false, true, 3.4f, 19f)
        verticalLineTo(8.6f)
        arcTo(1.6f, 1.6f, 0f, false, true, 5f, 7f)
        horizontalLineToRelative(4.5f)
    }

    /** Compute cost. */
    val Chip = icon("chip") {
        moveTo(7.5f, 7.5f); horizontalLineToRelative(9f); verticalLineToRelative(9f); horizontalLineToRelative(-9f); close()
        moveTo(10f, 4f); verticalLineToRelative(3.5f)
        moveTo(14f, 4f); verticalLineToRelative(3.5f)
        moveTo(10f, 16.5f); verticalLineToRelative(3.5f)
        moveTo(14f, 16.5f); verticalLineToRelative(3.5f)
        moveTo(4f, 10f); horizontalLineToRelative(3.5f)
        moveTo(4f, 14f); horizontalLineToRelative(3.5f)
        moveTo(16.5f, 10f); horizontalLineToRelative(3.5f)
        moveTo(16.5f, 14f); horizontalLineToRelative(3.5f)
    }

    /** Storage cost. */
    val Disk = icon("disk") {
        moveTo(4f, 7f)
        arcToRelative(8f, 3f, 0f, true, true, 16f, 0f)
        arcToRelative(8f, 3f, 0f, true, true, -16f, 0f)
        close()
        moveTo(4f, 7f); verticalLineToRelative(10f)
        arcToRelative(8f, 3f, 0f, false, false, 16f, 0f)
        verticalLineTo(7f)
        moveTo(4f, 12f)
        arcToRelative(8f, 3f, 0f, false, false, 16f, 0f)
    }

    /** Explains a control without a second line of text beside it. */
    val Info = icon("info") {
        moveTo(12f, 21f)
        arcToRelative(9f, 9f, 0f, true, true, 0f, -18f)
        arcToRelative(9f, 9f, 0f, true, true, 0f, 18f)
        close()
        moveTo(12f, 11f); verticalLineToRelative(5.2f)
        moveTo(12f, 7.8f); verticalLineToRelative(0.2f)
    }

    /** Alerts that leave this machine. */
    val Phone = icon("phone") {
        moveTo(7f, 2.8f)
        horizontalLineToRelative(10f)
        arcToRelative(1.6f, 1.6f, 0f, false, true, 1.6f, 1.6f)
        verticalLineToRelative(15.2f)
        arcToRelative(1.6f, 1.6f, 0f, false, true, -1.6f, 1.6f)
        horizontalLineTo(7f)
        arcToRelative(1.6f, 1.6f, 0f, false, true, -1.6f, -1.6f)
        verticalLineTo(4.4f)
        arcTo(1.6f, 1.6f, 0f, false, true, 7f, 2.8f)
        close()
        moveTo(10.5f, 17.6f); horizontalLineToRelative(3f)
    }

    /** Add another provider. */
    val Plus = icon("plus") {
        moveTo(12f, 5f); verticalLineToRelative(14f)
        moveTo(5f, 12f); horizontalLineToRelative(14f)
    }

    private fun icon(name: String, path: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).addPath(
            pathData = PathBuilder().apply(path).nodes,
            fill = null,
            stroke = SolidColor(Color.Black), // replaced by the caller's tint
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
}
