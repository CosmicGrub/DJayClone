package com.oblivion.djayclone

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A vinyl-record jog wheel styled after djay's deck view: the track's own
 * cover art fills the label area at the center, real vinyl grooves ring it,
 * and the whole disc spins together (art included) while playing, exactly
 * like a physical record on a platter.
 */
@Composable
fun Turntable(
    accentColor: Color,
    isPlaying: Boolean,
    trackName: String,
    albumArt: Bitmap?,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    onTap: () -> Unit,
) {
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "platter")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    val effectiveRotation = if (isPlaying) rotation else 0f
    val imageBitmap = remember(albumArt) { albumArt?.asImageBitmap() }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(enabled = !locked) { onTap() },
        contentAlignment = Alignment.Center
    ) {
        // Slight drop shadow / rim glow behind the platter for depth.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(accentColor.copy(alpha = 0.25f), Color.Transparent)))
        )

        // Everything below rotates together as one physical disc.
        Box(
            modifier = Modifier
                .fillMaxSize(0.97f)
                .graphicsLayer { rotationZ = effectiveRotation }
                .clip(CircleShape)
                .background(Color(0xFF0A0A0C)),
            contentAlignment = Alignment.Center
        ) {
            // Vinyl body texture (grooves), drawn full-bleed under the art.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = size.minDimension / 2f

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF232328), Color(0xFF08080A)),
                        center = center,
                        radius = maxRadius
                    ),
                    radius = maxRadius,
                    center = center
                )

                var r = maxRadius * 0.42f
                while (r < maxRadius * 0.98f) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.05f),
                        radius = r,
                        center = center,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f)
                    )
                    r += maxRadius * 0.045f
                }

                // Glossy highlight arc, like light catching the vinyl.
                drawArc(
                    brush = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.10f), Color.Transparent)
                    ),
                    startAngle = -140f,
                    sweepAngle = 80f,
                    useCenter = true,
                    topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                    size = androidx.compose.ui.geometry.Size(maxRadius * 2, maxRadius * 2)
                )

                // Position stripe so rotation speed/direction reads clearly,
                // like a stroboscope mark on a real platter.
                drawLine(
                    color = accentColor,
                    start = Offset(center.x, center.y - maxRadius * 0.4f),
                    end = Offset(center.x, center.y - maxRadius * 0.98f),
                    strokeWidth = 3f
                )
            }

            // Center label = album art, or a plain colored label if none.
            // Sized large (like djay's deck view) so artwork reads clearly,
            // leaving just a narrow groove rim visible around the edge.
            Box(
                modifier = Modifier
                    .fillMaxSize(0.72f)
                    .clip(CircleShape)
                    .background(if (imageBitmap != null) Color.Black else Color(0xFF1B1B20)),
                contentAlignment = Alignment.Center
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Album art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = trackName.take(2).uppercase(),
                        color = accentColor,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Spindle hole.
            Box(
                modifier = Modifier
                    .fillMaxSize(0.045f)
                    .clip(CircleShape)
                    .background(Color.Black)
            )
        }
    }
}
