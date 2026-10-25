package com.awclippy.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException
import java.net.*
import java.security.MessageDigest
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString

class ClipboardSyncService : Service() {
    companion object {
        private const val TAG = "AWClippy"
        private const val MAX_CLIPBOARD_SIZE = 1024 * 1024 // 1MB
        const val ACTION_START_SERVICE = "com.awclippy.android.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.awclippy.android.STOP_SERVICE"
        private const val PREFS_NAME = "awclippy_prefs"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        
        @Volatile
        var instance: ClipboardSyncService? = null
            private set

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        fun isServiceRequested(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_SERVICE_ENABLED, true)
        }

        fun setServiceRequested(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
        }
    }

    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var okHttpClient: OkHttpClient? = null
    private var activeWebSocket: WebSocket? = null
    private var nsdJob: Job? = null
    private var retryJob: Job? = null
    private var heartbeatJob: Job? = null
    
    private val config = ClipboardConfig()
    private val crypto = ClipboardCrypto(config.sharedSecret)
    
    // Wi-Fi multicast lock for mDNS reliability
    private var multicastLock: WifiManager.MulticastLock? = null
    
    // Cached Windows PC address and identity
    @Volatile
    private var windowsPcAddress: InetSocketAddress? = null

    @Volatile
    private var synchronizedPcName: String? = null

    @Volatile
    private var isSynchronized: Boolean = false

    @Volatile
    private var lastSyncTimestamp: Long = 0L

    @Volatile
    private var currentNetworkName: String = "Local Network"
    
    // Pending clipboard to send (queued when Windows PC not yet discovered)
    @Volatile
    private var pendingClipboard: String? = null

    @Volatile
    private var isServiceStopped: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isServiceStopped = false
        isServiceRunning = true
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        setServiceRequested(this, true)
        Log.d(TAG, "ClipboardSyncService starting...")
        
        currentNetworkName = getActiveNetworkName()
        Log.d(TAG, "Initial network: $currentNetworkName")
        
        // Start foreground notification immediately
        startForegroundNotification()
        
        // Acquire multicast lock for reliable mDNS
        acquireMulticastLock()
        
        // Start discovery methods
        startDiscoveryJobs()

        // Start heartbeat and connection monitor
        startHeartbeatMonitor()
    }
    
    private fun startDiscoveryJobs() {
        if (isServiceStopped || !isServiceRunning) return
        nsdJob?.cancel()
        nsdJob = serviceScope.launch {
            startNsdDiscovery()
        }
    }

    private fun startHeartbeatMonitor() {
        if (isServiceStopped || !isServiceRunning) return
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive && !isServiceStopped && isServiceRunning) {
                delay(10000) // Check every 10 seconds
                if (isServiceStopped || !isServiceRunning) break
                
                // Refresh network name
                val updatedNetwork = getActiveNetworkName()
                if (updatedNetwork != currentNetworkName) {
                    currentNetworkName = updatedNetwork
                    if (!isSynchronized) {
                        updateNotification("Connected to $currentNetworkName (Searching for PC...)")
                    } else if (synchronizedPcName != null) {
                        updateNotification("Connected to $currentNetworkName, but synchronized with $synchronizedPcName")
                    }
                }

                // If disconnected but we have target address, attempt reconnect
                if (!isSynchronized && activeWebSocket == null && !isServiceStopped) {
                    val target = windowsPcAddress
                    if (target != null) {
                        connectWebSocket(target)
                    }
                }
            }
        }
    }

    fun triggerRediscovery() {
        if (isServiceStopped || !isServiceRunning) return
        Log.d(TAG, "Triggering re-discovery of Windows PC...")
        startDiscoveryJobs()
    }

    fun getNetworkName(): String = currentNetworkName

    fun getPcName(): String? = synchronizedPcName

    fun isDeviceSynchronized(): Boolean = isSynchronized

    fun getLastSyncTime(): Long = lastSyncTimestamp

    fun getActiveNetworkName(): String {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                // 1. Check active default network
                val activeNetwork = cm.activeNetwork
                val activeCaps = cm.getNetworkCapabilities(activeNetwork)
                if (activeCaps != null) {
                    if (activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        val wifiInfo = activeCaps.transportInfo as? WifiInfo
                        val ssid = wifiInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"")
                        if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>") {
                            return ssid
                        }
                        return "Wi-Fi"
                    } else if (activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        return "Ethernet"
                    }
                }

                // 2. Fallback: check if Wi-Fi interface is active even if not the primary default network
                val wifiSsid = findConnectedWifiSsid(cm)
                if (wifiSsid != null) {
                    return wifiSsid
                }

                if (activeCaps != null && activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return "Mobile Data"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting network name: ${e.message}")
        }
        return "Local Network"
    }

    @Suppress("DEPRECATION")
    private fun findConnectedWifiSsid(cm: ConnectivityManager): String? {
        return try {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val wifiInfo = caps.transportInfo as? WifiInfo
                    val ssid = wifiInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"")
                    if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>") {
                        return ssid
                    }
                    return "Wi-Fi"
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun getDeviceModelName(): String {
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model.replaceFirstChar { it.uppercase() }
        } else {
            "${manufacturer.replaceFirstChar { it.uppercase() }} $model"
        }
    }

    private fun createStopServicePendingIntent(): android.app.PendingIntent {
        val stopIntent = Intent(this, ClipboardSyncService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        return android.app.PendingIntent.getService(
            this,
            1,
            stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun startForegroundNotification() {
        if (isServiceStopped || !isServiceRunning) return
        val channelId = "awclippy_sync"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Clipboard Sync",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Syncs clipboard with Windows PC"
            }
            
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
        
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        val initialText = if (isSynchronized && synchronizedPcName != null) {
            "Connected to $currentNetworkName, but synchronized with $synchronizedPcName"
        } else {
            "Connected to $currentNetworkName (Searching for PC...)"
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("AW Clippy")
            .setContentText(initialText)
            .setSmallIcon(NotificationIconHelper.getNotificationIcon())
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Service", createStopServicePendingIntent())
            .setOngoing(true)
            .build()
        
        startForeground(1, notification)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.d(TAG, "onStartCommand: Received ACTION_STOP_SERVICE")
            setServiceRequested(this, false)
            stopSyncService()
            return START_NOT_STICKY
        }
        
        // Reset stopped flag when restarted
        if (isServiceStopped) {
            isServiceStopped = false
            isServiceRunning = true
            instance = this
        }
        setServiceRequested(this, true)

        intent?.getStringExtra("clipboard_text")?.let { text ->
            if (!isServiceStopped && isServiceRunning) {
                serviceScope.launch {
                    sendClipboard(text)
                }
            }
        }
        return START_NOT_STICKY
    }

    private var nsdDiscoveryListener: android.net.nsd.NsdManager.DiscoveryListener? = null
    private var nsdRegistrationListener: android.net.nsd.NsdManager.RegistrationListener? = null

    fun stopSyncService() {
        Log.d(TAG, "ClipboardSyncService stopSyncService() called - stopping everything cleanly")
        
        isServiceStopped = true
        isServiceRunning = false
        instance = null
        
        isSynchronized = false
        synchronizedPcName = null
        windowsPcAddress = null
        pendingClipboard = null

        // 1. Stop foreground mode and remove notification immediately
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground: ${e.message}")
        }
        
        // Cancel notification explicitly as a backup
        try {
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager?.cancel(1)
            notificationManager?.cancelAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling notification: ${e.message}")
        }
        
        // 2. Release multicast lock
        releaseMulticastLock()
        
        // 3. Immediately cancel active WebSocket and cancel OkHttpClient
        try {
            activeWebSocket?.cancel()
        } catch (_: Exception) {}
        activeWebSocket = null

        try {
            okHttpClient?.dispatcher?.cancelAll()
            okHttpClient?.connectionPool?.evictAll()
        } catch (_: Exception) {}
        okHttpClient = null

        // 4. Stop NSD discovery and unregister service
        val nsdManager = getSystemService(NSD_SERVICE) as? android.net.nsd.NsdManager
        nsdDiscoveryListener?.let {
            try { nsdManager?.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
        nsdRegistrationListener?.let {
            try { nsdManager?.unregisterService(it) } catch (_: Exception) {}
        }
        nsdDiscoveryListener = null
        nsdRegistrationListener = null

        // 5. Cancel all coroutine jobs
        nsdJob?.cancel()
        retryJob?.cancel()
        heartbeatJob?.cancel()
        serviceScope.cancel()
        
        // 6. Stop the service
        stopSelf()
        Log.d(TAG, "ClipboardSyncService fully stopped and cleaned up")
    }

    override fun onDestroy() {
        Log.d(TAG, "ClipboardSyncService onDestroy() called")
        if (!isServiceStopped) {
            stopSyncService()
        }
        super.onDestroy()
    }
    
    private fun acquireMulticastLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("awclippy_mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Multicast lock acquired for mDNS reliability")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock: ${e.message}")
        }
    }
    
    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
            multicastLock = null
            Log.d(TAG, "Multicast lock released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release multicast lock: ${e.message}")
        }
    }
    
    fun sendClipboardFromAccessibility(text: String) {
        if (isServiceStopped || !isServiceRunning) {
            Log.d(TAG, "[ANDROID→WINDOWS] Service is stopped, ignoring clipboard send request")
            return
        }
        Log.d(TAG, "[ANDROID→WINDOWS] sendClipboardFromAccessibility called with ${text.length} chars")
        
        if (!isSynchronized || activeWebSocket == null) {
            Log.w(TAG, "[ANDROID→WINDOWS] ⏳ No active WebSocket connection yet, queueing clipboard...")
            pendingClipboard = text
            
            if (retryJob == null || !retryJob!!.isActive) {
                retryJob = serviceScope.launch {
                    retryPendingClipboard()
                }
            }
            return
        }
        
        serviceScope.launch {
            sendClipboard(text)
        }
    }
    
    private suspend fun retryPendingClipboard() {
        var attempts = 0
        val maxAttempts = 100 // 10 seconds total
        
        while (attempts < maxAttempts && pendingClipboard != null && !isServiceStopped && isServiceRunning) {
            delay(100)
            attempts++
            
            if (isServiceStopped || !isServiceRunning) {
                pendingClipboard = null
                return
            }
            
            if (isSynchronized && activeWebSocket != null) {
                val textToSend = pendingClipboard
                pendingClipboard = null
                
                if (textToSend != null) {
                    Log.d(TAG, "[ANDROID→WINDOWS] ✅ Windows PC synchronized after ${attempts * 100}ms! Sending queued clipboard...")
                    sendClipboard(textToSend)
                }
                return
            }
        }
        
        if (pendingClipboard != null) {
            Log.e(TAG, "[ANDROID→WINDOWS] ❌ Windows PC not synchronized after ${maxAttempts * 100}ms, clearing queue")
            pendingClipboard = null
        }
    }
    
    fun hasWindowsDevice(): Boolean {
        return isSynchronized && activeWebSocket != null && !isServiceStopped && isServiceRunning
    }

    private fun connectWebSocket(target: InetSocketAddress) {
        if (isServiceStopped || !isServiceRunning) return
        
        if (activeWebSocket != null && isSynchronized && windowsPcAddress?.address == target.address) {
            return
        }

        try {
            activeWebSocket?.close(1000, "Reconnecting")
        } catch (_: Exception) {}
        activeWebSocket = null

        val client = okHttpClient ?: OkHttpClient.Builder()
            .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build().also { okHttpClient = it }

        val host = target.address.hostAddress ?: return
        val port = target.port
        val url = "ws://$host:$port"
        Log.d(TAG, "[WEBSOCKET] Connecting to $url...")

        val request = Request.Builder().url(url).build()
        activeWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (isServiceStopped || !isServiceRunning) {
                    webSocket.cancel()
                    return
                }
                Log.d(TAG, "[WEBSOCKET] ✅ Link established with Windows PC at $host:$port")
                windowsPcAddress = target
                
                // Immediately send HANDSHAKE over WebSocket
                val handshakeJson = JSONObject().apply {
                    put("type", "HANDSHAKE")
                    put("device_name", getDeviceModelName())
                    put("network_name", currentNetworkName)
                }
                val encrypted = crypto.encrypt(handshakeJson.toString())
                webSocket.send(encrypted.toByteString())
                Log.d(TAG, "[HANDSHAKE] Sent HANDSHAKE over persistent WebSocket link")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (isServiceStopped || !isServiceRunning) return
                try {
                    val decrypted = crypto.decrypt(bytes.toByteArray())
                    handleDecryptedPayload(decrypted)
                } catch (e: Exception) {
                    Log.e(TAG, "[WEBSOCKET] Decryption error: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isServiceStopped || !isServiceRunning) return
                handleDecryptedPayload(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "[WEBSOCKET] Link closed ($code): $reason")
                if (isServiceStopped || !isServiceRunning) return
                handleDisconnection()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "[WEBSOCKET] Link failure: ${t.message}")
                if (isServiceStopped || !isServiceRunning) return
                handleDisconnection()
            }
        })
    }

    private fun handleDisconnection() {
        if (isServiceStopped || !isServiceRunning) return
        activeWebSocket = null
        isSynchronized = false
        synchronizedPcName = null
        updateNotification("Connected to $currentNetworkName (Searching for PC...)")
    }

    private fun handleDecryptedPayload(text: String) {
        if (isServiceStopped || !isServiceRunning) return
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                val type = json.optString("type")

                when (type) {
                    "HANDSHAKE_ACK" -> {
                        val pcName = json.optString("device_name", "Windows PC")
                        val pcNetName = json.optString("network_name", "")
                        if (pcNetName.isNotBlank() && (currentNetworkName == "Mobile Data" || currentNetworkName == "Local Network")) {
                            currentNetworkName = pcNetName
                        }
                        synchronizedPcName = pcName
                        isSynchronized = true
                        lastSyncTimestamp = System.currentTimeMillis()
                        updateNotification("Connected to $currentNetworkName, but synchronized with $pcName")
                        Log.d(TAG, "[HANDSHAKE] ✅ Synchronized with '$pcName' over persistent WebSocket")
                        
                        val pending = pendingClipboard
                        if (pending != null) {
                            pendingClipboard = null
                            serviceScope.launch {
                                sendClipboard(pending)
                            }
                        }
                    }
                    "CLIPBOARD" -> {
                        val content = json.optString("content", "")
                        val senderName = json.optString("device_name", "Windows PC")
                        synchronizedPcName = senderName
                        isSynchronized = true
                        lastSyncTimestamp = System.currentTimeMillis()
                        updateNotification("Connected to $currentNetworkName, but synchronized with $senderName")
                        serviceScope.launch {
                            applyClipboardText(content)
                        }
                    }
                    else -> {
                        serviceScope.launch {
                            applyClipboardText(text)
                        }
                    }
                }
                return
            } catch (e: Exception) {
                Log.w(TAG, "JSON parse error: ${e.message}")
            }
        }
        serviceScope.launch {
            applyClipboardText(text)
        }
    }

    private suspend fun applyClipboardText(content: String) {
        val accessibilityService = ClipboardAccessibilityService.instance
        if (accessibilityService != null) {
            withContext(Dispatchers.Main) {
                accessibilityService.setClipboardText(content)
            }
            Log.d(TAG, "[WINDOWS→ANDROID] ✅ Clipboard set successfully! (${content.length} chars)")
        } else {
            Log.e(TAG, "[WINDOWS→ANDROID] ❌ Accessibility service not available")
        }
    }

    private suspend fun startNsdDiscovery() {
        if (isServiceStopped || !isServiceRunning) return
        try {
            val nsdManager = getSystemService(NSD_SERVICE) as android.net.nsd.NsdManager
            
            val discoveryListener = object : android.net.nsd.NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "NSD discovery started")
                }

                override fun onServiceFound(serviceInfo: android.net.nsd.NsdServiceInfo) {
                    if (isServiceStopped || !isServiceRunning) return
                    val serviceName = serviceInfo.serviceName
                    Log.d(TAG, "Service found: $serviceName")
                    
                    if (serviceName.contains("awclippy-windows", ignoreCase = true)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            @Suppress("NewApi")
                            nsdManager.registerServiceInfoCallback(
                                serviceInfo,
                                java.util.concurrent.Executors.newSingleThreadExecutor(),
                                object : android.net.nsd.NsdManager.ServiceInfoCallback {
                                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                                    override fun onServiceUpdated(serviceInfo: android.net.nsd.NsdServiceInfo) {
                                        if (isServiceStopped || !isServiceRunning) return
                                        handleResolvedService(serviceInfo)
                                    }
                                    override fun onServiceLost() {}
                                    override fun onServiceInfoCallbackUnregistered() {}
                                }
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            nsdManager.resolveService(serviceInfo, object : android.net.nsd.NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                                    Log.w(TAG, "NSD resolve failed for ${serviceInfo.serviceName}: $errorCode")
                                }
                                override fun onServiceResolved(serviceInfo: android.net.nsd.NsdServiceInfo) {
                                    if (isServiceStopped || !isServiceRunning) return
                                    Log.d(TAG, "NSD resolved: ${serviceInfo.serviceName} at ${serviceInfo.host}:${serviceInfo.port}")
                                    handleResolvedService(serviceInfo)
                                }
                            })
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: android.net.nsd.NsdServiceInfo) {
                    if (serviceInfo.serviceName.contains("awclippy-windows", ignoreCase = true)) {
                        Log.d(TAG, "Windows PC service lost via NSD")
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }

            nsdDiscoveryListener = discoveryListener
            nsdManager.discoverServices("_awclippy._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            registerNsdService(nsdManager)
            
        } catch (e: Exception) {
            Log.e(TAG, "NSD discovery error: ${e.message}")
        }
    }
    
    private fun handleResolvedService(serviceInfo: android.net.nsd.NsdServiceInfo) {
        if (isServiceStopped || !isServiceRunning) return
        val hostAddresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("NewApi")
            serviceInfo.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(serviceInfo.host)
        }
        
        val port = serviceInfo.port
        val validAddress = hostAddresses.firstOrNull { addr ->
            addr is Inet4Address && 
            addr.hostAddress?.let { host ->
                host.isNotEmpty() && 
                !host.startsWith("169.254") && 
                !host.startsWith("fe80:")
            } ?: false
        }
        
        if (validAddress != null) {
            val candidateAddr = InetSocketAddress(validAddress, port)
            // If already connected and synchronized with this PC, ignore repeated announcements
            if (isSynchronized && windowsPcAddress?.address == validAddress && activeWebSocket != null) {
                return
            }
            connectWebSocket(candidateAddr)
        }
    }

    private fun registerNsdService(nsdManager: android.net.nsd.NsdManager) {
        if (isServiceStopped || !isServiceRunning) return
        try {
            val localIp = getLocalIpAddress()
            val ipSuffix = localIp?.hostAddress?.replace(".", "-") ?: "unknown"
            
            val serviceInfo = android.net.nsd.NsdServiceInfo().apply {
                serviceName = "awclippy-android-$ipSuffix"
                serviceType = "_awclippy._tcp"
                port = config.syncPort
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAttribute("name", getDeviceModelName())
                    setAttribute("net", currentNetworkName)
                    setAttribute("v", "2")
                }
            }

            val regListener = object : android.net.nsd.NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
                override fun onUnregistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
                override fun onServiceRegistered(serviceInfo: android.net.nsd.NsdServiceInfo) {
                    Log.d(TAG, "NSD service registered: ${serviceInfo.serviceName}")
                }
                override fun onServiceUnregistered(serviceInfo: android.net.nsd.NsdServiceInfo) {}
            }
            nsdRegistrationListener = regListener
            nsdManager.registerService(serviceInfo, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, regListener)
        } catch (e: Exception) {
            Log.e(TAG, "NSD registration error: ${e.message}")
        }
    }
    
    private fun updateNotification(message: String) {
        if (isServiceStopped || !isServiceRunning) {
            Log.d(TAG, "Skipping notification update because service is stopped")
            return
        }
        val channelId = "awclippy_sync"
        
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("AW Clippy")
            .setContentText(message)
            .setSmallIcon(NotificationIconHelper.getNotificationIcon())
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Service", createStopServicePendingIntent())
            .setOngoing(true)
            .build()
        
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager?.notify(1, notification)
    }

    private fun getLocalIpAddress(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP: ${e.message}")
        }
        return null
    }

    suspend fun sendClipboard(text: String) {
        if (isServiceStopped || !isServiceRunning) return
        val ws = activeWebSocket
        if (ws == null || !isSynchronized) {
            Log.w(TAG, "[ANDROID→WINDOWS] ⏳ Not synchronized over WebSocket yet, queueing clipboard...")
            pendingClipboard = text
            return
        }

        try {
            val clipJson = JSONObject().apply {
                put("type", "CLIPBOARD")
                put("content", text)
                put("device_name", getDeviceModelName())
            }
            val encrypted = crypto.encrypt(clipJson.toString())
            ws.send(encrypted.toByteString())
            lastSyncTimestamp = System.currentTimeMillis()
            Log.d(TAG, "[ANDROID→WINDOWS] ✅ Sent clipboard (${text.length} chars) over persistent WebSocket link")
        } catch (e: Exception) {
            Log.e(TAG, "[ANDROID→WINDOWS] ❌ Error sending clipboard: ${e.message}")
        }
    }
}

data class ClipboardConfig(
    val sharedSecret: ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(BuildConfig.AWCLIPPY_SHARED_SECRET.toByteArray(Charsets.UTF_8)),
    val syncPort: Int = BuildConfig.AWCLIPPY_SYNC_PORT.toIntOrNull() ?: 48900,
    val discoveryPort: Int = BuildConfig.AWCLIPPY_DISCOVERY_PORT.toIntOrNull() ?: 48899
)
