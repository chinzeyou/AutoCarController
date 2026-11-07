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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlin.math.roundToInt

private val Navy = Color(0xFF101827)
private val Panel = Color(0xFF1E293B)
private val Blue = Color(0xFF2563EB)
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
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Blue,
                background = Navy,
                surface = Panel,
                onBackground = Color.White,
                onSurface = Color.White
            )) {
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

/**
 * Bluetooth Controller implementation handling BT-06 RFCOMM Socket & Fallback
 */
class BluetoothController(
    private val adapter: BluetoothAdapter,
    private val onStatus: (String) -> Unit,
    private val onCommand: (String) -> Unit
) {
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-0000805F9B34")
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val deviceName = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
        onStatus("Connecting to $deviceName...")

        Thread {
            try {
                try { adapter.cancelDiscovery() } catch (_: SecurityException) {}
                closeSocket()

                // Try standard SPP connection, fallback to reflection channel 1 on handshake timeout
                try {
                    socket = device.createRfcommSocketToServiceRecord(sppUuid)
                    socket?.connect()
                } catch (e: IOException) {
                    val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    socket = method.invoke(device, 1) as BluetoothSocket
                    socket?.connect()
                }

                outputStream = socket?.outputStream

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

    fun disconnect() {
        closeSocket()
        onStatus("Disconnected")
    }

    fun shutdown() {
        closeSocket()
    }

    private fun closeSocket() {
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
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
            onCommand = { lastCommand = it }
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

            // Speed Control Slider Card
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
                            val speedStep = (speedValue / 100f * 9f).roundToInt()
                            bluetoothController?.send('V')
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
                            modeMessage = "Mode command M sent — check the car's mode indicator"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                    ) {
                        Text("↻   SWITCH MODE (M)", fontWeight = FontWeight.Bold)
                    }
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