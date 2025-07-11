package com.example.signaltestenhanced


import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.telephony.PhoneStateListener
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.text.DecimalFormat

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
    private lateinit var stopButton: Button

    // 5G/4G Status Icons
    private lateinit var fiveGIcon: TextView
    private lateinit var fourGIcon: TextView
    private lateinit var networkStatusText: TextView

    // System Services
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var locationManager: LocationManager
    private lateinit var wifiManager: WifiManager

    // Data
    private lateinit var deviceId: String
    private var lastLocation: Location? = null
    private var isMonitoring = false
    private var isServerAvailable = false
    private var serverBaseUrl = ""
    private var connectionType = "Unknown"
    private var monitoringHandler: Handler? = null
    private var monitoringRunnable: Runnable? = null

    // Signal data
    private var currentSignalStrength: Int = -999
    private var currentNetworkType: String = "Unknown"
    private var currentCarrier: String = "Unknown"

    // 5G Detection
    private var is5GActive = false
    private var phoneStateListener: PhoneStateListener? = null

    // Speed Test Variables
    private var isSpeedTestRunning = false
    private var speedTestQueueId: String? = null
    private var speedTestExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "=== INTERNET READY VERSION 20250110_1200 ===")
        Log.d(TAG, "Android API Level: ${Build.VERSION.SDK_INT}")

        createUI()
        initializeServices()
        checkPermissions()
    }

    private fun createUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Device Info
        deviceInfoText = TextView(this).apply {
            text = "Device Info: Loading..."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(deviceInfoText)

        // GPS Info
        gpsInfoText = TextView(this).apply {
            text = "GPS: Waiting for location..."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(gpsInfoText)

        // Network Status with Icons
        val networkLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        fiveGIcon = TextView(this).apply {
            text = "5G"
            textSize = 16f
            setPadding(0, 0, 16, 0)
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
        }
        networkLayout.addView(fiveGIcon)

        fourGIcon = TextView(this).apply {
            text = "4G"
            textSize = 16f
            setPadding(0, 0, 16, 0)
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
        }
        networkLayout.addView(fourGIcon)

        networkStatusText = TextView(this).apply {
            text = "Network: Detecting..."
            textSize = 14f
        }
        networkLayout.addView(networkStatusText)

        layout.addView(networkLayout)

        // 4G Signal
        signal4gText = TextView(this).apply {
            text = "4G Signal: Waiting..."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(signal4gText)

        // 5G Signal
        signal5gText = TextView(this).apply {
            text = "5G Signal: Waiting..."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(signal5gText)

        // General Signal
        generalSignalText = TextView(this).apply {
            text = "General Signal: Waiting..."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(generalSignalText)

        // Status
        statusText = TextView(this).apply {
            text = "Status: Ready"
            textSize = 14f
            setPadding(0, 0, 0, 32)
        }
        layout.addView(statusText)

        // Buttons
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        startButton = Button(this).apply {
            text = "Start Monitoring"
            setOnClickListener { startMonitoring() }
        }
        buttonLayout.addView(startButton)

        stopButton = Button(this).apply {
            text = "Stop Monitoring"
            setOnClickListener { stopMonitoring() }
            isEnabled = false
        }
        buttonLayout.addView(stopButton)

        layout.addView(buttonLayout)
        setContentView(layout)
    }

    private fun initializeServices() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        Log.d(TAG, "Device ID: $deviceId")

        updateDeviceInfo()
        setup5GDetection()
        detectServerConfiguration()
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.INTERNET
        )

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startLocationUpdates()
            } else {
                statusText.text = "Status: Permissions required"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000L,
                10f,
                this
            )
            statusText.text = "Status: GPS tracking started"
        } catch (e: Exception) {
            Log.e(TAG, "Error starting location updates", e)
            statusText.text = "Status: GPS error"
        }
    }

    private fun updateDeviceInfo() {
        val deviceInfo = StringBuilder()
        deviceInfo.append("Model: ${Build.MODEL}\n")
        deviceInfo.append("Manufacturer: ${Build.MANUFACTURER}\n")
        deviceInfo.append("Android: ${Build.VERSION.RELEASE}\n")
        deviceInfo.append("API Level: ${Build.VERSION.SDK_INT}\n")
        deviceInfo.append("Device ID: ${deviceId.take(8)}...")

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val networkOperator = telephonyManager.networkOperatorName
                if (networkOperator.isNotEmpty()) {
                    deviceInfo.append("\nCarrier: $networkOperator")
                    currentCarrier = networkOperator
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting network operator", e)
            }
        }

        deviceInfoText.text = deviceInfo.toString()
    }

    @SuppressLint("MissingPermission")
    private fun setup5GDetection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            phoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                    super.onDisplayInfoChanged(telephonyDisplayInfo)

                    val networkType = telephonyDisplayInfo.networkType
                    val overrideNetworkType = telephonyDisplayInfo.overrideNetworkType

                    is5GActive = when (overrideNetworkType) {
                        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
                        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> true
                        else -> networkType == TelephonyManager.NETWORK_TYPE_NR
                    }

                    runOnUiThread {
                        update5GStatus()
                    }
                }
            }

            try {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up 5G detection", e)
            }
        }
    }

    private fun update5GStatus() {
        if (is5GActive) {
            fiveGIcon.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            fourGIcon.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            networkStatusText.text = "Network: 5G Active"
            currentNetworkType = "5G"
        } else {
            fiveGIcon.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            fourGIcon.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
            networkStatusText.text = "Network: 4G/LTE"
            currentNetworkType = "4G/LTE"
        }
    }

    private fun detectServerConfiguration() {
        thread {
            val testUrls = listOf(
                "https://signal.manus.chat",
                "https://signal-test.manus.chat",
                "http://localhost:5000",
                "http://192.168.1.100:5000"
            )

            for (url in testUrls) {
                try {
                    val connection = URL("$url/api/health").openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000

                    if (connection.responseCode == 200) {
                        serverBaseUrl = url
                        isServerAvailable = true
                        connectionType = if (url.startsWith("https")) "HTTPS" else "HTTP"

                        runOnUiThread {
                            statusText.text = "Status: Server connected ($connectionType)"
                        }

                        Log.d(TAG, "Server detected: $serverBaseUrl")
                        break
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Server test failed for $url: ${e.message}")
                }
            }

            if (!isServerAvailable) {
                runOnUiThread {
                    statusText.text = "Status: No server available"
                }
                Log.w(TAG, "No server configuration found")
            }
        }
    }

    private fun startMonitoring() {
        if (!isServerAvailable) {
            statusText.text = "Status: Server not available"
            return
        }

        isMonitoring = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusText.text = "Status: Monitoring active"

        monitoringHandler = Handler(Looper.getMainLooper())
        monitoringRunnable = object : Runnable {
            override fun run() {
                if (isMonitoring) {
                    updateSignalInfo()
                    sendDataToServer()
                    checkForQueuedSpeedTests()
                    monitoringHandler?.postDelayed(this, 5000)
                }
            }
        }
        monitoringHandler?.post(monitoringRunnable!!)
    }

    private fun stopMonitoring() {
        isMonitoring = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
        statusText.text = "Status: Monitoring stopped"

        monitoringHandler?.removeCallbacks(monitoringRunnable!!)
        monitoringHandler = null
        monitoringRunnable = null
    }

    @SuppressLint("MissingPermission")
    private fun updateSignalInfo() {
        try {
            val cellInfoList = telephonyManager.allCellInfo
            var lteCount = 0
            var nrCount = 0
            var strongestLteSignal = -999
            var strongestNrSignal = -999

            cellInfoList?.forEach { cellInfo ->
                when (cellInfo) {
                    is CellInfoLte -> {
                        lteCount++
                        val signalStrength = cellInfo.cellSignalStrength as CellSignalStrengthLte
                        val rsrp = signalStrength.rsrp
                        if (rsrp > strongestLteSignal) {
                            strongestLteSignal = rsrp
                        }
                    }
                    is CellInfoNr -> {
                        nrCount++
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val signalStrength = cellInfo.cellSignalStrength as CellSignalStrengthNr
                            val ssRsrp = signalStrength.ssRsrp
                            if (ssRsrp > strongestNrSignal) {
                                strongestNrSignal = ssRsrp
                            }
                        }
                    }
                }
            }

            currentSignalStrength = if (is5GActive && strongestNrSignal != -999) {
                strongestNrSignal
            } else if (strongestLteSignal != -999) {
                strongestLteSignal
            } else {
                -999
            }

            runOnUiThread {
                signal4gText.text = "4G Signal: ${if (strongestLteSignal != -999) "${strongestLteSignal}dBm" else "No signal"} ($lteCount cells)"
                signal5gText.text = "5G Signal: ${if (strongestNrSignal != -999) "${strongestNrSignal}dBm" else "No signal"} ($nrCount cells)"
                generalSignalText.text = "Current Signal: ${if (currentSignalStrength != -999) "${currentSignalStrength}dBm" else "No signal"} (${currentNetworkType})"
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error updating signal info", e)
            runOnUiThread {
                signal4gText.text = "4G Signal: Error reading"
                signal5gText.text = "5G Signal: Error reading"
                generalSignalText.text = "General Signal: Error reading"
            }
        }
    }

    private fun sendDataToServer() {
        if (!isServerAvailable) return

        thread {
            try {
                val jsonData = JSONObject().apply {
                    put("device_id", deviceId)
                    put("timestamp", System.currentTimeMillis())
                    put("signal_strength", currentSignalStrength)
                    put("network_type", currentNetworkType)
                    put("carrier", currentCarrier)
                    put("is_5g_active", is5GActive)

                    lastLocation?.let { location ->
                        put("latitude", location.latitude)
                        put("longitude", location.longitude)
                        put("accuracy", location.accuracy)
                    }
                }

                val url = URL("$serverBaseUrl/api/signal-data")
                val connection = if (serverBaseUrl.startsWith("https")) {
                    url.openConnection() as HttpsURLConnection
                } else {
                    url.openConnection() as HttpURLConnection
                }

                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 10000
                }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(jsonData.toString())
                    writer.flush()
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "Data sent via Internet $connectionType, response: $responseCode")

            } catch (e: Exception) {
                Log.e(TAG, "Error sending data to server", e)
            }
        }
    }

    /**
     * Step 3: Check for queued speed tests from the dashboard
     * Polls the queue endpoint to see if a speed test has been requested
     */
    private fun checkForQueuedSpeedTests() {
        if (!isServerAvailable || isSpeedTestRunning) return

        thread {
            try {
                val url = URL("$serverBaseUrl/api/devices/$deviceId/speedtest/queue")
                val connection = if (serverBaseUrl.startsWith("https")) {
                    url.openConnection() as HttpsURLConnection
                } else {
                    url.openConnection() as HttpURLConnection
                }

                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                        reader.readText()
                    }

                    val jsonResponse = JSONObject(response)
                    val hasQueuedTest = jsonResponse.optBoolean("has_queued_test", false)

                    if (hasQueuedTest) {
                        val queueId = jsonResponse.optString("queue_id", "")
                        Log.d(TAG, "Speed test requested from dashboard, queue ID: $queueId")

                        runOnUiThread {
                            statusText.text = "Status: Speed test requested from dashboard..."
                        }

                        // Step 4: Execute the speed test
                        executeSpeedTest(queueId)
                    } else {
                        Log.d(TAG, "No queued speed tests found")
                    }
                } else {
                    Log.d(TAG, "Queue check failed with response code: $responseCode")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error checking for queued speed tests", e)
            }
        }
    }

    /**
     * Step 4: Execute speed test using simple HTTP-based approach
     * This replaces JSpeedTest with a custom implementation
     */
    private fun executeSpeedTest(queueId: String) {
        if (isSpeedTestRunning) {
            Log.d(TAG, "Speed test already running, skipping")
            return
        }

        isSpeedTestRunning = true
        speedTestQueueId = queueId

        Log.d(TAG, "Starting speed test execution for queue ID: $queueId")

        runOnUiThread {
            statusText.text = "Status: Running speed test..."
        }

        speedTestExecutor.execute {
            try {
                val startTime = System.currentTimeMillis()

                // Test download speed
                val downloadSpeed = measureDownloadSpeed()

                // Test upload speed
                val uploadSpeed = measureUploadSpeed()

                // Test ping
                val pingResult = measurePing()

                val endTime = System.currentTimeMillis()
                val duration = endTime - startTime

                Log.d(TAG, "Speed test completed - Download: ${downloadSpeed}Mbps, Upload: ${uploadSpeed}Mbps, Ping: ${pingResult}ms")

                // Submit results to server
                submitSpeedTestResults(queueId, downloadSpeed, uploadSpeed, pingResult, duration)

            } catch (e: Exception) {
                Log.e(TAG, "Speed test execution failed", e)
                runOnUiThread {
                    statusText.text = "Status: Speed test failed"
                }
            } finally {
                isSpeedTestRunning = false
                speedTestQueueId = null
            }
        }
    }

    /**
     * Measure download speed by downloading test data
     */
    private fun measureDownloadSpeed(): Double {
        return try {
            val testUrl = "https://speed.cloudflare.com/__down?bytes=10000000" // 10MB test
            val startTime = System.currentTimeMillis()

            val connection = URL(testUrl).openConnection() as HttpsURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            val inputStream = connection.inputStream
            val buffer = ByteArray(8192)
            var totalBytes = 0
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                totalBytes += bytesRead
            }

            val endTime = System.currentTimeMillis()
            val durationSeconds = (endTime - startTime) / 1000.0
            val speedMbps = (totalBytes * 8.0) / (durationSeconds * 1_000_000)

            inputStream.close()
            connection.disconnect()

            Log.d(TAG, "Download test: ${totalBytes} bytes in ${durationSeconds}s = ${speedMbps}Mbps")
            speedMbps

        } catch (e: Exception) {
            Log.e(TAG, "Download speed test failed", e)
            0.0
        }
    }

    /**
     * Measure upload speed by uploading test data
     */
    private fun measureUploadSpeed(): Double {
        return try {
            val testUrl = "$serverBaseUrl/api/speedtest/upload"
            val testData = ByteArray(1000000) // 1MB test data
            testData.fill(65) // Fill with 'A' characters

            val startTime = System.currentTimeMillis()

            val connection = if (serverBaseUrl.startsWith("https")) {
                URL(testUrl).openConnection() as HttpsURLConnection
            } else {
                URL(testUrl).openConnection() as HttpURLConnection
            }

            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/octet-stream")
                doOutput = true
                connectTimeout = 30000
                readTimeout = 30000
            }

            connection.outputStream.use { outputStream ->
                outputStream.write(testData)
                outputStream.flush()
            }

            val responseCode = connection.responseCode
            val endTime = System.currentTimeMillis()

            if (responseCode == 200) {
                val durationSeconds = (endTime - startTime) / 1000.0
                val speedMbps = (testData.size * 8.0) / (durationSeconds * 1_000_000)

                Log.d(TAG, "Upload test: ${testData.size} bytes in ${durationSeconds}s = ${speedMbps}Mbps")
                speedMbps
            } else {
                Log.e(TAG, "Upload test failed with response code: $responseCode")
                0.0
            }

        } catch (e: Exception) {
            Log.e(TAG, "Upload speed test failed", e)
            0.0
        }
    }

    /**
     * Measure ping latency
     */
    private fun measurePing(): Double {
        return try {
            val testUrl = "$serverBaseUrl/api/ping"
            var totalPing = 0.0
            val pingCount = 5

            repeat(pingCount) {
                val startTime = System.currentTimeMillis()

                val connection = if (serverBaseUrl.startsWith("https")) {
                    URL(testUrl).openConnection() as HttpsURLConnection
                } else {
                    URL(testUrl).openConnection() as HttpURLConnection
                }

                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                val responseCode = connection.responseCode
                val endTime = System.currentTimeMillis()

                if (responseCode == 200) {
                    val pingTime = endTime - startTime
                    totalPing += pingTime
                    Log.d(TAG, "Ping ${it + 1}: ${pingTime}ms")
                }

                connection.disconnect()

                // Small delay between pings
                Thread.sleep(100)
            }

            val averagePing = totalPing / pingCount
            Log.d(TAG, "Average ping: ${averagePing}ms")
            averagePing

        } catch (e: Exception) {
            Log.e(TAG, "Ping test failed", e)
            0.0
        }
    }

    /**
     * Submit speed test results to the server
     */
    private fun submitSpeedTestResults(queueId: String, downloadSpeed: Double, uploadSpeed: Double, ping: Double, duration: Long) {
        try {
            val resultsData = JSONObject().apply {
                put("queue_id", queueId)
                put("device_id", deviceId)
                put("timestamp", System.currentTimeMillis())
                put("download_speed", downloadSpeed)
                put("upload_speed", uploadSpeed)
                put("ping", ping)
                put("jitter", 0.0) // Placeholder for jitter
                put("packet_loss", 0.0) // Placeholder for packet loss
                put("test_duration", duration)
                put("network_type", currentNetworkType)
                put("signal_strength", currentSignalStrength)

                lastLocation?.let { location ->
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                }
            }

            val url = URL("$serverBaseUrl/api/devices/$deviceId/speedtest/results")
            val connection = if (serverBaseUrl.startsWith("https")) {
                url.openConnection() as HttpsURLConnection
            } else {
                url.openConnection() as HttpURLConnection
            }

            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(resultsData.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == 200 || responseCode == 201) {
                Log.d(TAG, "Speed test results submitted successfully")
                runOnUiThread {
                    statusText.text = "Status: Speed test completed (${formatDecimal(downloadSpeed, 1)}↓ ${formatDecimal(uploadSpeed, 1)}↑ ${formatDecimal(ping, 0)}ms)"
                }
            } else {
                Log.e(TAG, "Failed to submit speed test results, response code: $responseCode")
                runOnUiThread {
                    statusText.text = "Status: Speed test completed but submission failed"
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error submitting speed test results", e)
            runOnUiThread {
                statusText.text = "Status: Speed test completed but submission failed"
            }
        }
    }

    /**
     * Format double values with specified decimal places
     */
    private fun formatDecimal(value: Double, decimals: Int): String {
        val formatter = DecimalFormat()
        formatter.maximumFractionDigits = decimals
        formatter.minimumFractionDigits = decimals
        return formatter.format(value)
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        runOnUiThread {
            gpsInfoText.text = "GPS: ${formatDecimal(location.latitude, 6)}, ${formatDecimal(location.longitude, 6)} (±${formatDecimal(location.accuracy.toDouble(), 1)}m)"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        phoneStateListener?.let {
            telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
        }
        speedTestExecutor.shutdown()
        try {
            if (!speedTestExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                speedTestExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            speedTestExecutor.shutdownNow()
        }
    }
}