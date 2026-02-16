package com.example.voxtranscribe.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap

@Composable
fun AudioVisualizer(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val centerY = size.height / 2
        val count = amplitudes.size.coerceAtLeast(1)
        val barWidth = size.width / count
        
        amplitudes.forEachIndexed { index, amp ->
            val barHeight = (amp * size.height).coerceAtLeast(4f)
            
            drawLine(
                color = primaryColor,
                start = Offset(x = index * barWidth + barWidth / 2, y = centerY - barHeight / 2),
                end = Offset(x = index * barWidth + barWidth / 2, y = centerY + barHeight / 2),
                strokeWidth = barWidth * 0.6f,
                cap = StrokeCap.Round
            )
        }
    }
}
