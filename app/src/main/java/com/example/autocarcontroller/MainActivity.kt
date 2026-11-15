package com.example.autocarcontroller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private val Navy = Color(0xFF101827)
private val Panel = Color(0xFF1E293B)
private val TerminalBg = Color(0xFF090D16)
private val Blue = Color(0xFF2563EB)
private val Purple = Color(0xFF7C3AED)
private val Muted = Color(0xFF94A3B8)
private val Red = Color(0xFFDC2626)
private val Green = Color(0xFF16A34A)
private val Amber = Color(0xFFD97706)

class MainActivity : ComponentActivity() {
    private var controller: BluetoothController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Blue,
                    background = Navy,
                    surface = Panel,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                AutoCarScreen(
                    adapter = adapter,
                    onControllerReady = { controller = it }
                )
            }
        }
    }

    override fun onDestroy() {
        controller?.shutdown()
        controller = null
        super.onDestroy()
    }
}

class BluetoothController(
    private val adapter: BluetoothAdapter,
    private val onStatus: (String) -> Unit,
    private val onCommand: (String) -> Unit,
    private val onMessageReceived: (String) -> Unit
) {
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-0000805F9B34")
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var readerThread: Thread? = null
    @Volatile private var isReading = false
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val deviceName = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
        onStatus("Connecting to $deviceName...")

        Thread {
            try {
                try { adapter.cancelDiscovery() } catch (_: SecurityException) {}
                closeSocket()

                try {
                    socket = device.createRfcommSocketToServiceRecord(sppUuid)
                    socket?.connect()
                } catch (e: IOException) {
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    socket = method.invoke(device, 1) as BluetoothSocket
                    socket?.connect()
                }

                outputStream = socket?.outputStream
                startListening(socket!!)

                mainHandler.post {
                    onStatus("Connected to $deviceName")
                }
            } catch (e: SecurityException) {
                closeSocket()
                mainHandler.post { onStatus("Permission denied!") }
            } catch (e: Exception) {
                e.printStackTrace()
                closeSocket()
                mainHandler.post {
                    onStatus("Connection failed: read failed / socket timeout")
                }
            }
        }.start()
    }

    private fun startListening(activeSocket: BluetoothSocket) {
        isReading = true
        readerThread = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(activeSocket.inputStream))
                while (isReading && activeSocket.isConnected) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) {
                        mainHandler.post {
                            onMessageReceived(line.trim())
                        }
                    }
                }
            } catch (_: IOException) {
                // Socket closed or disconnected normally
            }
        }.apply { start() }
    }

    fun send(charCmd: Char) {
        val commandStr = charCmd.toString()
        onCommand(commandStr)

        val stream = outputStream ?: return

        Thread {
            try {
                stream.write(charCmd.code)
                stream.flush()
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    onStatus("Transmission error")
                }
            }
        }.start()
    }

    fun sendLine(command: String) {
        onCommand(command)
        val stream = outputStream ?: return

        Thread {
            try {
                stream.write("$command\n".toByteArray(Charsets.UTF_8))
                stream.flush()
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    onStatus("Transmission error")
                }
            }
        }.start()
    }

    fun disconnect() {
        closeSocket()
        onStatus("Disconnected")
    }

    fun shutdown() {
        closeSocket()
    }

    private fun closeSocket() {
        isReading = false
        try { readerThread?.interrupt() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        readerThread = null
        outputStream = null
        socket = null
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun AutoCarScreen(
    adapter: BluetoothAdapter?,
    onControllerReady: (BluetoothController) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var status by remember { mutableStateOf("Not connected") }
    var lastCommand by remember { mutableStateOf("—") }
    var pairedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var modeMessage by remember { mutableStateOf("Use M to switch the car's mode") }
    var speedValue by remember { mutableFloatStateOf(50f) }

    // Debug Mode States
    var debugModeEnabled by remember { mutableStateOf(false) }
    val debugLogs = remember { mutableStateListOf<String>() }

    // Colour Threshold Slider States (0.00f - 1.00f)
    var redMargin by remember { mutableFloatStateOf(0.10f) }
    var redMin by remember { mutableFloatStateOf(0.40f) }
    var greenMin by remember { mutableFloatStateOf(0.40f) }
    var blueMin by remember { mutableFloatStateOf(0.40f) }
    var blueMax by remember { mutableFloatStateOf(0.62f) }
    var rgDiff by remember { mutableFloatStateOf(0.08f) }

    // PID Tuning Slider States
    var kpValue by remember { mutableFloatStateOf(1.00f) }
    var kdValue by remember { mutableFloatStateOf(0.50f) }
    // Timing / Mission Parameter Slider States (ms)
    var cornerFwdMs by remember { mutableFloatStateOf(200f) }
    var branchFwdMs by remember { mutableFloatStateOf(200f) }
    var t1Ms by remember { mutableFloatStateOf(500f) }
    var tsMs by remember { mutableFloatStateOf(500f) }
    var winMs by remember { mutableFloatStateOf(500f) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) refreshPairedDevices(adapter) { pairedDevices = it }
        else status = "Bluetooth permission is required"
    }

    val bluetoothController = remember(adapter) {
        if (adapter == null) null else BluetoothController(
            adapter = adapter,
            onStatus = { status = it },
            onCommand = { lastCommand = it },
            onMessageReceived = { incomingLine ->
                debugLogs.add(incomingLine)
                if (debugLogs.size > 12) {
                    debugLogs.removeAt(0)
                }
            }
        )
    }

    DisposableEffect(bluetoothController) {
        bluetoothController?.let(onControllerReady)
        onDispose { }
    }

    fun refresh() {
        if (!hasPermission) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                status = "Bluetooth permission unavailable"
            }
        } else {
            refreshPairedDevices(adapter) { pairedDevices = it }
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) refreshPairedDevices(adapter) { pairedDevices = it }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        color = Navy
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("AutoCar", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("BLUETOOTH CONTROLLER", color = Muted, fontSize = 11.sp, letterSpacing = 1.5.sp)
                }
                Box(
                    modifier = Modifier
                        .background(
                            if (status.startsWith("Connected")) Color(0xFF14532D) else Color(0xFF334155),
                            CircleShape
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        if (status.startsWith("Connected")) "CONNECTED" else "OFFLINE",
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Connection Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            if (status.startsWith("Connected")) Icons.Default.BluetoothConnected
                            else Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = Color(0xFF60A5FA)
                        )
                        Text("  Bluetooth device", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { refresh() }) {
                            androidx.compose.material3.Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                            Text(" Refresh")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box {
                        OutlinedButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                selectedDevice?.let { "${it.name ?: "Unknown"} (${it.address})" }
                                    ?: "Select a paired device",
                                maxLines = 1
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            pairedDevices.forEach { device ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(device.name ?: "Unknown device")
                                            Text(device.address, color = Muted, fontSize = 11.sp)
                                        }
                                    },
                                    onClick = {
                                        selectedDevice = device
                                        menuExpanded = false
                                    }
                                )
                            }
                            if (pairedDevices.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No paired devices. Pair BT-06 in Android Settings first.") },
                                    onClick = { menuExpanded = false }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                if (selectedDevice != null && bluetoothController != null) {
                                    bluetoothController.connect(selectedDevice!!)
                                } else status = "Select a paired BT-06 device first"
                            },
                            modifier = Modifier.weight(1f),
                            enabled = hasPermission && adapter != null && selectedDevice != null
                        ) { Text("Connect") }
                        OutlinedButton(
                            onClick = { bluetoothController?.disconnect() },
                            modifier = Modifier.weight(1f)
                        ) { Text("Disconnect") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(status, color = if (status.startsWith("Connected")) Color(0xFF86EFAC) else Muted, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Debug Mode Card ('T' = ON, 't' = OFF)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("DEBUG MODE", color = Color(0xFFC084FC), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                            Text("Sensor telemetry, thresholds & PID (T / t)", color = Muted, fontSize = 11.sp)
                        }
                        Switch(
                            checked = debugModeEnabled,
                            onCheckedChange = { isEnabled ->
                                debugModeEnabled = isEnabled
                                bluetoothController?.send(if (isEnabled) 'T' else 't')
                            }
                        )
                    }

                    if (debugModeEnabled) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = Color(0xFF334155))
                        Spacer(Modifier.height(12.dp))

                        // 1. Colour Detection & Status Section
                        Text("1. COLOUR SENSOR (TCS34725)", color = Muted, fontSize = 11.sp, letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { bluetoothController?.send('z') },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Purple)
                            ) {
                                Text("DETECT (z)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            OutlinedButton(
                                onClick = { bluetoothController?.sendLine("v") },
                                modifier = Modifier.height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Status (v)", fontSize = 13.sp)
                            }
                            OutlinedButton(
                                onClick = { debugLogs.clear() },
                                modifier = Modifier.height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Clear", fontSize = 13.sp)
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Threshold Tuning Sliders (0.00 - 1.00)
                        Text("COLOUR THRESHOLD TUNING (0.00 - 1.00)", color = Muted, fontSize = 11.sp, letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(6.dp))

                        ThresholdSlider("Red Margin (m)", "m", redMargin, Color(0xFFF87171), onValueChange = { redMargin = it }) {
                            bluetoothController?.sendLine(it)
                        }
                        ThresholdSlider("Red Min (r)", "r", redMin, Color(0xFFEF4444), onValueChange = { redMin = it }) {
                            bluetoothController?.sendLine(it)
                        }
                        ThresholdSlider("Green Min (g)", "g", greenMin, Color(0xFF4ADE80), onValueChange = { greenMin = it }) {
                            bluetoothController?.sendLine(it)
                        }
                        ThresholdSlider("Blue Min (b)", "b", blueMin, Color(0xFF60A5FA), onValueChange = { blueMin = it }) {
                            bluetoothController?.sendLine(it)
                        }
                        ThresholdSlider("Blue Max (o)", "o", blueMax, Color(0xFF38BDF8), onValueChange = { blueMax = it }) {
                            bluetoothController?.sendLine(it)
                        }
                        ThresholdSlider("RG Diff (p)", "p", rgDiff, Color(0xFFC084FC), onValueChange = { rgDiff = it }) {
                            bluetoothController?.sendLine(it)
                        }

                        Spacer(Modifier.height(12.dp))

                        // Live BT Serial Telemetry Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(TerminalBg, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            if (debugLogs.isEmpty()) {
                                Text(
                                    "Waiting for BT response... Tap DETECT, Status, or adjust sliders",
                                    color = Muted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    debugLogs.forEach { logLine ->
                                        val lineColor = when {
                                            logLine.contains("RED") -> Color(0xFFF87171)
                                            logLine.contains("GREEN") -> Color(0xFF4ADE80)
                                            logLine.contains("BLUE") -> Color(0xFF60A5FA)
                                            logLine.contains("[TUNE]") || logLine.contains("[STATUS]") -> Color(0xFF38BDF8)
                                            logLine.contains("[ERR]") || logLine.contains("[ERROR]") -> Color(0xFFFBBF24)
                                            else -> Color(0xFFE2E8F0)
                                        }
                                        Text(
                                            text = logLine,
                                            color = lineColor,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            fontWeight = if (
                                                logLine.startsWith("->") ||
                                                logLine.startsWith("[TUNE]") ||
                                                logLine.startsWith("[STATUS]")
                                            ) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // 2. PID Tuning Sliders (KP<val>; / KD<val>; / K;)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("2. PID TUNING (Kp / Kd)", color = Muted, fontSize = 11.sp, letterSpacing = 1.2.sp)
                            OutlinedButton(
                                onClick = { bluetoothController?.sendLine("K;") },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Read Gains (K;)", fontSize = 11.sp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))

                        ThresholdSlider(
                            label = "Proportional Gain (KP)",
                            prefix = "KP",
                            value = kpValue,
                            accentColor = Color(0xFFFBBF24),
                            valueRange = 0f..20f,
                            suffix = ";",
                            onValueChange = { kpValue = it }
                        ) {
                            bluetoothController?.sendLine(it)
                        }

                        ThresholdSlider(
                            label = "Derivative Gain (KD)",
                            prefix = "KD",
                            value = kdValue,
                            accentColor = Color(0xFF34D399),
                            valueRange = 0f..20f,
                            suffix = ";",
                            onValueChange = { kdValue = it }
                        ) {
                            bluetoothController?.sendLine(it)
                        }

                        Spacer(Modifier.height(10.dp))
                        Text("3. TIMING & MISSION TUNING (ms)", color = Muted, fontSize = 11.sp, letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(6.dp))

                        ThresholdSlider(
                            label = "Corner Fwd (KC)",
                            prefix = "KC",
                            value = cornerFwdMs,
                            accentColor = Color(0xFF38BDF8),
                            valueRange = 0f..2000f,
                            suffix = ";",
                            isInteger = true,
                            onValueChange = { cornerFwdMs = it }
                        ) {
                            bluetoothController?.sendLine(it)
                        }

                        ThresholdSlider(
                            label = "Branch Fwd (KJ)",
                            prefix = "KJ",
                            value = branchFwdMs,
                            accentColor = Color(0xFF818CF8),
                            valueRange = 0f..2000f,
                            suffix = ";",
                            isInteger = true,
                            onValueChange = { branchFwdMs = it }
                        ) {
                            bluetoothController?.sendLine(it)
                        }

                        ThresholdSlider(
                            label = "Mission t1Ms (KA)",
                            prefix = "KA",
                            value = t1Ms,
                            accentColor = Color(0xFFF472B6),
                            valueRange = 0f..5000f,
                            suffix = ";",
                            isInteger = true,
                            onValueChange = { t1Ms = it }
                        ) {
                            bluetoothController?.sendLine(it)
                        }

                        ThresholdSlider(
                            label = "Mission tsMs (KB)",
                            prefix = "KB",
                            value = tsMs,
                            accentColor = Color(0xFFFB923C),
                            valueRange = 0f..5000f,
                            suffix = ";",
                            isInteger = true,
                            onValueChange = { tsMs = it }
                        ) {
                            bluetoothController?.sendLine(it)
                        }

                        ThresholdSlider(
                            label = "Mission winMs (KW)",
                            prefix = "KW",
                            value = winMs,
                            accentColor = Color(0xFFA78BFA),
                            valueRange = 0f..5000f,
                            suffix = ";",
                            isInteger = true,
                            onValueChange = { winMs = it }
                        ) {
                            bluetoothController?.sendLine(it)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Speed Control Slider Card (1 - 9)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("MOTOR SPEED CONTROL", color = Muted, fontSize = 11.sp, letterSpacing = 1.3.sp)
                        Text("${speedValue.roundToInt()}%", color = Color(0xFF60A5FA), fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = speedValue,
                        onValueChange = { speedValue = it },
                        onValueChangeFinished = {
                            val speedStep = ((speedValue / 100f * 8f).roundToInt() + 1).coerceIn(1, 9)
                            bluetoothController?.send(speedStep.toString()[0])
                        },
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = Blue,
                            activeTrackColor = Blue,
                            inactiveTrackColor = Navy
                        )
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("MOVEMENT CONTROL", color = Muted, fontSize = 12.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(10.dp))

            // D-PAD
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ControlButton("W", "▲", Blue, { bluetoothController?.send('W') }, { bluetoothController?.send('X') })
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ControlButton("A", "◀", Panel, { bluetoothController?.send('A') }, { bluetoothController?.send('X') })
                    Button(
                        onClick = { bluetoothController?.send('X') },
                        modifier = Modifier.size(width = 96.dp, height = 80.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Red)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("■", fontSize = 20.sp)
                            Text("STOP", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    ControlButton("D", "▶", Panel, { bluetoothController?.send('D') }, { bluetoothController?.send('X') })
                }
                Spacer(Modifier.height(8.dp))
                ControlButton("S", "▼", Blue, { bluetoothController?.send('S') }, { bluetoothController?.send('X') })
            }

            Spacer(Modifier.height(16.dp))

            // Claw Actions (F & G)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("CLAW ACTION", color = Muted, fontSize = 11.sp, letterSpacing = 1.3.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { bluetoothController?.send('F') },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Green)
                        ) {
                            Text("GRAB (F)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Button(
                            onClick = { bluetoothController?.send('G') },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Amber)
                        ) {
                            Text("RELEASE (G)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Driving Mode Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Panel),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("DRIVING MODE", color = Muted, fontSize = 11.sp, letterSpacing = 1.3.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            bluetoothController?.send('M')
                            modeMessage = "Mission Mode (M) sent — Basic Map Loop active"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                    ) {
                        Text("↻   START MISSION (M)", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            bluetoothController?.send('N')
                            modeMessage = "Line Follow Only (N) sent"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("LINE FOLLOW ONLY (N)", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(modeMessage, color = Muted, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("LAST COMMAND", color = Muted, fontSize = 11.sp)
                Text(lastCommand, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ThresholdSlider(
    label: String,
    prefix: String,
    value: Float,
    accentColor: Color,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    suffix: String = "",
    isInteger: Boolean = false,
    onValueChange: (Float) -> Unit,
    onSendCommand: (String) -> Unit
) {
    val displayText = if (isInteger) {
        "${value.roundToInt()} ms"
    } else {
        String.format(Locale.US, "%.2f", value)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(
                displayText,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = {
                val formatted = if (isInteger) {
                    "$prefix${value.roundToInt()}$suffix"
                } else {
                    String.format(Locale.US, "%s%.2f%s", prefix, value, suffix)
                }
                onSendCommand(formatted)
            },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = Navy
            )
        )
    }
}

@Composable
private fun ControlButton(
    command: String,
    symbol: String,
    color: Color,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(width = 96.dp, height = 80.dp)
            .pointerInput(command) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        try {
                            tryAwaitRelease()
                        } finally {
                            onRelease()
                        }
                    }
                )
            },
        shape = RoundedCornerShape(18.dp),
        color = color,
        contentColor = Color.White,
        shadowElevation = 3.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(symbol, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(command, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@SuppressLint("MissingPermission")
private fun refreshPairedDevices(
    adapter: BluetoothAdapter?,
    onResult: (List<BluetoothDevice>) -> Unit
) {
    if (adapter == null) {
        onResult(emptyList())
        return
    }
    try {
        onResult(adapter.bondedDevices.sortedBy { it.name ?: it.address })
    } catch (_: SecurityException) {
        onResult(emptyList())
    }
}