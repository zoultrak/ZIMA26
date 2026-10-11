package com.zima26.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zima26.app.ui.theme.Brass
import com.zima26.app.ui.theme.BrassDark
import com.zima26.app.ui.theme.Carbon
import com.zima26.app.ui.theme.CarbonLight
import com.zima26.app.ui.theme.Paper
import com.zima26.app.ui.theme.PaperDim
import com.zima26.app.ui.theme.Safelight
import com.zima26.app.ui.theme.Sepia
import com.zima26.app.ui.theme.SepiaDark

/**
 * Botón de disparo grande con aspecto de cámara antigua: anillo de latón moleteado,
 * aro oscuro y pulsador de cuero rojizo que se hunde al pulsarlo.
 */
@Composable
fun ShutterButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier, size: Dp = 84.dp) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "shutterScale")

    Canvas(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
    ) {
        val r = this.size.minDimension / 2
        // Anillo exterior de latón con brillo metálico
        drawCircle(
            Brush.sweepGradient(listOf(Brass, BrassDark, Brass, Color(0xFFE8CF9A), BrassDark, Brass)),
            radius = r
        )
        // Moleteado: pequeñas muescas alrededor del anillo
        val notches = 48
        for (i in 0 until notches) {
            val a = Math.toRadians(i * 360.0 / notches)
            val c = Offset(center.x + (r * 0.93f) * kotlin.math.cos(a).toFloat(), center.y + (r * 0.93f) * kotlin.math.sin(a).toFloat())
            drawCircle(BrassDark.copy(alpha = 0.6f), radius = r * 0.025f, center = c)
        }
        drawCircle(Carbon, radius = r * 0.80f)
        drawCircle(Brass.copy(alpha = 0.5f), radius = r * 0.80f, style = Stroke(width = r * 0.03f))
        // Pulsador
        val buttonColor = if (enabled) Color(0xFF6E2A1C) else Color(0xFF4A3A32)
        drawCircle(
            Brush.radialGradient(
                listOf(buttonColor.copy(red = (buttonColor.red + 0.15f).coerceAtMost(1f)), buttonColor, Color(0xFF2A100A)),
                center = Offset(center.x - r * 0.15f, center.y - r * 0.15f),
                radius = r * 0.7f
            ),
            radius = r * 0.62f
        )
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = r * 0.62f, style = Stroke(width = r * 0.04f))
    }
}

/** Botón redondo con icono, borde sepia y fondo carbón. */
@Composable
fun RoundIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(CarbonLight)
            .border(1.5.dp, if (enabled) Sepia else SepiaDark, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = if (enabled) Paper else PaperDim.copy(alpha = 0.5f),
            modifier = Modifier.size(size * 0.46f)
        )
    }
}

/** Botón con icono y texto en mayúsculas de máquina de escribir. */
@Composable
fun VintageActionButton(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.5.dp, if (enabled) Sepia else SepiaDark),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = CarbonLight, contentColor = Paper),
        contentPadding = ButtonDefaults.ContentPadding
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(2.dp))
            Text(label.uppercase(), fontSize = 11.sp, maxLines = 1)
        }
    }
}

/**
 * Pantalla de "Revelando...": luz roja de cuarto oscuro, puntos animados y barra de avance.
 */
@Composable
fun DevelopingOverlay(progress: Float, batchLabel: String? = null) {
    val transition = rememberInfiniteTransition(label = "revelando")
    val dots by transition.animateFloat(
        0f, 4f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "dots"
    )
    val glow by transition.animateFloat(
        0.55f, 0.85f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "glow"
    )
    val animatedProgress by animateFloatAsState(progress, tween(300), label = "progress")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(Safelight.copy(alpha = glow), Color(0xFF1A0705), Color.Black)
                )
            )
            // Absorbe los toques para que no lleguen a la cámara
            .clickable(enabled = true, indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                "Revelando" + ".".repeat(dots.toInt().coerceAtMost(3)).padEnd(3, ' '),
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFFFFB4A0),
                textAlign = TextAlign.Center
            )
            batchLabel?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = Color(0xFFFFB4A0), modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(20.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.width(220.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = Color(0xFFFF8A70),
                trackColor = Color(0x33FFFFFF),
                drawStopIndicator = {}
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "No abras la puerta del cuarto oscuro",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xAAFFB4A0),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}
