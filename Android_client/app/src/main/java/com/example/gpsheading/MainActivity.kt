package com.example.gpsheading

import android.Manifest
import android.content.SharedPreferences
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*
import kotlin.math.*

private const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

private enum class AppScreen {
    MAIN,
    ROVER,
    SETTINGS,
    DEBUG,
}

enum class ConnectionStage {
    WAITING_FOR_PERMISSION,
    WAITING_FOR_DEVICE,
    DEVICE_NOT_FOUND,
    DEVICE_FOUND,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    CONNECTION_FAILED,
    PERMISSION_DENIED,
    BLUETOOTH_UNAVAILABLE,
}

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val vm: HeadingViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return HeadingViewModel(
                    getSharedPreferences("gps_heading", MODE_PRIVATE)
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val state by vm.ui
                var menuExpanded by rememberSaveable { mutableStateOf(false) }
                var currentScreen by rememberSaveable { mutableStateOf(AppScreen.MAIN) }

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
                ) { result ->
                    val denied = neededPerms.filter { result[it] != true }
                    if (denied.isEmpty()) {
                        vm.onBluetoothPermissionsGranted()
                    } else {
                        vm.onBluetoothPermissionsDenied()
                    }
                }

                LaunchedEffect(Unit) {
                    val notGranted = neededPerms.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (notGranted.isNotEmpty()) {
                        launcher.launch(notGranted.toTypedArray())
                    } else {
                        vm.onBluetoothPermissionsGranted()
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    when (currentScreen) {
                                        AppScreen.MAIN -> "GPS Compass"
                                        AppScreen.ROVER -> "Rover"
                                        AppScreen.SETTINGS -> "Settings"
                                        AppScreen.DEBUG -> "Debug"
                                    }
                                )
                            },
                            actions = {
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(Icons.Filled.Menu, contentDescription = "Open menu")
                                    }
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Main") },
                                            onClick = {
                                                currentScreen = AppScreen.MAIN
                                                menuExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Settings") },
                                            onClick = {
                                                currentScreen = AppScreen.SETTINGS
                                                menuExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Rover") },
                                            onClick = {
                                                currentScreen = AppScreen.ROVER
                                                menuExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Debug") },
                                            onClick = {
                                                currentScreen = AppScreen.DEBUG
                                                menuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    when (currentScreen) {
                        AppScreen.MAIN -> MainScreenContent(
                            state = state,
                            onConnect = { state.selectedDeviceMac?.let(vm::reconnect) },
                            modifier = Modifier.padding(innerPadding)
                        )
                        AppScreen.SETTINGS -> SettingsScreenContent(
                            state = state,
                            onSelectDevice = vm::selectDevice,
                            onReconnect = { state.selectedDeviceMac?.let(vm::reconnect) },
                            onRefreshDevices = vm::onBluetoothPermissionsGranted,
                            onFIRChange = { vm.setFIR(it.roundToInt().coerceIn(1, 300)) },
                            onOffsetChange = { vm.setOffset(it.toDouble()) },
                            modifier = Modifier.padding(innerPadding)
                        )
                        AppScreen.ROVER -> RoverScreenContent(
                            state = state,
                            onSaveHomeLocator = vm::setHomeStationMaidenhead,
                            modifier = Modifier.padding(innerPadding)
                        )
                        AppScreen.DEBUG -> DebugScreenContent(
                            state = state,
                            onToggleDebug = vm::setDebugEnabled,
                            onClearDebug = vm::clearDebugStream,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }
}

data class BluetoothTarget(
    val name: String,
    val mac: String,
)

data class UiState(
    val filteredHeading: Double? = null,
    val rawHeading: Double? = null,
    val fixText: String = "No fix",
    val maidenhead: String? = null,
    val currentLatitude: Double? = null,
    val currentLongitude: Double? = null,
    val homeStationMaidenhead: String? = null,
    val roverTargetBearingDeg: Double? = null,
    val roverDistanceKm: Double? = null,
    val satsUsed: Int? = null,
    val satsInView: Int? = null,
    val hdop: Double? = null,
    val altM: Double? = null,
    val utc: String? = null,
    val firWindow: Int = 15,
    val offsetDeg: Double = 0.0,
    val availableDevices: List<BluetoothTarget> = emptyList(),
    val selectedDeviceMac: String? = null,
    val selectedDeviceName: String? = null,
    val connectionStatus: String = "Waiting for Bluetooth permission",
    val connectionStage: ConnectionStage = ConnectionStage.WAITING_FOR_PERMISSION,
    val isConnected: Boolean = false,
    val debugEnabled: Boolean = false,
    val debugStreamText: String = "",
)

class HeadingViewModel(
    private val prefs: SharedPreferences,
) : ViewModel() {
    private val _uiState = mutableStateOf(
        UiState(
            selectedDeviceMac = prefs.getString(KEY_DEVICE_MAC, null),
            selectedDeviceName = prefs.getString(KEY_DEVICE_NAME, null),
            homeStationMaidenhead = prefs.getString(KEY_HOME_MAIDENHEAD, null),
        )
    )
    val ui: State<UiState> get() = _uiState

    private val fir = HeadingFIR(window = 15)
    private val debugLines = ArrayDeque<String>()
    private val debugLock = Any()
    private var socket: BluetoothSocket? = null
    private var readJob: Job? = null
    private var reconnectJob: Job? = null
    private var readWatchdogJob: Job? = null
    @Volatile private var lastDataReceivedAt: Long = 0L
    private var consecutiveConnectFailures: Int = 0

    companion object {
        private const val MAX_DEBUG_LINES = 250
        private const val AUTO_RECONNECT_DELAY_MS = 3000L
        private const val CONNECT_FAILURE_RETRY_BASE_MS = 8000L
        private const val CONNECT_FAILURE_RETRY_MAX_MS = 30000L
        private const val READ_STALL_TIMEOUT_MS = 5000L
        private const val TAG = "GPSHeading"
        private const val KEY_DEVICE_MAC = "selected_device_mac"
        private const val KEY_DEVICE_NAME = "selected_device_name"
        private const val KEY_HOME_MAIDENHEAD = "home_station_maidenhead"
    }

    fun setFIR(n: Int) {
        fir.setWindow(n)
        _uiState.value = _uiState.value.copy(firWindow = n)
    }

    fun setOffset(deg: Double) {
        _uiState.value = _uiState.value.copy(offsetDeg = deg)
    }

    fun setHomeStationMaidenhead(locator: String) {
        val normalized = locator.trim().uppercase(Locale.US).take(8)
        if (normalized.isBlank()) {
            prefs.edit().remove(KEY_HOME_MAIDENHEAD).apply()
            _uiState.value = recomputeRoverState(
                _uiState.value.copy(homeStationMaidenhead = null)
            )
            return
        }

        if (!isValidMaidenhead(normalized)) {
            return
        }

        prefs.edit().putString(KEY_HOME_MAIDENHEAD, normalized).apply()
        _uiState.value = recomputeRoverState(
            _uiState.value.copy(homeStationMaidenhead = normalized)
        )
    }

    fun reconnect(mac: String) {
        Log.d(TAG, "Reconnect requested for $mac")
        cancelPendingReconnect()
        disconnect()
        connect(mac)
    }

    fun setDebugEnabled(enabled: Boolean) {
        Log.d(TAG, "Debug raw stream ${if (enabled) "enabled" else "disabled"}")
        _uiState.value = _uiState.value.copy(
            debugEnabled = enabled,
            debugStreamText = if (enabled) snapshotDebugStream() else _uiState.value.debugStreamText
        )
    }

    fun clearDebugStream() {
        Log.d(TAG, "Clearing debug stream buffer")
        synchronized(debugLock) {
            debugLines.clear()
        }
        _uiState.value = _uiState.value.copy(debugStreamText = "")
    }

    fun selectDevice(device: BluetoothTarget) {
        Log.d(TAG, "Selected Bluetooth device ${device.name} / ${device.mac}")
        cancelPendingReconnect()
        prefs.edit()
            .putString(KEY_DEVICE_MAC, device.mac)
            .putString(KEY_DEVICE_NAME, device.name)
            .apply()
        _uiState.value = _uiState.value.copy(
            selectedDeviceMac = device.mac,
            selectedDeviceName = device.name,
            connectionStatus = "Selected device ${device.name}",
            connectionStage = ConnectionStage.DEVICE_FOUND,
        )
    }

    fun onBluetoothPermissionsGranted() {
        val bt = BluetoothAdapter.getDefaultAdapter()
        if (bt == null) {
            Log.w(TAG, "Bluetooth adapter is not available on this device")
            _uiState.value = _uiState.value.copy(
                connectionStatus = "Bluetooth is not available on this device",
                connectionStage = ConnectionStage.BLUETOOTH_UNAVAILABLE,
                isConnected = false
            )
            return
        }

        val devices = bt.bondedDevices
            ?.sortedBy { it.name ?: it.address }
            ?.map {
                BluetoothTarget(
                    name = it.name ?: "Unknown device",
                    mac = it.address
                )
            }
            .orEmpty()

        Log.d(TAG, "Bluetooth permissions granted; bonded devices=${devices.joinToString { "${it.name}/${it.mac}" }}")

        val current = _uiState.value
        val matchedSavedDevice = when {
            current.selectedDeviceMac != null -> devices.firstOrNull { it.mac == current.selectedDeviceMac }
            current.selectedDeviceName != null -> devices.firstOrNull { it.name == current.selectedDeviceName }
            else -> null
        }

        val connectionStage = when {
            devices.isEmpty() -> ConnectionStage.DEVICE_NOT_FOUND
            matchedSavedDevice != null -> ConnectionStage.DEVICE_FOUND
            current.selectedDeviceName != null || current.selectedDeviceMac != null -> ConnectionStage.DEVICE_NOT_FOUND
            else -> ConnectionStage.WAITING_FOR_DEVICE
        }

        val connectionStatus = when {
            devices.isEmpty() -> "No paired Bluetooth devices found"
            matchedSavedDevice != null -> "Saved device ${matchedSavedDevice.name} found"
            current.selectedDeviceName != null -> "Saved device ${current.selectedDeviceName} not found"
            else -> "Select a paired device in Settings"
        }

        _uiState.value = _uiState.value.copy(
            availableDevices = devices,
            selectedDeviceMac = matchedSavedDevice?.mac ?: current.selectedDeviceMac,
            selectedDeviceName = matchedSavedDevice?.name ?: current.selectedDeviceName,
            connectionStatus = connectionStatus,
            connectionStage = connectionStage,
            isConnected = false
        )

        if (matchedSavedDevice != null && !current.isConnected && current.connectionStage != ConnectionStage.CONNECTING && reconnectJob?.isActive != true) {
            reconnect(matchedSavedDevice.mac)
        }
    }

    fun onBluetoothPermissionsDenied() {
        Log.w(TAG, "Bluetooth permissions denied")
        _uiState.value = _uiState.value.copy(
            connectionStatus = "Bluetooth permission denied",
            connectionStage = ConnectionStage.PERMISSION_DENIED,
            isConnected = false
        )
    }

    fun connect(mac: String) {
        if (mac.isBlank()) {
            Log.w(TAG, "Connect requested with a blank MAC address")
            _uiState.value = _uiState.value.copy(
                connectionStatus = "No Bluetooth device selected",
                connectionStage = ConnectionStage.WAITING_FOR_DEVICE,
                isConnected = false
            )
            return
        }

        cancelPendingReconnect()
        readJob?.cancel()
        readJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting Bluetooth connect flow for $mac")
                val bt = BluetoothAdapter.getDefaultAdapter() ?: return@launch
                val dev: BluetoothDevice = bt.getRemoteDevice(mac)
                Log.d(TAG, "Resolved remote device ${dev.name ?: "Unknown"} / ${dev.address}, bondState=${dev.bondState}")
                if (dev.bondState != BluetoothDevice.BOND_BONDED) {
                    Log.w(TAG, "Device ${dev.address} is not bonded; refusing SPP connect")
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            connectionStatus = "Device is not paired in Android Bluetooth settings",
                            connectionStage = ConnectionStage.DEVICE_NOT_FOUND,
                            isConnected = false,
                            selectedDeviceMac = mac,
                            selectedDeviceName = dev.name,
                        )
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        connectionStatus = "Connecting to ${dev.name ?: mac}...",
                        connectionStage = ConnectionStage.CONNECTING,
                        isConnected = false,
                        selectedDeviceMac = mac,
                        selectedDeviceName = dev.name,
                    )
                }

                Log.d(TAG, "Cancelling discovery before SPP connection")
                bt.cancelDiscovery()
                val uuid = UUID.fromString(SPP_UUID)
                val sock = runCatching {
                    Log.d(TAG, "Trying secure RFCOMM socket to ${dev.address}")
                    dev.createRfcommSocketToServiceRecord(uuid).also { it.connect() }
                }.recoverCatching {
                    Log.w(TAG, "Secure RFCOMM failed for ${dev.address}; retrying insecure socket", it)
                    dev.createInsecureRfcommSocketToServiceRecord(uuid).also { it.connect() }
                }.getOrElse { throw it }
                socket = sock
                consecutiveConnectFailures = 0
                Log.d(TAG, "Bluetooth socket connected to ${dev.name ?: mac}")
                startReadWatchdog(mac, dev.name)

                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        connectionStatus = "Connected to ${dev.name ?: mac}; waiting for data...",
                        connectionStage = ConnectionStage.CONNECTED,
                        isConnected = true,
                        selectedDeviceMac = mac,
                        selectedDeviceName = dev.name,
                    )
                }

                val reader = BufferedReader(InputStreamReader(sock.inputStream))
                var lastPush = 0L
                val parser = NMEAParser()

                while (isActive) {
                    val line = reader.readLine() ?: break
                    lastDataReceivedAt = System.currentTimeMillis()
                    parser.handle(line)

                    parser.headingRaw?.let { hdg -> fir.add(hdg) }

                    val now = System.currentTimeMillis()
                    appendDebugLine(line)
                    if (now - lastPush > 200) {
                        val filt = fir.value()?.let { norm360(it + _uiState.value.offsetDeg) }
                        val current = _uiState.value
                        val state = recomputeRoverState(current.copy(
                            filteredHeading = filt,
                            rawHeading = parser.headingRaw,
                            fixText = parser.fixText(),
                            maidenhead = parser.maidenhead,
                            currentLatitude = parser.latitude,
                            currentLongitude = parser.longitude,
                            satsUsed = parser.satsUsed,
                            satsInView = parser.satsInView,
                            hdop = parser.hdop,
                            altM = parser.altM,
                            utc = parser.utc,
                            firWindow = current.firWindow,
                            offsetDeg = current.offsetDeg,
                            debugEnabled = current.debugEnabled,
                            debugStreamText = if (current.debugEnabled) snapshotDebugStream() else current.debugStreamText,
                            connectionStatus = "Connected to ${dev.name ?: mac}",
                            connectionStage = ConnectionStage.CONNECTED,
                            isConnected = true,
                            selectedDeviceMac = mac,
                            selectedDeviceName = dev.name,
                        ))
                        withContext(Dispatchers.Main) { _uiState.value = state }
                        lastPush = now
                    }
                }
                Log.w(TAG, "Bluetooth stream ended for ${dev.name ?: mac}")
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        connectionStatus = "Disconnected from ${dev.name ?: mac}",
                        connectionStage = ConnectionStage.DISCONNECTED,
                        isConnected = false
                    )
                }
                scheduleReconnect(mac, dev.name, AUTO_RECONNECT_DELAY_MS, ConnectionStage.DISCONNECTED)
            } catch (e: Exception) {
                consecutiveConnectFailures += 1
                val retryDelayMs = nextConnectFailureRetryDelayMs()
                Log.e(TAG, "Bluetooth connection failed for $mac", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        connectionStatus = "Device found, but socket connect failed. Retrying in ${retryDelayMs / 1000}s",
                        connectionStage = ConnectionStage.DEVICE_FOUND,
                        isConnected = false,
                        selectedDeviceMac = mac
                    )
                }
                scheduleReconnect(mac, _uiState.value.selectedDeviceName, retryDelayMs, ConnectionStage.DEVICE_FOUND)
            } finally {
                readJob = null
                stopReadWatchdog()
                disconnect()
            }
        }
    }

    private fun startReadWatchdog(mac: String, deviceName: String?) {
        stopReadWatchdog()
        lastDataReceivedAt = System.currentTimeMillis()
        val label = deviceName ?: _uiState.value.selectedDeviceName ?: mac
        readWatchdogJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(READ_STALL_TIMEOUT_MS)
                val idleFor = System.currentTimeMillis() - lastDataReceivedAt
                if (socket != null && idleFor >= READ_STALL_TIMEOUT_MS) {
                    Log.w(TAG, "No data received from $label for ${idleFor}ms; closing socket to force reconnect")
                    try {
                        socket?.close()
                    } catch (_: Exception) {
                    }
                    break
                }
            }
        }
    }

    private fun stopReadWatchdog() {
        readWatchdogJob?.cancel()
        readWatchdogJob = null
    }

    private fun scheduleReconnect(
        mac: String,
        deviceName: String?,
        delayMs: Long,
        waitingStage: ConnectionStage,
    ) {
        if (mac.isBlank()) {
            return
        }
        if (reconnectJob?.isActive == true) {
            return
        }

        val label = deviceName ?: _uiState.value.selectedDeviceName ?: mac
        Log.d(TAG, "Scheduling auto reconnect for $label / $mac in ${delayMs}ms")
        reconnectJob = viewModelScope.launch {
            val current = _uiState.value
            if (!current.isConnected && current.selectedDeviceMac == mac) {
                _uiState.value = current.copy(
                    connectionStatus = "Retrying connection to $label in ${delayMs / 1000}s...",
                    connectionStage = waitingStage,
                    isConnected = false,
                )
            }

            delay(delayMs)
            reconnectJob = null

            val latest = _uiState.value
            if (!latest.isConnected && latest.selectedDeviceMac == mac && latest.connectionStage != ConnectionStage.CONNECTING) {
                Log.d(TAG, "Auto reconnect timer fired for $label / $mac")
                connect(mac)
            }
        }
    }

    private fun nextConnectFailureRetryDelayMs(): Long {
        val exponent = (consecutiveConnectFailures - 1).coerceAtLeast(0)
        val multiplier = 1L shl exponent.coerceAtMost(3)
        return (CONNECT_FAILURE_RETRY_BASE_MS * multiplier).coerceAtMost(CONNECT_FAILURE_RETRY_MAX_MS)
    }

    private fun cancelPendingReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun disconnect() {
        Log.d(TAG, "Disconnecting Bluetooth socket")
        stopReadWatchdog()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun appendDebugLine(line: String) {
        if (_uiState.value.debugEnabled) {
            Log.d(TAG, "RAW $line")
        }
        synchronized(debugLock) {
            debugLines.addLast(line)
            while (debugLines.size > MAX_DEBUG_LINES) {
                debugLines.removeFirst()
            }
        }
    }

    private fun snapshotDebugStream(): String = synchronized(debugLock) {
        debugLines.joinToString(separator = "\n")
    }
}

