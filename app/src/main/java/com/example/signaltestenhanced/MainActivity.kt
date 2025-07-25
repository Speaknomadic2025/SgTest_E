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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
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
    private lateinit var stopButton: Button
    private lateinit var speedTestButton: Button
    private lateinit var speedTestText: TextView

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
    
    // Enhanced signal metrics (SINR and Cell ID)
    private var currentCellMetrics: CellMetrics = CellMetrics.empty()
    private var lastCellMetricsUpdate: Long = 0

    // 5G Detection
    private var is5GActive = false
    private var phoneStateListener: PhoneStateListener? = null

    // Speed Test State
    @Volatile private var isSpeedTestRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "=== INTERNET READY VERSION WITH SPEED TEST ===")
        Log.d(TAG, "Android API Level: ${Build.VERSION.SDK_INT}")

        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        createUI()
        initializeServices()
        checkPermissions()
    }

    private fun createUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // App Title
        val titleText = TextView(this).apply {
            text = "SignalTestEnhanced - Internet Ready"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 40)
        }
        layout.addView(titleText)

        // Device Info
        deviceInfoText = TextView(this).apply {
            text = "Device Info: Loading..."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(deviceInfoText)

        // GPS Info
        gpsInfoText = TextView(this).apply {
            text = "GPS: Not available"
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(gpsInfoText)

        // Network Status Icons Section
        val networkSectionTitle = TextView(this).apply {
            text = "📶 Network Status"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 20, 0, 16)
        }
        layout.addView(networkSectionTitle)

        // Icons Container
        val iconsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        // 5G Icon Container
        val fiveGContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 0, 16, 0)
        }

        fiveGIcon = TextView(this).apply {
            text = "5G"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.darker_gray))
            background = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.btn_default)
            setPadding(16, 16, 16, 16)
        }
        fiveGContainer.addView(fiveGIcon)

        val fiveGLabel = TextView(this).apply {
            text = "5G Network"
            textSize = 12f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(0, 8, 0, 0)
        }
        fiveGContainer.addView(fiveGLabel)

        // 4G Icon Container
        val fourGContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 0, 16, 0)
        }

        fourGIcon = TextView(this).apply {
            text = "4G"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
            background = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.btn_default)
            setPadding(16, 16, 16, 16)
        }
        fourGContainer.addView(fourGIcon)

        val fourGLabel = TextView(this).apply {
            text = "4G/LTE"
            textSize = 12f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(0, 8, 0, 0)
        }
        fourGContainer.addView(fourGLabel)

        iconsContainer.addView(fiveGContainer)
        iconsContainer.addView(fourGContainer)
        layout.addView(iconsContainer)

        // Network Status Text
        networkStatusText = TextView(this).apply {
            text = "Network: Detecting..."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(networkStatusText)

        // Signal Info Section
        val signalSectionTitle = TextView(this).apply {
            text = "📊 Signal Strength"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 20, 0, 16)
        }
        layout.addView(signalSectionTitle)

        signal4gText = TextView(this).apply {
            text = "4G Signal: --"
            textSize = 16f
            setPadding(0, 0, 0, 8)
        }
        layout.addView(signal4gText)

        signal5gText = TextView(this).apply {
            text = "5G Signal: Not detected"
            textSize = 16f
            setPadding(0, 0, 0, 8)
        }
        layout.addView(signal5gText)

        generalSignalText = TextView(this).apply {
            text = "General Signal: --"
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(generalSignalText)

        // THREE BUTTON LAYOUT - Option 1
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 16)
        }

        startButton = Button(this).apply {
            text = "START\nMON"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener { startMonitoring() }
        }
        buttonContainer.addView(startButton)

        stopButton = Button(this).apply {
            text = "STOP\nMON"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            }
            setOnClickListener { stopMonitoring() }
            isEnabled = false
        }
        buttonContainer.addView(stopButton)

        speedTestButton = Button(this).apply {
            text = "SPEED\nTEST"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 0, 0)
            }
            setOnClickListener { runSpeedTest() }
        }
        buttonContainer.addView(speedTestButton)

        layout.addView(buttonContainer)

        // Status and Speed Test Results
        statusText = TextView(this).apply {
            text = "Ready to start monitoring"
            textSize = 14f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 8)
        }
        layout.addView(statusText)

        speedTestText = TextView(this).apply {
            text = "Speed Test: Not run yet"
            textSize = 12f
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(0, 0, 0, 16)
        }
        layout.addView(speedTestText)

        setContentView(layout)
    }

    private fun initializeServices() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        updateDeviceInfo()
    }

    @SuppressLint("MissingPermission")
    private fun setupTelephonyListener() {
        if (!hasPhonePermission()) {
            Log.w(TAG, "Phone permission not granted, cannot setup telephony listener")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                phoneStateListener = object : PhoneStateListener() {
                    @Suppress("DEPRECATION")
                    override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                        super.onDisplayInfoChanged(telephonyDisplayInfo)

                        Log.d(TAG, "=== TELEPHONY DISPLAY INFO CHANGED ===")

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                val networkType = if (Build.VERSION.SDK_INT >= 30) {
                                    telephonyDisplayInfo.networkType
                                } else {
                                    -1
                                }

                                val overrideNetworkType = if (Build.VERSION.SDK_INT >= 30) {
                                    telephonyDisplayInfo.overrideNetworkType
                                } else {
                                    -1
                                }

                                Log.d(TAG, "Network Type: $networkType")
                                Log.d(TAG, "Override Network Type: $overrideNetworkType")

                                handleDisplayInfoChanged(networkType, overrideNetworkType)

                            } catch (e: Exception) {
                                Log.e(TAG, "Error reading TelephonyDisplayInfo: ${e.message}")
                            }
                        }
                    }
                }

                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED)
                Log.d(TAG, "PhoneStateListener with DisplayInfo registered")

            } else {
                Log.w(TAG, "TelephonyDisplayInfo not available on Android < 11, using fallback detection")
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException setting up telephony listener: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up telephony listener: ${e.message}")
        }
    }

    private fun handleDisplayInfoChanged(networkType: Int, overrideNetworkType: Int) {
        val was5GActive = is5GActive

        is5GActive = if (Build.VERSION.SDK_INT >= 30) {
            when (overrideNetworkType) {
                1, 3, 4 -> {
                    Log.d(TAG, "5G NSA/Advanced detected via TelephonyDisplayInfo!")
                    true
                }
                else -> {
                    Log.d(TAG, "No 5G override detected, checking base network type")
                    networkType == TelephonyManager.NETWORK_TYPE_NR
                }
            }
        } else {
            networkType == TelephonyManager.NETWORK_TYPE_NR
        }

        Log.d(TAG, "5G Status: was=$was5GActive, now=$is5GActive")

        if (was5GActive != is5GActive) {
            runOnUiThread {
                updateNetworkIcons()
                updateSignalInfo()
            }
        }
    }

    private fun hasPhonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateNetworkIcons() {
        runOnUiThread {
            if (is5GActive) {
                fiveGIcon.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                fourGIcon.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                networkStatusText.text = "Network: 5G NSA Active"
                Log.d(TAG, "UI Updated: 5G ACTIVE (Green 5G, Grey 4G)")
            } else {
                fiveGIcon.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
                fourGIcon.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                networkStatusText.text = "Network: 4G/LTE Active"
                Log.d(TAG, "UI Updated: 4G ONLY (Grey 5G, Green 4G)")
            }
        }
    }

    private fun updateDeviceInfo() {
        val model = Build.MODEL
        val androidVersion = Build.VERSION.RELEASE

        runOnUiThread {
            deviceInfoText.text = "Device ID: ${deviceId.take(12)}...\nModel: $model\nAndroid: $androidVersion (API ${Build.VERSION.SDK_INT})"
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateSignalInfo() {
        try {
            Log.d(TAG, "=== ENHANCED SIGNAL UPDATE START ===")

            if (!hasPhonePermission()) {
                Log.w(TAG, "Phone permission not granted, cannot read signal info")
                runOnUiThread {
                    signal4gText.text = "4G Signal: Permission required"
                    signal5gText.text = "5G Signal: Permission required"
                    generalSignalText.text = "Phone permission required"
                }
                return
            }

            // Collect enhanced signal metrics in background thread
            thread {
                try {
                    val enhancedMetrics = SignalCollectionUtils.collectEnhancedSignalMetrics(telephonyManager)
                    
                    // Update current metrics
                    currentCellMetrics = enhancedMetrics
                    lastCellMetricsUpdate = System.currentTimeMillis()
                    
                    Log.d(TAG, "Enhanced metrics collected: $enhancedMetrics")
                    
                    // Process signal results on UI thread
                    runOnUiThread {
                        processSignalResults(enhancedMetrics)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error collecting enhanced signal metrics", e)
                    // Fallback to basic signal collection
                    collectBasicSignalInfo()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateSignalInfo", e)
            runOnUiThread {
                signal4gText.text = "4G Signal: Error"
                signal5gText.text = "5G Signal: Error"
                generalSignalText.text = "Signal collection error"
            }
        }
    }
    
    private fun processSignalResults(metrics: CellMetrics) {
        try {
            // Update signal strength variables
            currentSignalStrength = when {
                metrics.is5G && metrics.signalStrength5G != -999 -> metrics.signalStrength5G
                metrics.signalStrength4G != -999 -> metrics.signalStrength4G
                else -> -999
            }
            
            currentNetworkType = if (metrics.is5G) "NR" else "LTE"
            
            // Update UI with enhanced metrics
            val signal4GText = if (metrics.signalStrength4G != -999) {
                val sinrText = if (metrics.sinr4G != -999.0) " | SINR: ${String.format("%.1f", metrics.sinr4G)}dB" else ""
                val cellIdText = if (metrics.cellId4G != -1) " | Cell: ${metrics.cellId4G}" else ""
                "4G Signal: ${metrics.signalStrength4G}dBm$sinrText$cellIdText"
            } else {
                "4G Signal: Not detected"
            }
            
            val signal5GText = if (metrics.signalStrength5G != -999) {
                val sinrText = if (metrics.sinr5G != -999.0) " | SINR: ${String.format("%.1f", metrics.sinr5G)}dB" else ""
                val cellIdText = if (metrics.cellId5G != -1L) " | Cell: ${metrics.cellId5G}" else ""
                "5G Signal: ${metrics.signalStrength5G}dBm$sinrText$cellIdText"
            } else {
                "5G Signal: Not detected"
            }
            
            val generalText = "General Signal: ${currentSignalStrength}dBm (${currentNetworkType})"
            
            // Update UI
            this.signal4gText.text = signal4GText
            this.signal5gText.text = signal5GText
            this.generalSignalText.text = generalText
            
            Log.d(TAG, "UI updated with enhanced metrics")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing signal results", e)
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun collectBasicSignalInfo() {
        try {
            val cellInfoList = telephonyManager.allCellInfo
            var has4G = false
            var has5GCells = false

            // Track the STRONGEST signals instead of last processed
            var bestSignalStrength4G = -999
            var bestSignalStrength5G = -999
            var cellCount4G = 0
            var cellCount5G = 0

            Log.d(TAG, "CellInfoList: ${if (cellInfoList != null) "Available (${cellInfoList.size} cells)" else "NULL"}")

            cellInfoList?.forEach { cellInfo ->
                Log.d(TAG, "Processing cell: ${cellInfo.javaClass.simpleName}")

                when (cellInfo) {
                    is CellInfoLte -> {
                        has4G = true
                        cellCount4G++
                        val lteSignal = cellInfo.cellSignalStrength as CellSignalStrengthLte
                        val rsrp = lteSignal.rsrp
                        if (rsrp != Int.MAX_VALUE && rsrp < 0) {
                            Log.d(TAG, "Valid 4G signal: ${rsrp}dBm")

                            if (bestSignalStrength4G == -999 || rsrp > bestSignalStrength4G) {
                                bestSignalStrength4G = rsrp
                                Log.d(TAG, "NEW BEST 4G signal: ${rsrp}dBm")
                            }
                        }
                    }
                    is CellInfoNr -> {
                        has5GCells = true
                        cellCount5G++
                        val nrSignal = cellInfo.cellSignalStrength as CellSignalStrengthNr
                        val ssRsrp = nrSignal.ssRsrp
                        if (ssRsrp != Int.MAX_VALUE && ssRsrp < 0) {
                            Log.d(TAG, "Valid 5G signal: ${ssRsrp}dBm")

                            if (bestSignalStrength5G == -999 || ssRsrp > bestSignalStrength5G) {
                                bestSignalStrength5G = ssRsrp
                                Log.d(TAG, "NEW BEST 5G signal: ${ssRsrp}dBm")
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "Signal Selection Summary:")
            Log.d(TAG, "  - 4G Cells: $cellCount4G, Best Signal: ${if (bestSignalStrength4G != -999) "${bestSignalStrength4G}dBm" else "None"}")
            Log.d(TAG, "  - 5G Cells: $cellCount5G, Best Signal: ${if (bestSignalStrength5G != -999) "${bestSignalStrength5G}dBm" else "None"}")

            // Enhanced 5G detection combining multiple methods
            val dataNetworkType = telephonyManager.dataNetworkType
            val voiceNetworkType = telephonyManager.voiceNetworkType
            val is5GByNetworkType = (dataNetworkType == TelephonyManager.NETWORK_TYPE_NR || voiceNetworkType == TelephonyManager.NETWORK_TYPE_NR)

            // Combine detection methods
            val combined5G = is5GActive || has5GCells || is5GByNetworkType

            Log.d(TAG, "5G Detection Summary:")
            Log.d(TAG, "  - TelephonyDisplayInfo: $is5GActive")
            Log.d(TAG, "  - CellInfoNr: $has5GCells")
            Log.d(TAG, "  - NetworkType: $is5GByNetworkType (data=$dataNetworkType, voice=$voiceNetworkType)")
            Log.d(TAG, "  - Combined Result: $combined5G")

            // Update 5G status based on combined detection
            if (combined5G != is5GActive) {
                is5GActive = combined5G
                runOnUiThread { updateNetworkIcons() }
            }

            // Fallback signal reading if no valid cell info
            if (bestSignalStrength4G == -999) {
                try {
                    val signalStrength = telephonyManager.signalStrength
                    signalStrength?.let { ss ->
                        if (ss.isGsm) {
                            val gsmSignal = ss.gsmSignalStrength
                            if (gsmSignal != 99) {
                                bestSignalStrength4G = -113 + 2 * gsmSignal
                                Log.d(TAG, "Fallback GSM signal: ${bestSignalStrength4G}dBm")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading fallback signal: ${e.message}")
                }
            }

            currentCarrier = telephonyManager.networkOperatorName ?: "Unknown"

            // Network type determination
            currentNetworkType = when {
                is5GActive -> "5G NSA"
                has5GCells -> "5G NR"
                dataNetworkType == TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                else -> "Unknown"
            }

            // Use the BEST signal strength available
            currentSignalStrength = when {
                is5GActive && bestSignalStrength5G != -999 -> bestSignalStrength5G
                bestSignalStrength4G != -999 -> bestSignalStrength4G
                else -> -999
            }

            Log.d(TAG, "Final Results: Network=$currentNetworkType, Signal=${currentSignalStrength}dBm, Carrier=$currentCarrier")

            runOnUiThread {
                // Dynamic signal label based on 5G status
                val signalLabel = if (is5GActive) "4G Anchor Signal" else "4G Signal"
                signal4gText.text = "$signalLabel: ${if (bestSignalStrength4G != -999) "${bestSignalStrength4G}dBm" else "No Signal"}"

                signal5gText.text = when {
                    is5GActive && bestSignalStrength5G != -999 -> "5G Signal: ${bestSignalStrength5G}dBm"
                    is5GActive -> "5G Signal: Active"
                    has5GCells -> "5G Signal: Detected (CellInfoNr)"
                    else -> "5G Signal: Not detected"
                }

                generalSignalText.text = "Network: $currentNetworkType\nCarrier: $currentCarrier\nSignal: ${if (currentSignalStrength != -999) "${currentSignalStrength}dBm" else "No Signal"}"
            }

            Log.d(TAG, "=== SIGNAL UPDATE COMPLETE ===")

        } catch (e: Exception) {
            Log.e(TAG, "Error updating signal info: ${e.message}")
            runOnUiThread {
                signal4gText.text = "4G Signal: Error"
                signal5gText.text = "5G Signal: Error"
                generalSignalText.text = "Error reading signal data"
            }
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            initializeLocation()
            updateSignalInfo()
            setupTelephonyListener()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                initializeLocation()
                updateSignalInfo()
                setupTelephonyListener()
            } else {
                showErrorDialog("Permissions Required", "This app requires location and phone permissions to function properly.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun initializeLocation() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Location permission not granted")
                return
            }

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 10f, this)
                lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                updateLocationDisplay()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing location: ${e.message}")
        }
    }

    private fun updateLocationDisplay() {
        runOnUiThread {
            lastLocation?.let { location ->
                gpsInfoText.text = "Lat: ${String.format("%.6f", location.latitude)}\n" +
                        "Lng: ${String.format("%.6f", location.longitude)}\n" +
                        "Accuracy: ${String.format("%.1f", location.accuracy)}m"
            } ?: run {
                gpsInfoText.text = "GPS: Searching for location..."
            }
        }
    }

    private fun startMonitoring() {
        if (isMonitoring) return

        thread {
            discoverServer()

            runOnUiThread {
                if (isServerAvailable) {
                    isMonitoring = true
                    startButton.isEnabled = false
                    stopButton.isEnabled = true
                    statusText.text = "📡 Monitoring active via $connectionType..."

                    startPeriodicUpdates()
                } else {
                    showErrorDialog("Server Error", "Cannot connect to monitoring server. Please check your internet connection or ensure the local server is running.")
                }
            }
        }
    }

    private fun stopMonitoring() {
        isMonitoring = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
        statusText.text = "Monitoring stopped"

        monitoringHandler?.removeCallbacks(monitoringRunnable!!)
        monitoringHandler = null
        monitoringRunnable = null
    }

    private fun startPeriodicUpdates() {
        monitoringHandler = Handler(Looper.getMainLooper())
        monitoringRunnable = object : Runnable {
            override fun run() {
                if (isMonitoring) {
                    updateSignalInfo()
                    sendDataToServer()
                    monitoringHandler?.postDelayed(this, 5000)
                }
            }
        }
        monitoringHandler?.post(monitoringRunnable!!)
    }

    private fun discoverServer() {
        Log.d(TAG, "=== SERVER DISCOVERY START ===")

        val serverUrls = listOf(
            "https://assurance.signal-monitor.com",
            "http://localhost:5000",
            "http://127.0.0.1:5000"
        )

        for (url in serverUrls) {
            Log.d(TAG, "Testing connection to: $url")

            if (testServerConnection(url)) {
                serverBaseUrl = url
                isServerAvailable = true
                connectionType = when {
                    url.startsWith("https://") -> "Internet HTTPS"
                    url.contains("localhost") || url.contains("127.0.0.1") -> "Local HTTP"
                    else -> "Unknown"
                }

                Log.d(TAG, "✅ SUCCESS: Connected via $connectionType")
                Log.d(TAG, "Server URL: $serverBaseUrl")
                return
            }
        }

        isServerAvailable = false
        serverBaseUrl = ""
        connectionType = "None"
        Log.e(TAG, "❌ FAILED: No server connection available")

        Log.d(TAG, "=== SERVER DISCOVERY COMPLETE ===")
    }

    private fun testServerConnection(url: String): Boolean {
        return try {
            val connection = if (url.startsWith("https://")) {
                URL("$url/api/health").openConnection() as HttpsURLConnection
            } else {
                URL("$url/api/health").openConnection() as HttpURLConnection
            }

            connection.apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "SignalTestEnhanced/1.0")
            }

            val responseCode = connection.responseCode
            val success = responseCode == 200

            Log.d(TAG, "Connection test result: $responseCode (${if (success) "SUCCESS" else "FAILED"})")

            connection.disconnect()
            success

        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed for $url: ${e.message}")
            false
        }
    }

    private fun sendDataToServer() {
        if (!isServerAvailable || lastLocation == null) {
            Log.w(TAG, "Cannot send data: server=${isServerAvailable}, location=${lastLocation != null}")
            return
        }

        thread {
            try {
                val data = JSONObject().apply {
                    put("device_id", deviceId)
                    put("latitude", lastLocation!!.latitude)
                    put("longitude", lastLocation!!.longitude)
                    put("accuracy", lastLocation!!.accuracy)
                    put("signal_strength", currentSignalStrength)
                    put("network_type", if (is5GActive) "NR" else currentNetworkType)
                    put("carrier", currentCarrier)
                    put("timestamp", System.currentTimeMillis())
                    put("is_5g_active", is5GActive)
                    put("connection_type", connectionType)
                    
                    // Enhanced signal metrics (SINR and Cell ID)
                    if (currentCellMetrics.isValid()) {
                        // 4G/LTE metrics
                        if (currentCellMetrics.signalStrength4G != -999) {
                            put("signal_strength_4g", currentCellMetrics.signalStrength4G)
                            if (currentCellMetrics.sinr4G != -999.0) {
                                put("sinr_4g", currentCellMetrics.sinr4G)
                            }
                            if (currentCellMetrics.cellId4G != -1) {
                                put("cell_id_4g", currentCellMetrics.cellId4G)
                            }
                            if (currentCellMetrics.pci4G != -1) {
                                put("pci_4g", currentCellMetrics.pci4G)
                            }
                        }
                        
                        // 5G/NR metrics
                        if (currentCellMetrics.signalStrength5G != -999) {
                            put("signal_strength_5g", currentCellMetrics.signalStrength5G)
                            if (currentCellMetrics.sinr5G != -999.0) {
                                put("sinr_5g", currentCellMetrics.sinr5G)
                            }
                            if (currentCellMetrics.cellId5G != -1L) {
                                put("cell_id_5g", currentCellMetrics.cellId5G)
                            }
                            if (currentCellMetrics.pci5G != -1) {
                                put("pci_5g", currentCellMetrics.pci5G)
                            }
                        }
                        
                        // Metadata
                        put("enhanced_metrics_available", true)
                        put("metrics_timestamp", lastCellMetricsUpdate)
                    } else {
                        put("enhanced_metrics_available", false)
                    }
                }

                val connection = if (serverBaseUrl.startsWith("https://")) {
                    URL("$serverBaseUrl/api/signal").openConnection() as HttpsURLConnection
                } else {
                    URL("$serverBaseUrl/api/signal").openConnection() as HttpURLConnection
                }

                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "SignalTestEnhanced/1.0")
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(data.toString())
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "Data sent via $connectionType, response: $responseCode")

                runOnUiThread {
                    statusText.text = "📡 Data sent via $connectionType (${responseCode})"
                }

                connection.disconnect()

            } catch (e: Exception) {
                Log.e(TAG, "Error sending data to server: ${e.message}")
                runOnUiThread {
                    statusText.text = "❌ Server communication error"
                }
            }
        }
    }

    // Speed Test Implementation
    private fun runSpeedTest() {
        if (isSpeedTestRunning) {
            Toast.makeText(this, "Speed test already running", Toast.LENGTH_SHORT).show()
            return
        }

        isSpeedTestRunning = true
        speedTestButton.isEnabled = false
        speedTestText.text = "Running speed test... (~20MB data usage)"

        Toast.makeText(this, "Running Cloudflare speed test (~20MB data usage)", Toast.LENGTH_LONG).show()

        thread {
            try {
                Log.d(TAG, "Starting speed test...")

                // Measure Latency (10 pings)
                val latencies = mutableListOf<Long>()
                for (i in 0 until 10) {
                    latencies.add(measureLatency())
                }
                val avgLatency = latencies.average()
                val jitter = calculateJitter(latencies)

                // Measure Download (10MB)
                val downloadSpeed = measureDownload(10000000L)

                // Measure Upload (10MB)
                val uploadSpeed = measureUpload(10000000L)

                Log.d(TAG, "Speed test completed: Download: $downloadSpeed Mbps, Upload: $uploadSpeed Mbps, Ping: $avgLatency ms")

                // Update UI
                runOnUiThread {
                    speedTestText.text = "↓${String.format("%.1f", downloadSpeed)}Mbps ↑${String.format("%.1f", uploadSpeed)}Mbps ${String.format("%.0f", avgLatency)}ms"
                }

                // Send results to server
                sendSpeedTestResultsToServer(downloadSpeed, uploadSpeed, avgLatency, jitter)

            } catch (e: Exception) {
                Log.e(TAG, "Speed test error: ${e.message}")
                runOnUiThread {
                    speedTestText.text = "Speed Test Error: ${e.message}"
                }
            } finally {
                isSpeedTestRunning = false
                runOnUiThread {
                    speedTestButton.isEnabled = true
                }
            }
        }
    }

    private fun measureLatency(): Long {
        val start = System.currentTimeMillis()
        try {
            val url = URL("https://speed.cloudflare.com/__up")
            val conn = url.openConnection() as HttpsURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.connect()
            conn.responseCode
            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Latency measurement error: ${e.message}")
        }
        return System.currentTimeMillis() - start
    }

    private fun calculateJitter(latencies: List<Long>): Double {
        if (latencies.size < 2) return 0.0
        var sumDiff = 0.0
        for (i in 1 until latencies.size) {
            sumDiff += Math.abs(latencies[i] - latencies[i - 1])
        }
        return sumDiff / (latencies.size - 1)
    }

    private fun measureDownload(size: Long): Double {
        var speed = 0.0
        try {
            val url = URL("https://speed.cloudflare.com/__down?bytes=$size")
            val conn = url.openConnection() as HttpsURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            val start = System.currentTimeMillis()
            val input = conn.inputStream
            var bytesRead: Long = 0
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                bytesRead += read
            }
            input.close()
            conn.disconnect()
            val time = (System.currentTimeMillis() - start) / 1000.0
            if (time > 0) speed = (bytesRead * 8 / 1000000.0) / time
        } catch (e: Exception) {
            Log.e(TAG, "Download measurement error: ${e.message}")
        }
        return speed
    }

    private fun measureUpload(size: Long): Double {
        var speed = 0.0
        try {
            val url = URL("https://speed.cloudflare.com/__up")
            val conn = url.openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.setRequestProperty("Content-Length", size.toString())
            val start = System.currentTimeMillis()
            val output = conn.outputStream
            val buffer = ByteArray(8192)
            var bytesWritten: Long = 0
            while (bytesWritten < size) {
                val toWrite = minOf(8192L, size - bytesWritten).toInt()
                output.write(buffer, 0, toWrite)
                bytesWritten += toWrite
            }
            output.flush()
            output.close()
            conn.responseCode
            conn.disconnect()
            val time = (System.currentTimeMillis() - start) / 1000.0
            if (time > 0) speed = (size * 8 / 1000000.0) / time
        } catch (e: Exception) {
            Log.e(TAG, "Upload measurement error: ${e.message}")
        }
        return speed
    }

    private fun sendSpeedTestResultsToServer(downloadSpeed: Double, uploadSpeed: Double, latency: Double, jitter: Double) {
        if (!isServerAvailable || lastLocation == null) {
            Log.w(TAG, "Cannot send speed test results: server=${isServerAvailable}, location=${lastLocation != null}")
            return
        }

        Log.d(TAG, "Sending speed test results to server...")

        thread {
            try {
                val data = JSONObject().apply {
                    // Existing signal data
                    put("device_id", deviceId)
                    put("latitude", lastLocation!!.latitude)
                    put("longitude", lastLocation!!.longitude)
                    put("accuracy", lastLocation!!.accuracy)
                    put("signal_strength", currentSignalStrength)
                    put("network_type", if (is5GActive) "NR" else currentNetworkType)
                    put("carrier", currentCarrier)
                    put("timestamp", System.currentTimeMillis())
                    put("is_5g_active", is5GActive)
                    put("connection_type", connectionType)

                    // Speed test results
                    put("speed_test_download_mbps", downloadSpeed)
                    put("speed_test_upload_mbps", uploadSpeed)
                    put("speed_test_latency_ms", latency)
                    put("speed_test_jitter_ms", jitter)
                    put("has_speed_test_data", true)
                }

                val connection = if (serverBaseUrl.startsWith("https://")) {
                    URL("$serverBaseUrl/api/signal").openConnection() as HttpsURLConnection
                } else {
                    URL("$serverBaseUrl/api/signal").openConnection() as HttpURLConnection
                }

                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "SignalTestEnhanced/1.0")
                    connectTimeout = 10000
                    readTimeout = 10000
                    doOutput = true
                }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(data.toString())
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "Speed test results sent via $connectionType, response: $responseCode")

                runOnUiThread {
                    if (responseCode == 200 || responseCode == 201) {
                        Toast.makeText(this@MainActivity, "Speed test results sent to server", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Server response: $responseCode", Toast.LENGTH_SHORT).show()
                    }
                }

                connection.disconnect()

            } catch (e: Exception) {
                Log.e(TAG, "Error sending speed test results to server: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to send results to server", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
        updateLocationDisplay()
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            if (phoneStateListener != null) {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering telephony listener: ${e.message}")
        }

        stopMonitoring()
    }
}