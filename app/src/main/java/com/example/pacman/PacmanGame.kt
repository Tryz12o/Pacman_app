package com.example.pacman

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min


enum class GhostType { CHASER, RANDOM, AMBUSH }

data class Ghost(
    var x: Int,
    var y: Int,
    val color: Color,
    val type: GhostType,
    var dirX: Int = 0,
    var dirY: Int = 0
)

private fun lerp(start: Color, stop: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (stop.red - start.red) * f,
        green = start.green + (stop.green - start.green) * f,
        blue = start.blue + (stop.blue - start.blue) * f,
        alpha = start.alpha + (stop.alpha - start.alpha) * f
    )
}

@Composable
fun PacmanGame() {
    val rows = 17
    val cols = 15
    var gameOver by remember { mutableStateOf(false) }
    var collectedDots by remember { mutableStateOf(0) }
    var mouthOpen by remember { mutableStateOf(true) }
    var brightness by remember { mutableStateOf(1f) }

    val map = remember { Array(rows) { IntArray(cols) { 2 } } } // 2=dot,1=wall,0=empty

    var pacX by remember { mutableStateOf(cols / 2) }
    var pacY by remember { mutableStateOf(rows / 2) }
    var dirX by remember { mutableStateOf(0) }
    var dirY by remember { mutableStateOf(0) }
    var nextDirX by remember { mutableStateOf(0) }
    var nextDirY by remember { mutableStateOf(0) }

    val ghosts = remember {
        mutableStateListOf(
            Ghost(1, 1, Color.Red, GhostType.CHASER),
            Ghost(cols - 2, 1, Color.Cyan, GhostType.RANDOM),
            Ghost(1, rows - 2, Color.Magenta, GhostType.AMBUSH)
        )
    }

    // --- Pac-Man mouth animation ---
    LaunchedEffect(Unit) {
        while (true) {
            mouthOpen = !mouthOpen
            delay(200)
        }
    }

    // --- Brightness sensor ---
    val context = LocalContext.current
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val lux = event.values[0]
                // Normalize lux value to a brightness factor between 0.2 and 1.0.
                // We'''ll consider a lux value of around 670 to be full brightness, making a typical afternoon (400 lux) the midpoint.
                brightness = (lux / 670f).coerceIn(0.2f, 1.0f)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    val themeFraction = ((brightness - 0.2f) / 0.8f).coerceIn(0f, 1f)

    val backgroundColor = lerp(Color.Black, Color.White, themeFraction)
    val wallColor = lerp(Color.Blue, Color(0f, 0f, 0.5f), themeFraction)
    val dotColor = lerp(Color.White, Color.Black, themeFraction)
    val scoreColor = if (themeFraction > 0.5f) Color.Black else Color.White


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
        nextDirX = 0; nextDirY = 0

        while (true) {
            if (gameOver) {
                delay(1000)
                collectedDots = 0
                resetMap()
                pacX = cols / 2; pacY = rows / 2
                dirX = 0; dirY = 0
                nextDirX = 0; nextDirY = 0
                ghosts.replaceAll {
                    when(it.type) {
                        GhostType.CHASER -> Ghost(1, 1, it.color, it.type)
                        GhostType.RANDOM -> Ghost(cols - 2, 1, it.color, it.type)
                        GhostType.AMBUSH -> Ghost(1, rows - 2, it.color, it.type)
                    }
                }
                gameOver = false
            }

            // --- Pac-Man movement ---
            // Check if we can apply the new intended direction from user input
            val tryNextX = pacX + nextDirX
            val tryNextY = pacY + nextDirY
            if (nextDirX != 0 || nextDirY != 0) {
                 if (tryNextY in 0 until rows && tryNextX in 0 until cols && map[tryNextY][tryNextX] != 1) {
                    dirX = nextDirX
                    dirY = nextDirY
                    nextDirX = 0
                    nextDirY = 0
                 }
            }

            // Move in the current direction if possible
            val nextPacX = pacX + dirX
            val nextPacY = pacY + dirY
            if (nextPacY in 0 until rows && nextPacX in 0 until cols && map[nextPacY][nextPacX] != 1) {
                pacX = nextPacX
                pacY = nextPacY
            }

            // --- Dot collection ---
            if (map[pacY][pacX] == 2) {
                map[pacY][pacX] = 0
                collectedDots += 1
            }

            // --- Ghost movement ---
            ghosts.forEach { ghost ->
                moveGhostGrid(map, ghost, pacX, pacY, dirX, dirY, ghosts)
            }

            // --- Collision check ---
            if (ghosts.any { it.x == pacX && it.y == pacY }) gameOver = true

            delay(200)
        }
    }

    // --- UI ---
    Column(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        Box(modifier = Modifier.weight(1f)) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val tileWidth = size.width / cols
                val tileHeight = size.height / rows
                val tileSize = min(tileWidth, tileHeight)
                val offsetX = (size.width - tileSize * cols) / 2f
                val offsetY = (size.height - tileSize * rows) / 2f

                drawMazeGrid(map, tileSize, offsetX, offsetY, wallColor, dotColor)
                drawPacmanClassic(pacX, pacY, tileSize, offsetX, offsetY, mouthOpen, Color.Yellow)
                ghosts.forEach { drawGhostClassic(it, tileSize, offsetX, offsetY, dotColor) }
            }

            Text(
                text = "Score: $collectedDots",
                color = scoreColor,
                fontSize = 24.sp,
                modifier = Modifier.padding(16.dp)
            )
        }

        // --- Movement Buttons ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row {
                Button(onClick = { nextDirY = -1; nextDirX = 0 }) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up")
                }
            }
            Row {
                Button(onClick = { nextDirX = -1; nextDirY = 0 }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Left")
                }
                Spacer(modifier = Modifier.width(64.dp))
                Button(onClick = { nextDirX = 1; nextDirY = 0 }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Right")
                }
            }
            Row {
                Button(onClick = { nextDirY = 1; nextDirX = 0 }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Down")
                }
            }
        }
    }
}

