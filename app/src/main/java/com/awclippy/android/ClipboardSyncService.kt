package com.awclippy.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ClipboardSyncService : Service() {
    companion object {
        private const val TAG = "AWClippy"
        private const val MAX_CLIPBOARD_SIZE = 1024 * 1024 // 1MB
        
        @Volatile
        var instance: ClipboardSyncService? = null
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenerJob: Job? = null
    private var nsdJob: Job? = null
    private var retryJob: Job? = null
    
    private val config = ClipboardConfig()
    private val crypto = ClipboardCrypto(config.sharedSecret)
    
    // Wi-Fi multicast lock for mDNS reliability
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    
    // Cached Windows PC address
    @Volatile
    private var windowsPcAddress: InetSocketAddress? = null
    
    // Pending clipboard to send (queued when Windows PC not yet discovered)
    @Volatile
    private var pendingClipboard: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "ClipboardSyncService starting...")
        
        // Key initialization
        Log.d(TAG, "Cryptographic key initialized successfully (SHA-256 derived)")
        
        // MUST call startForeground immediately for foreground services
        startForegroundNotification()
        
        // Acquire multicast lock for reliable mDNS on Android
        acquireMulticastLock()
        
        // Start TCP listener
        listenerJob = serviceScope.launch {
            startTcpListener()
        }
        
        // Start NSD (Android's mDNS implementation)
        nsdJob = serviceScope.launch {
            // Start both discovery methods in parallel
            launch { startNsdDiscovery() }
            launch { fallbackNetworkScan() } // Start immediately, not just as fallback
        }
    }
    
    private fun startForegroundNotification() {
        val channelId = "awclippy_sync"
        
        // Create notification channel (required for Android 8+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Clipboard Sync",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Syncs clipboard with Windows PC"
            }
            
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
        
        // Create pending intent to open MainActivity when notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        // Create notification with proper resource icon
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("AW Clippy")
            .setContentText("Clipboard sync active - discovering Windows PC...")
            .setSmallIcon(NotificationIconHelper.getNotificationIcon())
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        
        startForeground(1, notification)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle clipboard text from accessibility service
        intent?.getStringExtra("clipboard_text")?.let { text ->
            serviceScope.launch {
                sendClipboard(text)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        releaseMulticastLock()
        listenerJob?.cancel()
        nsdJob?.cancel()
        retryJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
    
    private fun acquireMulticastLock() {
        try {
            val wifiManager = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
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
    
    // Public method for accessibility service to send clipboard
    fun sendClipboardFromAccessibility(text: String) {
        Log.d(TAG, "[ANDROID→WINDOWS] sendClipboardFromAccessibility called with ${text.length} bytes")
        
        if (windowsPcAddress == null) {
            Log.w(TAG, "[ANDROID→WINDOWS] ⏳ No Windows PC found yet, queueing clipboard...")
            pendingClipboard = text
            
            // Start retry job if not already running
            if (retryJob == null || !retryJob!!.isActive) {
                retryJob = serviceScope.launch {
                    retryPendingClipboard()
                }
            }
            return
        }
        
        Log.d(TAG, "[ANDROID→WINDOWS] Windows PC address: $windowsPcAddress")
        Log.d(TAG, "[ANDROID→WINDOWS] Launching coroutine to send...")
        
        serviceScope.launch {
            Log.d(TAG, "[ANDROID→WINDOWS] Inside coroutine, calling sendClipboard...")
            sendClipboard(text)
        }
    }
    
    private suspend fun retryPendingClipboard() {
        Log.d(TAG, "[ANDROID→WINDOWS] Starting retry loop for pending clipboard...")
        
        var attempts = 0
        val maxAttempts = 100 // 10 seconds total
        
        while (attempts < maxAttempts && pendingClipboard != null) {
            delay(100) // Check every 100ms
            attempts++
            
            if (windowsPcAddress != null) {
                val textToSend = pendingClipboard
                pendingClipboard = null
                
                if (textToSend != null) {
                    Log.d(TAG, "[ANDROID→WINDOWS] ✅ Windows PC discovered after ${attempts * 100}ms! Sending queued clipboard...")
                    sendClipboard(textToSend)
                }
                return
            }
        }
        
        if (pendingClipboard != null) {
            Log.e(TAG, "[ANDROID→WINDOWS] ❌ Windows PC not discovered after ${maxAttempts * 100}ms, giving up")
            pendingClipboard = null
        }
    }
    
    // Check if Windows PC is connected
    fun hasWindowsDevice(): Boolean {
        return windowsPcAddress != null
    }

    private suspend fun startTcpListener() {
        try {
            val serverSocket = ServerSocket(config.syncPort)
            Log.d(TAG, "TCP listener started on port ${config.syncPort}")
            
            withContext(Dispatchers.IO) {
                while (isActive) {
                    try {
                        val client = serverSocket.accept()
                        
                        launch {
                            handleConnection(client)
                        }
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            Log.e(TAG, "Accept error: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Listener error: ${e.message}")
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        try {
            socket.use {
                it.soTimeout = 5000 // 5-second socket read timeout
                val input = it.getInputStream()
                
                // Read length prefix
                val lenBytes = ByteArray(4)
                var totalRead = 0
                while (totalRead < 4) {
                    val read = input.read(lenBytes, totalRead, 4 - totalRead)
                    if (read == -1) return
                    totalRead += read
                }
                
                val len = ByteBuffer.wrap(lenBytes).int
                if (len <= 0 || len > MAX_CLIPBOARD_SIZE) {
                    Log.e(TAG, "Invalid payload size: $len")
                    return
                }

                // Read encrypted payload
                val payload = ByteArray(len)
                totalRead = 0
                while (totalRead < len) {
                    val read = input.read(payload, totalRead, len - totalRead)
                    if (read == -1) break
                    totalRead += read
                }

                if (totalRead != len) {
                    Log.e(TAG, "Incomplete read: $totalRead/$len")
                    return
                }

                // Decrypt and set clipboard
                val text = crypto.decrypt(payload)
                Log.d(TAG, "[WINDOWS→ANDROID] ✅ Decrypted successfully (${text.length} bytes)")
                
                val accessibilityService = ClipboardAccessibilityService.instance
                if (accessibilityService != null) {
                    withContext(Dispatchers.Main) {
                        accessibilityService.setClipboardText(text)
                    }
                    Log.d(TAG, "[WINDOWS→ANDROID] ✅ Clipboard set successfully!")
                } else {
                    Log.e(TAG, "[WINDOWS→ANDROID] ❌ Accessibility service not available")
                }
                
                Log.d(TAG, "[WINDOWS→ANDROID] Clipboard synced: ${text.length} bytes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection error: ${e.message}")
        }
    }

    /**
     * Start Android NSD (Network Service Discovery) to find Windows PC
     * This is Android's built-in mDNS implementation
     */
    private suspend fun startNsdDiscovery() {
        try {
            val nsdManager = getSystemService(NSD_SERVICE) as android.net.nsd.NsdManager
            
            val discoveryListener = object : android.net.nsd.NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "NSD discovery started")
                }

                override fun onServiceFound(serviceInfo: android.net.nsd.NsdServiceInfo) {
                    val serviceName = serviceInfo.serviceName
                    Log.d(TAG, "Service found: $serviceName")
                    
                    // Only resolve Windows devices, ignore our own service
                    if (serviceName.contains("awclippy-windows", ignoreCase = true)) {
                        // Use newer API for Android 14+ (API 34+)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            @Suppress("NewApi")
                            nsdManager.registerServiceInfoCallback(
                                serviceInfo,
                                java.util.concurrent.Executors.newSingleThreadExecutor(),
                                object : android.net.nsd.NsdManager.ServiceInfoCallback {
                                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                                        Log.e(TAG, "Registration failed: $errorCode")
                                    }

                                    override fun onServiceUpdated(serviceInfo: android.net.nsd.NsdServiceInfo) {
                                        handleResolvedService(serviceInfo)
                                    }

                                    override fun onServiceLost() {
                                        Log.d(TAG, "Service lost")
                                    }

                                    override fun onServiceInfoCallbackUnregistered() {
                                        Log.d(TAG, "Callback unregistered")
                                    }
                                }
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            nsdManager.resolveService(serviceInfo, object : android.net.nsd.NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                                    Log.e(TAG, "Resolve failed: $errorCode")
                                }

                                override fun onServiceResolved(serviceInfo: android.net.nsd.NsdServiceInfo) {
                                    handleResolvedService(serviceInfo)
                                }
                            })
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: android.net.nsd.NsdServiceInfo) {
                    if (serviceInfo.serviceName.contains("awclippy-windows", ignoreCase = true)) {
                        windowsPcAddress = null
                        Log.d(TAG, "Windows PC disconnected")
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "NSD discovery stopped")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "NSD start failed: $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "NSD stop failed: $errorCode")
                }
            }

            nsdManager.discoverServices("_awclippy._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            
            // Register our own service so Windows can find us too
            registerNsdService(nsdManager)
            
        } catch (e: Exception) {
            Log.e(TAG, "NSD error: ${e.message}")
            
            // Fallback to network scanning if NSD fails
            fallbackNetworkScan()
        }
    }
    
    private fun handleResolvedService(serviceInfo: android.net.nsd.NsdServiceInfo) {
        val hostAddresses = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Use new API for Android 14+
            @Suppress("NewApi")
            serviceInfo.hostAddresses
        } else {
            // Use deprecated host property for older versions
            @Suppress("DEPRECATION")
            listOfNotNull(serviceInfo.host)
        }
        
        val port = serviceInfo.port
        
        // Find first non-link-local IPv4 address
        val validAddress = hostAddresses.firstOrNull { addr ->
            addr is Inet4Address && 
            addr.hostAddress?.let { host ->
                host.isNotEmpty() && 
                !host.startsWith("169.254") && 
                !host.startsWith("fe80:")
            } ?: false
        }
        
        if (validAddress != null) {
            windowsPcAddress = InetSocketAddress(validAddress, port)
            val hostAddress = validAddress.hostAddress ?: "unknown"
            Log.d(TAG, "Windows PC found via NSD at $hostAddress:$port")
            updateNotification("Connected to Windows PC at $hostAddress")
        } else {
            Log.d(TAG, "No valid IPv4 address found in resolved service")
        }
    }

    private fun registerNsdService(nsdManager: android.net.nsd.NsdManager) {
        try {
            val localIp = getLocalIpAddress()
            val ipSuffix = localIp?.hostAddress?.replace(".", "-") ?: "unknown"
            
            val serviceInfo = android.net.nsd.NsdServiceInfo().apply {
                serviceName = "awclippy-android-$ipSuffix"
                serviceType = "_awclippy._tcp"
                port = config.syncPort
            }

            nsdManager.registerService(serviceInfo, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, object : android.net.nsd.NsdManager.RegistrationListener {
                override fun onRegistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD registration failed: $errorCode")
                }

                override fun onUnregistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD unregistration failed: $errorCode")
                }

                override fun onServiceRegistered(serviceInfo: android.net.nsd.NsdServiceInfo) {
                    Log.d(TAG, "NSD service registered: ${serviceInfo.serviceName}")
                }

                override fun onServiceUnregistered(serviceInfo: android.net.nsd.NsdServiceInfo) {
                    Log.d(TAG, "NSD service unregistered")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "NSD registration error: ${e.message}")
        }
    }

    /**
     * Network scan: Try to find Windows PC by direct connection attempts
     * Now runs in parallel with NSD for faster discovery
     */
    private suspend fun fallbackNetworkScan() {
        Log.d(TAG, "Network scan: Looking for Windows PC...")
        
        val localIp = getLocalIpAddress()
        if (localIp == null) {
            Log.e(TAG, "Network scan: Cannot get local IP")
            return
        }
        
        val hostAddress = localIp.hostAddress
        if (hostAddress == null) {
            Log.e(TAG, "Network scan: Host address is null")
            return
        }
        
        val subnet = hostAddress.substringBeforeLast(".")
        Log.d(TAG, "Network scan: Scanning subnet $subnet.*")
        
        // Try common IP addresses in parallel
        val commonIps = listOf(1, 2, 100, 101, 102, 103, 198, 199, 254)
        val ports = listOf(config.syncPort, 48899, 48901, 48902)
        
        coroutineScope {
            commonIps.forEach { lastOctet ->
                launch {
                    if (windowsPcAddress != null) return@launch // Already found
                    
                    val ip = "$subnet.$lastOctet"
                    for (port in ports) {
                        if (windowsPcAddress != null) return@launch // Already found
                        
                        try {
                            withTimeout(500) {
                                withContext(Dispatchers.IO) {
                                    Socket().use { socket ->
                                        socket.connect(InetSocketAddress(ip, port), 500)
                                        
                                        if (windowsPcAddress == null) { // Double-check before setting
                                            windowsPcAddress = InetSocketAddress(ip, config.syncPort)
                                            Log.d(TAG, "Network scan: ✅ Windows PC found at $ip:${config.syncPort}")
                                            updateNotification("Connected to Windows PC at $ip")
                                        }
                                    }
                                }
                            }
                            return@launch
                        } catch (e: Exception) {
                            // Continue scanning
                        }
                    }
                }
            }
        }
        
        if (windowsPcAddress == null) {
            Log.d(TAG, "Network scan: No Windows PC found in subnet")
        }
    }
    
    private fun updateNotification(message: String) {
        val channelId = "awclippy_sync"
        
        // Create pending intent to open MainActivity when notification is tapped
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
            .setOngoing(true)
            .build()
        
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        notificationManager.notify(1, notification)
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
        Log.d(TAG, "[ANDROID→WINDOWS] sendClipboard function called")
        
        val address = windowsPcAddress
        if (address == null) {
            Log.e(TAG, "[ANDROID→WINDOWS] ❌ FAILED - No Windows PC found yet, cannot send clipboard")
            return
        }

        Log.d(TAG, "[ANDROID→WINDOWS] Target address: $address")
        
        try {
            Log.d(TAG, "[ANDROID→WINDOWS] Encrypting ${text.length} bytes...")
            val encrypted = crypto.encrypt(text)
            Log.d(TAG, "[ANDROID→WINDOWS] Encrypted to ${encrypted.size} bytes")
            
            withContext(Dispatchers.IO) {
                Log.d(TAG, "[ANDROID→WINDOWS] Opening socket to $address...")
                Socket().use { socket ->
                    socket.soTimeout = 5000 // 5-second socket timeout
                    socket.connect(address, 2000)
                    Log.d(TAG, "[ANDROID→WINDOWS] Connected! Sending data...")
                    val output = socket.getOutputStream()
                    
                    // Send length prefix
                    val lenBytes = ByteBuffer.allocate(4).putInt(encrypted.size).array()
                    output.write(lenBytes)
                    
                    // Send payload
                    output.write(encrypted)
                    output.flush()
                    Log.d(TAG, "[ANDROID→WINDOWS] ✅ SUCCESS - Data sent!")
                }
            }
            
            Log.d(TAG, "[ANDROID→WINDOWS] ✅ Sent clipboard to Windows: ${text.length} bytes")
        } catch (e: IOException) {
            // Connection failed - clear cached address to trigger rediscovery
            windowsPcAddress = null
            Log.e(TAG, "[ANDROID→WINDOWS] ❌ FAILED - IOException: ${e.message}")
            Log.e(TAG, "[ANDROID→WINDOWS] Cleared cached address, will rediscover")
        } catch (e: Exception) {
            Log.e(TAG, "[ANDROID→WINDOWS] ❌ FAILED - ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }
}

// Configuration data class with SHA-256 key derivation
data class ClipboardConfig(
    val sharedSecret: ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(BuildConfig.AWCLIPPY_SHARED_SECRET.toByteArray(Charsets.UTF_8)),
    val syncPort: Int = BuildConfig.AWCLIPPY_SYNC_PORT.toIntOrNull() ?: 48900,
    val discoveryPort: Int = BuildConfig.AWCLIPPY_DISCOVERY_PORT.toIntOrNull() ?: 48899
)
