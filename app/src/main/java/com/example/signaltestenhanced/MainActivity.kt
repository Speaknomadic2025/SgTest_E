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
    private lateinit var httpsSwitch: Switch
    private lateinit var batteryButton: Button

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
            text = "📡 Enhanced Signal Monitor"
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
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
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

        // Method 1: Priority IP scanning (including 192.168.23.35)
        val priorityResult = discoverViaPriorityIPs(protocol, port)
        if (priorityResult.isNotEmpty()) return priorityResult

        // Method 2: Universal subnet scanning
        val subnetResult = discoverViaUniversalScanning(protocol, port)
        if (subnetResult.isNotEmpty()) return subnetResult

        // Method 3: Network interface analysis
        val interfaceResult = discoverViaNetworkInterface(protocol, port)
        if (interfaceResult.isNotEmpty()) return interfaceResult

        return ""
    }

    private suspend fun discoverViaPriorityIPs(protocol: String, port: Int): String {
        return withContext(Dispatchers.IO) {
            // Priority networks and IPs based on your server location
            val priorityTests = listOf(
                "192.168.23.35",  // Your exact server IP
                "192.168.23.1",
                "192.168.23.10",
                "192.168.23.100",
                "192.168.1.35",
                "192.168.1.1",
                "192.168.0.35",
                "192.168.0.1",
                "10.0.0.35",
                "10.0.0.1"
            )

            for (ip in priorityTests) {
                val testUrl = "$protocol://$ip:$port"
                if (testServerConnection(testUrl).isNotEmpty()) {

                    return@withContext testUrl
                }
            }
            ""
        }
    }

    private suspend fun discoverViaUniversalScanning(protocol: String, port: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                // Get all network interfaces and extract subnets
                val subnets = mutableSetOf<String>()

                // WiFi subnet
                try {
                    val wifiInfo = wifiManager.connectionInfo
                    val ipAddress = wifiInfo.ipAddress
                    if (ipAddress != 0) {
                        val ip = String.format(
                            "%d.%d.%d.%d",
                            ipAddress and 0xff,
                            ipAddress shr 8 and 0xff,
                            ipAddress shr 16 and 0xff,
                            ipAddress shr 24 and 0xff
                        )
                        subnets.add(ip.substring(0, ip.lastIndexOf('.')))
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "WiFi subnet error: ${e.message}")
                }

                // Network interface subnets
                try {
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    for (networkInterface in interfaces) {
                        if (!networkInterface.isLoopback && networkInterface.isUp) {
                            for (address in networkInterface.inetAddresses) {
                                if (!address.isLoopbackAddress && address.hostAddress?.contains(':') == false) {
                                    val ip = address.hostAddress
                                    subnets.add(ip.substring(0, ip.lastIndexOf('.')))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "Interface subnet error: ${e.message}")
                }

                // Scan each subnet with priority IPs (including 35)
                for (subnet in subnets) {
                    val result = scanSubnetParallel(protocol, port, subnet, listOf(35, 1, 10, 100, 50, 20, 30, 40, 60, 70, 80, 90))
                    if (result.isNotEmpty()) return@withContext result
                }

                ""
            } catch (e: Exception) {
                Log.e("MainActivity", "Universal scanning error: ${e.message}")
                ""
            }
        }
    }

    private suspend fun discoverViaNetworkInterface(protocol: String, port: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                for (networkInterface in interfaces) {
                    if (!networkInterface.isLoopback && networkInterface.isUp) {
                        for (address in networkInterface.inetAddresses) {
                            if (!address.isLoopbackAddress && address.hostAddress?.contains(':') == false) {
                                val ip = address.hostAddress
                                val subnet = ip.substring(0, ip.lastIndexOf('.'))
                                val result = scanSubnetParallel(protocol, port, subnet, listOf(35, 1, 10, 100, 50))
                                if (result.isNotEmpty()) return@withContext result
                            }
                        }
                    }
                }
                ""
            } catch (e: Exception) {
                Log.e("MainActivity", "Interface discovery error: ${e.message}")
                ""
            }
        }
    }

    private suspend fun scanSubnetParallel(protocol: String, port: Int, subnet: String, ips: List<Int>): String {
        return withContext(Dispatchers.IO) {
            val jobs = ips.map { ip ->
                async {
                    testServerConnection("$protocol://$subnet.$ip:$port")
                }
            }

            for (job in jobs) {
                val result = job.await()
                if (result.isNotEmpty()) {
                    jobs.forEach { it.cancel() }
                    return@withContext result
                }
            }
            ""
        }
    }

    private fun testServerConnection(url: String): String {
        return try {
            val connection = URL("$url/api/devices").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 100
            connection.readTimeout = 100

            val responseCode = connection.responseCode
            connection.disconnect()

            if (responseCode in 200..299 || responseCode == 404) url else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun registerDevice() {
        if (serverUrl.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val deviceData = JSONObject().apply {
                    put("device_id", deviceId)
                    put("device_name", "Samsung S24 Ultra Enhanced")
                    put("device_model", Build.MODEL)
                    put("android_version", Build.VERSION.RELEASE)
                    put("app_status", "running")
                    put("https_enabled", useHttps)
                    put("monitoring_mode", "foreground")
                    put("network_discovery_method", "universal_scanning")
                    put("app_version", "enhanced")

                    currentLocation?.let {
                        put("latitude", it.latitude)
                        put("longitude", it.longitude)
                        put("location_accuracy", it.accuracy)
                    }
                }

                sendDataToServer("/api/devices/register", deviceData)
            } catch (e: Exception) {
                Log.e("MainActivity", "Registration error: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 1f, this)
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 1f, this)
            } catch (e: Exception) {
                Log.e("MainActivity", "Location updates error: ${e.message}")
            }
        }
    }

    private fun startMonitoring() {
        if (serverUrl.isEmpty()) {
            showErrorDialog("Server Error", "Server not connected")
            return
        }

        isMonitoring = true
        startButton.isEnabled = false
        stopButton.isEnabled = true

        monitoringLoop()
    }

    private fun stopMonitoring() {
        isMonitoring = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    private fun startBackgroundMonitoring() {
        val intent = Intent(this, SignalMonitoringService::class.java)
        intent.putExtra("serverUrl", serverUrl)
        intent.putExtra("deviceId", deviceId)
        intent.putExtra("useHttps", useHttps)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(this, "Background monitoring started", Toast.LENGTH_SHORT).show()
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Battery optimization settings not available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun monitoringLoop() {
        if (!isMonitoring) return

        updateSignalInfo()
        sendSignalData()

        handler.postDelayed({ monitoringLoop() }, MONITORING_INTERVAL)
    }

    @SuppressLint("MissingPermission")
    private fun updateSignalInfo() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val cellInfos = telephonyManager.allCellInfo
                val signalInfo = StringBuilder()

                cellInfos?.forEach { cellInfo ->
                    when (cellInfo) {
                        is CellInfoLte -> {
                            val strength = cellInfo.cellSignalStrength.dbm
                            signalInfo.append("📶 4G: ${strength} dBm\n")
                        }
                        is CellInfoNr -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val strength = (cellInfo.cellSignalStrength as CellSignalStrengthNr).dbm
                                signalInfo.append("🔥 5G: ${strength} dBm\n")
                            }
                        }
                    }
                }

                if (signalInfo.isEmpty()) {
                    signalInfo.append("📶 Signal: Not available")
                }

                signalText.text = signalInfo.toString()
            }
        } catch (e: Exception) {
            signalText.text = "📶 Signal: Error reading"
            Log.e("MainActivity", "Signal update error: ${e.message}")
        }
    }

    private fun sendSignalData() {
        if (serverUrl.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val signalData = collectSignalData()
                sendDataToServer("/api/devices/$deviceId/signal", signalData)
            } catch (e: Exception) {
                Log.e("MainActivity", "Send signal error: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun collectSignalData(): JSONObject {
        val data = JSONObject()

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val cellInfos = telephonyManager.allCellInfo
                var signalStrength = -999
                var networkType = "Unknown"

                cellInfos?.forEach { cellInfo ->
                    when (cellInfo) {
                        is CellInfoLte -> {
                            signalStrength = cellInfo.cellSignalStrength.dbm
                            networkType = "4G LTE"
                        }
                        is CellInfoNr -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                signalStrength = (cellInfo.cellSignalStrength as CellSignalStrengthNr).dbm
                                networkType = "5G NR"
                            }
                        }
                    }
                }

                data.put("signal_strength", signalStrength)
                data.put("network_type", networkType)
                data.put("carrier", telephonyManager.networkOperatorName ?: "Unknown")
            }

            currentLocation?.let {
                data.put("latitude", it.latitude)
                data.put("longitude", it.longitude)
                data.put("accuracy", it.accuracy)
            }

            data.put("https_enabled", useHttps)
            data.put("monitoring_mode", "foreground")
            data.put("background_service_active", false)

        } catch (e: Exception) {
            Log.e("MainActivity", "Collect signal error: ${e.message}")
        }

        return data
    }

    private fun sendDataToServer(endpoint: String, data: JSONObject) {
        try {
            val url = URL(serverUrl + endpoint)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(data.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d("MainActivity", "Server response: $responseCode")

        } catch (e: Exception) {
            Log.e("MainActivity", "Send data error: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        currentLocation = location

        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timeString = dateFormat.format(Date())

        locationText.text = "📍 ${location.latitude}, ${location.longitude} (GPS)\n🎯 Accuracy: ±${location.accuracy.toInt()}m\n⏰ Updated: $timeString"

        if (serverUrl.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val locationData = JSONObject().apply {
                        put("latitude", location.latitude)
                        put("longitude", location.longitude)
                        put("accuracy", location.accuracy)
                        put("https_enabled", useHttps)
                    }
                    sendDataToServer("/api/devices/$deviceId/location", locationData)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Send location error: ${e.message}")
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                initializeServices()
            } else {
                showErrorDialog("Permissions Required", "All permissions are required for the app to work properly")
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