// --- Ghost grid movement (no overlapping) ---
private fun moveGhostGrid(map: Array<IntArray>, ghost: Ghost, pacX: Int, pacY: Int, pacDirX: Int, pacDirY: Int, ghosts: List<Ghost>) {
    val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    // Filter out illegal moves (walls, other ghosts)
    var possibleDirs = dirs.filter { (dx, dy) ->
        val nx = ghost.x + dx
        val ny = ghost.y + dy
        nx in 0 until map[0].size && ny in 0 until map.size && map[ny][nx] != 1 &&
                ghosts.none { other -> other != ghost && other.x == nx && other.y == ny }
    }

    // Prevent reversing direction unless it's a dead end
    if (possibleDirs.size > 1) {
        possibleDirs = possibleDirs.filterNot { (dx, dy) ->
            dx == -ghost.dirX && dy == -ghost.dirY
        }
    }

    if (possibleDirs.isNotEmpty()) {
        val bestDir = when (ghost.type) {
            GhostType.CHASER -> { // Red: chases Pac-Man directly
                possibleDirs.minByOrNull { (dx, dy) ->
                    val newX = ghost.x + dx
                    val newY = ghost.y + dy
                    (pacX - newX) * (pacX - newX) + (pacY - newY) * (pacY - newY)
                }
            }
            GhostType.AMBUSH -> { // Pink: tries to get in front of Pac-Man
                val targetX = (pacX + pacDirX * 4).coerceIn(0, map[0].size - 1)
                val targetY = (pacY + pacDirY * 4).coerceIn(0, map.size - 1)
                possibleDirs.minByOrNull { (dx, dy) ->
                    val newX = ghost.x + dx
                    val newY = ghost.y + dy
                    (targetX - newX) * (targetX - newX) + (targetY - newY) * (targetY - newY)
                }
            }
            GhostType.RANDOM -> { // Cyan: chases from a distance, flees when close
                val distanceToPac = abs(ghost.x - pacX) + abs(ghost.y - pacY)
                if (distanceToPac > 8) {
                    // Chase Pac-Man
                     possibleDirs.minByOrNull { (dx, dy) ->
                        val newX = ghost.x + dx
                        val newY = ghost.y + dy
                        (pacX - newX) * (pacX - newX) + (pacY - newY) * (pacY - newY)
                    }
                } else {
                    // Flee to a corner (bottom-left)
                    val cornerX = 1
                    val cornerY = map.size - 2
                     possibleDirs.minByOrNull { (dx, dy) ->
                        val newX = ghost.x + dx
                        val newY = ghost.y + dy
                        (cornerX - newX) * (cornerX - newX) + (cornerY - newY) * (cornerY - newY)
                    }
                }
            }
        } ?: possibleDirs.random() // Fallback to random if a choice is null

        ghost.x += bestDir.first
        ghost.y += bestDir.second
        ghost.dirX = bestDir.first
        ghost.dirY = bestDir.second
    }
}

// --- Draw maze ---
private fun DrawScope.drawMazeGrid(map: Array<IntArray>, tileSize: Float, offsetX: Float, offsetY: Float, wallColor: Color, dotColor: Color) {
    val rows = map.size
    val cols = map[0].size
    for (y in 0 until rows) {
        for (x in 0 until cols) {
            val left = x * tileSize + offsetX
            val top = y * tileSize + offsetY
            when (map[y][x]) {
                1 -> drawRect(wallColor, Offset(left, top), Size(tileSize, tileSize))
                2 -> {
                    val dotRadius = tileSize / 6
                    val dotCenter = Offset(left + tileSize / 2, top + tileSize / 2)
                    // Draw outline for visibility
                    drawCircle(Color.Black, dotRadius + 1.5f, dotCenter)
                    drawCircle(dotColor, dotRadius, dotCenter)
                }
            }
        }
    }
}

// --- Draw Pac-Man ---
private fun DrawScope.drawPacmanClassic(pacX: Int, pacY: Int, tileSize: Float, offsetX: Float, offsetY: Float, mouthOpen: Boolean, color: Color) {
    val centerX = offsetX + (pacX + 0.5f) * tileSize
    val centerY = offsetY + (pacY + 0.5f) * tileSize
    val radius = tileSize * 0.4f
    if (mouthOpen) {
        drawArc(color, 30f, 300f, true, Offset(centerX - radius, centerY - radius), Size(radius * 2, radius * 2))
    } else {
        drawCircle(color, radius, Offset(centerX, centerY))
    }
}

// --- Draw ghost ---
private fun DrawScope.drawGhostClassic(ghost: Ghost, tileSize: Float, offsetX: Float, offsetY: Float, eyeColor: Color) {
    val centerX = offsetX + (ghost.x + 0.5f) * tileSize
    val centerY = offsetY + (ghost.y + 0.5f) * tileSize
    val radius = tileSize * 0.4f
    drawCircle(ghost.color, radius, Offset(centerX, centerY - radius / 4))
    for (i in 0..3) {
        val left = centerX - radius + i * radius * 0.66f
        drawCircle(ghost.color, radius / 4, Offset(left, centerY))
    }
    drawCircle(eyeColor, radius / 5, Offset(centerX - radius / 4, centerY - radius / 4))
    drawCircle(eyeColor, radius / 5, Offset(centerX + radius / 4, centerY - radius / 4))
}
