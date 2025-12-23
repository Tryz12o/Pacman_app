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
import kotlinx.coroutines.launch
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.min

enum class GhostType { CHASER, RANDOM, AMBUSH }

enum class AppTheme { SYSTEM, LIGHT, DARK }

data class Ghost(
    var x: Int,
    var y: Int,
    val color: Color,
    val type: GhostType,
    val homeX: Int,
    val homeY: Int,
    var dirX: Int = 0,
    var dirY: Int = 0,
    var alive: Boolean = true
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
    var pacPowered by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(true) }
    var mouthOpen by remember { mutableStateOf(true) }
    var targetBrightness by remember { mutableFloatStateOf(1f) }
    var useLightSensor by remember { mutableStateOf(true) } // New state for light sensor toggle
    var selectedTheme by remember { mutableStateOf(AppTheme.SYSTEM) } // New state for theme selection

    val brightness by animateFloatAsState(
        targetValue = if (useLightSensor) targetBrightness else {
            when (selectedTheme) {
                AppTheme.LIGHT -> 1f
                AppTheme.DARK -> 0.2f
                AppTheme.SYSTEM -> targetBrightness // System theme will still react to light sensor
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

    var pacX by remember { mutableIntStateOf(cols / 2).apply { value } }
    var pacY by remember { mutableIntStateOf(rows / 2).apply { value } }
    var dirX by remember { mutableIntStateOf(0).apply { value } }
    var dirY by remember { mutableIntStateOf(0).apply { value } }
    var nextDirX by remember { mutableIntStateOf(0).apply { value } }
    var nextDirY by remember { mutableIntStateOf(0).apply { value } }

    val ghosts = remember {
        mutableStateListOf(
            Ghost(1, 1, Color.Red, GhostType.CHASER, 1, 1),
            Ghost(cols - 2, 1, Color.Cyan, GhostType.RANDOM, cols - 2, 1),
            Ghost(1, rows - 2, Color.Magenta, GhostType.AMBUSH, 1, rows - 2)
        )
    }

    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val biometricManager = BiometricManager.from(context)
    val canAuthenticate = biometricManager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS

    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val authCallback = remember {
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                locked = false
                isPaused = false
            }
        }
    }

    val biometricPrompt = remember(activity, executor, authCallback) {
        activity?.let { BiometricPrompt(it, executor, authCallback) }
    }

    val promptInfo = remember {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Pac-Man")
            .setSubtitle("Authenticate with fingerprint")
            .setNegativeButtonText("Cancel")
            .build()
    }

    if (useGyroscope) {
        DisposableEffect(context) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (isPaused) return
                    val tiltX = event.values[0]
                    val tiltY = event.values[1]

                    // Lower threshold to make Pac-Man more responsive to light tilts
                    val threshold = 0.8f

                    if (abs(tiltX) > abs(tiltY)) {
                        if (tiltX < -threshold) {
                            nextDirX = 1; nextDirY = 0
                        } else if (tiltX > threshold) {
                            nextDirX = -1; nextDirY = 0
                        }
                    } else {
                        if (tiltY < -threshold) {
                            nextDirY = -1; nextDirX = 0
                        } else if (tiltY > threshold) {
                            nextDirY = 1; nextDirX = 0
                        }
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }

            if (accel != null)
                sensorManager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)

            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

    // --- Mouth animation ---
    LaunchedEffect(Unit) {
        while (true) {
            if (!isPaused) {
                mouthOpen = !mouthOpen
            }
            delay(200)
        }
    }

    // --- Brightness sensor ---
    if (useLightSensor) {
        DisposableEffect(context) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if(isPaused) return
                    val lux = event.values[0]
                    @Suppress("UNUSED_VALUE") // False positive: value is read by 'brightness' state
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
            for (y in 0 until rows)
                for (x in 0 until cols)
                    map[y][x] = if (x == 0 || y == 0 || x == cols - 1 || y == rows - 1) 1 else 2

            for (x in 3 until 12) map[4][x] = 1
            for (x in 3 until 12) map[12][x] = 1
            // place four green power-pellets (bigger dots)
            val powerPositions = listOf(
                2 to 2,
                cols - 3 to 2,
                2 to rows - 3,
                cols - 3 to rows - 3
            )
            for ((px, py) in powerPositions) {
                if (py in 0 until rows && px in 0 until cols && map[py][px] != 1) {
                    map[py][px] = 3
                }
            }
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
                    ghosts.replaceAll {
                        when (it.type) {
                            GhostType.CHASER -> Ghost(1, 1, it.color, it.type, 1, 1)
                            GhostType.RANDOM -> Ghost(cols - 2, 1, it.color, it.type, cols - 2, 1)
                            GhostType.AMBUSH -> Ghost(1, rows - 2, it.color, it.type, 1, rows - 2)
                        }
                    }
                    gameOver = false
                }

                // --- Pac-Man movement ---
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
                    // Power pellet: make Pac-Man invulnerable for a short time
                    map[pacY][pacX] = 0
                    collectedDots++
                    pacPowered = true
                    launch {
                        delay(8000)
                        pacPowered = false
                    }
                }

                ghosts.forEach { g ->
                    if (g.alive) moveGhostGrid(map, g, pacX, pacY, dirX, dirY, ghosts)
                }

                // Handle collisions with ghosts: if powered, 'eat' ghost -> disappear 3s then respawn
                ghosts.forEach { g ->
                    if (g.alive && g.x == pacX && g.y == pacY) {
                        if (pacPowered) {
                            g.alive = false
                            // respawn after 3 seconds at home
                            launch {
                                delay(3000)
                                g.x = g.homeX
                                g.y = g.homeY
                                g.dirX = 0
                                g.dirY = 0
                                g.alive = true
                            }
                        } else {
                            gameOver = true
                        }
                    }
                }
            }
            delay(200)
        }
    }

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
                ghosts.forEach {
                    if (it.alive) drawGhostClassic(it, tileSize, offsetX, offsetY, dotColor)
                }
            }

            if (locked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("App locked", color = Color.White, fontSize = 20.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = {
                            if (canAuthenticate) {
                                biometricPrompt?.authenticate(promptInfo)
                            } else {
                                // fallback: if biometric not available, unlock
                                locked = false
                                isPaused = false
                            }
                        }) {
                            Text(if (canAuthenticate) "Unlock with fingerprint" else "Unlock")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Score: $collectedDots",
                    color = scoreColor,
                    fontSize = 24.sp
                )
                IconButton(onClick = { isPaused = true }) {
                    Icon(
                        imageVector = Icons.Filled.Pause,
                        contentDescription = "Pause",
                        tint = scoreColor
                    )
                }
            }

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
                                Switch(
                                    checked = useGyroscope,
                                    onCheckedChange = { useGyroscope = it })
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Use Light Sensor", color = fixedScoreColor)
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    checked = useLightSensor,
                                    onCheckedChange = {
                                        useLightSensor = it
                                        // Reset to system theme if light sensor is enabled
                                        @Suppress("UNUSED_VALUE") // False positive: value is read by 'brightness' state
                                        if (it) selectedTheme = AppTheme.SYSTEM
                                    }
                                )
                            }
                            if (!useLightSensor) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    Button(onClick = {
                                        @Suppress("UNUSED_VALUE") // False positive: value is read by 'brightness' state
                                        selectedTheme = AppTheme.SYSTEM
                                    }) {
                                        Text("System", color = fixedScoreColor)
                                    }
                                    Button(onClick = {
                                        @Suppress("UNUSED_VALUE") // False positive: value is read by 'brightness' state
                                        selectedTheme = AppTheme.LIGHT
                                    }) {
                                        Text("Light", color = fixedScoreColor)
                                    }
                                    Button(onClick = {
                                        @Suppress("UNUSED_VALUE") // False positive: value is read by 'brightness' state
                                        selectedTheme = AppTheme.DARK
                                    }) {
                                        Text("Dark", color = fixedScoreColor)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { showSettings = false }) {
                                Text("Back")
                            }
                        }
                    } else if (isResuming) {
                        val seconds = countdown / 1000
                        val tenths = (countdown % 1000) / 100
                        Text(
                            text = "${seconds}s.${tenths}ms",
                            color = fixedScoreColor,
                            fontSize = 48.sp,
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .background(fixedWallColor, shape = RoundedCornerShape(16.dp))
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Paused", color = fixedScoreColor, fontSize = 24.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { isResuming = true }) {
                                Text("Resume")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { showSettings = true }) {
                                Text("Settings")
                            }
                        }
                    }
                }
            }
        }

        if (!useGyroscope) {
            // --- Buttons for fallback control ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row {
                    Button(onClick = { nextDirY = -1; nextDirX = 0 }) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Up")
                    }
                }
                Row {
                    Button(onClick = { nextDirX = -1; nextDirY = 0 }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Left")
                    }
                    Spacer(modifier = Modifier.width(64.dp))
                    Button(onClick = { nextDirX = 1; nextDirY = 0 }) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Right"
                        )
                    }
                }
                Row {
                    Button(onClick = { nextDirY = 1; nextDirX = 0 }) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Down")
                    }
                }
            }
        }
    }
}

