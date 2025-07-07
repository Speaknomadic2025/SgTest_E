package com.example.signaltestenhanced

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.*
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var statusText: TextView
    private lateinit var signalText: TextView
    private lateinit var locationText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var backgroundButton: Button
    private lateinit var batteryButton: Button
    private lateinit var httpsSwitch: Switch

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var locationManager: LocationManager
    private lateinit var wifiManager: WifiManager

    private var isMonitoring = false
    private var serverUrl = ""
    private var deviceId = ""
    private var useHttps = false
    private var currentLocation: Location? = null

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(4)

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val MONITORING_INTERVAL = 3000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // Initialize services
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            // Initialize device ID
            deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

            // Create UI
            createUI()

            // Request permissions
            requestPermissions()

        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onCreate: ${e.message}")
            showErrorDialog("Initialization Error", e.message ?: "Unknown error")
        }
    }

    private fun createUI() {
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Title
        val title = TextView(this).apply {
            text = "📶 Enhanced Signal Monitor"
            textSize = 24f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        layout.addView(title)

        // Status
        statusText = TextView(this).apply {
            text = "🔄 Initializing..."
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#f0f0f0"))
        }
        layout.addView(statusText)

        // HTTPS Toggle
        val httpsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 16)
        }
        val httpsLabel = TextView(this).apply {
            text = "🔒 HTTPS Mode: "
            textSize = 16f
            setTextColor(Color.BLACK)
        }
        httpsSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, isChecked ->
                useHttps = isChecked
                if (serverUrl.isNotEmpty()) {
                    discoverServer()
                }
            }
        }
        httpsLayout.addView(httpsLabel)
        httpsLayout.addView(httpsSwitch)
        layout.addView(httpsLayout)

        // Signal info
        signalText = TextView(this).apply {
            text = "📶 Signal: Not available"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#f8f8f8"))
        }
        layout.addView(signalText)

        // Location info
        locationText = TextView(this).apply {
            text = "📍 Location: Not available"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#f8f8f8"))
        }
        layout.addView(locationText)

        // Buttons
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 16)
        }

        startButton = Button(this).apply {
            text = "START MONITORING"
            setOnClickListener { startMonitoring() }
        }
        buttonLayout.addView(startButton)

        stopButton = Button(this).apply {
            text = "STOP MONITORING"
            setOnClickListener { stopMonitoring() }
            isEnabled = false
        }
        buttonLayout.addView(stopButton)

        backgroundButton = Button(this).apply {
            text = "START BACKGROUND MONITORING"
            setOnClickListener { startBackgroundMonitoring() }
        }
        buttonLayout.addView(backgroundButton)

        batteryButton = Button(this).apply {
            text = "DISABLE BATTERY OPTIMIZATION"
            setOnClickListener { requestBatteryOptimization() }
        }
        buttonLayout.addView(batteryButton)

        layout.addView(buttonLayout)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.INTERNET
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            initializeServices()
        }
    }

    private fun initializeServices() {
        statusText.text = "✅ All permissions granted"
        discoverServer()
        startLocationUpdates()
    }

    private fun discoverServer() {
        statusText.text = "🔍 Discovering server..."

        CoroutineScope(Dispatchers.IO).launch {
            val discoveredUrl = performUniversalServerDiscovery()

            withContext(Dispatchers.Main) {
                if (discoveredUrl.isNotEmpty()) {
                    serverUrl = discoveredUrl
                    statusText.text = "✅ Server connected at $serverUrl"
                    registerDevice()
                } else {
                    statusText.text = "❌ Cannot detect network - Running in offline mode"
                }
            }
        }
    }

    private suspend fun performUniversalServerDiscovery(): String {
        val protocol = if (useHttps) "https" else "http"
        val port = 5000

        // Method 1: Priority addresses for emulator and localhost
        val priorityAddresses = listOf(
            "localhost",        // For emulator and local testing
            "10.0.2.2",        // Android emulator host
            "127.0.0.1"        // Localhost fallback
        )

        for (address in priorityAddresses) {
            val testUrl = "$protocol://$address:$port"
            if (testServerConnection(testUrl)) {
                return testUrl
            }
        }

        // Method 2: Network interface discovery
        val interfaceResult = discoverViaNetworkInterface(protocol, port)
        if (interfaceResult.isNotEmpty()) return interfaceResult

        // Method 3: Common network ranges
        val commonRanges = listOf(
            "192.168.1",
            "192.168.0",
            "10.0.0",
            "172.16.0"
        )

        for (range in commonRanges) {
            for (lastOctet in listOf(1, 2, 100, 101, 254)) {
                val testUrl = "$protocol://$range.$lastOctet:$port"
                if (testServerConnection(testUrl)) {
                    return testUrl
                }
            }
        }

        return ""
    }

    private suspend fun discoverViaNetworkInterface(protocol: String, port: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (networkInterface in interfaces) {
                    if (networkInterface.isLoopback || !networkInterface.isUp) continue

                    for (address in networkInterface.inetAddresses) {
                        if (!address.isLoopbackAddress && address.hostAddress?.contains(':') == false) {
                            val ip = address.hostAddress
                            val subnet = ip.substring(0, ip.lastIndexOf('.'))

                            // Test gateway and common addresses in this subnet
                            for (lastOctet in listOf(1, 2, 100, 101, 254)) {
                                val testUrl = "$protocol://$subnet.$lastOctet:$port"
                                if (testServerConnection(testUrl)) {
                                    return@withContext testUrl
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Network interface discovery error: ${e.message}")
            }
            ""
        }
    }

    private suspend fun testServerConnection(testUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("ServerTest", "Testing: $testUrl/api/devices")
                val url = URL("$testUrl/api/devices")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000  // Increased from 2000
                connection.readTimeout = 5000     // Increased from 2000
                connection.setRequestProperty("Accept", "application/json")

                val responseCode = connection.responseCode
                Log.d("ServerTest", "Response from $testUrl: $responseCode")
                connection.disconnect()

                val success = responseCode in 200..299 || responseCode == 404
                Log.d("ServerTest", "Connection to $testUrl: ${if (success) "SUCCESS" else "FAILED"}")
                success
            } catch (e: Exception) {
                Log.e("ServerTest", "Connection failed to $testUrl: ${e.message}")
                false
            }
        }
    }

    private fun registerDevice() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("$serverUrl/api/devices/register")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val jsonData = JSONObject().apply {
                    put("device_id", deviceId)
                    put("device_type", "android")
                    put("app_version", "1.0")
                }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(jsonData.toString())
                }

                val responseCode = connection.responseCode
                withContext(Dispatchers.Main) {
                    if (responseCode in 200..299) {
                        statusText.text = "✅ Device registered successfully"
                    } else {
                        statusText.text = "⚠️ Registration failed (Code: $responseCode)"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "❌ Registration error: ${e.message}"
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 10f, this)
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 10f, this)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Location updates error: ${e.message}")
        }
    }

    private fun startMonitoring() {
        if (serverUrl.isEmpty()) {
            showErrorDialog("Server Error", "No server connection available")
            return
        }

        isMonitoring = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusText.text = "🔄 Monitoring active..."

        monitorSignals()
    }

    private fun stopMonitoring() {
        isMonitoring = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
        statusText.text = "⏹️ Monitoring stopped"
    }

    private fun startBackgroundMonitoring() {
        val intent = Intent(this, SignalMonitoringService::class.java)
        intent.putExtra("serverUrl", serverUrl)
        intent.putExtra("deviceId", deviceId)
        startForegroundService(intent)
        statusText.text = "🔄 Background monitoring started"
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    @SuppressLint("MissingPermission")
    private fun monitorSignals() {
        if (!isMonitoring) return

        try {
            val signalInfo = getSignalInfo()
            signalText.text = signalInfo

            if (serverUrl.isNotEmpty()) {
                sendSignalData(signalInfo)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Signal monitoring error: ${e.message}")
        }

        handler.postDelayed({ monitorSignals() }, MONITORING_INTERVAL)
    }

    @SuppressLint("MissingPermission")
    private fun getSignalInfo(): String {
        return try {
            val cellInfoList = telephonyManager.allCellInfo
            if (cellInfoList.isNullOrEmpty()) {
                "📶 No cell info available"
            } else {
                val cellInfo = cellInfoList[0]
                when (cellInfo) {
                    is CellInfoLte -> {
                        val signalStrength = cellInfo.cellSignalStrength as CellSignalStrengthLte
                        "📶 LTE Signal: ${signalStrength.dbm} dBm, RSRP: ${signalStrength.rsrp} dBm"
                    }
                    is CellInfoGsm -> {
                        val signalStrength = cellInfo.cellSignalStrength as CellSignalStrengthGsm
                        "📶 GSM Signal: ${signalStrength.dbm} dBm"
                    }
                    else -> "📶 Signal: Available"
                }
            }
        } catch (e: Exception) {
            "📶 Signal: Error - ${e.message}"
        }
    }

    private fun sendSignalData(signalInfo: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("$serverUrl/api/devices/$deviceId/signal")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val jsonData = JSONObject().apply {
                    put("signal_info", signalInfo)
                    put("timestamp", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                    currentLocation?.let {
                        put("latitude", it.latitude)
                        put("longitude", it.longitude)
                    }
                }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(jsonData.toString())
                }

                connection.responseCode // Trigger the request
            } catch (e: Exception) {
                Log.e("MainActivity", "Send signal data error: ${e.message}")
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        currentLocation = location
        locationText.text = "📍 Location: ${location.latitude}, ${location.longitude}"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initializeServices()
            } else {
                statusText.text = "❌ Some permissions denied"
            }
        }
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
        executor.shutdown()
    }
}