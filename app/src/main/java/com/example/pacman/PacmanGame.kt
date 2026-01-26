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
    var renderX: Float = 0f,
    var renderY: Float = 0f
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

    var pacX by remember { mutableIntStateOf(cols / 2) }
    var pacY by remember { mutableIntStateOf(rows / 2) }
    var pacRenderX by remember { mutableFloatStateOf(cols / 2f) }
    var pacRenderY by remember { mutableFloatStateOf(rows / 2f) }
    var dirX by remember { mutableIntStateOf(0) }
    var dirY by remember { mutableIntStateOf(0) }
    var nextDirX by remember { mutableIntStateOf(0) }
    var nextDirY by remember { mutableIntStateOf(0) }

    val ghosts = remember {
        mutableStateListOf(
            Ghost(1, 1, Color.Red, GhostType.CHASER, 1, 1, renderX = 1f, renderY = 1f),
            Ghost(cols - 2, 1, Color.Cyan, GhostType.RANDOM, cols - 2, 1, renderX = (cols - 2).toFloat(), renderY = 1f),
            Ghost(1, rows - 2, Color.Magenta, GhostType.AMBUSH, 1, rows - 2, renderX = 1f, renderY = (rows - 2).toFloat())
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
        pacX = cols / 2; pacY = rows / 2
        pacRenderX = (cols / 2).toFloat(); pacRenderY = (rows / 2).toFloat()
        dirX = 0; dirY = 0
        nextDirX = 0; nextDirY = 0
        pacPowerTimeLeft = 0L
        pacPowered = false
        ghosts.replaceAll {
            when (it.type) {
                GhostType.CHASER -> Ghost(1, 1, it.color, it.type, 1, 1, renderX = 1f, renderY = 1f)
                GhostType.RANDOM -> Ghost(cols - 2, 1, it.color, it.type, cols - 2, 1, renderX = (cols - 2).toFloat(), renderY = 1f)
                GhostType.AMBUSH -> Ghost(1, rows - 2, it.color, it.type, 1, rows - 2, renderX = 1f, renderY = (rows - 2).toFloat())
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
        var lastGridUpdate = 0L
        val gridUpdateInterval = 200L  // Grid updates every 200ms (game speed)
        
        while (true) {
            if (!isPaused && !showLevelSelector) {
                val currentTime = System.currentTimeMillis()
                
                // Grid-based logic updates (every 200ms)
                if (currentTime - lastGridUpdate >= gridUpdateInterval) {
                    lastGridUpdate = currentTime
                    
                    if (gameOver) {
                        delay(1000)
                        fullReset(selectedLevelIndex)
                        continue
                    }

                    val tryNextX = pacX + nextDirX
                    val tryNextY = pacY + nextDirY
                    if (nextDirX != 0 || nextDirY != 0) {
                        if (tryNextY in 0 until rows && tryNextX in 0 until cols && map[tryNextY][tryNextX] != 1) {
                            dirX = nextDirX; dirY = nextDirY
                            nextDirX = 0; nextDirY = 0
                        }
                    }

                    val nx = pacX + dirX
                    val ny = pacY + dirY
                    if (ny in 0 until rows && nx in 0 until cols && map[ny][nx] != 1) {
                        pacX = nx; pacY = ny
                    }

                    if (map[pacY][pacX] == 2) {
                        map[pacY][pacX] = 0
                        collectedDots++
                    } else if (map[pacY][pacX] == 3) {
                        map[pacY][pacX] = 0
                        collectedDots++
                        pacPowered = true
                        pacPowerTimeLeft = 8000L
                    }

                    ghosts.forEach { g ->
                        if (g.alive) moveGhostGrid(map, g, pacX, pacY, dirX, dirY, ghosts)
                    }

                    ghosts.forEach { g ->
                        if (g.alive && g.x == pacX && g.y == pacY) {
                            if (pacPowered) {
                                g.alive = false
                                launch {
                                    delay(3000)
                                    g.x = g.homeX; g.y = g.homeY
                                    g.renderX = g.homeX.toFloat(); g.renderY = g.homeY.toFloat()
                                    g.dirX = 0; g.dirY = 0; g.alive = true
                                }
                            } else {
                                gameOver = true
                            }
                        }
                    }
                }
                
                // Smooth interpolation (60 FPS)
                val timeSinceLastUpdate = (currentTime - lastGridUpdate).coerceAtMost(gridUpdateInterval)
                val interpolationFactor = (timeSinceLastUpdate.toFloat() / gridUpdateInterval).coerceIn(0f, 1f)
                
                // Interpolate Pacman position
                pacRenderX = pacX - dirX * (1f - interpolationFactor)
                pacRenderY = pacY - dirY * (1f - interpolationFactor)
                
                // Interpolate ghost positions
                ghosts.forEach { g ->
                    if (g.alive) {
                        g.renderX = g.x - g.dirX * (1f - interpolationFactor)
                        g.renderY = g.y - g.dirY * (1f - interpolationFactor)
                    }
                }
            }
            delay(16)  // ~60 FPS
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
                        drawPacmanClassic(pacRenderX, pacRenderY, tileSize, offsetX, offsetY, mouthOpen, Color.Yellow)
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

private fun moveGhostGrid(map: Array<IntArray>, ghost: Ghost, pacX: Int, pacY: Int, pacDirX: Int, pacDirY: Int, ghosts: List<Ghost>) {
    val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    var possible = dirs.filter { (dx, dy) ->
        val nx = ghost.x + dx
        val ny = ghost.y + dy
        nx in map[0].indices && ny in map.indices && map[ny][nx] != 1 && ghosts.none { it != ghost && it.alive && it.x == nx && it.y == ny }
    }
    if (possible.size > 1) possible = possible.filterNot { (dx, dy) -> dx == -ghost.dirX && dy == -ghost.dirY }
    if (possible.isEmpty()) return
    val best = when (ghost.type) {
        GhostType.CHASER -> possible.minByOrNull { (dx, dy) -> val nx = ghost.x + dx; val ny = ghost.y + dy; (pacX - nx) * (pacX - nx) + (pacY - ny) * (pacY - ny) }
        GhostType.AMBUSH -> {
            val tx = (pacX + pacDirX * 4).coerceIn(0, map[0].lastIndex)
            val ty = (pacY + pacDirY * 4).coerceIn(0, map.lastIndex)
            possible.minByOrNull { (dx, dy) -> val nx = ghost.x + dx; val ny = ghost.y + dy; (tx - nx) * (tx - nx) + (ty - ny) * (ty - ny) }
        }
        GhostType.RANDOM -> {
            val d = abs(ghost.x - pacX) + abs(ghost.y - pacY)
            if (d > 8) possible.minByOrNull { (dx, dy) -> val nx = ghost.x + dx; val ny = ghost.y + dy; (pacX - nx) * (pacX - nx) + (pacY - ny) * (pacY - ny) }
            else { val cx = 1; val cy = map.lastIndex - 1; possible.minByOrNull { (dx, dy) -> val nx = ghost.x + dx; val ny = ghost.y + dy; (cx - nx) * (cx - nx) + (cy - ny) * (cy - ny) } }
        }
    } ?: possible.random()
    ghost.x += best.first; ghost.y += best.second; ghost.dirX = best.first; ghost.dirY = best.second
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
    val cx = ox + (x + 0.5f) * tile; val cy = oy + (y + 0.5f) * tile; val r = tile * 0.4f
    if (open) drawArc(color, 30f, 300f, true, Offset(cx - r, cy - r), Size(r * 2, r * 2))
    else drawCircle(color, r, Offset(cx, cy))
}

private fun DrawScope.drawGhostClassic(g: Ghost, tile: Float, ox: Float, oy: Float, eye: Color) {
    val cx = ox + (g.renderX + 0.5f) * tile; val cy = oy + (g.renderY + 0.5f) * tile; val r = tile * 0.4f
    drawCircle(g.color, r, Offset(cx, cy - r / 4))
    for (i in 0..3) { val lx = cx - r + i * r * 0.66f; drawCircle(g.color, r / 4, Offset(lx, cy)) }
    drawCircle(eye, r / 5, Offset(cx - r / 4, cy - r / 4)); drawCircle(eye, r / 5, Offset(cx + r / 4, cy - r / 4))
}
