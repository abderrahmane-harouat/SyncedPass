package com.abdurahmanharouat.syncedpass.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.abdurahmanharouat.syncedpass.ui.theme.SyncedPassTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A green circle that draws itself, then a checkmark drawn inside it with a
 * spring "pop", plus a light haptic tap: the same animation as the Mac's
 * SuccessCheckmark. With animations turned off in the system settings, it
 * simply appears.
 */
@Composable
fun SuccessCheckmark(size: Dp = 96.dp) {
    val color = SyncedPassTheme.colors.success
    val haptics = LocalHapticFeedback.current
    val resolver = LocalContext.current.contentResolver
    val ring = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    val scale = remember { Animatable(0.6f) }

    LaunchedEffect(Unit) {
        val animationsOff = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (animationsOff) {
            ring.snapTo(1f); check.snapTo(1f); scale.snapTo(1f)
            return@LaunchedEffect
        }
        delay(200)  // after the success screen has appeared
        launch { ring.animateTo(1f, tween(450, easing = LinearOutSlowInEasing)) }
        launch { scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
        delay(400)
        launch { delay(50); haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
        check.animateTo(1f, tween(300, easing = LinearOutSlowInEasing))
    }

    Canvas(Modifier.size(size).semantics { contentDescription = "Success" }) {
        val side = this.size.minDimension
        drawCircle(color.copy(alpha = 0.14f), radius = side / 2 * scale.value)

        val ringWidth = side * 0.055f
        drawArc(
            color, startAngle = -90f, sweepAngle = 360f * ring.value, useCenter = false,
            topLeft = Offset(ringWidth / 2, ringWidth / 2), size = Size(side - ringWidth, side - ringWidth),
            style = Stroke(ringWidth, cap = StrokeCap.Round),
        )

        // The checkmark inside, inset by 27% like on the Mac.
        val inset = side * 0.27f
        val box = side - inset * 2
        fun point(x: Float, y: Float) = Offset(inset + box * x, inset + box * y)
        val full = Path().apply {
            point(0.08f, 0.52f).let { moveTo(it.x, it.y) }
            point(0.38f, 0.80f).let { lineTo(it.x, it.y) }
            point(0.92f, 0.22f).let { lineTo(it.x, it.y) }
        }
        val measure = PathMeasure().apply { setPath(full, false) }
        val drawn = Path().also { measure.getSegment(0f, measure.length * check.value, it, true) }
        drawPath(drawn, color, style = Stroke(side * 0.07f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
