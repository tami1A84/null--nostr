package io.nurunuru.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object NuruIcons {
    fun Like(filled: Boolean): ImageVector {
        return ImageVector.Builder(
            name = "Like",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(
            fill = if (filled) SolidColor(Color.White) else null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // M14 9V5a3 3 0 00-3-3l-4 9v11h11.28a2 2 0 002-1.7l1.38-9a2 2 0 00-2-2.3H14z
            moveTo(14f, 9f)
            verticalLineTo(5f)
            arcToRelative(3f, 3f, 0f, false, false, -3f, -3f)
            lineToRelative(-4f, 9f)
            verticalLineToRelative(11f)
            horizontalLineToRelative(11.28f)
            arcToRelative(2f, 2f, 0f, false, false, 2f, -1.7f)
            lineToRelative(1.38f, -9f)
            arcToRelative(2f, 2f, 0f, false, false, -2f, -2.3f)
            horizontalLineTo(14f)
            close()
            // M7 22H4a2 2 0 01-2-2v-7a2 2 0 012-2h3
            moveTo(7f, 22f)
            horizontalLineTo(4f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            verticalLineToRelative(-7f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineTo(7f)
        }.build()
    }

    val Website: ImageVector = ImageVector.Builder(
        name = "Website",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(
        fill = null,
        stroke = SolidColor(Color.White),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ) {
        // <circle cx="12" cy="12" r="10"/>
        moveTo(22f, 12f)
        arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
        arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
        close()
        // <line x1="2" y1="12" x2="22" y2="12"/>
        moveTo(2f, 12f)
        horizontalLineTo(22f)
        // <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>
        moveTo(12f, 2f)
        arcToRelative(15.3f, 15.3f, 0f, false, true, 4f, 10f)
        arcToRelative(15.3f, 15.3f, 0f, false, true, -4f, 10f)
        arcToRelative(15.3f, 15.3f, 0f, false, true, -4f, -10f)
        arcToRelative(15.3f, 15.3f, 0f, false, true, 4f, -10f)
        close()
    }.build()

    val Cake: ImageVector = ImageVector.Builder(
        name = "Cake",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(
        fill = null,
        stroke = SolidColor(Color.White),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ) {
        // <path d="M20 21v-8a2 2 0 00-2-2H6a2 2 0 00-2 2v8"/>
        moveTo(20f, 21f)
        verticalLineToRelative(-8f)
        arcToRelative(2f, 2f, 0f, false, false, -2f, -2f)
        horizontalLineTo(6f)
        arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
        verticalLineToRelative(8f)
        // <path d="M4 16s.5-1 2-1 2.5 2 4 2 2.5-2 4-2 2.5 2 4 2 2-1 2-1"/>
        moveTo(4f, 16f)
        reflectiveCurveToRelative(0.5f, -1f, 2f, -1f)
        reflectiveCurveToRelative(2.5f, 2f, 4f, 2f)
        reflectiveCurveToRelative(2.5f, -2f, 4f, -2f)
        reflectiveCurveToRelative(2.5f, 2f, 4f, 2f)
        reflectiveCurveToRelative(2f, -1f, 2f, -1f)
        // <path d="M2 21h20"/>
        moveTo(2f, 21f)
        horizontalLineTo(22f)
        // <path d="M7 8v2"/>
        moveTo(7f, 8f)
        verticalLineToRelative(2f)
        // <path d="M12 8v2"/>
        moveTo(12f, 8f)
        verticalLineToRelative(2f)
        // <path d="M17 8v2"/>
        moveTo(17f, 8f)
        verticalLineToRelative(2f)
        // <path d="M7 4h.01"/>
        moveTo(7f, 4f)
        horizontalLineTo(7.01f)
        // <path d="M12 4h.01"/>
        moveTo(12f, 4f)
        horizontalLineTo(12.01f)
        // <path d="M17 4h.01"/>
        moveTo(17f, 4f)
        horizontalLineTo(17.01f)
    }.build()

    val Repost: ImageVector = ImageVector.Builder(
        name = "Repost",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(
        fill = null,
        stroke = SolidColor(Color.White),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    ) {
        // Polyline: 17 1 21 5 17 9
        moveTo(17f, 1f)
        lineTo(21f, 5f)
        lineTo(17f, 9f)
        // Path: M3 11V9a4 4 0 014-4h14
        moveTo(3f, 11f)
        verticalLineTo(9f)
        arcToRelative(4f, 4f, 0f, false, true, 4f, -4f)
        horizontalLineTo(21f)
        // Polyline: 7 23 3 19 7 15
        moveTo(7f, 23f)
        lineTo(3f, 19f)
        lineTo(7f, 15f)
        // Path: M21 13v2a4 4 0 01-4 4H3
        moveTo(21f, 13f)
        verticalLineTo(15f)
        arcToRelative(4f, 4f, 0f, false, true, -4f, 4f)
        horizontalLineTo(3f)
    }.build()

    fun Zap(filled: Boolean): ImageVector {
        return ImageVector.Builder(
            name = "Zap",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(
            fill = if (filled) SolidColor(Color.White) else null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // 13 2 3 14 12 14 11 22 21 10 12 10 13 2
            moveTo(13f, 2f)
            lineTo(3f, 14f)
            lineTo(12f, 14f)
            lineTo(11f, 22f)
            lineTo(21f, 10f)
            lineTo(12f, 10f)
            close()
        }.build()
    }
}
