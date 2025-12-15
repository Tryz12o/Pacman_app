package com.example.pacman

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
enum class AppTheme { SYSTEM, LIGHT, DARK }

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
    var collectedDots by remember { mutableIntStateOf(0) }
    var mouthOpen by remember { mutableStateOf(true) }
    var targetBrightness by remember { mutableFloatStateOf(1f) }
    var useLightSensor by remember { mutableStateOf(true) }
    var selectedTheme by remember { mutableStateOf(AppTheme.SYSTEM) }
    val brightness by animateFloatAsState(
        targetValue = if (useLightSensor) targetBrightness else {
            when (selectedTheme) {
                AppTheme.LIGHT -> 1f
                AppTheme.DARK -> 0.2f
                AppTheme.SYSTEM -> targetBrightness
            }
        },
        animationSpec = tween(durationMillis = 1000)
    )

    var showSettings by remember { mutableStateOf(false) }
    var useGyroscope by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var isResuming by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }

    val map = remember { Array(rows) { IntArray(cols) { 2 } } }

    var pacX by remember { mutableIntStateOf(cols / 2) }
    var pacY by remember { mutableIntStateOf(rows / 2) }
    var dirX by remember { mutableIntStateOf(0) }
    var dirY by remember { mutableIntStateOf(0) }
    var nextDirX by remember { mutableIntStateOf(0) }
    var nextDirY by remember { mutableIntStateOf(0) }

    val ghosts = remember {
        mutableStateListOf(
            Ghost(1, 1, Color.Red, GhostType.CHASER),
            Ghost(cols - 2, 1, Color.Cyan, GhostType.RANDOM),
            Ghost(1, rows - 2, Color.Magenta, GhostType.AMBUSH)
        )
    }

    val context = LocalContext.current

    // Gyroscope control
    if (useGyroscope) {
        DisposableEffect(context) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (isPaused) return
                    val tiltX = event.values[0]
                    val tiltY = event.values[1]
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
            if (accel != null) sensorManager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

    // Mouth animation
    LaunchedEffect(Unit) {
        while (true) {
            if (!isPaused) mouthOpen = !mouthOpen
            delay(200)
        }
    }

    // Brightness sensor
    if (useLightSensor) {
        DisposableEffect(context) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if(isPaused) return
                    val lux = event.values[0]
                    targetBrightness = (lux / 670f).coerceIn(0.2f, 1.0f)
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_UI)
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

    val themeFraction = ((brightness - 0.2f) / 0.8f).coerceIn(0f, 1f)
    val backgroundColor = lerp(Color.Black, Color.White, themeFraction)
    val wallColor = lerp(Color.Blue, Color(0f, 0f, 0.5f), themeFraction)
    val dotColor = lerp(Color.White, Color.Black, themeFraction)
    val scoreColor = if (themeFraction > 0.5f) Color.Black else Color.White

    // --- Game loop ---
    LaunchedEffect(Unit) {
        fun resetMap() {
            for (y in 0 until rows) for (x in 0 until cols)
                map[y][x] = if (x == 0 || y == 0 || x == cols - 1 || y == rows - 1) 1 else 2
            for (x in 3 until 12) map[4][x] = 1
            for (x in 3 until 12) map[12][x] = 1

            // --- Three green special dots ---
            map[2][2] = 3
            map[rows - 3][cols - 3] = 3
            map[8][7] = 3
        }
        resetMap()
        pacX = cols / 2; pacY = rows / 2
        dirX = 0; dirY = 0
        nextDirX = 0; nextDirY = 0

        while (true) {
            if (!isPaused) {
                if (gameOver) {
                    delay(1000)
                    collectedDots = 0
                    resetMap()
                    pacX = cols / 2; pacY = rows / 2
                    dirX = 0; dirY = 0
                    nextDirX = 0; nextDirY = 0
                    ghosts.replaceAll { g ->
                        when (g.type) {
                            GhostType.CHASER -> Ghost(1, 1, g.color, g.type)
                            GhostType.RANDOM -> Ghost(cols - 2, 1, g.color, g.type)
                            GhostType.AMBUSH -> Ghost(1, rows - 2, g.color, g.type)
                        }
                    }
                    gameOver = false
                }

                // Pac-Man movement
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
                val nx = pacX + dirX
                val ny = pacY + dirY
                if (ny in 0 until rows && nx in 0 until cols && map[ny][nx] != 1) {
                    pacX = nx
                    pacY = ny
                }

                if (map[pacY][pacX] == 2) {
                    map[pacY][pacX] = 0
                    collectedDots++
                } else if (map[pacY][pacX] == 3) {
                    map[pacY][pacX] = 0
                    collectedDots += 5 // Green dot special value
                }

                ghosts.forEach { g ->
                    moveGhostGrid(map, g, pacX, pacY, dirX, dirY, ghosts)
                }

                if (ghosts.any { it.x == pacX && it.y == pacY }) gameOver = true
            }
            delay(200)
        }
    }

    // --- Resuming countdown ---
    if (isResuming) {
        LaunchedEffect(Unit) {
            for (i in 3000 downTo 0 step 100) {
                countdown = i
                delay(100)
            }
            countdown = 0
            isPaused = false
            isResuming = false
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
                ghosts.forEach { drawGhostClassic(it, tileSize, offsetX, offsetY, dotColor) }
            }

            // Score + pause button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Score: $collectedDots", color = scoreColor, fontSize = 24.sp)
                IconButton(onClick = { isPaused = true }) {
                    Icon(Icons.Filled.Pause, contentDescription = "Pause", tint = scoreColor)
                }
            }

            // Pause menu
            if (isPaused) {
                val fixedWallColor = lerp(Color.Blue, Color(0f, 0f, 0.5f), 0f)
                val fixedScoreColor = Color.White
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (showSettings) {
                        Column(
                            modifier = Modifier
                                .background(fixedWallColor, shape = RoundedCornerShape(16.dp))
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Settings", color = fixedScoreColor, fontSize = 24.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Use Gyroscope", color = fixedScoreColor)
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(checked = useGyroscope, onCheckedChange = { useGyroscope = it })
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Use Light Sensor", color = fixedScoreColor)
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(checked = useLightSensor, onCheckedChange = {
                                    useLightSensor = it
                                    if (it) selectedTheme = AppTheme.SYSTEM
                                })
                            }
                            if (!useLightSensor) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    Button(onClick = { selectedTheme = AppTheme.SYSTEM }) { Text("System", color = fixedScoreColor) }
                                    Button(onClick = { selectedTheme = AppTheme.LIGHT }) { Text("Light", color = fixedScoreColor) }
                                    Button(onClick = { selectedTheme = AppTheme.DARK }) { Text("Dark", color = fixedScoreColor) }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { showSettings = false }) { Text("Back") }
                        }
                    } else if (isResuming) {
                        val seconds = countdown / 1000
                        val tenths = (countdown % 1000) / 100
                        Text(text = "${seconds}s.${tenths}ms", color = fixedScoreColor, fontSize = 48.sp)
                    } else {
                        Column(
                            modifier = Modifier
                                .background(fixedWallColor, shape = RoundedCornerShape(16.dp))
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Paused", color = fixedScoreColor, fontSize = 24.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { isResuming = true }) { Text("Resume") }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { showSettings = true }) { Text("Settings") }
                        }
                    }
                }
            }
        }

        // Fallback buttons (if no gyroscope)
        if (!useGyroscope) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row { Button(onClick = { nextDirY = -1; nextDirX = 0 }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Up") } }
                Row {
                    Button(onClick = { nextDirX = -1; nextDirY = 0 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Left") }
                    Spacer(modifier = Modifier.width(64.dp))
                    Button(onClick = { nextDirX = 1; nextDirY = 0 }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Right") }
                }
                Row { Button(onClick = { nextDirY = 1; nextDirX = 0 }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Down") } }
            }
        }
    }
}

// ---------------- Ghost movement ----------------
private fun moveGhostGrid(
    map: Array<IntArray>,
    ghost: Ghost,
    pacX: Int,
    pacY: Int,
    pacDirX: Int,
    pacDirY: Int,
    ghosts: List<Ghost>
) {
    val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    var possible = dirs.filter { (dx, dy) ->
        val nx = ghost.x + dx
        val ny = ghost.y + dy
        nx in map[0].indices && ny in map.indices && map[ny][nx] != 1 && ghosts.none { it != ghost && it.x == nx && it.y == ny }
    }
    if (possible.size > 1) {
        possible = possible.filterNot { (dx, dy) -> dx == -ghost.dirX && dy == -ghost.dirY }
    }
    if (possible.isEmpty()) return
    val best = when (ghost.type) {
        GhostType.CHASER -> possible.minByOrNull { (dx, dy) ->
            val nx = ghost.x + dx
            val ny = ghost.y + dy
            (pacX - nx) * (pacX - nx) + (pacY - ny) * (pacY - ny)
        }
        GhostType.AMBUSH -> {
            val tx = (pacX + pacDirX * 4).coerceIn(0, map[0].lastIndex)
            val ty = (pacY + pacDirY * 4).coerceIn(0, map.lastIndex)
            possible.minByOrNull { (dx, dy) ->
                val nx = ghost.x + dx
                val ny = ghost.y + dy
                (tx - nx) * (tx - nx) + (ty - ny) * (ty - ny)
            }
        }
        GhostType.RANDOM -> {
            val d = abs(ghost.x - pacX) + abs(ghost.y - pacY)
            if (d > 8) {
                possible.minByOrNull { (dx, dy) ->
                    val nx = ghost.x + dx
                    val ny = ghost.y + dy
                    (pacX - nx) * (pacX - nx) + (pacY - ny) * (pacY - ny)
                }
            } else possible.randomOrNull()
        }
    } ?: possible.random()
    ghost.dirX = best.first
    ghost.dirY = best.second
    ghost.x += ghost.dirX
    ghost.y += ghost.dirY
}

// ---------------- Drawing helpers ----------------
private fun DrawScope.drawMazeGrid(map: Array<IntArray>, tileSize: Float, offsetX: Float, offsetY: Float, wallColor: Color, dotColor: Color) {
    for (y in map.indices) {
        for (x in map[0].indices) {
            val left = offsetX + x * tileSize
            val top = offsetY + y * tileSize
            when (map[y][x]) {
                1 -> drawRect(wallColor, topLeft = Offset(left, top), size = Size(tileSize, tileSize))
                2 -> drawCircle(dotColor, radius = tileSize / 8, center = Offset(left + tileSize / 2, top + tileSize / 2))
                3 -> drawCircle(Color.Green, radius = tileSize / 6, center = Offset(left + tileSize / 2, top + tileSize / 2))
            }
        }
    }
}

private fun DrawScope.drawPacmanClassic(x: Int, y: Int, tileSize: Float, offsetX: Float, offsetY: Float, mouthOpen: Boolean, color: Color) {
    val left = offsetX + x * tileSize
    val top = offsetY + y * tileSize
    val center = Offset(left + tileSize / 2, top + tileSize / 2)
    val sweep = if (mouthOpen) 270f else 360f
    drawArc(color, startAngle = 45f, sweepAngle = sweep, useCenter = true, topLeft = Offset(left, top), size = Size(tileSize, tileSize))
}

private fun DrawScope.drawGhostClassic(ghost: Ghost, tileSize: Float, offsetX: Float, offsetY: Float, eyeColor: Color) {
    val left = offsetX + ghost.x * tileSize
    val top = offsetY + ghost.y * tileSize
    drawRect(ghost.color, topLeft = Offset(left, top), size = Size(tileSize, tileSize))
    drawCircle(eyeColor, radius = tileSize / 8, center = Offset(left + tileSize / 4, top + tileSize / 3))
    drawCircle(eyeColor, radius = tileSize / 8, center = Offset(left + 3 * tileSize / 4, top + tileSize / 3))
}
