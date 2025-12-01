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

    val map = remember { Array(rows) { IntArray(cols) { 2 } } }

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

    // -------------------------------
    // 📌 ДОДАНО: Змінні акселерометра
    // -------------------------------
    var tiltX by remember { mutableStateOf(0f) }
    var tiltY by remember { mutableStateOf(0f) }

    val context = LocalContext.current

    // ---------------------------------------------
    // 📌 ДОДАНО: Керування Pac-Man через акселерометр
    // ---------------------------------------------
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                tiltX = event.values[0]
                tiltY = event.values[1]

                val threshold = 2.5f

                if (abs(tiltX) > abs(tiltY)) {
                    if (tiltX < -threshold) { nextDirX = 1; nextDirY = 0 }
                    else if (tiltX > threshold) { nextDirX = -1; nextDirY = 0 }
                } else {
                    if (tiltY < -threshold) { nextDirY = -1; nextDirX = 0 }
                    else if (tiltY > threshold) { nextDirY = 1; nextDirX = 0 }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (accel != null)
            sensorManager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)

        onDispose { sensorManager.unregisterListener(listener) }
    }

    // --- Mouth animation ---
    LaunchedEffect(Unit) {
        while (true) {
            mouthOpen = !mouthOpen
            delay(200)
        }
    }

    // --- Brightness sensor ---
    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val lux = event.values[0]
                brightness = (lux / 670f).coerceIn(0.2f, 1.0f)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    val themeFraction = ((brightness - 0.2f) / 0.8f).coerceIn(0f, 1f)
    val backgroundColor = lerp(Color.Black, Color.White, themeFraction)
    val wallColor = lerp(Color.Blue, Color(0f, 0f, 0.5f), themeFraction)
    val dotColor = lerp(Color.White, Color.Black, themeFraction)
    val scoreColor = if (themeFraction > 0.5f) Color.Black else Color.White

    // --- Game loop ---
    LaunchedEffect(Unit) {
        fun resetMap() {
            for (y in 0 until rows)
                for (x in 0 until cols)
                    map[y][x] = if (x == 0 || y == 0 || x == cols - 1 || y == rows - 1) 1 else 2

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
            val tryNextX = pacX + nextDirX
            val tryNextY = pacY + nextDirY

            if (nextDirX != 0 || nextDirY != 0) {
                if (map[tryNextY][tryNextX] != 1) {
                    dirX = nextDirX
                    dirY = nextDirY
                    nextDirX = 0
                    nextDirY = 0
                }
            }

            val nx = pacX + dirX
            val ny = pacY + dirY
            if (map[ny][nx] != 1) {
                pacX = nx
                pacY = ny
            }

            if (map[pacY][pacX] == 2) {
                map[pacY][pacX] = 0
                collectedDots++
            }

            ghosts.forEach { g ->
                moveGhostGrid(map, g, pacX, pacY, dirX, dirY, ghosts)
            }

            if (ghosts.any { it.x == pacX && it.y == pacY }) gameOver = true

            delay(200)
        }
    }

    // --- UI ---
    Column(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        Box(modifier = Modifier.weight(1f)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val tileWidth = size.width / cols
                val tileHeight = size.height / rows
                val tileSize = min(tileWidth, tileHeight)
                val offsetX = (size.width - tileSize * cols) / 2f
                val offsetY = (size.height - tileSize * rows) / 2f

                drawMazeGrid(map, tileSize, offsetX, offsetY, wallColor, dotColor)
                drawPacmanClassic(pacX, pacY, tileSize, offsetX, offsetY, mouthOpen, Color.Yellow)
                ghosts.forEach {
                    drawGhostClassic(it, tileSize, offsetX, offsetY, dotColor)
                }
            }

            Text(
                text = "Score: $collectedDots",
                color = scoreColor,
                fontSize = 24.sp,
                modifier = Modifier.padding(16.dp)
            )
        }

        // --- Buttons for fallback control ---
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
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

