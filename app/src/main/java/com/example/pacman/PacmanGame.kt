package com.example.pacman

import com.example.pacman.GameStateManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableLongStateOf
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

enum class DifficultyLevel(val speedMultiplier: Float, val powerUpDuration: Long) {
    EASY(0.8f, 10000L),
    NORMAL(1.0f, 8000L),
    HARD(1.3f, 5000L)
}

data class Ghost(
    var x: Int,
    var y: Int,
    val color: Color,
    val type: GhostType,
    val homeX: Int,
    val homeY: Int,
    var dirX: Int = 0,
    var dirY: Int = 0,
    var alive: Boolean = true,
    var stunnedUntil: Long = 0L  // Timestamp when ghost recovers from power-up effect
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
    var pacPowerUpTime by remember { mutableLongStateOf(0L) }
    var locked by remember { mutableStateOf(true) }
    var mouthOpen by remember { mutableStateOf(true) }
    var targetBrightness by remember { mutableFloatStateOf(1f) }
    var useLightSensor by remember { mutableStateOf(true) }
    var selectedTheme by remember { mutableStateOf(AppTheme.SYSTEM) }
    var gameSpeed by remember { mutableIntStateOf(200) }
    var difficultyLevel by remember { mutableStateOf(DifficultyLevel.NORMAL) }
    var highScore by remember { mutableIntStateOf(0) }
    var showStats by remember { mutableStateOf(false) }
    var showLevelSelector by remember { mutableStateOf(false) }
    var selectedLevelIndex by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    val gameStateManager = remember { GameStateManager(context) }
    
    // Load saved settings and scores
    LaunchedEffect(Unit) {
        useLightSensor = gameStateManager.getUseLightSensor()
        selectedTheme = AppTheme.valueOf(gameStateManager.getSelectedTheme())
        gameSpeed = gameStateManager.getGameSpeed()
        difficultyLevel = when (gameStateManager.getDifficultyLevel()) {
            2 -> DifficultyLevel.NORMAL
            3 -> DifficultyLevel.HARD
            else -> DifficultyLevel.EASY
        }
        highScore = gameStateManager.getHighScore()
    }

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
        fun resetMap(level: Int) {
            for (y in 0 until rows)
                for (x in 0 until cols)
                    map[y][x] = if (x == 0 || y == 0 || x == cols - 1 || y == rows - 1) 1 else 2

            when (level) {
                0 -> {
                    // Horizontal lines
                    for (x in 3 until 12) map[4][x] = 1
                    for (x in 3 until 12) map[12][x] = 1
                }
                1 -> {
                    // Vertical lines
                    for (y in 3 until 14) map[y][4] = 1
                    for (y in 3 until 14) map[y][10] = 1
                }
                2 -> {
                    // Complex layout
                    for (x in 2 until 6) map[4][x] = 1
                    for (x in 9 until 13) map[4][x] = 1
                    for (x in 2 until 6) map[12][x] = 1
                    for (x in 9 until 13) map[12][x] = 1
                    for (y in 6 until 11) map[y][7] = 1
                }
                3 -> {
                    // Random layout - same as default
                    for (x in 3 until 12) map[4][x] = 1
                    for (x in 3 until 12) map[12][x] = 1
                    for (y in 6 until 11) map[y][2] = 1
                    for (y in 6 until 11) map[y][12] = 1
                }
            }
            
            // Place four green power-pellets (bigger dots)
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

        fun fullReset(level: Int) {
            collectedDots = 0
            resetMap(level)
            pacX = cols / 2; pacY = rows / 2
            dirX = 0; dirY = 0
            nextDirX = 0; nextDirY = 0
            pacPowered = false
            pacPowerUpTime = 0L
            ghosts.replaceAll {
                when (it.type) {
                    GhostType.CHASER -> Ghost(1, 1, it.color, it.type, 1, 1)
                    GhostType.RANDOM -> Ghost(cols - 2, 1, it.color, it.type, cols - 2, 1)
                    GhostType.AMBUSH -> Ghost(1, rows - 2, it.color, it.type, 1, rows - 2)
                }
            }
            gameOver = false
        }

        resetMap(selectedLevelIndex)
        pacX = cols / 2; pacY = rows / 2
        dirX = 0; dirY = 0
        nextDirX = 0; nextDirY = 0

        while (true) {
            if (!isPaused) {
                if (gameOver) {
                    delay(1000)
                    // Save score when game ends
                    gameStateManager.saveScore(collectedDots)
                    if (collectedDots > highScore) {
                        highScore = collectedDots
                    }
                    
                    collectedDots = 0
                    resetMap(selectedLevelIndex)
                    pacX = cols / 2; pacY = rows / 2
                    dirX = 0; dirY = 0
                    nextDirX = 0; nextDirY = 0
                    pacPowered = false
                    pacPowerUpTime = 0L
                    
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
                    // Power pellet: Pac-Man becomes invulnerable with visual feedback
                    map[pacY][pacX] = 0
                    collectedDots += 5  // Power pellets worth more points
                    pacPowered = true
                    pacPowerUpTime = System.currentTimeMillis()
                    
                    // Stun all ghosts when power-up activated
                    val currentTime = System.currentTimeMillis()
                    ghosts.forEach { ghost ->
                        ghost.stunnedUntil = currentTime + (difficultyLevel.powerUpDuration / 2)
                    }
                    
                    launch {
                        delay(difficultyLevel.powerUpDuration)
                        pacPowered = false
                        ghosts.forEach { ghost ->
                            ghost.stunnedUntil = 0L
                        }
                    }
                }

                val currentTime = System.currentTimeMillis()
                ghosts.forEach { g ->
                    if (g.alive && currentTime > g.stunnedUntil) {
                        moveGhostGrid(map, g, pacX, pacY, dirX, dirY, ghosts)
                    }
                }

                // Handle collisions with ghosts
                ghosts.forEach { g ->
                    if (g.alive && g.x == pacX && g.y == pacY) {
                        if (pacPowered) {
                            // Eat ghost - disappear and respawn after delay
                            g.alive = false
                            collectedDots += 10  // Bonus for eating ghost
                            launch {
                                delay(2000)
                                g.x = g.homeX
                                g.y = g.homeY
                                g.dirX = 0
                                g.dirY = 0
                                g.alive = true
                                g.stunnedUntil = 0L
                            }
                        } else {
                            gameOver = true
                        }
                    }
                }
            }
            delay((gameSpeed / difficultyLevel.speedMultiplier).toLong())
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
                drawPacmanClassic(pacX, pacY, tileSize, offsetX, offsetY, mouthOpen, Color.Yellow, pacPowered)
                ghosts.forEach {
                    if (it.alive) drawGhostClassic(it, tileSize, offsetX, offsetY, dotColor, System.currentTimeMillis())
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
                Column {
                    Text(
                        text = "Score: $collectedDots",
                        color = scoreColor,
                        fontSize = 24.sp
                    )
                    Text(
                        text = "High: $highScore",
                        color = scoreColor,
                        fontSize = 16.sp
                    )
                }
                
                // Power-up indicator
                if (pacPowered) {
                    val remainingTime = (difficultyLevel.powerUpDuration - (System.currentTimeMillis() - pacPowerUpTime)) / 1000
                    if (remainingTime > 0) {
                        Text(
                            text = "⚡ ${remainingTime}s",
                            color = Color.Green,
                            fontSize = 18.sp
                        )
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = difficultyLevel.name,
                        color = scoreColor,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    IconButton(onClick = { isPaused = true }) {
                        Icon(
                            imageVector = Icons.Filled.Pause,
                            contentDescription = "Pause",
                            tint = scoreColor
                        )
                    }
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
                    if (showLevelSelector) {
                        Column(
                            modifier = Modifier
                                .background(fixedWallColor, shape = RoundedCornerShape(16.dp))
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Select Level", color = fixedScoreColor, fontSize = 24.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Text(
                                when (selectedLevelIndex) {
                                    0 -> "Level 1: Horizontal"
                                    1 -> "Level 2: Vertical"
                                    2 -> "Level 3: Complex"
                                    3 -> "Level 4: Random"
                                    else -> "Level ${selectedLevelIndex + 1}"
                                },
                                color = fixedScoreColor,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(onClick = {
                                    selectedLevelIndex = (selectedLevelIndex - 1 + 4) % 4
                                }) {
                                    Text("< Prev")
                                }
                                Text(
                                    "${selectedLevelIndex + 1}/4",
                                    color = fixedScoreColor,
                                    fontSize = 16.sp
                                )
                                Button(onClick = {
                                    selectedLevelIndex = (selectedLevelIndex + 1) % 4
                                }) {
                                    Text("Next >")
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = {
                                showLevelSelector = false
                                isPaused = true
                                fullReset(selectedLevelIndex)
                            }) {
                                Text("Start Level")
                            }
                            Button(onClick = { showLevelSelector = false }) {
                                Text("Back")
                            }
                        }
                    } else if (showStats) {
                        Column(
                            modifier = Modifier
                                .background(fixedWallColor, shape = RoundedCornerShape(16.dp))
                                .padding(24.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Statistics", color = fixedScoreColor, fontSize = 24.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("High Score: $highScore", color = fixedScoreColor, fontSize = 18.sp)
                            Text("Last Score: ${gameStateManager.getLastScore()}", color = fixedScoreColor, fontSize = 16.sp)
                            Text("Games Played: ${gameStateManager.getGamesPlayed()}", color = fixedScoreColor, fontSize = 16.sp)
                            Text("Total Dots: ${gameStateManager.getTotalDotsCollected()}", color = fixedScoreColor, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { showStats = false }) {
                                Text("Back")
                            }
                        }
                    } else if (showSettings) {
                        Column(
                            modifier = Modifier
                                .background(fixedWallColor, shape = RoundedCornerShape(16.dp))
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Settings", color = fixedScoreColor, fontSize = 24.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Difficulty selector
                            Text("Difficulty", color = fixedScoreColor, fontSize = 16.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Button(onClick = {
                                    difficultyLevel = DifficultyLevel.EASY
                                    gameStateManager.saveDifficultyLevel(1)
                                }) {
                                    Text("Easy")
                                }
                                Button(onClick = {
                                    difficultyLevel = DifficultyLevel.NORMAL
                                    gameStateManager.saveDifficultyLevel(2)
                                }) {
                                    Text("Normal")
                                }
                                Button(onClick = {
                                    difficultyLevel = DifficultyLevel.HARD
                                    gameStateManager.saveDifficultyLevel(3)
                                }) {
                                    Text("Hard")
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Use Gyroscope", color = fixedScoreColor)
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    checked = useGyroscope,
                                    onCheckedChange = { useGyroscope = it }
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Use Light Sensor", color = fixedScoreColor)
                                Spacer(modifier = Modifier.width(8.dp))
                                Switch(
                                    checked = useLightSensor,
                                    onCheckedChange = {
                                        useLightSensor = it
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
                                    Button(onClick = { selectedTheme = AppTheme.SYSTEM }) {
                                        Text("System")
                                    }
                                    Button(onClick = { selectedTheme = AppTheme.LIGHT }) {
                                        Text("Light")
                                    }
                                    Button(onClick = { selectedTheme = AppTheme.DARK }) {
                                        Text("Dark")
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = {
                                gameStateManager.saveSettings(useGyroscope, useLightSensor, selectedTheme.name)
                                gameStateManager.saveGameSpeed(gameSpeed)
                                showSettings = false
                            }) {
                                Text("Save Settings")
                            }
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
                            Button(onClick = { showLevelSelector = true; isPaused = true }) {
                                Text("Levels")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { showSettings = true }) {
                                Text("Settings")
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { showStats = true }) {
                                Text("Statistics")
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
        GhostType.CHASER -> {
            // Direct chase towards Pac-Man (like Blinky in original)
            possible.minByOrNull { (dx, dy) ->
                val nx = ghost.x + dx
                val ny = ghost.y + dy
                (pacX - nx) * (pacX - nx) + (pacY - ny) * (pacY - ny)
            }
        }

        GhostType.AMBUSH -> {
            // Ambush ahead of Pac-Man's direction (like Inky in original)
            val tx = (pacX + pacDirX * 4).coerceIn(0, map[0].lastIndex)
            val ty = (pacY + pacDirY * 4).coerceIn(0, map.lastIndex)
            possible.minByOrNull { (dx, dy) ->
                val nx = ghost.x + dx
                val ny = ghost.y + dy
                (tx - nx) * (tx - nx) + (ty - ny) * (ty - ny)
            }
        }

        GhostType.RANDOM -> {
            // Random/scattering behavior (like Sue in original)
            // Close to Pac-Man: run away; far: go to corner
            val d = abs(ghost.x - pacX) + abs(ghost.y - pacY)
            if (d > 8) {
                // Chase behavior when far
                possible.minByOrNull { (dx, dy) ->
                    val nx = ghost.x + dx
                    val ny = ghost.y + dy
                    (pacX - nx) * (pacX - nx) + (pacY - ny) * (pacY - ny)
                }
            } else {
                // Scatter to corner when near
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
    open: Boolean, color: Color, powered: Boolean = false
) {
    val cx = ox + (x + 0.5f) * tile
    val cy = oy + (y + 0.5f) * tile
    val r = tile * 0.4f

    if (open)
        drawArc(color, 30f, 300f, true, Offset(cx - r, cy - r), Size(r * 2, r * 2))
    else
        drawCircle(color, r, Offset(cx, cy))
    
    // Visual effect when powered - glow/halo
    if (powered) {
        drawCircle(Color.Yellow.copy(alpha = 0.3f), r * 1.3f, Offset(cx, cy))
    }
}

private fun DrawScope.drawGhostClassic(
    g: Ghost, tile: Float, ox: Float, oy: Float, eye: Color, currentTime: Long
) {
    val cx = ox + (g.x + 0.5f) * tile
    val cy = oy + (g.y + 0.5f) * tile
    val r = tile * 0.4f

    // If stunned, use blue color; otherwise use ghost's original color
    val ghostColor = if (currentTime < g.stunnedUntil) Color.Blue else g.color

    drawCircle(ghostColor, r, Offset(cx, cy - r / 4))
    for (i in 0..3) {
        val lx = cx - r + i * r * 0.66f
        drawCircle(ghostColor, r / 4, Offset(lx, cy))
    }
    
    // Eyes
    drawCircle(eye, r / 5, Offset(cx - r / 4, cy - r / 4))
    drawCircle(eye, r / 5, Offset(cx + r / 4, cy - r / 4))
    
    // Visual indicator when stunned
    if (currentTime < g.stunnedUntil) {
        drawCircle(Color.Cyan.copy(alpha = 0.3f), r * 1.2f, Offset(cx, cy))
    }
}
