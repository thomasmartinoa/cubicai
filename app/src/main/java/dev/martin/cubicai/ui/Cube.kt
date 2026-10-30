package dev.martin.cubicai.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The CubicAI mark: an isometric cube with a lit top face. While [spinning], the light rolls
 * from face to face, which is the app's only "thinking" animation.
 */
@Composable
fun Cube(size: Dp, modifier: Modifier = Modifier, spinning: Boolean = false) {
    val cube = LocalCube.current
    val gap = MaterialTheme.colorScheme.background
    // The animated value is read only while drawing, so the spin redraws the cube without recomposing anything.
    val phase: State<Float> = if (spinning) {
        rememberInfiniteTransition(label = "cube").animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
            label = "phase",
        )
    } else Still
    val shades = listOf(cube.top, cube.left, cube.right)

    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val c = center
        val dx = r * sqrt(3f) / 2f
        val top = Offset(c.x, c.y - r)
        val upRight = Offset(c.x + dx, c.y - r / 2)
        val downRight = Offset(c.x + dx, c.y + r / 2)
        val bottom = Offset(c.x, c.y + r)
        val downLeft = Offset(c.x - dx, c.y + r / 2)
        val upLeft = Offset(c.x - dx, c.y - r / 2)

        fun face(a: Offset, b: Offset, c: Offset, d: Offset) = Path().apply {
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
        }

        val step = floor(phase.value).toInt()
        val t = phase.value - step
        fun shade(i: Int): Color = lerp(shades[(i + step) % 3], shades[(i + step + 1) % 3], t)

        drawPath(face(top, upRight, c, upLeft), shade(0))
        drawPath(face(upLeft, c, bottom, downLeft), shade(1))
        drawPath(face(c, upRight, downRight, bottom), shade(2))

        // Hairline seams between the faces make it read as a solid rather than a hexagon.
        val seam = (r * 0.07f).coerceAtLeast(1f)
        drawLine(gap, c, upLeft, seam)
        drawLine(gap, c, upRight, seam)
        drawLine(gap, c, bottom, seam)
    }
}

private val Still: State<Float> = mutableFloatStateOf(0f)

@Composable
fun CubeMark(modifier: Modifier = Modifier) = Cube(28.dp, modifier)
