package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.model.BrainActivityPhase
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyberBlue
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeonPurpleBright
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.SurfaceCard
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NeuralCoreOrb(
    isActive: Boolean,
    isError: Boolean = false,
    size: Dp = 64.dp,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "neural_core_transition")
    val rotationAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 2800 else 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbital_rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 750 else 2100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val primaryAccent = if (isError) CrimsonError else NeonPurpleBright
    val secondaryAccent = if (isError) CrimsonError else CyberBlue

    Canvas(modifier = modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = this.size.minDimension / 2f

        // Ambient radial aura
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryAccent.copy(alpha = if (isActive) 0.36f else 0.18f),
                    secondaryAccent.copy(alpha = if (isActive) 0.16f else 0.06f),
                    Color.Transparent
                ),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )

        // Outer dashed / sweeping neural ring
        drawCircle(
            brush = Brush.sweepGradient(
                colors = listOf(primaryAccent, secondaryAccent, primaryAccent.copy(alpha = 0.25f), primaryAccent)
            ),
            radius = radius * 0.80f,
            center = center,
            style = Stroke(width = radius * 0.07f, cap = StrokeCap.Round)
        )

        // Inner counter-ring
        drawCircle(
            color = secondaryAccent.copy(alpha = 0.45f),
            radius = radius * 0.56f,
            center = center,
            style = Stroke(width = radius * 0.045f)
        )

        // Orbiting synapse nodes
        val nodeCount = 5
        for (i in 0 until nodeCount) {
            val angleDeg = rotationAngle + (i * (360f / nodeCount))
            val rad = angleDeg * (PI / 180.0)
            val orbitRadius = radius * 0.78f
            val nx = center.x + (orbitRadius * cos(rad)).toFloat()
            val ny = center.y + (orbitRadius * sin(rad)).toFloat()
            val nodePos = Offset(nx, ny)

            drawLine(
                color = secondaryAccent.copy(alpha = if (isActive) 0.45f else 0.22f),
                start = center,
                end = nodePos,
                strokeWidth = radius * 0.03f
            )
            drawCircle(
                color = if (i % 2 == 0) primaryAccent else secondaryAccent,
                radius = radius * 0.085f,
                center = nodePos
            )
        }

        // Central luminous core
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, primaryAccent, secondaryAccent.copy(alpha = 0.5f)),
                center = center,
                radius = radius * 0.38f * pulse
            ),
            radius = radius * 0.34f * pulse,
            center = center
        )
    }
}

@Composable
fun BrainActivityStrip(
    phase: BrainActivityPhase,
    activeModelLabel: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val isError = phase == BrainActivityPhase.ERROR
    val indicatorColor = when {
        isError -> CrimsonError
        isActive -> CyberBlue
        phase == BrainActivityPhase.READY -> ElectricTeal
        else -> NeonPurple
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        SurfaceCard,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        SurfaceCard
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        NeonPurple.copy(alpha = 0.45f),
                        CyberBlue.copy(alpha = 0.45f)
                    )
                ),
                shape = RoundedCornerShape(18.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("brain_activity_strip"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NeuralCoreOrb(
            isActive = isActive,
            isError = isError,
            size = 44.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(indicatorColor)
                )
                Text(
                    text = phase.statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = phase.detailLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f))
                .border(1.dp, OutlineSubtle, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(
                text = activeModelLabel,
                style = MaterialTheme.typography.labelSmall,
                color = CyberBlue,
                maxLines = 1
            )
        }
    }
}
