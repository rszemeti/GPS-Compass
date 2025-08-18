package com.example.gpsheading

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*
import kotlin.math.*

private const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
// TODO: Set your Brainboxes MAC here:
private const val DEVICE_MAC = "00:11:22:33:44:55"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = HeadingViewModel()

        setContent {
            MaterialTheme {
                val state by vm.ui.collectAsState()

                val neededPerms = buildList {
                    if (Build.VERSION.SDK_INT >= 31) {
                        add(Manifest.permission.BLUETOOTH_CONNECT)
                        add(Manifest.permission.BLUETOOTH_SCAN)
                    } else {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }
                val launcher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { _ -> }

                LaunchedEffect(Unit) {
                    val notGranted = neededPerms.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (notGranted.isNotEmpty()) {
                        launcher.launch(notGranted.toTypedArray())
                    }
                    vm.connect(DEVICE_MAC)
                }

                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(16.dp).fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.filteredHeading?.let { "${it.format1()}°T" } ?: "--.-°T",
                            fontSize = 72.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Fix: ${state.fixText}   Used: ${state.satsUsed ?: "?"} / InView: ${state.satsInView ?: "?"}   HDOP: ${state.hdop?.format1() ?: "?"}",
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("FIR: ")
                            Slider(
                                value = state.firWindow.toFloat(),
                                onValueChange = { vm.setFIR(it.roundToInt().coerceIn(1, 300)) },
                                valueRange = 1f..300f, steps = 298, modifier = Modifier.width(220.dp)
                            )
                            Text(state.firWindow.toString(), modifier = Modifier.padding(start = 8.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Offset (°): ")
                            Slider(
                                value = state.offsetDeg.toFloat(),
                                onValueChange = { vm.setOffset(it.toDouble()) },
                                valueRange = -180f..180f, modifier = Modifier.width(260.dp)
                            )
                            Text(state.offsetDeg.format1())
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "raw: ${state.rawHeading?.format1() ?: "--.-"}°T   UTC: ${state.utc ?: "--:--:--"}   Alt: ${state.altM?.format1() ?: "?"} m",
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.reconnect(DEVICE_MAC) }) { Text("Reconnect") }
                    }
                }
            }
        }
    }
}

data class UiState(
    val filteredHeading: Double? = null,
    val rawHeading: Double? = null,
    val fixText: String = "No fix",
    val satsUsed: Int? = null,
    val satsInView: Int? = null,
    val hdop: Double? = null,
    val altM: Double? = null,
    val utc: String? = null,
    val firWindow: Int = 15,
    val offsetDeg: Double = 0.0,
)

class HeadingViewModel : ViewModel() {
    private val _uiState = mutableStateOf(UiState())
    val ui: State<UiState> get() = _uiState

    private val fir = HeadingFIR(window = 15)
    private var socket: BluetoothSocket? = null
    private var readJob: Job? = null

    fun setFIR(n: Int) {
        fir.setWindow(n)
        _uiState.value = _uiState.value.copy(firWindow = n)
    }

    fun setOffset(deg: Double) {
        _uiState.value = _uiState.value.copy(offsetDeg = deg)
    }

    fun reconnect(mac: String) {
        disconnect()
        connect(mac)
    }