// ---------------- Ghost movement ----------------
private fun moveGhostGrid(
    map: Array<IntArray>, ghost: Ghost,
    pacX: Int, pacY: Int, pacDirX: Int, pacDirY: Int,
    ghosts: List<Ghost>
) {

    val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    var possible = dirs.filter { (dx, dy) ->
        val nx = ghost.x + dx
        val ny = ghost.y + dy
        nx in map[0].indices && ny in map.indices &&
                map[ny][nx] != 1 &&
                ghosts.none { it != ghost && it.alive && it.x == nx && it.y == ny }
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
            } else {
                val cx = 1
                val cy = map.lastIndex - 1
                possible.minByOrNull { (dx, dy) ->
                    val nx = ghost.x + dx
                    val ny = ghost.y + dy
                    (cx - nx) * (cx - nx) + (cy - ny) * (cy - ny)
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
    val rows = map.size
    val cols = map[0].size
    val visited = Array(rows) { BooleanArray(cols) { false } }

    for (y in 0 until rows) {
        for (x in 0 until cols) {
            when (map[y][x]) {
                1 -> { // Wall
                    if (!visited[y][x]) {
                        // Find horizontal stretch
                        var endX = x
                        while (endX + 1 < cols && map[y][endX + 1] == 1) {
                            endX++
                        }

                        // Find vertical stretch
                        var endY = y
                        while (endY + 1 < rows && map[endY + 1][x] == 1) {
                            endY++
                        }

                        if ((endX - x) >= (endY - y)) { // Prefer horizontal line
                            val startOffset = Offset(x * tile + ox, y * tile + oy)
                            val size = Size((endX - x + 1) * tile, tile)
                            drawRect(wall, startOffset, size)
                            for (i in x..endX) {
                                visited[y][i] = true
                            }
                        } else { // Draw vertical line
                            val startOffset = Offset(x * tile + ox, y * tile + oy)
                            val size = Size(tile, (endY - y + 1) * tile)
                            drawRect(wall, startOffset, size)
                            for (i in y..endY) {
                                visited[i][x] = true
                            }
                        }
                    }
                }
                2 -> { // Dot
                    val r = tile / 6
                    val c = Offset(x * tile + ox + tile / 2, y * tile + oy + tile / 2)
                    drawCircle(Color.Black, r + 1.5f, c)
                    drawCircle(dot, r, c)
                }
                3 -> { // Power pellet (bigger green dot)
                    val r = tile / 3
                    val c = Offset(x * tile + ox + tile / 2, y * tile + oy + tile / 2)
                    drawCircle(Color.Black, r + 2f, c)
                    drawCircle(Color.Green, r, c)
                }
            }
        }
    }
}

private fun DrawScope.drawPacmanClassic(
    x: Int, y: Int, tile: Float, ox: Float, oy: Float,
    open: Boolean, color: Color
) {
    val cx = ox + (x + 0.5f) * tile
    val cy = oy + (y + 0.5f) * tile
    val r = tile * 0.4f

    if (open)
        drawArc(color, 30f, 300f, true, Offset(cx - r, cy - r), Size(r * 2, r * 2))
    else
        drawCircle(color, r, Offset(cx, cy))
}

private fun DrawScope.drawGhostClassic(
    g: Ghost, tile: Float, ox: Float, oy: Float, eye: Color
) {
    val cx = ox + (g.x + 0.5f) * tile
    val cy = oy + (g.y + 0.5f) * tile
    val r = tile * 0.4f

    drawCircle(g.color, r, Offset(cx, cy - r / 4))
    for (i in 0..3) {
        val lx = cx - r + i * r * 0.66f
        drawCircle(g.color, r / 4, Offset(lx, cy))
    }
    drawCircle(eye, r / 5, Offset(cx - r / 4, cy - r / 4))
    drawCircle(eye, r / 5, Offset(cx + r / 4, cy - r / 4))
}
