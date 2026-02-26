package io.nurunuru.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object NuruIcons {
    val Close: ImageVector = ImageVector.Builder(
        name = "Close",
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
        // <line x1="18" y1="6" x2="6" y2="18" />
        moveTo(18f, 6f)
        lineTo(6f, 18f)
        // <line x1="6" y1="6" x2="18" y2="18" />
        moveTo(6f, 6f)
        lineTo(18f, 18f)
    }.build()

    val Video: ImageVector = ImageVector.Builder(
        name = "Video",
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
        // <polygon points="23 7 16 12 23 17 23 7"></polygon>
        moveTo(23f, 7f)
        lineTo(16f, 12f)
        lineTo(23f, 17f)
        close()
        // <rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect>
        moveTo(3f, 5f)
        horizontalLineTo(14f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
        verticalLineToRelative(10f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
        horizontalLineTo(3f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
        verticalLineTo(7f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
        close()
    }.build()

    val Image: ImageVector = ImageVector.Builder(
        name = "Image",
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
        // <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
        moveTo(5f, 3f)
        horizontalLineTo(19f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
        verticalLineToRelative(14f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
        horizontalLineTo(5f)
        arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
        verticalLineTo(5f)
        arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
        close()
        // <circle cx="8.5" cy="8.5" r="1.5" />
        moveTo(8.5f, 10f)
        arcToRelative(1.5f, 1.5f, 0f, true, false, 0f, -3f)
        arcToRelative(1.5f, 1.5f, 0f, false, false, 0f, 3f)
        close()
        // <polyline points="21 15 16 10 5 21" />
        moveTo(21f, 15f)
        lineTo(16f, 10f)
        lineTo(5f, 21f)
    }.build()

    val Warning: ImageVector = ImageVector.Builder(
        name = "Warning",
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
        // <path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/>
        moveTo(10.29f, 3.86f)
        lineTo(1.82f, 18f)
        arcToRelative(2f, 2f, 0f, false, false, 1.71f, 3f)
        horizontalLineToRelative(16.94f)
        arcToRelative(2f, 2f, 0f, false, false, 1.71f, -3f)
        lineTo(13.71f, 3.86f)
        arcToRelative(2f, 2f, 0f, false, false, -3.42f, 0f)
        close()
        // <line x1="12" y1="9" x2="12" y2="13"/>
        moveTo(12f, 9f)
        verticalLineToRelative(4f)
        // <line x1="12" y1="17" x2="12.01" y2="17"/>
        moveTo(12f, 17f)
        horizontalLineToRelative(0.01f)
    }.build()

    val Emoji: ImageVector = ImageVector.Builder(
        name = "Emoji",
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
        // <circle cx="12" cy="12" r="10" />
        moveTo(22f, 12f)
        arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
        arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
        close()
        // <path d="M8 14s1.5 2 4 2 4-2 4-2" />
        moveTo(8f, 14f)
        reflectiveCurveToRelative(1.5f, 2f, 4f, 2f)
        reflectiveCurveToRelative(4f, -2f, 4f, -2f)
        // <line x1="9" y1="9" x2="9.01" y2="9" />
        moveTo(9f, 9f)
        horizontalLineToRelative(0.01f)
        // <line x1="15" y1="9" x2="15.01" y2="9" />
        moveTo(15f, 9f)
        horizontalLineToRelative(0.01f)
    }.build()

    val Mic: ImageVector = ImageVector.Builder(
        name = "Mic",
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
        // <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/>
        moveTo(12f, 1f)
        arcToRelative(3f, 3f, 0f, false, false, -3f, 3f)
        verticalLineToRelative(8f)
        arcToRelative(3f, 3f, 0f, false, false, 6f, 0f)
        verticalLineTo(4f)
        arcToRelative(3f, 3f, 0f, false, false, -3f, -3f)
        close()
        // <path d="M19 10v2a7 7 0 0 1-14 0v-2"/>
        moveTo(19f, 10f)
        verticalLineToRelative(2f)
        arcToRelative(7f, 7f, 0f, false, true, -14f, 0f)
        verticalLineToRelative(-2f)
        // <line x1="12" y1="19" x2="12" y2="23"/>
        moveTo(12f, 19f)
        verticalLineToRelative(4f)
        // <line x1="8" y1="23" x2="16" y2="23"/>
        moveTo(8f, 23f)
        horizontalLineTo(16f)
    }.build()

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

    val Search: ImageVector = ImageVector.Builder(
        name = "Search",
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
        // <circle cx="11" cy="11" r="8"/>
        moveTo(11f, 11f)
        moveToRelative(-8f, 0f)
        arcToRelative(8f, 8f, 0f, true, true, 16f, 0f)
        arcToRelative(8f, 8f, 0f, true, true, -16f, 0f)
        // <line x1="21" y1="21" x2="16.65" y2="16.65"/>
        moveTo(21f, 21f)
        lineTo(16.65f, 16.65f)
    }.build()

    val Notifications: ImageVector = ImageVector.Builder(
        name = "Notifications",
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
        // <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/>
        moveTo(18f, 8f)
        arcToRelative(6f, 6f, 0f, false, false, -12f, 0f)
        curveToRelative(0f, 7f, -3f, 9f, -3f, 9f)
        horizontalLineToRelative(18f)
        reflectiveCurveToRelative(-3f, -2f, -3f, -9f)
        // <path d="M13.73 21a2 2 0 0 1-3.46 0"/>
        moveTo(13.73f, 21f)
        arcToRelative(2f, 2f, 0f, false, true, -3.46f, 0f)
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

    val Edit: ImageVector = ImageVector.Builder(
        name = "Edit",
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
        // M11 4H4a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7
        moveTo(11f, 4f)
        horizontalLineTo(4f)
        arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
        verticalLineToRelative(14f)
        arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
        horizontalLineToRelative(14f)
        arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
        verticalLineToRelative(-7f)

        // M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z
        moveTo(18.5f, 2.5f)
        arcToRelative(2.121f, 2.121f, 0f, false, true, 3f, 3f)
        lineTo(12f, 15f)
        lineToRelative(-4f, 1f)
        lineToRelative(1f, -4f)
        lineToRelative(9.5f, -9.5f)
        close()
    }.build()

    fun Home(filled: Boolean): ImageVector {
        return ImageVector.Builder(
            name = "Home",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(
            fill = if (filled) SolidColor(Color.White) else null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = if (filled) 0f else 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            if (filled) {
                moveTo(12f, 2f)
                lineTo(3f, 9f)
                verticalLineToRelative(12f)
                arcToRelative(1f, 1f, 0f, false, false, 1f, 1f)
                horizontalLineToRelative(5f)
                arcToRelative(1f, 1f, 0f, false, false, 1f, -1f)
                verticalLineToRelative(-5f)
                arcToRelative(1f, 1f, 0f, false, true, 1f, -1f)
                horizontalLineToRelative(2f)
                arcToRelative(1f, 1f, 0f, false, true, 1f, 1f)
                verticalLineToRelative(5f)
                arcToRelative(1f, 1f, 0f, false, false, 1f, 1f)
                horizontalLineToRelative(5f)
                arcToRelative(1f, 1f, 0f, false, false, 1f, -1f)
                verticalLineTo(9f)
                lineTo(12f, 2f)
                close()
            } else {
                moveTo(3f, 9f)
                lineToRelative(9f, -7f)
                lineToRelative(9f, 7f)
                verticalLineToRelative(11f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(9f)
                close()
                moveTo(9f, 22f)
                verticalLineTo(12f)
                horizontalLineToRelative(6f)
                verticalLineToRelative(10f)
            }
        }.build()
    }

    fun Talk(filled: Boolean): ImageVector {
        return ImageVector.Builder(
            name = "Talk",
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
            // M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 01-7.6 4.7 8.38 8.38 0 01-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 01-.9-3.8 8.5 8.5 0 014.7-7.6 8.38 8.38 0 013.8-.9h.5a8.48 8.48 0 018 8v.5z
            moveTo(21f, 11.5f)
            arcToRelative(8.38f, 8.38f, 0f, false, true, -0.9f, 3.8f)
            arcToRelative(8.5f, 8.5f, 0f, false, true, -7.6f, 4.7f)
            arcToRelative(8.38f, 8.38f, 0f, false, true, -3.8f, -0.9f)
            lineTo(3f, 21f)
            lineToRelative(1.9f, -5.7f)
            arcToRelative(8.38f, 8.38f, 0f, false, true, -0.9f, -3.8f)
            arcToRelative(8.5f, 8.5f, 0f, false, true, 4.7f, -7.6f)
            arcToRelative(8.38f, 8.38f, 0f, false, true, 3.8f, -0.9f)
            horizontalLineToRelative(0.5f)
            arcToRelative(8.48f, 8.48f, 0f, false, true, 8f, 8f)
            verticalLineToRelative(0.5f)
            close()
        }.build()
    }

    fun Timeline(filled: Boolean): ImageVector {
        return ImageVector.Builder(
            name = "Timeline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(
            fill = if (filled) SolidColor(Color.White) else null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = if (filled) 0f else 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            if (filled) {
                moveTo(4f, 4f)
                horizontalLineTo(20f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(18f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(6f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
                moveTo(6f, 8f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(4f)
                verticalLineTo(8f)
                horizontalLineTo(6f)
                close()
                moveTo(6f, 12f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(8f)
                verticalLineToRelative(-2f)
                horizontalLineTo(6f)
                close()
                moveTo(6f, 16f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(6f)
                verticalLineToRelative(-2f)
                horizontalLineTo(6f)
                close()
                moveTo(16f, 8f)
                verticalLineToRelative(10f)
                horizontalLineToRelative(2f)
                verticalLineTo(8f)
                horizontalLineToRelative(-2f)
                close()
            } else {
                moveTo(19f, 20f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(6f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                horizontalLineToRelative(14f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineToRelative(12f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                close()
                moveTo(16f, 2f)
                verticalLineToRelative(4f)
                moveTo(8f, 2f)
                verticalLineToRelative(4f)
                moveTo(3f, 10f)
                horizontalLineToRelative(18f)
            }
        }.build()
    }

    fun Grid(filled: Boolean): ImageVector {
        return ImageVector.Builder(
            name = "Grid",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).path(
            fill = if (filled) SolidColor(Color.White) else null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = if (filled) 0f else 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(4f, 4f)
            horizontalLineTo(10f)
            verticalLineTo(10f)
            horizontalLineTo(4f)
            verticalLineTo(4f)
            close()
            moveTo(14f, 4f)
            horizontalLineTo(20f)
            verticalLineTo(10f)
            horizontalLineTo(14f)
            verticalLineTo(4f)
            close()
            moveTo(4f, 14f)
            horizontalLineTo(10f)
            verticalLineTo(20f)
            horizontalLineTo(4f)
            verticalLineTo(14f)
            close()
            moveTo(14f, 14f)
            horizontalLineTo(20f)
            verticalLineTo(20f)
            horizontalLineTo(14f)
            verticalLineTo(14f)
            close()
        }.build()
    }
}
