package com.example.pacman

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min

@Composable
fun PacmanGame() {
    val rows = 17
    val cols = 15
    val map = remember { Array(rows) { IntArray(cols) { 0 } } }

    // --- 1. Prepare map ---
    LaunchedEffect(Unit) {
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                map[y][x] = if (x == 0 || y == 0 || x == cols - 1 || y == rows - 1) 1 else 0
            }
        }
        for (x in 3 until 12) map[4][x] = 1
        for (x in 3 until 12) map[12][x] = 1
    }

    var pacX by remember { mutableStateOf(0f) }
    var pacY by remember { mutableStateOf(0f) }
    var dirX by remember { mutableStateOf(0) }
    var dirY by remember { mutableStateOf(0) }

    val speed = 0.1f
    val pacDrawSize = 2f / 3f
    val pacCollisionSize = 0.75f

    // --- 2. Game loop ---
    LaunchedEffect(map) {
        // Wait a tiny moment for map to initialize
        delay(10)

        // Place Pacman exactly at center of map
        pacX = (cols - pacDrawSize) / 2f
        pacY = (rows - pacDrawSize) / 2f

        while (true) {
            val nx = pacX + dirX * speed
            val ny = pacY + dirY * speed

            // Bounding box collision
            val collisionOffset = 0.1f // how much bigger on top/left

            val left = nx - collisionOffset
            val right = nx + pacCollisionSize
            val top = ny - collisionOffset
            val bottom = ny + pacCollisionSize

            val collision = listOf(
                map[top.toInt()][left.toInt()],
                map[top.toInt()][right.toInt()],
                map[bottom.toInt()][left.toInt()],
                map[bottom.toInt()][right.toInt()]
            ).any { it == 1 }


            if (!collision) {
                pacX = nx
                pacY = ny
            }

            delay(16)
        }
    }

    // --- 3. Canvas ---
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val centerX = size.width / 2
                    val centerY = size.height / 2
                    val dx = offset.x - centerX
                    val dy = offset.y - centerY
                    dirX = 0
                    dirY = 0
                    if (abs(dx) > abs(dy)) dirX = if (dx > 0) 1 else -1
                    else dirY = if (dy > 0) 1 else -1
                }
            }
    ) {
        // --- Center map on screen ---
        val tileWidth = size.width / cols
        val tileHeight = size.height / rows
        val tileSize = min(tileWidth, tileHeight)

        val offsetX = (size.width - tileSize * cols) / 2f
        val offsetY = (size.height - tileSize * rows) / 2f

        drawMaze(map, tileSize, offsetX, offsetY)
        drawPacman(pacX, pacY, tileSize, pacDrawSize, offsetX, offsetY)
    }
}

// --- Drawing helpers ---
private fun DrawScope.drawMaze(
    map: Array<IntArray>,
    tileSize: Float,
    offsetX: Float,
    offsetY: Float
) {
    val rows = map.size
    val cols = map[0].size
    for (y in 0 until rows) {
        for (x in 0 until cols) {
            val left = x * tileSize + offsetX
            val top = y * tileSize + offsetY
            if (map[y][x] == 1) {
                drawRect(
                    color = Color.Blue,
                    topLeft = Offset(left, top),
                    size = Size(tileSize, tileSize)
                )
            } else {
                drawCircle(
                    color = Color.White,
                    radius = tileSize / 10f,
                    center = Offset(left + tileSize / 2, top + tileSize / 2)
                )
            }
        }
    }
}

private fun DrawScope.drawPacman(
    pacX: Float,
    pacY: Float,
    tileSize: Float,
    pacSize: Float,
    offsetX: Float,
    offsetY: Float
) {
    drawCircle(
        color = Color.Yellow,
        radius = (pacSize / 2f) * tileSize,
        center = Offset(
            offsetX + (pacX + pacSize / 2f) * tileSize,
            offsetY + (pacY + pacSize / 2f) * tileSize
        )
    )
}
