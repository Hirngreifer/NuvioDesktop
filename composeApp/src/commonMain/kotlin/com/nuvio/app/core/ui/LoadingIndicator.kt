package com.nuvio.app.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import kotlin.math.min

private const val LoadingIndicatorSegments = 12
private const val LoadingIndicatorAnimationMillis = 1_000

@Composable
fun NuvioLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.nuvio.colors.textSecondary,
    size: Dp = NuvioTokens.Space.s40,
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        val transition = rememberInfiniteTransition(label = "nuvio_loading_indicator")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = LoadingIndicatorSegments.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = LoadingIndicatorAnimationMillis,
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "nuvio_loading_indicator_phase",
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasSize = this.size
            val minDimension = min(canvasSize.width, canvasSize.height)
            val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
            val strokeWidth = minDimension * 0.067f
            val outerRadius = minDimension * 0.463f
            val innerRadius = minDimension * 0.293f
            val segmentCount = LoadingIndicatorSegments.toFloat()

            repeat(LoadingIndicatorSegments) { index ->
                val distanceFromHead = (index - phase + segmentCount) % segmentCount
                val alpha = (1f - distanceFromHead / segmentCount)
                    .coerceIn(0.08f, 1f)
                rotate(
                    degrees = index * (360f / LoadingIndicatorSegments),
                    pivot = center,
                ) {
                    drawLine(
                        color = color.copy(alpha = alpha),
                        start = Offset(center.x, center.y - outerRadius),
                        end = Offset(center.x, center.y - innerRadius),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
