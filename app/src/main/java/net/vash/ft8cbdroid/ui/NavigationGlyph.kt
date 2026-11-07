package net.vash.ft8cbdroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Small authored line icons; navigation labels supply accessibility names. */
@Composable
fun NavigationGlyph(destination: AppDestination) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24
        fun line(a: Float, b: Float, c: Float, d: Float) = drawLine(color, Offset(a*u,b*u), Offset(c*u,d*u), 1.6f*u, StrokeCap.Round)
        when (destination) {
            AppDestination.OPERATE -> {
                line(3f,20f,21f,20f); line(6f,16f,6f,11f); line(12f,16f,12f,4f); line(18f,16f,18f,8f)
            }
            AppDestination.CHAT -> {
                drawRoundRect(color, Offset(3*u,3*u), Size(18*u,14*u), CornerRadius(3*u), style=Stroke(1.6f*u))
                val tail=Path().apply { moveTo(7*u,17*u); lineTo(7*u,21*u); lineTo(12*u,17*u) }
                drawPath(tail,color,style=Stroke(1.6f*u));line(7f,8f,17f,8f);line(7f,12f,13f,12f)
            }
            else -> {
                for ((y,x) in listOf(6f to 9f,12f to 16f,18f to 7f)) {
                    line(3f,y,x-2,y);line(x+2,y,21f,y)
                    drawCircle(color,2*u,Offset(x*u,y*u),style=Stroke(1.6f*u))
                }
            }
        }
    }
}
