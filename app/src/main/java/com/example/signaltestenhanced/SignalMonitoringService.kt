package com.example.signaltestenhanced

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
import android.os.Build
import android.os.IBinder
import android.telephony.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class SignalMonitoringService : Service(), LocationListener {

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var locationManager: LocationManager

    private var serverUrl = ""
    private var deviceId = ""
    private var useHttps = false
    private var currentLocation: Location? = null
    private var isRunning = false

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "signal_monitoring_channel"
        private const val MONITORING_INTERVAL = 3000L // 3 seconds
    }

    override fun onCreate() {
        super.onCreate()

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverUrl = intent?.getStringExtra("serverUrl") ?: ""
        deviceId = intent?.getStringExtra("deviceId") ?: ""
        useHttps = intent?.getBooleanExtra("useHttps", false) ?: false

        if (serverUrl.isNotEmpty()) {
            startForegroundService()
            startLocationUpdates()
            startMonitoring()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Signal Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background signal strength monitoring"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = createNotification("🔄 Initializing background monitoring...")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📡 Signal Monitor Enhanced")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MONITORING_INTERVAL,
                    1f,
                    this
                )
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MONITORING_INTERVAL,
                    1f,
                    this
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startMonitoring() {
        isRunning = true

        serviceScope.launch {
            while (isRunning) {
                try {
                    val signalData = collectSignalData()
                    sendDataToServer("/api/devices/$deviceId/signal", signalData)

                    // Update notification with current status
                    val signalStrength = signalData.optInt("signal_strength", -999)
                    val networkType = signalData.optString("network_type", "Unknown")
                    val status = "📶 $networkType: ${signalStrength}dBm | 🔄 Active"

                    withContext(Dispatchers.Main) {
                        updateNotification(status)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        updateNotification("⚠️ Monitoring error - retrying...")
                    }
                }

                delay(MONITORING_INTERVAL)
            }
        }

        updateNotification("✅ Background monitoring active")
    }

    @SuppressLint("MissingPermission")
    private fun collectSignalData(): JSONObject {
        val data = JSONObject()

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val cellInfos = telephonyManager.allCellInfo
                var signalStrength = -999
                var networkType = "Unknown"
                var rsrp = -999
                var rsrq = -999

                cellInfos?.forEach { cellInfo ->
                    when (cellInfo) {
                        is CellInfoLte -> {
                            val lteStrength = cellInfo.cellSignalStrength
                            signalStrength = lteStrength.dbm
                            networkType = "4G LTE"
                            rsrp = lteStrength.rsrp
                            rsrq = lteStrength.rsrq
                        }
                        is CellInfoNr -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val nrStrength = cellInfo.cellSignalStrength as CellSignalStrengthNr
                                signalStrength = nrStrength.dbm
                                networkType = "5G NR"
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    rsrp = nrStrength.ssRsrp
                                    rsrq = nrStrength.ssRsrq
                                }
                            }
                        }
                        is CellInfoGsm -> {
                            signalStrength = cellInfo.cellSignalStrength.dbm
                            networkType = "2G GSM"
                        }
                        is CellInfoWcdma -> {
                            signalStrength = cellInfo.cellSignalStrength.dbm
                            networkType = "3G WCDMA"
                        }
                    }
                }

                data.put("signal_strength", signalStrength)
                data.put("network_type", networkType)
                data.put("carrier", telephonyManager.networkOperatorName ?: "Unknown")
                data.put("rsrp", rsrp)
                data.put("rsrq", rsrq)
            }

            // Add location data
            currentLocation?.let {
                data.put("latitude", it.latitude)
                data.put("longitude", it.longitude)
                data.put("accuracy", it.accuracy)
                data.put("speed", it.speed)
                data.put("bearing", it.bearing)
            }

            // Add service-specific fields
            data.put("https_enabled", useHttps )
            data.put("monitoring_mode", "background")
            data.put("background_service_active", true)
            data.put("service_type", "foreground_service")

            // Add timestamp
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            data.put("timestamp", dateFormat.format(Date()))

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return data
    }

    private suspend fun sendDataToServer(endpoint: String, data: JSONObject) {
        try {
            val url = URL(serverUrl + endpoint)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "SignalMonitorEnhanced-BackgroundService")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true

            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(data.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            // Log response if needed for debugging

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onLocationChanged(location: Location) {
        currentLocation = location

        // Send location update to server
        if (serverUrl.isNotEmpty()) {
            serviceScope.launch {
                try {
                    val locationData = JSONObject().apply {
                        put("latitude", location.latitude)
                        put("longitude", location.longitude)
                        put("accuracy", location.accuracy)
                        put("speed", location.speed)
                        put("bearing", location.bearing)
                        put("https_enabled", useHttps )
                        put("monitoring_mode", "background")
                        put("background_service_active", true)

                        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        put("timestamp", dateFormat.format(Date()))
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
        isRunning = false
        serviceScope.cancel()

        try {
            locationManager.removeUpdates(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
