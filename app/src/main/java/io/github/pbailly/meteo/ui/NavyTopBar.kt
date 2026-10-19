package io.github.pbailly.meteo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import io.github.pbailly.meteo.ui.theme.FrontCold
import io.github.pbailly.meteo.ui.theme.FrontWarm
import io.github.pbailly.meteo.ui.theme.headerBlue

/** Barre du haut bleu marine commune aux écrans, soulignée par la bande de fronts de l'identité. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavyTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val header = headerBlue()
    Column(Modifier.background(header)) {
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = header,
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White,
                actionIconContentColor = Color.White,
            ),
        )
        FrontStrip()
    }
}

/** Ligne de front météo posée sur le bord bas : triangles (front froid) et demi-cercles (front chaud) alternés. */
@Composable
fun FrontStrip(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val step = 18.dp.toPx()
        val half = 4.dp.toPx()
        val peak = 6.5.dp.toPx()
        val base = size.height
        var x = 15.dp.toPx()
        var cold = true
        while (x - half < size.width) {
            if (cold) {
                val triangle = Path().apply {
                    moveTo(x - half, base)
                    lineTo(x + half, base)
                    lineTo(x, base - peak)
                    close()
                }
                drawPath(triangle, FrontCold.copy(alpha = 0.9f))
            } else {
                drawArc(
                    FrontWarm.copy(alpha = 0.9f),
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(x - half, base - half),
                    size = Size(2 * half, 2 * half),
                )
            }
            x += step
            cold = !cold
        }
    }
}
