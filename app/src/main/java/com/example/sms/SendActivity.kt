package com.example.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SendActivity : AppCompatActivity() {

    private lateinit var locationManager: LocationManager
    private lateinit var coordsTextView: TextView
    private lateinit var etPort: EditText
    private lateinit var etSendInterval: EditText
    private lateinit var sendLocationButton: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnClearLog: Button
    private lateinit var btnToggleLog: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var tvServerList: TextView
    private lateinit var scrollView: ScrollView
    private var isLogVisible = true

    private var locationListener: LocationListener? = null
    private var currentLocation: Location? = null
    private var phoneNumber: String = ""
    private var isConnected = false
    private var isSendingPeriodically = false
    private var sendJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    // ═══════════════════════════════════════════════════════════════
    // SERVIDORES HARDCODEADOS
    // ═══════════════════════════════════════════════════════════════
    private val SERVERS = listOf(
        "chidrobo.ddns.net",
        "uesteban.ddnsking.com",
        "jesucaracu.ddns.net"
    )

    // ═══════════════════════════════════════════════════════════════
    // CONFIGURACIÓN DE CIFRADO AES-GCM
    // ═══════════════════════════════════════════════════════════════
    private val AES_KEY = byteArrayOf(
        0x2b, 0x7e, 0x15, 0x16, 0x28, 0xae.toByte(), 0xd2.toByte(), 0xa6.toByte(),
        0xab.toByte(), 0xf7.toByte(), 0x15, 0x88.toByte(), 0x09, 0xcf.toByte(), 0x4f, 0x3c
    )
    private val GCM_TAG_LENGTH = 128
    private val GCM_IV_LENGTH = 12

    private lateinit var deviceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_send)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        phoneNumber = intent.getStringExtra("phone_number") ?: ""
        deviceId = getOrCreateDeviceId()

        initializeViews()
        setupClickListeners()

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        updateConnectionStatus("No conectado", false)
        checkLocationPermission()
    }

    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)

        if (deviceId == null) {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            deviceId = "ANDROID_${androidId.take(8)}"
            prefs.edit().putString("device_id", deviceId).apply()
            Log.d("SendActivity", "Device ID generado: $deviceId")
        } else {
            Log.d("SendActivity", "Device ID recuperado: $deviceId")
        }

        return deviceId
    }

    private fun initializeViews() {
        coordsTextView = findViewById(R.id.coords_text)
        etPort = findViewById(R.id.etPort)
        etSendInterval = findViewById(R.id.etSendInterval)
        sendLocationButton = findViewById(R.id.send_location_btn)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnClearLog = findViewById(R.id.btnClearLog)
        btnToggleLog = findViewById(R.id.btnToggleLog)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        tvServerList = findViewById(R.id.tvServerList)
        scrollView = findViewById(R.id.scrollView)

        tvDeviceId.text = "🆔 $deviceId"

        // Mostrar lista de servidores
        val serverListText = buildString {
            append("📡 Servidores configurados:\n")
            SERVERS.forEachIndexed { index, server ->
                append("   ${index + 1}. $server\n")
            }
        }
        tvServerList.text = serverListText

        etPort.setText("5051")
        etSendInterval.setText("10")

        sendLocationButton.isEnabled = false
        sendLocationButton.text = "Obteniendo ubicación..."
    }

    private fun setupClickListeners() {
        sendLocationButton.setOnClickListener {
            if (isSendingPeriodically) {
                stopPeriodicSending()
            } else {
                startPeriodicSending()
            }
        }

        btnDisconnect.setOnClickListener {
            stopPeriodicSending()
            updateConnectionStatus("Desconectado", false)
            addToLog("🔴 Desconectado por el usuario")
        }

        btnClearLog.setOnClickListener {
            tvLog.text = ""
        }

        btnToggleLog.setOnClickListener {
            toggleLogVisibility()
        }
    }

    private fun toggleLogVisibility() {
        isLogVisible = !isLogVisible
        scrollView.visibility = if (isLogVisible) View.VISIBLE else View.GONE
        btnToggleLog.text = if (isLogVisible) "Ocultar Log" else "Mostrar Log"
    }

    private fun encryptMessage(plaintext: String): ByteArray {
        try {
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(AES_KEY, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            val ciphertextWithTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val packet = ByteArray(iv.size + ciphertextWithTag.size)
            System.arraycopy(iv, 0, packet, 0, iv.size)
            System.arraycopy(ciphertextWithTag, 0, packet, iv.size, ciphertextWithTag.size)

            Log.d("SendActivity", "Mensaje cifrado: ${packet.size} bytes")
            return packet

        } catch (e: Exception) {
            Log.e("SendActivity", "Error cifrando mensaje: ${e.message}", e)
            throw e
        }
    }

    private fun startPeriodicSending() {
        if (currentLocation == null) {
            Toast.makeText(this, "Ubicación no disponible", Toast.LENGTH_SHORT).show()
            return
        }

        val port = etPort.text.toString().toIntOrNull() ?: 5051
        val intervalSeconds = etSendInterval.text.toString().toIntOrNull() ?: 10

        if (intervalSeconds < 1) {
            Toast.makeText(this, "El intervalo debe ser al menos 1 segundo", Toast.LENGTH_SHORT).show()
            return
        }

        isSendingPeriodically = true
        updateSendingUI(true)

        addToLog("════════════════════════════════════════")
        addToLog("🚀 Iniciando transmisión cifrada")
        addToLog("🆔 Device ID: $deviceId")
        addToLog("⏱️  Intervalo: $intervalSeconds segundos")
        addToLog("🔐 Cifrado: AES-128-GCM")
        addToLog("🌐 Puerto: $port")
        addToLog("📡 ${SERVERS.size} servidores activos")
        addToLog("════════════════════════════════════════")

        sendJob = scope.launch {
            var sendCount = 0
            while (isSendingPeriodically) {
                try {
                    sendCount++
                    currentLocation?.let { location ->
                        runOnUiThread {
                            addToLog("\n📤 Envío #$sendCount")
                        }
                        sendEncryptedLocationToServers(port, location, sendCount)
                    }

                    var remainingSeconds = intervalSeconds
                    while (remainingSeconds > 0 && isSendingPeriodically) {
                        runOnUiThread {
                            sendLocationButton.text = "⏳ Próximo en ${remainingSeconds}s"
                        }
                        delay(1000)
                        remainingSeconds--
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        addToLog("❌ Error en envío periódico: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    private suspend fun sendEncryptedLocationToServers(
        port: Int,
        location: Location,
        sendCount: Int
    ) {
        withContext(Dispatchers.IO) {
            try {
                val lat = location.latitude
                val lng = location.longitude

                val plaintext = "$deviceId,%.6f,%.6f".format(Locale.US, lat, lng)

                runOnUiThread {
                    addToLog("   📍 Ubicación: ${"%.6f".format(lat)}, ${"%.6f".format(lng)}")
                    addToLog("   🔒 Cifrando mensaje...")
                }

                val encryptedPacket = encryptMessage(plaintext)

                runOnUiThread {
                    addToLog("   ✅ Cifrado completado (${encryptedPacket.size} bytes)")
                }

                val results = SERVERS.mapIndexed { index, server ->
                    async {
                        val serverName = "Servidor ${index + 1}"
                        serverName to sendEncryptedUdpPacket(serverName, server, port, encryptedPacket)
                    }
                }.awaitAll()

                val successful = results.filter { it.second }
                val failed = results.filter { !it.second }

                runOnUiThread {
                    when {
                        successful.size == results.size -> {
                            addToLog("   ✅ Envío exitoso a ${successful.size}/${results.size} servidores")
                        }
                        successful.isNotEmpty() -> {
                            addToLog("   ⚠️  Envío parcial: ${successful.size}/${results.size} servidores")
                            failed.forEach {
                                addToLog("      ❌ ${it.first} falló")
                            }
                        }
                        else -> {
                            addToLog("   ❌ Fallo total en todos los servidores")
                        }
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    addToLog("   ❌ Error crítico: ${e.message}")
                    Log.e("SendActivity", "Error en envío cifrado", e)
                }
            }
        }
    }

    private suspend fun sendEncryptedUdpPacket(
        serverName: String,
        host: String,
        port: Int,
        encryptedData: ByteArray
    ): Boolean {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            socket.soTimeout = 5000

            val address = InetAddress.getByName(host)
            val packet = DatagramPacket(encryptedData, encryptedData.size, address, port)

            socket.send(packet)

            Log.d("SendActivity", "Paquete enviado a $serverName ($host:$port): ${encryptedData.size} bytes")
            true
        } catch (e: Exception) {
            Log.e("SendActivity", "Error enviando a $serverName: ${e.message}", e)
            false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun stopPeriodicSending() {
        isSendingPeriodically = false
        sendJob?.cancel()
        sendJob = null
        updateSendingUI(false)
        addToLog("\n⏹️  Transmisión detenida")
    }

    private fun updateSendingUI(sending: Boolean) {
        if (sending) {
            sendLocationButton.text = "⏹️ Detener Transmisión"
            sendLocationButton.setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, null))

            etPort.isEnabled = false
            etSendInterval.isEnabled = false
            btnDisconnect.visibility = Button.VISIBLE
        } else {
            sendLocationButton.text = "▶️ Iniciar Transmisión"
            sendLocationButton.setBackgroundColor(resources.getColor(android.R.color.holo_green_dark, null))

            etPort.isEnabled = true
            etSendInterval.isEnabled = true
            btnDisconnect.visibility = Button.GONE
        }
    }

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            } else {
                coordsTextView.text = "⚠️ Se requieren permisos de ubicación"
                Toast.makeText(this, "Permisos de ubicación requeridos", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startLocationUpdates() {
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                currentLocation = location
                updateLocationDisplay(location)
                enableSendButton()
            }

            override fun onProviderEnabled(provider: String) {
                Log.d("SendActivity", "Provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Log.d("SendActivity", "Provider disabled: $provider")
                coordsTextView.text = "⚠️ GPS deshabilitado"
                sendLocationButton.isEnabled = false
                stopPeriodicSending()
            }
        }

        try {
            coordsTextView.text = "🔍 Obteniendo ubicación..."

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    1f,
                    locationListener!!
                )
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    1f,
                    locationListener!!
                )
            }

            val lastKnownGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastKnownNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            val lastKnownLocation = when {
                lastKnownGPS != null && lastKnownNetwork != null -> {
                    if (lastKnownGPS.time > lastKnownNetwork.time) lastKnownGPS else lastKnownNetwork
                }
                lastKnownGPS != null -> lastKnownGPS
                lastKnownNetwork != null -> lastKnownNetwork
                else -> null
            }

            lastKnownLocation?.let { location ->
                currentLocation = location
                updateLocationDisplay(location)
                enableSendButton()
            }

        } catch (e: SecurityException) {
            coordsTextView.text = "❌ Error de permisos"
            Log.e("SendActivity", "Security exception: ${e.message}")
        }
    }

    private fun enableSendButton() {
        sendLocationButton.isEnabled = true
        if (!isSendingPeriodically) {
            sendLocationButton.text = "▶️ Iniciar Transmisión"
        }
    }

    private fun updateLocationDisplay(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val accuracy = location.accuracy
        val timestamp = location.time

        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val formattedTime = dateFormat.format(Date(timestamp))

        val locationText = buildString {
            append("📍 ${"%.6f".format(lat)}, ${"%.6f".format(lng)}")
            append(" | ⏱️ $formattedTime")
            if (isSendingPeriodically) {
                append(" | 🟢 Transmitiendo")
            } else {
                append(" | 🔴 Detenido")
            }
        }

        coordsTextView.text = locationText
        Log.d("SendActivity", "Location updated: Lat=$lat, Lng=$lng")
    }

    private fun updateConnectionStatus(status: String, connected: Boolean) {
        isConnected = connected
        tvStatus.text = if (connected) "🟢 $status" else "🔴 $status"
        tvStatus.setTextColor(
            if (connected)
                resources.getColor(android.R.color.holo_green_dark, null)
            else
                resources.getColor(android.R.color.holo_red_dark, null)
        )
    }

    private fun addToLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $message\n"
        tvLog.append(logEntry)

        if (!isLogVisible && isSendingPeriodically) {
            toggleLogVisibility()
        }

        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private suspend fun testConnections(port: Int) {
        withContext(Dispatchers.IO) {
            try {
                runOnUiThread {
                    updateConnectionStatus("Probando conexiones...", false)
                    addToLog("🔍 Iniciando prueba de conexión cifrada")
                    addToLog("📡 Probando ${SERVERS.size} servidores...")
                }

                val results = SERVERS.mapIndexed { index, server ->
                    async {
                        val serverName = "Servidor ${index + 1}"
                        testEncryptedConnection(serverName, server, port)
                    }
                }.awaitAll()

                val successfulTests = results.count { it }
                val totalTests = results.size

                runOnUiThread {
                    when {
                        successfulTests == totalTests -> {
                            updateConnectionStatus("Todos los servidores OK ($totalTests/$totalTests)", true)
                            addToLog("✅ Prueba exitosa: $totalTests/$totalTests servidores")
                        }
                        successfulTests > 0 -> {
                            updateConnectionStatus("Conexión parcial ($successfulTests/$totalTests)", false)
                            addToLog("⚠️  Prueba parcial: $successfulTests/$totalTests servidores")
                        }
                        else -> {
                            updateConnectionStatus("Sin conexión (0/$totalTests)", false)
                            addToLog("❌ Todas las pruebas fallaron")
                        }
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    updateConnectionStatus("Error en prueba", false)
                    addToLog("❌ Error: ${e.message}")
                }
            }
        }
    }

    private suspend fun testEncryptedConnection(serverName: String, host: String, port: Int): Boolean {
        return try {
            val testMessage = "TEST_$deviceId"
            val encryptedPacket = encryptMessage(testMessage)

            val result = sendEncryptedUdpPacket(serverName, host, port, encryptedPacket)

            runOnUiThread {
                if (result) {
                    addToLog("   ✅ $serverName ($host): OK")
                } else {
                    addToLog("   ❌ $serverName ($host): FAIL")
                }
            }

            result
        } catch (e: Exception) {
            runOnUiThread {
                addToLog("   ❌ $serverName: ${e.message}")
            }
            false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicSending()
        locationListener?.let {
            locationManager.removeUpdates(it)
        }
        scope.cancel()
    }
}