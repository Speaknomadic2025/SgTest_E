package com.example.signaltestenhanced

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import org.json.JSONObject

class MainActivity : AppCompatActivity(), LocationListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val MONITORING_INTERVAL = 3000L // 3 seconds
    }

    // UI Components
    private lateinit var deviceInfoText: TextView
    private lateinit var gpsInfoText: TextView
    private lateinit var signal4gText: TextView
    private lateinit var signal5gText: TextView
    private lateinit var generalSignalText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var statusText: TextView
    private lateinit var lastUpdateText: TextView
    private lateinit var debugInfoText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var backgroundButton: Button
    private lateinit var batteryButton: Button

    // System Services
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var locationManager: LocationManager
    private lateinit var wifiManager: WifiManager
    private lateinit var handler: Handler

    // State Variables
    private var deviceId: String = ""
    private var serverBaseUrl: String = ""
    private var isServerAvailable: Boolean = false
    private var isMonitoring: Boolean = false
    private var lastLocation: Location? = null
    private var isDeviceRegistered: Boolean = false
    private var monitoringRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e("DEPLOYMENT_TEST", "MainActivity onCreate() - LOCALHOST FIRST VERSION!")  // DEPLOYMENT TEST
        Log.d(TAG, "=== onCreate() STARTED ===")

        try {
            Log.d(TAG, "Initializing services...")
            // Initialize services
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            handler = Handler(Looper.getMainLooper())
            Log.d(TAG, "Services initialized successfully")

            // Initialize device ID
            deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            Log.d(TAG, "Device ID: $deviceId")

            // Create UI
            Log.d(TAG, "Creating UI...")
            createUI()
            Log.d(TAG, "UI created successfully")

            // Request permissions
            Log.d(TAG, "Requesting permissions...")
            requestPermissions()

            // Start server discovery
            Log.d(TAG, "Starting server discovery...")
            discoverServer()

        } catch (e: Exception) {
            Log.e(TAG, "ERROR in onCreate: ${e.message}", e)
            showErrorDialog("Initialization Error", e.message ?: "Unknown error")
        }
        Log.d(TAG, "=== onCreate() COMPLETED ===")
    }

    private fun createUI() {
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Title
        val titleText = TextView(this).apply {
            text = "📶 Signal Strength Monitor"
            textSize = 24f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 32)
        }
        layout.addView(titleText)

        // Device Information Section
        val deviceSectionTitle = TextView(this).apply {
            text = "📱 Device Information"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 24, 0, 8)
        }
        layout.addView(deviceSectionTitle)

        deviceInfoText = TextView(this).apply {
            text = "Loading device info..."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(16, 8, 16, 16)
            setBackgroundColor(Color.parseColor("#f0f0f0"))
        }
        layout.addView(deviceInfoText)

        // GPS Information Section
        val gpsSectionTitle = TextView(this).apply {
            text = "🌍 GPS Information"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 16, 0, 8)
        }
        layout.addView(gpsSectionTitle)

        gpsInfoText = TextView(this).apply {
            text = "Loading GPS info..."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(16, 8, 16, 16)
            setBackgroundColor(Color.parseColor("#f0f0f0"))
        }
        layout.addView(gpsInfoText)

        // 4G Signal Section
        val signal4gTitle = TextView(this).apply {
            text = "📶 4G Signal"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 16, 0, 8)
        }
        layout.addView(signal4gTitle)

        signal4gText = TextView(this).apply {
            text = "📶 4G: Not detected"
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(16, 8, 16, 16)
            setBackgroundColor(Color.LTGRAY)
        }
        layout.addView(signal4gText)

        // 5G Signal Section
        val signal5gTitle = TextView(this).apply {
            text = "🚀 5G Signal"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 16, 0, 8)
        }
        layout.addView(signal5gTitle)

        signal5gText = TextView(this).apply {
            text = "🚀 5G: Not detected"
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(16, 8, 16, 16)
            setBackgroundColor(Color.LTGRAY)
        }
        layout.addView(signal5gText)

        // General Signal Information
        val generalSectionTitle = TextView(this).apply {
            text = "📡 General Signal Information"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 24, 0, 8)
        }
        layout.addView(generalSectionTitle)

        generalSignalText = TextView(this).apply {
            text = "Loading signal info..."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(16, 8, 16, 16)
            setBackgroundColor(Color.parseColor("#f0f0f0"))
        }
        layout.addView(generalSignalText)

        // Server Connection Section
        val connectionSectionTitle = TextView(this).apply {
            text = "🌐 Server Connection"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 16, 0, 8)
        }
        layout.addView(connectionSectionTitle)

        connectionStatusText = TextView(this).apply {
            text = "🔍 Discovering server..."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(16, 8, 16, 16)
            setBackgroundColor(Color.parseColor("#f0f0f0"))
        }
        layout.addView(connectionStatusText)

        // Control Buttons
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 16)
        }

        startButton = Button(this).apply {
            text = "START MONITORING"
            textSize = 16f
            setPadding(24, 16, 24, 16)
            setOnClickListener { startMonitoring() }
        }
        buttonLayout.addView(startButton)

        stopButton = Button(this).apply {
            text = "STOP MONITORING"
            textSize = 16f
            setPadding(24, 16, 24, 16)
            isEnabled = false
            setOnClickListener { stopMonitoring() }
        }
        buttonLayout.addView(stopButton)

        layout.addView(buttonLayout)

        // Status Text
        statusText = TextView(this).apply {
            text = "Ready to start monitoring"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
        }
        layout.addView(statusText)

        // Last Update Text
        lastUpdateText = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }
        layout.addView(lastUpdateText)

        // Debug Information Section
        val debugSectionTitle = TextView(this).apply {
            text = "🔧 Debug Information"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 24, 0, 8)
        }
        layout.addView(debugSectionTitle)

        debugInfoText = TextView(this).apply {
            text = "Debug info will appear here..."
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(16, 8, 16, 16)
            setBackgroundColor(Color.parseColor("#f8f8f8"))
        }
        layout.addView(debugInfoText)

        scrollView.addView(layout)
        setContentView(scrollView)
        Log.d(TAG, "UI setup completed")
    }

    private fun requestPermissions() {
        Log.d(TAG, "=== requestPermissions() STARTED ===")
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
            Log.d(TAG, "Added ACCESS_BACKGROUND_LOCATION for Android 10+")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            Log.d(TAG, "Added POST_NOTIFICATIONS for Android 13+")
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting ${permissionsToRequest.size} permissions")
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            Log.d(TAG, "All permissions already granted")
            updateDeviceInfo()
        }
        Log.d(TAG, "=== requestPermissions() COMPLETED ===")
    }

    private fun showErrorDialog(title: String, message: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER
        }

        val errorText = TextView(this).apply {
            text = "❌ Error\n\nServer connection failed. Please restart the app and grant all permissions."
            textSize = 16f
            setTextColor(Color.RED)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 32)
        }

        layout.addView(errorText)
        setContentView(layout)
    }

    // LOCALHOST FIRST DISCOVERY LOGIC - FOR ADB PORT FORWARDING
    private fun discoverServer() {
        thread {
            try {
                updateConnectionStatus("🔍 Discovering server on network...")

                // Method 1: LOCALHOST FIRST (for ADB port forwarding)
                Log.d(TAG, "=== TESTING LOCALHOST FIRST FOR ADB ===")
                val localhostResult = discoverViaLocalhost()
                if (localhostResult.isNotEmpty()) {
                    setupServerConnection(localhostResult)
                    return@thread
                }

                // Method 2: WiFi network analysis
                val wifiResult = discoverViaWifiNetwork()
                if (wifiResult.isNotEmpty()) {
                    setupServerConnection(wifiResult)
                    return@thread
                }

                // Method 3: Network interface scanning
                val interfaceResult = discoverViaNetworkInterface()
                if (interfaceResult.isNotEmpty()) {
                    setupServerConnection(interfaceResult)
                    return@thread
                }

                // Method 4: Common IP patterns
                val commonResult = discoverViaCommonIPs()
                if (commonResult.isNotEmpty()) {
                    setupServerConnection(commonResult)
                    return@thread
                }

                // No server found
                handler?.post {
                    updateConnectionStatus("❌ Cannot detect network - Running in offline mode")
                    isServerAvailable = false
                }

            } catch (e: Exception) {
                Log.e(TAG, "Server discovery error: ${e.message}", e)
                handler?.post {
                    updateConnectionStatus("❌ Network error - Running in offline mode")
                    isServerAvailable = false
                }
            }
        }
    }

    private fun discoverViaLocalhost(): String {
        return try {
            updateConnectionStatus("🔍 Method 1: Testing localhost (ADB port forwarding)...")
            Log.d(TAG, "=== LOCALHOST DISCOVERY FOR ADB ===")

            val localhostIPs = listOf("127.0.0.1", "localhost")
            for (ip in localhostIPs) {
                updateConnectionStatus("🔍 Testing $ip:5000 (ADB tunnel)...")
                Log.d(TAG, "Testing ADB tunnel: $ip:5000")
                if (testServerConnection(ip)) {
                    Log.d(TAG, "SUCCESS: ADB tunnel working at $ip:5000")
                    return ip
                }
            }
            Log.d(TAG, "ADB tunnel test completed - no connection")
            ""
        } catch (e: Exception) {
            Log.e(TAG, "Localhost discovery error: ${e.message}")
            ""
        }
    }

    private fun discoverViaWifiNetwork(): String {
        return try {
            updateConnectionStatus("🔍 Method 2: WiFi network analysis...")

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
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

                updateConnectionStatus("🔍 WiFi IP: $ip, scanning network...")

                val networkBase = ip.substring(0, ip.lastIndexOf('.'))

                // Test common server IPs
                for (lastOctet in listOf(100, 1, 10, 50, 101, 200)) {
                    val testIp = "$networkBase.$lastOctet"
                    updateConnectionStatus("🔍 Testing $testIp...")
                    if (testServerConnection(testIp)) {
                        return testIp
                    }
                }
            }
            ""
        } catch (e: Exception) {
            Log.e(TAG, "WiFi discovery error: ${e.message}")
            ""
        }
    }

    private fun discoverViaNetworkInterface(): String {
        return try {
            updateConnectionStatus("🔍 Method 3: Network interface scanning...")

            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    updateConnectionStatus("🔍 Interface scan: ${networkInterface.name}...")

                    for (address in networkInterface.inetAddresses) {
                        if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                            val ip = address.hostAddress
                            val networkBase = ip.substring(0, ip.lastIndexOf('.'))
                            updateConnectionStatus("🔍 Interface scan: $networkBase.x...")

                            // Test common IPs
                            for (lastOctet in listOf(100, 1, 10)) {
                                val testIp = "$networkBase.$lastOctet"
                                if (testServerConnection(testIp)) {
                                    return testIp
                                }
                            }
                        }
                    }
                }
            }
            ""
        } catch (e: Exception) {
            Log.e(TAG, "Interface discovery error: ${e.message}")
            ""
        }
    }

    private fun discoverViaCommonIPs(): String {
        return try {
            updateConnectionStatus("🔍 Method 4: Common IP patterns...")

            // Common network patterns
            val commonNetworks = listOf(
                "192.168.1", "192.168.0", "192.168.23", "192.168.100",
                "10.0.0", "10.0.1", "172.16.0", "172.16.1"
            )

            for (network in commonNetworks) {
                updateConnectionStatus("🔍 Testing $network.x network...")
                for (lastOctet in listOf(100, 1, 10)) {
                    val testIp = "$network.$lastOctet"
                    if (testServerConnection(testIp)) {
                        return testIp
                    }
                }
            }
            ""
        } catch (e: Exception) {
            Log.e(TAG, "Common IP discovery error: ${e.message}")
            ""
        }
    }

    private fun testServerConnection(ip: String): Boolean {
        return try {
            Log.d(TAG, "Testing connection to: $ip:5000")
            val url = URL("http://$ip:5000/api/devices")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000

            val responseCode = connection.responseCode
            connection.disconnect()

            val success = responseCode in 200..299 || responseCode == 404
            Log.d(TAG, "Connection test result for $ip: $responseCode (${if (success) "SUCCESS" else "FAILED"})")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed for $ip: ${e.message}")
            false
        }
    }

    private fun setupServerConnection(serverIp: String) {
        serverBaseUrl = "http://$serverIp:5000"
        isServerAvailable = true

        handler?.post {
            updateConnectionStatus("✅ Server connected at $serverIp:5000")
            Log.d(TAG, "Server connected: $serverBaseUrl")
        }

        // Register device with server - CRITICAL STEP
        registerDevice()
    }

    private fun updateConnectionStatus(status: String) {
        handler?.post {
            connectionStatusText.text = status
            Log.d(TAG, "Connection status: $status")
        }
    }

    private fun registerDevice() {
        Log.d(TAG, "=== registerDevice() STARTED ===")
        thread {
            try {
                val url = URL("$serverBaseUrl/api/devices/register")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val deviceData = JSONObject().apply {
                    put("device_id", deviceId)
                    put("device_type", "android")
                    put("timestamp", System.currentTimeMillis())
                }

                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(deviceData.toString())
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                connection.disconnect()

                handler?.post {
                    if (responseCode in 200..299) {
                        statusText.text = "✅ Device registered successfully"
                        isDeviceRegistered = true
                        Log.d(TAG, "Device registration SUCCESS")
                    } else {
                        statusText.text = "⚠️ Registration failed (Code: $responseCode)"
                        Log.w(TAG, "Device registration FAILED with code: $responseCode")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Registration ERROR: ${e.message}", e)
                handler?.post {
                    statusText.text = "❌ Registration error: ${e.message}"
                }
            }
        }
        Log.d(TAG, "=== registerDevice() COMPLETED (thread launched) ===")
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        Log.d(TAG, "=== startLocationUpdates() STARTED ===")
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Starting GPS and Network location updates")
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 10f, this)
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 10f, this)
                Log.d(TAG, "Location updates started successfully")
            } else {
                Log.w(TAG, "Location permission not granted, cannot start location updates")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location updates ERROR: ${e.message}", e)
        }
        Log.d(TAG, "=== startLocationUpdates() COMPLETED ===")
    }

    private fun startMonitoring() {
        Log.d(TAG, "=== startMonitoring() STARTED ===")
        Log.d(TAG, "Current serverBaseUrl: '$serverBaseUrl'")

        if (serverBaseUrl.isEmpty()) {
            Log.w(TAG, "Cannot start monitoring - serverBaseUrl is empty")
            showErrorDialog("Server Error", "No server connection available")
            return
        }

        isMonitoring = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusText.text = "📡 Monitoring active..."

        startLocationUpdates()
        updateDeviceInfo()

        // Start monitoring loop
        monitoringRunnable = object : Runnable {
            override fun run() {
                if (isMonitoring) {
                    updateSignalInfo()
                    sendDataToServer()
                    handler.postDelayed(this, MONITORING_INTERVAL)
                }
            }
        }
        handler.post(monitoringRunnable!!)

        Log.d(TAG, "=== startMonitoring() COMPLETED ===")
    }

    private fun stopMonitoring() {
        Log.d(TAG, "=== stopMonitoring() STARTED ===")
        isMonitoring = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
        statusText.text = "⏹️ Monitoring stopped"

        monitoringRunnable?.let { handler.removeCallbacks(it) }
        locationManager.removeUpdates(this)

        Log.d(TAG, "=== stopMonitoring() COMPLETED ===")
    }

    @SuppressLint("MissingPermission")
    private fun updateDeviceInfo() {
        try {
            val deviceInfo = StringBuilder()
            deviceInfo.append("Device ID: $deviceId\n")
            deviceInfo.append("Model: ${Build.MODEL}\n")
            deviceInfo.append("Android: ${Build.VERSION.RELEASE}\n")

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val networkOperator = telephonyManager.networkOperatorName
                    deviceInfo.append("Carrier: $networkOperator\n")
                } catch (e: Exception) {
                    deviceInfo.append("Carrier: Unable to read\n")
                }
            }

            deviceInfoText.text = deviceInfo.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating device info: ${e.message}")
            deviceInfoText.text = "Error loading device info"
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateSignalInfo() {
        try {
            // Update signal information
            signal4gText.text = "📶 4G: Not detected"
            signal5gText.text = "🚀 5G: Not detected"
            generalSignalText.text = "Loading signal info..."

            // Update last update time
            val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            lastUpdateText.text = "Last update: $currentTime"

        } catch (e: Exception) {
            Log.e(TAG, "Error updating signal info: ${e.message}")
        }
    }

    private fun sendDataToServer() {
        if (!isServerAvailable || serverBaseUrl.isEmpty()) return

        thread {
            try {
                val url = URL("$serverBaseUrl/api/devices/$deviceId/signal")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val signalData = JSONObject().apply {
                    put("device_id", deviceId)
                    put("timestamp", System.currentTimeMillis())
                    put("signal_4g", "Not detected")
                    put("signal_5g", "Not detected")
                    lastLocation?.let {
                        put("latitude", it.latitude)
                        put("longitude", it.longitude)
                    }
                }

                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(signalData.toString())
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                connection.disconnect()

                Log.d(TAG, "Data sent to server, response: $responseCode")

            } catch (e: Exception) {
                Log.e(TAG, "Error sending data to server: ${e.message}")
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        val gpsInfo = "Lat: ${String.format("%.6f", location.latitude)}\n" +
                "Lng: ${String.format("%.6f", location.longitude)}\n" +
                "Accuracy: ${location.accuracy}m"
        gpsInfoText.text = gpsInfo
        Log.d(TAG, "Location updated: $gpsInfo")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            Log.d(TAG, "Permissions result received")
            updateDeviceInfo()
        }
    }
}