@Composable
private fun MainScreenContent(
    state: UiState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = state.filteredHeading?.let { "${it.format1()}°T" } ?: "--.-°T",
            fontSize = 72.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        ConnectionStateCard(state = state)
        Spacer(Modifier.height(16.dp))
        Text(
            text = state.selectedDeviceName?.let { "Device: $it" } ?: "No device selected",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.maidenhead?.let { locator ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Locator: $locator",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
        if (!state.isConnected) {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConnect,
                enabled = state.selectedDeviceMac != null,
            ) {
                Text("Connect")
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Fix: ${state.fixText}   Used: ${state.satsUsed ?: "?"} / InView: ${state.satsInView ?: "?"}",
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "UTC: ${state.utc ?: "--:--:--"}   Alt: ${state.altM?.format1() ?: "?"} m   HDOP: ${state.hdop?.format1() ?: "?"}",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RoverScreenContent(
    state: UiState,
    onSaveHomeLocator: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editDialogOpen by rememberSaveable { mutableStateOf(false) }
    var homeLocatorInput by rememberSaveable(state.homeStationMaidenhead) {
        mutableStateOf(state.homeStationMaidenhead.orEmpty())
    }
    val normalizedInput = homeLocatorInput.trim().uppercase(Locale.US).take(8)
    val canSave = normalizedInput.isEmpty() || isValidMaidenhead(normalizedInput)

    if (editDialogOpen) {
        AlertDialog(
            onDismissRequest = { editDialogOpen = false },
            title = { Text("Home station") },
            text = {
                OutlinedTextField(
                    value = homeLocatorInput,
                    onValueChange = { updated ->
                        homeLocatorInput = updated.filter { it.isLetterOrDigit() }.take(8)
                    },
                    label = { Text("Maidenhead locator") },
                    supportingText = { Text("Enter an 8-character locator such as IO91WM13") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveHomeLocator(normalizedInput)
                        editDialogOpen = false
                    },
                    enabled = canSave,
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.homeStationMaidenhead != null) {
                        TextButton(
                            onClick = {
                                homeLocatorInput = ""
                                onSaveHomeLocator("")
                                editDialogOpen = false
                            }
                        ) {
                            Text("Clear")
                        }
                    }
                    TextButton(onClick = { editDialogOpen = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ConnectionStateCard(state = state)
        Spacer(Modifier.height(20.dp))
        RoverValueCard(
            title = "Current locator",
            value = state.maidenhead ?: "Waiting for position fix"
        )
        Spacer(Modifier.height(12.dp))
        RoverValueCard(
            title = "Current heading",
            value = state.filteredHeading?.let { "${it.format1()}°T" } ?: "Waiting for heading"
        )
        Spacer(Modifier.height(12.dp))
        RoverValueCard(
            title = "Point antenna to",
            value = state.roverTargetBearingDeg?.let { "${it.format1()}°T" }
                ?: if (state.homeStationMaidenhead == null) "Enter home locator" else "Waiting for position fix"
        )
        Spacer(Modifier.height(12.dp))
        RoverValueCard(
            title = "Distance to home",
            value = state.roverDistanceKm?.let(::formatDistanceToHome)
                ?: if (state.homeStationMaidenhead == null) "Enter home locator" else "Waiting for position fix"
        )
        Spacer(Modifier.height(12.dp))
        RoverValueCard(
            title = "Home station",
            value = state.homeStationMaidenhead ?: "Not set",
            subtitle = "Tap to edit",
            onClick = { editDialogOpen = true }
        )
    }
}

@Composable
private fun RoverValueCard(
    title: String,
    value: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = value)
            subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreenContent(
    state: UiState,
    onSelectDevice: (BluetoothTarget) -> Unit,
    onReconnect: () -> Unit,
    onRefreshDevices: () -> Unit,
    onFIRChange: (Float) -> Unit,
    onOffsetChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ConnectionStateCard(state = state)
        Spacer(Modifier.height(16.dp))
        Text(
            text = state.selectedDeviceName?.let { "Saved device: $it" } ?: "No saved device yet",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onReconnect,
                enabled = state.selectedDeviceMac != null,
                modifier = Modifier.weight(1f)
            ) {
                Text("Reconnect")
            }
            OutlinedButton(
                onClick = onRefreshDevices,
                modifier = Modifier.weight(1f)
            ) {
                Text("Refresh")
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Paired Bluetooth devices",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        if (state.availableDevices.isEmpty()) {
            Text(
                text = "No paired serial adapters found yet. Pair the device in Android Bluetooth settings first.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.availableDevices.forEach { device ->
                val isSelected = device.mac == state.selectedDeviceMac
                if (isSelected) {
                    Button(
                        onClick = { onSelectDevice(device) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DeviceButtonContent(device = device)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelectDevice(device) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DeviceButtonContent(device = device)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "FIR window",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = state.firWindow.toFloat(),
                onValueChange = onFIRChange,
                valueRange = 1f..300f,
                steps = 298,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(state.firWindow.toString())
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Heading offset",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = state.offsetDeg.toFloat(),
                onValueChange = onOffsetChange,
                valueRange = -180f..180f,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(state.offsetDeg.format1())
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Android may show 'An app is needed to use this device' for Bluetooth serial adapters. That is normal after pairing; use this app to connect over SPP.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DebugScreenContent(
    state: UiState,
    onToggleDebug: (Boolean) -> Unit,
    onClearDebug: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        ConnectionStateCard(state = state)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Debug raw stream")
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = state.debugEnabled,
                    onCheckedChange = onToggleDebug,
                )
            }
            TextButton(onClick = onClearDebug) {
                Text("Clear")
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxSize(),
            tonalElevation = 1.dp,
            shape = MaterialTheme.shapes.medium,
        ) {
            val debugScroll = rememberScrollState()
            Text(
                text = if (state.debugStreamText.isBlank()) "Waiting for incoming stream data..." else state.debugStreamText,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .verticalScroll(debugScroll),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun DeviceButtonContent(device: BluetoothTarget) {
    Column(Modifier.fillMaxWidth()) {
        Text(device.name)
        Text(
            text = device.mac,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ConnectionStateCard(state: UiState) {
    val containerColor = when (state.connectionStage) {
        ConnectionStage.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
        ConnectionStage.CONNECTING, ConnectionStage.DEVICE_FOUND -> MaterialTheme.colorScheme.secondaryContainer
        ConnectionStage.DEVICE_NOT_FOUND,
        ConnectionStage.CONNECTION_FAILED,
        ConnectionStage.PERMISSION_DENIED,
        ConnectionStage.BLUETOOTH_UNAVAILABLE -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = state.connectionStage.displayName(),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.connectionStatus,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun ConnectionStage.displayName(): String = when (this) {
    ConnectionStage.WAITING_FOR_PERMISSION -> "Waiting for permission"
    ConnectionStage.WAITING_FOR_DEVICE -> "Select device"
    ConnectionStage.DEVICE_NOT_FOUND -> "Device not found"
    ConnectionStage.DEVICE_FOUND -> "Device found"
    ConnectionStage.CONNECTING -> "Connecting"
    ConnectionStage.CONNECTED -> "Connection established"
    ConnectionStage.DISCONNECTED -> "Disconnected"
    ConnectionStage.CONNECTION_FAILED -> "Connection failed"
    ConnectionStage.PERMISSION_DENIED -> "Permission denied"
    ConnectionStage.BLUETOOTH_UNAVAILABLE -> "Bluetooth unavailable"
}

class NMEAParser {
    var headingRaw: Double? = null
    var maidenhead: String? = null
    var latitude: Double? = null
    var longitude: Double? = null
    var satsUsed: Int? = null
    var satsInView: Int? = null
    var hdop: Double? = null
    var altM: Double? = null
    var utc: String? = null
    private var fixQuality: Int = 0
    private val satsInViewByTalker = mutableMapOf<String, Int>()

    fun handle(sentence: String) {
        if (!sentence.startsWith("$")) return
        val star = sentence.indexOf('*')
        val body = if (star >= 0) sentence.substring(1, star) else sentence.substring(1)
        val parts = body.split(',')
        if (parts.isEmpty() || parts[0].length < 5) return
        val talker = parts[0].substring(0, 2)
        val type = parts[0].substring(2)

        when (type) {
            "HDT" -> parts.getOrNull(1)?.toDoubleOrNull()?.let { headingRaw = norm360(it) }
            "TMTAR" -> if (talker == "PQ") {
                parts.getOrNull(8)?.toDoubleOrNull()?.let { headingRaw = norm360(it) }
            }
            "GGA" -> {
                fixQuality = parts.getOrNull(6)?.toIntOrNull() ?: 0
                val latitude = parseNmeaCoordinate(
                    value = parts.getOrNull(2),
                    hemisphere = parts.getOrNull(3),
                    degreeDigits = 2,
                )
                val longitude = parseNmeaCoordinate(
                    value = parts.getOrNull(4),
                    hemisphere = parts.getOrNull(5),
                    degreeDigits = 3,
                )
                this.latitude = if (fixQuality > 0) latitude else null
                this.longitude = if (fixQuality > 0) longitude else null
                maidenhead = if (fixQuality > 0 && latitude != null && longitude != null) {
                    toMaidenhead(latitude, longitude)
                } else {
                    null
                }
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
            "GSV" -> parts.getOrNull(3)?.toIntOrNull()?.let { inView ->
                satsInViewByTalker[talker] = inView
                satsInView = satsInViewByTalker["GN"] ?: satsInViewByTalker.values.sum()
            }
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

private fun recomputeRoverState(base: UiState): UiState {
    val home = base.homeStationMaidenhead?.takeIf(::isValidMaidenhead)
    val homeCoordinate = home?.let(::maidenheadCenter)
    val targetBearing = if (base.currentLatitude != null && base.currentLongitude != null && homeCoordinate != null) {
        initialBearing(
            fromLatitude = base.currentLatitude,
            fromLongitude = base.currentLongitude,
            toLatitude = homeCoordinate.first,
            toLongitude = homeCoordinate.second,
        )
    } else {
        null
    }
    val distanceKm = if (base.currentLatitude != null && base.currentLongitude != null && homeCoordinate != null) {
        distanceKm(
            fromLatitude = base.currentLatitude,
            fromLongitude = base.currentLongitude,
            toLatitude = homeCoordinate.first,
            toLongitude = homeCoordinate.second,
        )
    } else {
        null
    }

    return base.copy(
        roverTargetBearingDeg = targetBearing,
        roverDistanceKm = distanceKm,
    )
}

private fun formatDistanceToHome(distanceKm: Double): String {
    return if (distanceKm < 1.0) {
        "${(distanceKm * 1000.0).format1()} m"
    } else {
        "${distanceKm.format1()} km"
    }
}

private fun parseNmeaCoordinate(
    value: String?,
    hemisphere: String?,
    degreeDigits: Int,
): Double? {
    if (value.isNullOrBlank() || hemisphere.isNullOrBlank() || value.length <= degreeDigits) {
        return null
    }

    val degrees = value.substring(0, degreeDigits).toDoubleOrNull() ?: return null
    val minutes = value.substring(degreeDigits).toDoubleOrNull() ?: return null
    val decimal = degrees + (minutes / 60.0)

    return when (hemisphere.uppercase(Locale.US)) {
        "N", "E" -> decimal
        "S", "W" -> -decimal
        else -> null
    }
}

private fun isValidMaidenhead(locator: String): Boolean {
    return maidenheadCenter(locator) != null
}

private fun maidenheadCenter(locator: String): Pair<Double, Double>? {
    val normalized = locator.trim()
    if (normalized.length != 8) {
        return null
    }

    val fieldLon = normalized[0].uppercaseChar() - 'A'
    val fieldLat = normalized[1].uppercaseChar() - 'A'
    val squareLon = normalized[2].digitToIntOrNull() ?: return null
    val squareLat = normalized[3].digitToIntOrNull() ?: return null
    val subsquareLon = normalized[4].lowercaseChar() - 'a'
    val subsquareLat = normalized[5].lowercaseChar() - 'a'
    val extendedLon = normalized[6].digitToIntOrNull() ?: return null
    val extendedLat = normalized[7].digitToIntOrNull() ?: return null

    if (fieldLon !in 0..17 || fieldLat !in 0..17 || subsquareLon !in 0..23 || subsquareLat !in 0..23) {
        return null
    }

    val lonStep4 = 1.0 / 120.0
    val latStep4 = 1.0 / 240.0
    val longitude = -180.0 +
        (fieldLon * 20.0) +
        (squareLon * 2.0) +
        (subsquareLon / 12.0) +
        (extendedLon * lonStep4) +
        (lonStep4 / 2.0)
    val latitude = -90.0 +
        (fieldLat * 10.0) +
        squareLat.toDouble() +
        (subsquareLat / 24.0) +
        (extendedLat * latStep4) +
        (latStep4 / 2.0)

    return latitude to longitude
}

private fun initialBearing(
    fromLatitude: Double,
    fromLongitude: Double,
    toLatitude: Double,
    toLongitude: Double,
): Double {
    val fromLatRad = Math.toRadians(fromLatitude)
    val toLatRad = Math.toRadians(toLatitude)
    val deltaLonRad = Math.toRadians(toLongitude - fromLongitude)
    val y = sin(deltaLonRad) * cos(toLatRad)
    val x = cos(fromLatRad) * sin(toLatRad) -
        sin(fromLatRad) * cos(toLatRad) * cos(deltaLonRad)
    return norm360(Math.toDegrees(atan2(y, x)))
}

private fun distanceKm(
    fromLatitude: Double,
    fromLongitude: Double,
    toLatitude: Double,
    toLongitude: Double,
): Double {
    val earthRadiusKm = 6371.0088
    val deltaLat = Math.toRadians(toLatitude - fromLatitude)
    val deltaLon = Math.toRadians(toLongitude - fromLongitude)
    val fromLatRad = Math.toRadians(fromLatitude)
    val toLatRad = Math.toRadians(toLatitude)
    val a = sin(deltaLat / 2.0).pow(2.0) +
        cos(fromLatRad) * cos(toLatRad) * sin(deltaLon / 2.0).pow(2.0)
    val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    return earthRadiusKm * c
}

private fun toMaidenhead(latitude: Double, longitude: Double): String {
    val safeLon = longitude.coerceIn(-180.0, 179.999999)
    val safeLat = latitude.coerceIn(-90.0, 89.999999)
    var lon = safeLon + 180.0
    var lat = safeLat + 90.0

    val fieldLon = (lon / 20.0).toInt()
    val fieldLat = (lat / 10.0).toInt()
    lon %= 20.0
    lat %= 10.0

    val squareLon = (lon / 2.0).toInt()
    val squareLat = lat.toInt()
    lon %= 2.0
    lat %= 1.0

    val subsquareLon = (lon * 12.0).toInt()
    val subsquareLat = (lat * 24.0).toInt()
    lon -= subsquareLon / 12.0
    lat -= subsquareLat / 24.0

    val extendedLon = (lon * 120.0).toInt()
    val extendedLat = (lat * 240.0).toInt()

    return buildString(8) {
        append(('A'.code + fieldLon).toChar())
        append(('A'.code + fieldLat).toChar())
        append(squareLon)
        append(squareLat)
        append(('a'.code + subsquareLon).toChar())
        append(('a'.code + subsquareLat).toChar())
        append(extendedLon)
        append(extendedLat)
    }
}
