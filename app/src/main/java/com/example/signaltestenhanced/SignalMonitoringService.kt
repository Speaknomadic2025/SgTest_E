package com.example.signaltestenhanced

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telephony.*
import android.util.Log
import androidx.core.app.NotificationCompat
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

class SignalMonitoringService : Service(), LocationListener {

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var locationManager: LocationManager
    private lateinit var wifiManager: WifiManager

    private var serverUrl = ""
    private var deviceId = ""
    private var useHttps = false
    private var currentLocation: Location? = null
    private var isRunning = false

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val executor = Executors.newFixedThreadPool(2)

    companion object {
        private const val CHANNEL_ID = "SignalMonitoringChannel"
        private const val NOTIFICATION_ID = 1
        private const val MONITORING_INTERVAL = 3000L
    }

    override fun onCreate() {
        super.onCreate()

        try {
            // Initialize services
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            // Initialize device ID
            deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

            // Create notification channel
            createNotificationChannel()

            Log.d("SignalService", "Service created successfully")

        } catch (e: Exception) {
            Log.e("SignalService", "Error in onCreate: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Get parameters from intent
            serverUrl = intent?.getStringExtra("serverUrl") ?: ""
            deviceId = intent?.getStringExtra("deviceId") ?: deviceId
            useHttps = intent?.getBooleanExtra("useHttps", false) ?: false

            // Start foreground service
            startForegroundService()

            // Start monitoring
            startMonitoring()

            Log.d("SignalService", "Service started with server: $serverUrl")

        } catch (e: Exception) {
            Log.e("SignalService", "Error in onStartCommand: ${e.message}")
        }

        return START_STICKY // Restart service if killed
    }

    override fun onBind(intent: Intent): IBinder? {
        return null // This is a started service, not bound
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Signal Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Continuous signal strength and location monitoring"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = createNotification(
            "Signal Monitor Active",
            "Monitoring signal strength and location in background..."
        )

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startMonitoring() {
        if (isRunning) return

        isRunning = true

        // Start location updates
        startLocationUpdates()

        // Discover server if not provided
        if (serverUrl.isEmpty()) {
            discoverServer()
        } else {
            registerDevice()
        }

        // Start monitoring loop
        serviceScope.launch {
            while (isRunning) {
                try {
                    performMonitoringCycle()
                    delay(MONITORING_INTERVAL)
                } catch (e: Exception) {
                    Log.e("SignalService", "Error in monitoring cycle: ${e.message}")
                    delay(MONITORING_INTERVAL)
                }
            }
        }

        Log.d("SignalService", "Background monitoring started")
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 10f, this)
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 10f, this)
            } catch (e: Exception) {
                Log.e("SignalService", "Location updates error: ${e.message}")
            }
        }
    }

    private fun discoverServer() {
        serviceScope.launch {
            try {
                val discoveredUrl = performServerDiscovery()

                if (discoveredUrl.isNotEmpty()) {
                    serverUrl = discoveredUrl
                    Log.d("SignalService", "Server discovered at: $serverUrl")
                    registerDevice()
                    updateNotification("Server Connected", "Monitoring active at $serverUrl")
                } else {
                    Log.w("SignalService", "Server not found - running in offline mode")
                    updateNotification("Offline Mode", "Server not found - monitoring locally")
                }

            } catch (e: Exception) {
                Log.e("SignalService", "Server discovery error: ${e.message}")
                updateNotification("Discovery Error", "Server discovery failed - monitoring locally")
            }
        }
    }

    private suspend fun performServerDiscovery(): String {
        val protocol = if (useHttps) "https" else "http"
        val port = 5000

        // Priority IPs including the user's server
        val priorityIPs = listOf(
            "192.168.23.35",  // User's exact server IP
            "192.168.23.1",
            "192.168.1.35",
            "192.168.1.1",
            "192.168.0.35",
            "192.168.0.1",
            "10.0.0.35",
            "10.0.0.1"
        )

        // Test priority IPs first
        for (ip in priorityIPs) {
            val testUrl = "$protocol://$ip:$port"
            if (testServerConnection(testUrl)) {
                return testUrl
            }
        }

        // Scan network interfaces
        return discoverViaNetworkInterface(protocol, port)
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

                                // Test common IPs in this subnet
                                for (lastOctet in listOf(35, 1, 10, 100, 50)) {
                                    val testUrl = "$protocol://$subnet.$lastOctet:$port"
                                    if (testServerConnection(testUrl)) {
                                        return@withContext testUrl
                                    }
                                }
                            }
                        }
                    }
                }
                ""
            } catch (e: Exception) {
                Log.e("SignalService", "Interface discovery error: ${e.message}")
                ""
            }
        }
    }

    private fun testServerConnection(url: String): Boolean {
        return try {
            val connection = URL("$url/api/devices").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 1000
            connection.readTimeout = 1000

            val responseCode = connection.responseCode
            connection.disconnect()

            responseCode in 200..299 || responseCode == 404
        } catch (e: Exception) {
            false
        }
    }

    private fun registerDevice() {
        serviceScope.launch {
            try {
                val deviceData = JSONObject().apply {
                    put("device_id", deviceId)
                    put("device_name", "Samsung S24 Ultra (Background Service)")
                    put("device_model", Build.MODEL)
                    put("android_version", Build.VERSION.RELEASE)
                    put("app_status", "background_service")
                    put("https_enabled", useHttps)
                    put("monitoring_mode", "background")
                    put("service_version", "enhanced")

                    currentLocation?.let {
                        put("latitude", it.latitude)
                        put("longitude", it.longitude)
                        put("location_accuracy", it.accuracy)
                    }
                }

                sendDataToServer("/api/devices/register", deviceData)
                Log.d("SignalService", "Device registered successfully")

            } catch (e: Exception) {
                Log.e("SignalService", "Registration error: ${e.message}")
            }
        }
    }

    private suspend fun performMonitoringCycle() {
        try {
            // Collect signal data
            val signalData = collectSignalData()

            // Send to server if available
            if (serverUrl.isNotEmpty()) {
                sendDataToServer("/api/devices/$deviceId/signal", signalData)
            }

            // Update notification with current status
            val signalStrength = signalData.optInt("signal_strength", -999)
            val networkType = signalData.optString("network_type", "Unknown")

            val statusText = if (serverUrl.isNotEmpty()) {
                "Connected - $networkType: ${signalStrength} dBm"
            } else {
                "Offline - $networkType: ${signalStrength} dBm"
            }

            updateNotification("Signal Monitor Active", statusText)

        } catch (e: Exception) {
            Log.e("SignalService", "Error in monitoring cycle: ${e.message}")
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
                data.put("speed", if (it.hasSpeed()) it.speed else null)
                data.put("bearing", if (it.hasBearing()) it.bearing else null)
            }

            data.put("https_enabled", useHttps)
            data.put("monitoring_mode", "background")
            data.put("background_service_active", true)

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            data.put("timestamp", dateFormat.format(Date()))

        } catch (e: Exception) {
            Log.e("SignalService", "Error collecting signal data: ${e.message}")
        }

        return data
    }

    private suspend fun sendDataToServer(endpoint: String, data: JSONObject) {
        withContext(Dispatchers.IO) {
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

                if (responseCode in 200..299) {
                    Log.d("SignalService", "Data sent successfully: $responseCode")
                } else {
                    Log.w("SignalService", "Server response: $responseCode")
                }

            } catch (e: Exception) {
                Log.e("SignalService", "Error sending data: ${e.message}")
                // Server might be unreachable, continue monitoring locally
            }
        }
    }

    private fun updateNotification(title: String, content: String) {
        try {
            val notification = createNotification(title, content)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("SignalService", "Error updating notification: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        currentLocation = location

        // Send location update to server if available
        if (serverUrl.isNotEmpty()) {
            serviceScope.launch {
                try {
                    val locationData = JSONObject().apply {
                        put("latitude", location.latitude)
                        put("longitude", location.longitude)
                        put("accuracy", location.accuracy)
                        put("speed", if (location.hasSpeed()) location.speed else null)
                        put("bearing", if (location.hasBearing()) location.bearing else null)
                        put("https_enabled", useHttps)
                        put("monitoring_mode", "background")
                        put("background_service_active", true)

                        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        put("timestamp", dateFormat.format(Date()))
                    }
                    sendDataToServer("/api/devices/$deviceId/location", locationData)
                } catch (e: Exception) {
                    Log.e("SignalService", "Error sending location: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            isRunning = false
            serviceScope.cancel()
            executor.shutdown()

            // Remove location updates
            locationManager.removeUpdates(this)

            Log.d("SignalService", "Service destroyed")

        } catch (e: Exception) {
            Log.e("SignalService", "Error in onDestroy: ${e.message}")
        }
    }
}