package com.example.signaltestenhanced

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.collections.ArrayList

class MainActivity : ComponentActivity(), LocationListener {

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
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var wifiManager: WifiManager

    private var isMonitoring = false
    private var serverUrl = ""
    private var deviceId = ""
    private var useHttps = false
    private var currentLocation: Location? = null

    private val handler = Handler(Looper.getMainLooper( ))
    private val executor = Executors.newFixedThreadPool(4)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            statusText.text = "✅ All permissions granted"
            initializeServices()
        } else {
            statusText.text = "❌ Some permissions denied"
            Toast.makeText(this, "All permissions are required for the app to work properly", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create UI programmatically
        createUI()

        // Initialize device ID
        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        // Request permissions
        requestPermissions()
    }

    private fun createUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Title
        val title = TextView(this).apply {
            text = "📡 Enhanced Signal Monitor"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        }
        layout.addView(title)

        // Status
        statusText = TextView(this).apply {
            text = "🔄 Initializing..."
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(statusText)

        // HTTPS Toggle
        val httpsLayout = LinearLayout(this ).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val httpsLabel = TextView(this ).apply {
            text = "🔒 HTTPS Mode: "
            textSize = 16f
        }
        httpsSwitch = Switch(this ).apply {
            setOnCheckedChangeListener { _, isChecked ->
                useHttps = isChecked
                if (serverUrl.isNotEmpty()) {
                    discoverServer()
                }
            }
        }
        httpsLayout.addView(httpsLabel )
        httpsLayout.addView(httpsSwitch )
        layout.addView(httpsLayout )

        // Signal info
        signalText = TextView(this).apply {
            text = "📶 Signal: Not available"
            textSize = 14f
            setPadding(0, 16, 0, 8)
        }
        layout.addView(signalText)

        // Location info
        locationText = TextView(this).apply {
            text = "📍 Location: Not available"
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(locationText)

        // Buttons
        startButton = Button(this).apply {
            text = "START MONITORING"
            setOnClickListener { startMonitoring() }
        }
        layout.addView(startButton)

        stopButton = Button(this).apply {
            text = "STOP MONITORING"
            setOnClickListener { stopMonitoring() }
            isEnabled = false
        }
        layout.addView(stopButton)

        backgroundButton = Button(this).apply {
            text = "START BACKGROUND MONITORING"
            setOnClickListener { startBackgroundMonitoring() }
        }
        layout.addView(backgroundButton)

        batteryButton = Button(this).apply {
            text = "DISABLE BATTERY OPTIMIZATION"
            setOnClickListener { requestBatteryOptimization() }
        }
        layout.addView(batteryButton)

        setContentView(layout)
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

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun initializeServices() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Start server discovery
        discoverServer()

        // Start location updates
        startLocationUpdates()
    }

    private fun discoverServer() {
        statusText.text = "🔍 Discovering server..."

        CoroutineScope(Dispatchers.IO).launch {
            val discoveredUrl = performServerDiscovery()

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

    private suspend fun performServerDiscovery(): String {
        val protocol = if (useHttps) "https" else "http"
        val port = 5000

        // Method 1: Try WiFi network discovery
        val wifiUrl = discoverViaWiFiNetwork(protocol, port )
        if (wifiUrl.isNotEmpty()) return wifiUrl

        // Method 2: Try network interface discovery
        val interfaceUrl = discoverViaNetworkInterface(protocol, port)
        if (interfaceUrl.isNotEmpty()) return interfaceUrl

        // Method 3: Try common IP patterns
        val commonUrl = discoverViaCommonIPs(protocol, port)
        if (commonUrl.isNotEmpty()) return commonUrl

        // Method 4: Try localhost fallback
        val localhostUrl = testServerConnection("$protocol://localhost:$port")
        if (localhostUrl.isNotEmpty()) return localhostUrl

        return ""
    }

    private suspend fun discoverViaWiFiNetwork(protocol: String, port: Int): String {
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

                val subnet = ip.substring(0, ip.lastIndexOf('.'))
                val testIPs = listOf(35, 100, 1, 10, 50, 20, 30, 40, 60, 70, 80, 90, 101, 200)

                return testIPsInParallel(protocol, port, subnet, testIPs)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    private suspend fun discoverViaNetworkInterface(protocol: String, port: Int): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()

            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                for (address in networkInterface.inetAddresses) {
                    if (address.isLoopbackAddress || address.hostAddress.contains(":")) continue

                    val ip = address.hostAddress
                    val subnet = ip.substring(0, ip.lastIndexOf('.'))
                    val testIPs = listOf(35, 100, 1, 10, 50, 20, 30, 40)

                    val result = testIPsInParallel(protocol, port, subnet, testIPs)
                    if (result.isNotEmpty()) return result
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    private suspend fun discoverViaCommonIPs(protocol: String, port: Int): String {
        val commonSubnets = listOf("192.168.1", "192.168.0", "192.168.23", "10.0.0", "172.16.0")
        val testIPs = listOf(35, 100, 1, 10, 50, 20, 30, 40, 60, 70, 80, 90)

        for (subnet in commonSubnets) {
            val result = testIPsInParallel(protocol, port, subnet, testIPs)
            if (result.isNotEmpty()) return result
        }
        return ""
    }

    private suspend fun testIPsInParallel(protocol: String, port: Int, subnet: String, ips: List<Int>): String {
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
            val connection = URL("$url/api/server/info").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 100
            connection.readTimeout = 100

            if (connection.responseCode == 200) {
                url
            } else {
                ""
            }
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
                    put("https_enabled", useHttps )
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
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    3000L,
                    1f,
                    this
                )
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    3000L,
                    1f,
                    this
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startMonitoring() {
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "Server not connected", Toast.LENGTH_SHORT).show()
            return
        }

        isMonitoring = true
        startButton.isEnabled = false
        stopButton.isEnabled = true

        // Start monitoring loop
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
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun monitoringLoop() {
        if (!isMonitoring) return

        updateSignalInfo()
        sendSignalData()

        handler.postDelayed({ monitoringLoop() }, 3000)
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
            e.printStackTrace()
        }
    }

    private fun sendSignalData() {
        if (serverUrl.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val signalData = collectSignalData()
                sendDataToServer("/api/devices/$deviceId/signal", signalData)
            } catch (e: Exception) {
                e.printStackTrace()
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

            // Add location data
            currentLocation?.let {
                data.put("latitude", it.latitude)
                data.put("longitude", it.longitude)
                data.put("accuracy", it.accuracy)
            }

            // Add enhanced fields
            data.put("https_enabled", useHttps )
            data.put("monitoring_mode", "foreground")
            data.put("background_service_active", false)

        } catch (e: Exception) {
            e.printStackTrace()
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

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(data.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            // Handle response if needed

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onLocationChanged(location: Location) {
        currentLocation = location

        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timeString = dateFormat.format(Date())

        locationText.text = "📍 ${location.latitude}, ${location.longitude} (GPS)\n🎯 Accuracy: ±${location.accuracy.toInt()}m\n⏰ Updated: $timeString"

        // Send location update to server
        if (serverUrl.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val locationData = JSONObject().apply {
                        put("latitude", location.latitude)
                        put("longitude", location.longitude)
                        put("accuracy", location.accuracy)
                        put("https_enabled", useHttps )
                    }
                    sendDataToServer("/api/devices/$deviceId/location", locationData)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isMonitoring = false
        executor.shutdown()
    }
}
