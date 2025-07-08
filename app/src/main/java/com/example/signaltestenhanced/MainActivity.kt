package com.example.signaltestenhanced

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), LocationListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    // UI Components
    private lateinit var deviceInfoText: TextView
    private lateinit var gpsInfoText: TextView
    private lateinit var signal4gText: TextView
    private lateinit var signal5gText: TextView
    private lateinit var generalSignalText: TextView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button

    // System Services
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var locationManager: LocationManager
    private lateinit var wifiManager: WifiManager

    // Data
    private lateinit var deviceId: String
    private var lastLocation: Location? = null
    private var isMonitoring = false
    private var isServerAvailable = false
    private var isDeviceRegistered = false
    private var serverBaseUrl = ""
    private var monitoringHandler: Handler? = null
    private var monitoringRunnable: Runnable? = null

    // Signal data
    private var currentSignalStrength: Int = -999
    private var currentNetworkType: String = "Unknown"
    private var currentCarrier: String = "Unknown"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e("DEPLOYMENT_TEST", "MainActivity onCreate() - SIGNAL FIX VERSION!")
        Log.d(TAG, "=== onCreate() STARTED ===")

        try {
            Log.d(TAG, "Initializing services...")
            // Initialize services
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            Log.d(TAG, "Services initialized successfully")

            // Initialize device ID - FIXED TO MATCH DASHBOARD
            deviceId = "debug_test_device"
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Title
        val titleText = TextView(this).apply {
            text = "Signal Test Enhanced"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        }
        layout.addView(titleText)

        // Device Information
        deviceInfoText = TextView(this).apply {
            text = "Loading device info..."
            setPadding(0, 0, 0, 16)
        }
        layout.addView(deviceInfoText)

        // GPS Information
        gpsInfoText = TextView(this).apply {
            text = "Loading GPS info..."
            setPadding(0, 0, 0, 16)
        }
        layout.addView(gpsInfoText)

        // 4G Signal
        signal4gText = TextView(this).apply {
            text = "4G Signal: Not detected"
            setPadding(0, 0, 0, 16)
        }
        layout.addView(signal4gText)

        // 5G Signal
        signal5gText = TextView(this).apply {
            text = "5G Signal: Not detected"
            setPadding(0, 0, 0, 16)
        }
        layout.addView(signal5gText)

        // General Signal Information
        generalSignalText = TextView(this).apply {
            text = "General Signal Info: Loading..."
            setPadding(0, 0, 0, 16)
        }
        layout.addView(generalSignalText)

        // Status
        statusText = TextView(this).apply {
            text = "🔍 Discovering server..."
            setPadding(0, 0, 0, 16)
        }
        layout.addView(statusText)

        // Start Monitoring Button
        startButton = Button(this).apply {
            text = "Start Monitoring"
            isEnabled = false
            setOnClickListener {
                if (isMonitoring) {
                    stopMonitoring()
                } else {
                    startMonitoring()
                }
            }
        }
        layout.addView(startButton)

        setContentView(layout)
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            updateDeviceInfo()
        }
    }

    private fun discoverServer() {
        Log.d(TAG, "=== TESTING LOCALHOST FIRST FOR ADB ===")

        thread {
            // Test localhost first for ADB port forwarding
            Log.d(TAG, "Testing ADB tunnel: 127.0.0.1:5000")
            if (testServerConnection("127.0.0.1", 5000)) {
                Log.d(TAG, "SUCCESS: ADB tunnel working at 127.0.0.1:5000")
                serverBaseUrl = "http://127.0.0.1:5000"
                isServerAvailable = true

                runOnUiThread {
                    statusText.text = "✅ Server connected at 127.0.0.1:5000"
                    registerDevice()
                }
                return@thread
            }

            // If localhost fails, try other common addresses
            val testAddresses = listOf(
                "10.0.2.2" to 5000,  // Android emulator host
                "192.168.1.100" to 5000,
                "192.168.1.101" to 5000,
                "192.168.0.100" to 5000,
                "192.168.0.101" to 5000
            )

            for ((ip, port) in testAddresses) {
                Log.d(TAG, "Testing server at $ip:$port")
                if (testServerConnection(ip, port)) {
                    Log.d(TAG, "SUCCESS: Server found at $ip:$port")
                    serverBaseUrl = "http://$ip:$port"
                    isServerAvailable = true

                    runOnUiThread {
                        statusText.text = "✅ Server connected at $ip:$port"
                        registerDevice()
                    }
                    return@thread
                }
            }

            // No server found
            Log.w(TAG, "No server found")
            runOnUiThread {
                statusText.text = "❌ Server not found"
                showErrorDialog("Server Error", "Could not connect to server. Please ensure the server is running.")
            }
        }
    }

    private fun testServerConnection(ip: String, port: Int): Boolean {
        return try {
            val url = URL("http://$ip:$port/api/devices")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000

            val responseCode = connection.responseCode
            connection.disconnect()

            Log.d(TAG, "Connection test result for $ip: $responseCode")
            responseCode == 200
        } catch (e: Exception) {
            Log.d(TAG, "Connection test failed for $ip: ${e.message}")
            false
        }
    }

    private fun registerDevice() {
        Log.d(TAG, "=== registerDevice() STARTED ===")
        if (!isServerAvailable || serverBaseUrl.isEmpty()) {
            Log.w(TAG, "Cannot register: server not available")
            return
        }

        thread {
            try {
                val url = URL("$serverBaseUrl/api/devices/register")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val deviceData = JSONObject().apply {
                    put("device_id", deviceId)
                    put("device_name", "Debug Test Device")
                    put("device_model", android.os.Build.MODEL)
                    put("android_version", android.os.Build.VERSION.RELEASE)
                    lastLocation?.let {
                        put("latitude", it.latitude)
                        put("longitude", it.longitude)
                        put("location_accuracy", it.accuracy)
                    }
                }

                val writer = OutputStreamWriter(connection.outputStream)
                writer.write(deviceData.toString())
                writer.flush()
                writer.close()

                val responseCode = connection.responseCode
                connection.disconnect()

                runOnUiThread {
                    if (responseCode in 200..299) {
                        statusText.text = "✅ Device registered successfully"
                        isDeviceRegistered = true
                        startButton.isEnabled = true
                        Log.d(TAG, "Device registration SUCCESS")
                    } else {
                        statusText.text = "⚠️ Registration failed (Code: $responseCode)"
                        Log.w(TAG, "Device registration FAILED with code: $responseCode")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Registration ERROR: ${e.message}", e)
                runOnUiThread {
                    statusText.text = "❌ Registration error: ${e.message}"
                }
            }
        }
        Log.d(TAG, "=== registerDevice() COMPLETED (thread launched) ===")
    }

    @SuppressLint("MissingPermission")
    private fun updateDeviceInfo() {
        try {
            // Update device info
            val deviceInfo = "Device ID: ${deviceId.take(8)}...\n" +
                    "Model: ${android.os.Build.MODEL}\n" +
                    "Android: ${android.os.Build.VERSION.RELEASE}"
            deviceInfoText.text = deviceInfo

            // Update signal information
            updateSignalInfo()

            // Start location updates
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1f, this)
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1f, this)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error updating device info: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateSignalInfo() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                generalSignalText.text = "Permission required for signal info"
                return
            }

            // Get all cell info
            val cellInfoList = telephonyManager.allCellInfo
            var has4G = false
            var has5G = false
            var signal4G = "Not detected"
            var signal5G = "Not detected"

            cellInfoList?.forEach { cellInfo ->
                when (cellInfo) {
                    is CellInfoLte -> {
                        has4G = true
                        val signalStrength = cellInfo.cellSignalStrength as CellSignalStrengthLte
                        val rsrp = signalStrength.rsrp
                        val rsrq = signalStrength.rsrq
                        signal4G = "RSRP: ${rsrp}dBm, RSRQ: ${rsrq}dB"

                        // Set current signal strength for server
                        currentSignalStrength = rsrp
                        currentNetworkType = "LTE"
                    }
                    is CellInfoNr -> {
                        has5G = true
                        val signalStrength = cellInfo.cellSignalStrength as CellSignalStrengthNr
                        val ssRsrp = signalStrength.ssRsrp
                        val ssRsrq = signalStrength.ssRsrq
                        signal5G = "SS-RSRP: ${ssRsrp}dBm, SS-RSRQ: ${ssRsrq}dB"

                        // Set current signal strength for server (prefer 5G)
                        currentSignalStrength = ssRsrp
                        currentNetworkType = "NR"
                    }
                }
            }

            // Update UI
            signal4gText.text = "4G Signal: $signal4G"
            signal5gText.text = "5G Signal: $signal5G"

            // Get carrier info
            currentCarrier = telephonyManager.networkOperatorName ?: "Unknown"

            // Update general signal info
            val networkType = when (telephonyManager.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
                else -> "Other"
            }

            generalSignalText.text = "Network: $networkType\nCarrier: $currentCarrier\nSignal: ${currentSignalStrength}dBm"

        } catch (e: Exception) {
            Log.e(TAG, "Error updating signal info: ${e.message}")
            generalSignalText.text = "Error reading signal info"
        }
    }

    private fun startMonitoring() {
        if (!isServerAvailable || !isDeviceRegistered) {
            showErrorDialog("Cannot Start", "Server connection or device registration required")
            return
        }

        isMonitoring = true
        startButton.text = "Stop Monitoring"
        statusText.text = "📡 Monitoring active..."

        Log.d(TAG, "startMonitoring() STARTED with serverBaseUrl: '$serverBaseUrl'")

        // Create handler and runnable for periodic data sending
        monitoringHandler = Handler(Looper.getMainLooper())
        monitoringRunnable = object : Runnable {
            override fun run() {
                updateSignalInfo() // Update signal data
                sendDataToServer() // Send to server
                monitoringHandler?.postDelayed(this, 5000) // Every 5 seconds
            }
        }

        // Start the monitoring loop
        monitoringHandler?.post(monitoringRunnable!!)
    }

    private fun stopMonitoring() {
        isMonitoring = false
        startButton.text = "Start Monitoring"
        statusText.text = "⏹️ Monitoring stopped"

        // Stop the monitoring loop
        monitoringHandler?.removeCallbacks(monitoringRunnable!!)
        monitoringHandler = null
        monitoringRunnable = null

        Log.d(TAG, "Monitoring stopped")
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

                    // Required field for server
                    put("signal_strength", currentSignalStrength)

                    // Additional signal data
                    put("network_type", currentNetworkType)
                    put("carrier", currentCarrier)

                    // Location data if available
                    lastLocation?.let {
                        put("latitude", it.latitude)
                        put("longitude", it.longitude)
                        put("accuracy", it.accuracy)
                        put("speed", if (it.hasSpeed()) it.speed * 3.6 else null) // Convert m/s to km/h
                        put("bearing", if (it.hasBearing()) it.bearing else null)
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

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }
}