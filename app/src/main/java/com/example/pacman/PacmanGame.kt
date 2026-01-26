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
import androidx.compose.material.icons.filled.Refresh
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
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

enum class GhostType { CHASER, RANDOM, AMBUSH }

enum class AppTheme { SYSTEM, LIGHT, DARK }

// Game timing constants - 60 FPS physics
private const val FRAME_DELAY_MS = 16L  // ~60 FPS
private const val MOVEMENT_SPEED = 0.08f  // Grid cells per frame (1 cell per 200ms = 12.5 frames)

data class Ghost(
    var x: Float,
    var y: Float,
    val color: Color,
    val type: GhostType,
    val homeX: Float,
    val homeY: Float,
    var dirX: Float = 0f,
    var dirY: Float = 0f,
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
    var pacPowerTimeLeft by remember { mutableLongStateOf(0L) }
    var locked by remember { mutableStateOf(true) }
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
    var showLevelSelector by remember { mutableStateOf(false) }
    var selectedLevelIndex by remember { mutableIntStateOf(0) }
    var useGyroscope by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var isResuming by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }

    val map = remember { Array(rows) { IntArray(cols) { 2 } } }
    var mapVersion by remember { mutableIntStateOf(0) }

    // Persistent layout for the random level
    val randomWallLayout = remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }

    var pacX by remember { mutableFloatStateOf((cols / 2).toFloat() + 0.5f) }
    var pacY by remember { mutableFloatStateOf((rows / 2).toFloat() + 0.5f) }
    var dirX by remember { mutableFloatStateOf(0f) }
    var dirY by remember { mutableFloatStateOf(0f) }
    var nextDirX by remember { mutableFloatStateOf(0f) }
    var nextDirY by remember { mutableFloatStateOf(0f) }

    val ghosts = remember {
        mutableStateListOf(
            Ghost(1.5f, 1.5f, Color.Red, GhostType.CHASER, 1.5f, 1.5f),
            Ghost((cols - 2).toFloat() + 0.5f, 1.5f, Color.Cyan, GhostType.RANDOM, (cols - 2).toFloat() + 0.5f, 1.5f),
            Ghost(1.5f, (rows - 2).toFloat() + 0.5f, Color.Magenta, GhostType.AMBUSH, 1.5f, (rows - 2).toFloat() + 0.5f)
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

    fun generateRandomLayout() {
        val pacStartX = cols / 2
        val pacStartY = rows / 2

        fun isLayoutValid(walls: List<Pair<Int, Int>>): Boolean {
            val wallSet = walls.toSet()
            val visited = Array(rows) { BooleanArray(cols) }
            val queue = ArrayDeque<Pair<Int, Int>>()
            
            queue.add(pacStartX to pacStartY)
            visited[pacStartY][pacStartX] = true
            
            while (queue.isNotEmpty()) {
                val (cx, cy) = queue.removeFirst()
                for ((dx, dy) in listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx in 1 until cols - 1 && ny in 1 until rows - 1 && 
                        !visited[ny][nx] && !wallSet.contains(nx to ny)) {
                        visited[ny][nx] = true
                        queue.add(nx to ny)
                    }
                }
            }

            for (y in 1 until rows - 1) {
                for (x in 1 until cols - 1) {
                    if (!wallSet.contains(x to y) && !visited[y][x]) return false
                }
            }
            return true
        }

        var newWalls: List<Pair<Int, Int>>
        do {
            newWalls = mutableListOf()
            for (y in 2 until rows - 2) {
                for (x in 2 until cols / 2 + 1) {
                    val isPacArea = abs(x - pacStartX) <= 1 && abs(y - pacStartY) <= 1
                    val isGhostArea = (x <= 2 && y <= 2) || (x <= 2 && y >= rows - 3)
                    if (!isPacArea && !isGhostArea && Random.nextFloat() < 0.25f) {
                        (newWalls as MutableList).add(x to y)
                        if (x != cols - 1 - x) {
                            newWalls.add((cols - 1 - x) to y)
                        }
                    }
                }
            }
        } while (!isLayoutValid(newWalls))
        
        randomWallLayout.value = newWalls
    }

    fun resetMap(level: Int) {
        for (y in 0 until rows)
            for (x in 0 until cols)
                map[y][x] = if (x == 0 || y == 0 || x == cols - 1 || y == rows - 1) 1 else 2

        when (level) {
            0 -> {
                for (x in 3 until 12) map[4][x] = 1
                for (x in 3 until 12) map[12][x] = 1
            }
            1 -> {
                for (y in 3 until 14) map[y][4] = 1
                for (y in 3 until 14) map[y][10] = 1
            }
            2 -> {
                for (x in 2 until 6) map[4][x] = 1
                for (x in 9 until 13) map[4][x] = 1
                for (x in 2 until 6) map[12][x] = 1
                for (x in 9 until 13) map[12][x] = 1
                for (y in 6 until 11) map[y][7] = 1
            }
            3 -> {
                // Apply the pre-generated random layout
                randomWallLayout.value.forEach { (wx, wy) ->
                    if (wy in 0 until rows && wx in 0 until cols) {
                        map[wy][wx] = 1
                    }
                }
            }
        }

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
        mapVersion++
    }

    // Effect to reset map layout whenever index changes in level selector
    LaunchedEffect(selectedLevelIndex, showLevelSelector) {
        if (showLevelSelector) {
            if (selectedLevelIndex == 3 && randomWallLayout.value.isEmpty()) {
                generateRandomLayout()
            }
            resetMap(selectedLevelIndex)
        }
    }

    fun fullReset(level: Int) {
        collectedDots = 0
        resetMap(level)
        pacX = (cols / 2).toFloat() + 0.5f; pacY = (rows / 2).toFloat() + 0.5f
        dirX = 0f; dirY = 0f
        nextDirX = 0f; nextDirY = 0f
        pacPowerTimeLeft = 0L
        pacPowered = false
        ghosts.replaceAll {
            when (it.type) {
                GhostType.CHASER -> Ghost(1.5f, 1.5f, it.color, it.type, 1.5f, 1.5f)
                GhostType.RANDOM -> Ghost((cols - 2).toFloat() + 0.5f, 1.5f, it.color, it.type, (cols - 2).toFloat() + 0.5f, 1.5f)
                GhostType.AMBUSH -> Ghost(1.5f, (rows - 2).toFloat() + 0.5f, it.color, it.type, 1.5f, (rows - 2).toFloat() + 0.5f)
            }
        }
        gameOver = false
    }

    if (useGyroscope) {
        DisposableEffect(context) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (isPaused || showLevelSelector) return
                    val tiltX = event.values[0]
                    val tiltY = event.values[1]
                    val threshold = 0.8f

                    if (abs(tiltX) > abs(tiltY)) {
                        if (tiltX < -threshold) { nextDirX = 1f; nextDirY = 0f }
                        else if (tiltX > threshold) { nextDirX = -1f; nextDirY = 0f }
                    } else {
                        if (tiltY < -threshold) { nextDirY = -1f; nextDirX = 0f }
                        else if (tiltY > threshold) { nextDirY = 1f; nextDirX = 0f }
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            if (accel != null)
                sensorManager.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
            onDispose { sensorManager.unregisterListener(listener) }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (!isPaused && !showLevelSelector) {
                mouthOpen = !mouthOpen
            }
            delay(200)
        }
    }

    // Power-up countdown effect
    LaunchedEffect(Unit) {
        while (true) {
            if (!isPaused && pacPowerTimeLeft > 0) {
                pacPowerTimeLeft = (pacPowerTimeLeft - 100L).coerceAtLeast(0L)
                if (pacPowerTimeLeft == 0L) {
                    pacPowered = false
                }
            }
            delay(100)
        }
    }

    if (useLightSensor) {
        DisposableEffect(context) {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (isPaused) return
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

    LaunchedEffect(Unit) {
        fullReset(selectedLevelIndex)
        while (true) {
            if (!isPaused && !showLevelSelector) {
                if (gameOver) {
                    delay(1000)
                    fullReset(selectedLevelIndex)
                }

                // Only allow direction changes when near grid center (for grid-aligned movement)
                val atGridCenter = abs(pacX - pacX.toInt() - 0.5f) < 0.3f && abs(pacY - pacY.toInt() - 0.5f) < 0.3f
                
                // Try to change direction if requested and at grid center
                if ((nextDirX != 0f || nextDirY != 0f) && atGridCenter) {
                    // Check if the new direction is valid
                    val checkX = (pacX + nextDirX * 0.6f).toInt()
                    val checkY = (pacY + nextDirY * 0.6f).toInt()
                    if (checkY in 0 until rows && checkX in 0 until cols && map[checkY][checkX] != 1) {
                        dirX = nextDirX; dirY = nextDirY
                        nextDirX = 0f; nextDirY = 0f
                        // Align to grid center when changing direction
                        pacX = pacX.toInt() + 0.5f
                        pacY = pacY.toInt() + 0.5f
                    }
                }

                // Move Pacman with proper wall collision
                if (dirX != 0f || dirY != 0f) {
                    val nx = pacX + dirX * MOVEMENT_SPEED
                    val ny = pacY + dirY * MOVEMENT_SPEED
                    
                    // Check collision more carefully - check both current and target cells
                    val currentGridX = pacX.toInt()
                    val currentGridY = pacY.toInt()
                    val targetGridX = nx.toInt()
                    val targetGridY = ny.toInt()
                    
                    var canMove = true
                    
                    // Check if target grid cell is valid
                    if (targetGridY !in 0 until rows || targetGridX !in 0 until cols || map[targetGridY][targetGridX] == 1) {
                        canMove = false
                    }
                    
                    // Additional check: when crossing cell boundary, ensure we're not hitting a wall
                    if (canMove && (currentGridX != targetGridX || currentGridY != targetGridY)) {
                        // Only do the extra check if we're actually crossing into a different cell
                        if (dirX > 0 && targetGridX > currentGridX) { // Moving right into new cell
                            if (targetGridX >= cols || map[currentGridY][targetGridX] == 1) canMove = false
                        } else if (dirX < 0 && targetGridX < currentGridX) { // Moving left into new cell
                            if (targetGridX < 0 || map[currentGridY][targetGridX] == 1) canMove = false
                        }
                        
                        if (dirY > 0 && targetGridY > currentGridY) { // Moving down into new cell
                            if (targetGridY >= rows || map[targetGridY][currentGridX] == 1) canMove = false
                        } else if (dirY < 0 && targetGridY < currentGridY) { // Moving up into new cell
                            if (targetGridY < 0 || map[targetGridY][currentGridX] == 1) canMove = false
                        }
                    }
                    
                    if (canMove) {
                        pacX = nx
                        pacY = ny
                    } else {
                        // Stop at grid center if hitting a wall
                        if (dirX != 0f) pacX = currentGridX + 0.5f
                        if (dirY != 0f) pacY = currentGridY + 0.5f
                        dirX = 0f
                        dirY = 0f
                    }
                }

                // Check for dot/power-up collection at current grid position
                val currentGridX = pacX.toInt()
                val currentGridY = pacY.toInt()
                if (map[currentGridY][currentGridX] == 2) {
                    map[currentGridY][currentGridX] = 0
                    collectedDots++
                } else if (map[currentGridY][currentGridX] == 3) {
                    map[currentGridY][currentGridX] = 0
                    collectedDots++
                    pacPowered = true
                    pacPowerTimeLeft = 8000L
                }

                // Move ghosts
                ghosts.forEach { g ->
                    if (g.alive) moveGhostSmooth(map, g, pacX, pacY, dirX, dirY, ghosts)
                }

                // Check collision with ghosts
                ghosts.forEach { g ->
                    if (g.alive) {
                        val dx = abs(g.x - pacX)
                        val dy = abs(g.y - pacY)
                        if (dx < 0.5f && dy < 0.5f) {  // Collision threshold
                            if (pacPowered) {
                                g.alive = false
                                launch {
                                    delay(3000)
                                    g.x = g.homeX; g.y = g.homeY
                                    g.dirX = 0f; g.dirY = 0f; g.alive = true
                                }
                            } else {
                                gameOver = true
                            }
                        }
                    }
                }
            }
            delay(FRAME_DELAY_MS)
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

    Column(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        Box(modifier = Modifier.weight(1f)) {
            val mapPadding = if (showLevelSelector) 48.dp else 0.dp
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = mapPadding, vertical = mapPadding)) {
                key(selectedLevelIndex, mapVersion) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Access mapVersion to ensure Canvas redraws when map content changes
                        @Suppress("UNUSED_EXPRESSION")
                        mapVersion
                        
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
                }
            }

            if (showLevelSelector) {
                Box(modifier = Modifier.fillMaxSize()) {
                    IconButton(
                        onClick = {
                            selectedLevelIndex = (selectedLevelIndex + 3) % 4
                        },
                        modifier = Modifier.align(Alignment.CenterStart).padding(8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Prev Level", tint = scoreColor, modifier = Modifier.size(48.dp))
                    }
                    IconButton(
                        onClick = {
                            selectedLevelIndex = (selectedLevelIndex + 1) % 4
                        },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next Level", tint = scoreColor, modifier = Modifier.size(48.dp))
                    }

                    if (selectedLevelIndex == 3) {
                        IconButton(
                            onClick = { 
                                generateRandomLayout()
                                resetMap(3) 
                            },
                            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh Random Map", tint = scoreColor, modifier = Modifier.size(40.dp))
                        }
                    }
                }
            }

            if (locked) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("App locked", color = Color.White, fontSize = 20.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = {
                            if (canAuthenticate) biometricPrompt?.authenticate(promptInfo)
                            else { locked = false; isPaused = false }
                        }) {
                            Text(if (canAuthenticate) "Unlock with fingerprint" else "Unlock")
                        }
                    }
                }
            }

            if (!showLevelSelector) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Score: $collectedDots", color = scoreColor, fontSize = 24.sp)
                        IconButton(onClick = { isPaused = true }) {
                            Icon(imageVector = Icons.Filled.Pause, contentDescription = "Pause", tint = scoreColor)
                        }
                    }
                    
                    if (pacPowerTimeLeft > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 0.dp)) { //Powerup timer vertical height 'padding(top = X.dp)'
                            Canvas(modifier = Modifier.size(20.dp)) {
                                drawCircle(Color.Green, radius = size.minDimension / 2)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .width(120.dp)
                                    .height(10.dp)
                                    .background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(5.dp))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(pacPowerTimeLeft / 8000f)
                                        .background(Color.Green, RoundedCornerShape(5.dp))
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "${(pacPowerTimeLeft / 1000f).toInt()}s", color = scoreColor, fontSize = 14.sp)
                        }
                    }
                }
            }

            if (isPaused) {
                val fixedWallColor = lerp(Color.Blue, Color(0f, 0f, 0.5f), 0f)
                val fixedScoreColor = Color.White
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                    if (showSettings) {
                        Column(modifier = Modifier.background(fixedWallColor, shape = RoundedCornerShape(16.dp)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
                                Switch(checked = useLightSensor, onCheckedChange = { useLightSensor = it; if (it) selectedTheme = AppTheme.SYSTEM })
                            }
                            if (!useLightSensor) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
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
                        Column(modifier = Modifier.background(fixedWallColor, shape = RoundedCornerShape(16.dp)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Paused", color = fixedScoreColor, fontSize = 24.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { isResuming = true }) { Text("Resume") }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                showLevelSelector = true
                                isPaused = false
                            }) { Text("Levels") }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { showSettings = true }) { Text("Settings") }
                        }
                    }
                }
            }
        }

        if (showLevelSelector) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(onClick = { showLevelSelector = false; isPaused = true; fullReset(selectedLevelIndex) }) {
                    Text("Confirm")
                }
            }
        } else if (!useGyroscope) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row { Button(onClick = { nextDirY = -1f; nextDirX = 0f }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Up") } }
                Row {
                    Button(onClick = { nextDirX = -1f; nextDirY = 0f }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Left") }
                    Spacer(modifier = Modifier.width(64.dp))
                    Button(onClick = { nextDirX = 1f; nextDirY = 0f }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Right") }
                }
                Row { Button(onClick = { nextDirY = 1f; nextDirX = 0f }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Down") } }
            }
        }
    }
}

private fun moveGhostSmooth(map: Array<IntArray>, ghost: Ghost, pacX: Float, pacY: Float, pacDirX: Float, pacDirY: Float, ghosts: List<Ghost>) {
    // Only change direction when at grid center (for cleaner pathfinding)
    val atGridCenter = abs(ghost.x - ghost.x.toInt() - 0.5f) < 0.2f && abs(ghost.y - ghost.y.toInt() - 0.5f) < 0.2f
    
    if (atGridCenter || (ghost.dirX == 0f && ghost.dirY == 0f)) {
        val dirs = listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)
        var possible = dirs.filter { (dx, dy) ->
            val checkX = (ghost.x + dx * 0.6f).toInt()
            val checkY = (ghost.y + dy * 0.6f).toInt()
            checkX in map[0].indices && checkY in map.indices && map[checkY][checkX] != 1
        }
        if (possible.size > 1) possible = possible.filterNot { (dx, dy) -> dx == -ghost.dirX && dy == -ghost.dirY }
        if (possible.isEmpty()) {
            ghost.dirX = 0f
            ghost.dirY = 0f
            return
        }
        
        val best = when (ghost.type) {
            GhostType.CHASER -> possible.minByOrNull { (dx, dy) -> 
                val nx = ghost.x + dx; val ny = ghost.y + dy
                (pacX - nx) * (pacX - nx) + (pacY - ny) * (pacY - ny)
            }
            GhostType.AMBUSH -> {
                val tx = pacX + pacDirX * 4
                val ty = pacY + pacDirY * 4
                possible.minByOrNull { (dx, dy) -> 
                    val nx = ghost.x + dx; val ny = ghost.y + dy
                    (tx - nx) * (tx - nx) + (ty - ny) * (ty - ny)
                }
            }
            GhostType.RANDOM -> {
                val d = abs(ghost.x - pacX) + abs(ghost.y - pacY)
                if (d > 8) possible.minByOrNull { (dx, dy) -> 
                    val nx = ghost.x + dx; val ny = ghost.y + dy
                    (pacX - nx) * (pacX - nx) + (pacY - ny) * (pacY - ny)
                } else { 
                    val cx = 1f; val cy = map.lastIndex - 1f
                    possible.minByOrNull { (dx, dy) -> 
                        val nx = ghost.x + dx; val ny = ghost.y + dy
                        (cx - nx) * (cx - nx) + (cy - ny) * (cy - ny)
                    }
                }
            }
        } ?: possible.random()
        ghost.dirX = best.first
        ghost.dirY = best.second
        
        // Align to grid center when changing direction
        ghost.x = ghost.x.toInt() + 0.5f
        ghost.y = ghost.y.toInt() + 0.5f
    }
    
    // Move smoothly in current direction with proper wall collision
    if (ghost.dirX != 0f || ghost.dirY != 0f) {
        val nx = ghost.x + ghost.dirX * MOVEMENT_SPEED
        val ny = ghost.y + ghost.dirY * MOVEMENT_SPEED
        
        val currentGridX = ghost.x.toInt()
        val currentGridY = ghost.y.toInt()
        val targetGridX = nx.toInt()
        val targetGridY = ny.toInt()
        
        var canMove = true
        
        // Check if target grid cell is valid
        if (targetGridY !in map.indices || targetGridX !in map[0].indices || map[targetGridY][targetGridX] == 1) {
            canMove = false
        }
        
        // Additional check: when crossing cell boundary, ensure we're not hitting a wall
        if (canMove && (currentGridX != targetGridX || currentGridY != targetGridY)) {
            if (ghost.dirX > 0 && targetGridX > currentGridX) {
                if (targetGridX >= map[0].size || map[currentGridY][targetGridX] == 1) canMove = false
            } else if (ghost.dirX < 0 && targetGridX < currentGridX) {
                if (targetGridX < 0 || map[currentGridY][targetGridX] == 1) canMove = false
            }
            
            if (ghost.dirY > 0 && targetGridY > currentGridY) {
                if (targetGridY >= map.size || map[targetGridY][currentGridX] == 1) canMove = false
            } else if (ghost.dirY < 0 && targetGridY < currentGridY) {
                if (targetGridY < 0 || map[targetGridY][currentGridX] == 1) canMove = false
            }
        }
        
        if (canMove) {
            ghost.x = nx
            ghost.y = ny
        } else {
            // Stop at grid center if hitting a wall
            ghost.x = currentGridX + 0.5f
            ghost.y = currentGridY + 0.5f
            ghost.dirX = 0f
            ghost.dirY = 0f
        }
    }
}

private fun DrawScope.drawMazeGrid(map: Array<IntArray>, tile: Float, ox: Float, oy: Float, wall: Color, dot: Color) {
    val rows = map.size
    val cols = map[0].size
    val visited = Array(rows) { BooleanArray(cols) { false } }
    for (y in 0 until rows) {
        for (x in 0 until cols) {
            when (map[y][x]) {
                1 -> {
                    if (!visited[y][x]) {
                        var endX = x
                        while (endX + 1 < cols && map[y][endX + 1] == 1) endX++
                        var endY = y
                        while (endY + 1 < rows && map[endY + 1][x] == 1) endY++
                        if ((endX - x) >= (endY - y)) {
                            drawRect(wall, Offset(x * tile + ox, y * tile + oy), Size((endX - x + 1) * tile, tile))
                            for (i in x..endX) visited[y][i] = true
                        } else {
                            drawRect(wall, Offset(x * tile + ox, y * tile + oy), Size(tile, (endY - y + 1) * tile))
                            for (i in y..endY) visited[i][x] = true
                        }
                    }
                }
                2 -> {
                    val r = tile / 6; val c = Offset(x * tile + ox + tile / 2, y * tile + oy + tile / 2)
                    drawCircle(Color.Black, r + 1.5f, c); drawCircle(dot, r, c)
                }
                3 -> {
                    val r = tile / 3; val c = Offset(x * tile + ox + tile / 2, y * tile + oy + tile / 2)
                    drawCircle(Color.Black, r + 2f, c); drawCircle(Color.Green, r, c)
                }
            }
        }
    }
}

private fun DrawScope.drawPacmanClassic(x: Float, y: Float, tile: Float, ox: Float, oy: Float, open: Boolean, color: Color) {
    val cx = ox + x * tile; val cy = oy + y * tile; val r = tile * 0.4f
    if (open) drawArc(color, 30f, 300f, true, Offset(cx - r, cy - r), Size(r * 2, r * 2))
    else drawCircle(color, r, Offset(cx, cy))
}

private fun DrawScope.drawGhostClassic(g: Ghost, tile: Float, ox: Float, oy: Float, eye: Color) {
    val cx = ox + g.x * tile; val cy = oy + g.y * tile; val r = tile * 0.4f
    drawCircle(g.color, r, Offset(cx, cy - r / 4))
    for (i in 0..3) { val lx = cx - r + i * r * 0.66f; drawCircle(g.color, r / 4, Offset(lx, cy)) }
    drawCircle(eye, r / 5, Offset(cx - r / 4, cy - r / 4)); drawCircle(eye, r / 5, Offset(cx + r / 4, cy - r / 4))
}