// ---------------- Ghost movement ----------------
private fun moveGhostGrid(map: Array<IntArray>, ghost: Ghost,
                          pacX: Int, pacY: Int, pacDirX: Int, pacDirY: Int,
                          ghosts: List<Ghost>) {

    val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    var possible = dirs.filter { (dx, dy) ->
        val nx = ghost.x + dx
        val ny = ghost.y + dy
        nx in map[0].indices && ny in map.indices &&
                map[ny][nx] != 1 &&
                ghosts.none { it != ghost && it.x == nx && it.y == ny }
    }

    if (possible.size > 1) {
        possible = possible.filterNot { (dx, dy) ->
            dx == -ghost.dirX && dy == -ghost.dirY
        }
    }

    if (possible.isEmpty()) return

    val best = when (ghost.type) {
        GhostType.CHASER ->
            possible.minByOrNull { (dx, dy) ->
                val nx = ghost.x + dx
                val ny = ghost.y + dy
                (pacX - nx)*(pacX - nx) + (pacY - ny)*(pacY - ny)
            }

        GhostType.AMBUSH -> {
            val tx = (pacX + pacDirX * 4).coerceIn(0, map[0].lastIndex)
            val ty = (pacY + pacDirY * 4).coerceIn(0, map.lastIndex)
            possible.minByOrNull { (dx, dy) ->
                val nx = ghost.x + dx
                val ny = ghost.y + dy
                (tx - nx)*(tx - nx) + (ty - ny)*(ty - ny)
            }
        }

        GhostType.RANDOM -> {
            val d = abs(ghost.x - pacX) + abs(ghost.y - pacY)
            if (d > 8) {
                possible.minByOrNull { (dx, dy) ->
                    val nx = ghost.x + dx
                    val ny = ghost.y + dy
                    (pacX - nx)*(pacX - nx) + (pacY - ny)*(pacY - ny)
                }
            } else {
                val cx = 1
                val cy = map.lastIndex - 1
                possible.minByOrNull { (dx, dy) ->
                    val nx = ghost.x + dx
                    val ny = ghost.y + dy
                    (cx - nx)*(cx - nx) + (cy - ny)*(cy - ny)
                }
            }
        }
    } ?: possible.random()

    ghost.x += best.first
    ghost.y += best.second
    ghost.dirX = best.first
    ghost.dirY = best.second
}

private fun DrawScope.drawMazeGrid(
    map: Array<IntArray>, tile: Float, ox: Float, oy: Float,
    wall: Color, dot: Color
) {
    for (y in map.indices)
        for (x in map[0].indices) {
            val left = x * tile + ox
            val top = y * tile + oy
            when (map[y][x]) {
                1 -> drawRect(wall, Offset(left, top), Size(tile, tile))
                2 -> {
                    val r = tile / 6
                    val c = Offset(left + tile/2, top + tile/2)
                    drawCircle(Color.Black, r + 1.5f, c)
                    drawCircle(dot, r, c)
                }
            }
        }
}

private fun DrawScope.drawPacmanClassic(
    x: Int, y: Int, tile: Float, ox: Float, oy: Float,
    open: Boolean, color: Color
) {
    val cx = ox + (x + 0.5f)*tile
    val cy = oy + (y + 0.5f)*tile
    val r = tile*0.4f

    if (open)
        drawArc(color, 30f, 300f, true, Offset(cx - r, cy - r), Size(r*2, r*2))
    else
        drawCircle(color, r, Offset(cx, cy))
}

private fun DrawScope.drawGhostClassic(
    g: Ghost, tile: Float, ox: Float, oy: Float, eye: Color
) {
    val cx = ox + (g.x + 0.5f)*tile
    val cy = oy + (g.y + 0.5f)*tile
    val r = tile*0.4f

    drawCircle(g.color, r, Offset(cx, cy - r/4))
    for (i in 0..3) {
        val lx = cx - r + i * r*0.66f
        drawCircle(g.color, r/4, Offset(lx, cy))
    }
    drawCircle(eye, r/5, Offset(cx - r/4, cy - r/4))
    drawCircle(eye, r/5, Offset(cx + r/4, cy - r/4))
}
