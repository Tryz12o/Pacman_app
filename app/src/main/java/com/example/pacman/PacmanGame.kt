package com.example.pacman

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

enum class GhostType { CHASER, RANDOM, AMBUSH }

data class Ghost(
    var x: Int,
    var y: Int,
    val color: Color,
    val type: GhostType
)

@Composable
fun PacmanGame() {
    val rows = 17
    val cols = 15
    var gameOver by remember { mutableStateOf(false) }
    var collectedDots by remember { mutableStateOf(0) }
    var mouthOpen by remember { mutableStateOf(true) }

    val map = remember { Array(rows) { IntArray(cols) { 2 } } } // 2=dot,1=wall,0=empty

    var pacX by remember { mutableStateOf(cols / 2) }
    var pacY by remember { mutableStateOf(rows / 2) }
    var dirX by remember { mutableStateOf(0) }
    var dirY by remember { mutableStateOf(0) }

    val ghosts = remember {
        mutableStateListOf(
            Ghost(1, 1, Color.Red, GhostType.CHASER),
            Ghost(cols - 2, 1, Color.Cyan, GhostType.RANDOM),
            Ghost(1, rows - 2, Color.Magenta, GhostType.AMBUSH)
        )
    }

    // --- Анімація рота Pac-Man ---
    LaunchedEffect(Unit) {
        while (true) {
            mouthOpen = !mouthOpen
            delay(200)
        }
    }

    LaunchedEffect(Unit) {
        fun resetMap() {
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    map[y][x] = if (x == 0 || y == 0 || x == cols - 1 || y == rows - 1) 1 else 2
                }
            }
            for (x in 3 until 12) map[4][x] = 1
            for (x in 3 until 12) map[12][x] = 1
        }

        resetMap()
        pacX = cols / 2; pacY = rows / 2
        dirX = 0; dirY = 0

        while (true) {
            if (gameOver) {
                delay(1000)
                collectedDots = 0
                resetMap()
                pacX = cols / 2; pacY = rows / 2
                dirX = 0; dirY = 0
                ghosts[0].x = 1; ghosts[0].y = 1
                ghosts[1].x = cols - 2; ghosts[1].y = 1
                ghosts[2].x = 1; ghosts[2].y = rows - 2
                gameOver = false
            }

            // --- Рух Pac-Man по сітці ---
            val nextX = pacX + dirX
            val nextY = pacY + dirY
            if (nextY in 0 until rows && nextX in 0 until cols && map[nextY][nextX] != 1) {
                pacX = nextX
                pacY = nextY
            }

            // --- Збір цяток ---
            if (map[pacY][pacX] == 2) {
                map[pacY][pacX] = 0
                collectedDots += 1
            }

            // --- Рух привидів ---
            ghosts.forEach { ghost ->
                moveGhostGrid(map, ghost, pacX, pacY, ghosts)
            }

            // --- Перевірка зіткнення ---
            if (ghosts.any { it.x == pacX && it.y == pacY }) gameOver = true

            delay(200)
        }
    }

    // --- Canvas ---
    Box(modifier = Modifier.fillMaxSize()) {
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
                        dirX = 0; dirY = 0
                        if (abs(dx) > abs(dy)) dirX = if (dx > 0) 1 else -1
                        else dirY = if (dy > 0) 1 else -1
                    }
                }
        ) {
            val tileWidth = size.width / cols
            val tileHeight = size.height / rows
            val tileSize = min(tileWidth, tileHeight)
            val offsetX = (size.width - tileSize * cols) / 2f
            val offsetY = (size.height - tileSize * rows) / 2f

            drawMazeGrid(map, tileSize, offsetX, offsetY)
            drawPacmanClassic(pacX, pacY, tileSize, offsetX, offsetY, mouthOpen)
            ghosts.forEach { drawGhostClassic(it, tileSize, offsetX, offsetY) }
        }

        Text(
            text = "Score: $collectedDots",
            color = Color.White,
            fontSize = 24.sp,
            modifier = Modifier.padding(16.dp)
        )
    }
}

// --- Рух привидів по сітці (не заходять один в одного) ---
private fun moveGhostGrid(map: Array<IntArray>, ghost: Ghost, pacX: Int, pacY: Int, ghosts: List<Ghost>) {
    val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    val possibleDirs = dirs.filter {
        val nx = ghost.x + it.first
        val ny = ghost.y + it.second
        ny in 0 until map.size && nx in 0 until map[0].size && map[ny][nx] != 1 &&
                ghosts.none { other -> other != ghost && other.x == nx && other.y == ny }
    }

    if (possibleDirs.isNotEmpty()) {
        val best = when (ghost.type) {
            GhostType.CHASER -> possibleDirs.minByOrNull { (dx, dy) ->
                (pacX - (ghost.x + dx)) * (pacX - (ghost.x + dx)) + (pacY - (ghost.y + dy)) * (pacY - (ghost.y + dy))
            }!!
            GhostType.RANDOM, GhostType.AMBUSH -> possibleDirs.random()
        }
        ghost.x += best.first
        ghost.y += best.second
    }
}

// --- Малюємо карту ---
private fun DrawScope.drawMazeGrid(map: Array<IntArray>, tileSize: Float, offsetX: Float, offsetY: Float) {
    val rows = map.size
    val cols = map[0].size
    for (y in 0 until rows) {
        for (x in 0 until cols) {
            val left = x * tileSize + offsetX
            val top = y * tileSize + offsetY
            when (map[y][x]) {
                1 -> drawRect(Color.Blue, Offset(left, top), Size(tileSize, tileSize))
                2 -> drawCircle(Color.White, tileSize / 6, Offset(left + tileSize / 2, top + tileSize / 2))
            }
        }
    }
}

// --- Малюємо Pac-Man ---
private fun DrawScope.drawPacmanClassic(pacX: Int, pacY: Int, tileSize: Float, offsetX: Float, offsetY: Float, mouthOpen: Boolean) {
    val centerX = offsetX + (pacX + 0.5f) * tileSize
    val centerY = offsetY + (pacY + 0.5f) * tileSize
    val radius = tileSize * 0.4f
    if (mouthOpen) {
        drawArc(Color.Yellow, 30f, 300f, true, Offset(centerX - radius, centerY - radius), Size(radius * 2, radius * 2))
    } else {
        drawCircle(Color.Yellow, radius, Offset(centerX, centerY))
    }
}

// --- Малюємо привида ---
private fun DrawScope.drawGhostClassic(ghost: Ghost, tileSize: Float, offsetX: Float, offsetY: Float) {
    val centerX = offsetX + (ghost.x + 0.5f) * tileSize
    val centerY = offsetY + (ghost.y + 0.5f) * tileSize
    val radius = tileSize * 0.4f
    drawCircle(ghost.color, radius, Offset(centerX, centerY - radius / 4))
    for (i in 0..3) {
        val left = centerX - radius + i * radius * 0.66f
        drawCircle(ghost.color, radius / 4, Offset(left, centerY))
    }
    drawCircle(Color.White, radius / 5, Offset(centerX - radius / 4, centerY - radius / 4))
    drawCircle(Color.White, radius / 5, Offset(centerX + radius / 4, centerY - radius / 4))
}