    fun connect(mac: String) {
        readJob?.cancel()
        readJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val bt = BluetoothAdapter.getDefaultAdapter() ?: return@launch
                val dev: BluetoothDevice = bt.getRemoteDevice(mac)
                val sock = dev.createRfcommSocketToServiceRecord(UUID.fromString(SPP_UUID))
                socket = sock
                BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
                sock.connect()

                val reader = BufferedReader(InputStreamReader(sock.inputStream))
                var lastPush = 0L
                val parser = NMEAParser()

                while (isActive) {
                    val line = reader.readLine() ?: break
                    parser.handle(line)

                    parser.headingRaw?.let { hdg -> fir.add(hdg) }

                    val now = System.currentTimeMillis()
                    if (now - lastPush > 200) {
                        val filt = fir.value()?.let { norm360(it + _uiState.value.offsetDeg) }
                        val state = UiState(
                            filteredHeading = filt,
                            rawHeading = parser.headingRaw,
                            fixText = parser.fixText(),
                            satsUsed = parser.satsUsed,
                            satsInView = parser.satsInView,
                            hdop = parser.hdop,
                            altM = parser.altM,
                            utc = parser.utc,
                            firWindow = _uiState.value.firWindow,
                            offsetDeg = _uiState.value.offsetDeg
                        )
                        withContext(Dispatchers.Main) { _uiState.value = state }
                        lastPush = now
                    }
                }
            } catch (_: Exception) {
            } finally {
                disconnect()
            }
        }
    }

    private fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}

class NMEAParser {
    var headingRaw: Double? = null
    var satsUsed: Int? = null
    var satsInView: Int? = null
    var hdop: Double? = null
    var altM: Double? = null
    var utc: String? = null
    private var fixQuality: Int = 0

    fun handle(sentence: String) {
        if (!sentence.startsWith("$")) return
        val star = sentence.indexOf('*')
        val body = if (star >= 0) sentence.substring(1, star) else sentence.substring(1)
        val parts = body.split(',')
        if (parts.isEmpty() || parts[0].length < 5) return
        val type = parts[0].substring(2)

        when (type) {
            "HDT" -> parts.getOrNull(1)?.toDoubleOrNull()?.let { headingRaw = norm360(it) }
            "GGA" -> {
                fixQuality = parts.getOrNull(6)?.toIntOrNull() ?: 0
                parts.getOrNull(7)?.toIntOrNull()?.let { satsUsed = it }
                parts.getOrNull(8)?.toDoubleOrNull()?.let { hdop = it }
                parts.getOrNull(9)?.toDoubleOrNull()?.let { altM = it }
                val t = parts.getOrNull(1)
                if (!t.isNullOrBlank() && t.length >= 6) {
                    utc = "${t.substring(0,2)}:${t.substring(2,4)}:${t.substring(4,6)}"
                }
            }
            "GSA" -> {
                val used = parts.subList(3, min(15, parts.size)).count { it.isNotBlank() }
                if (used > 0) satsUsed = used
                parts.getOrNull(15)?.toDoubleOrNull()?.let { hdop = it }
            }
            "GSV" -> parts.getOrNull(3)?.toIntOrNull()?.let { satsInView = it }
            "ZDA" -> {
                val t = parts.getOrNull(1)
                if (!t.isNullOrBlank() && t.length >= 6) {
                    utc = "${t.substring(0,2)}:${t.substring(2,4)}:${t.substring(4,6)}"
                }
            }
        }
    }

    fun fixText(): String = when (fixQuality) {
        0 -> "No fix"
        1 -> "GPS"
        2 -> "DGPS"
        4 -> "RTK Fixed"
        5 -> "RTK Float"
        6 -> "Dead reckoning"
        else -> fixQuality.toString()
    }
}

class HeadingFIR(window: Int) {
    private var window = max(1, window)
    private val angles = ArrayDeque<Double>()
    private var sumSin = 0.0
    private var sumCos = 0.0

    fun setWindow(n: Int) {
        window = max(1, n)
        while (angles.size > window) {
            val old = angles.removeFirst()
            sumSin -= sin(old)
            sumCos -= cos(old)
        }
    }

    fun add(deg: Double) {
        val rad = Math.toRadians(norm360(deg))
        angles.addLast(rad)
        sumSin += sin(rad)
        sumCos += cos(rad)
        if (angles.size > window) {
            val old = angles.removeFirst()
            sumSin -= sin(old)
            sumCos -= cos(old)
        }
    }

    fun value(): Double? {
        if (angles.isEmpty()) return null
        val mean = Math.toDegrees(atan2(sumSin, sumCos))
        return norm360(mean)
    }
}

fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
fun Double.format1(): String = "%,.1f".format(this)
