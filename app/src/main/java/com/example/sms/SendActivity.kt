package com.example.sms

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class SendActivity : AppCompatActivity() {

    private lateinit var locationManager: LocationManager
    private lateinit var coordsTextView: TextView
    private lateinit var sendLocationButton: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnClearLog: Button
    private lateinit var btnToggleLog: Button
    private lateinit var btnScanBluetooth: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var tvBluetoothStatus: TextView
    private lateinit var scrollView: ScrollView
    private var isLogVisible = true

    private var locationListener: LocationListener? = null
    private var currentLocation: Location? = null
    private var phoneNumber: String = ""
    private var isConnected = false
    private var isSendingToESP32 = false
    private var sendJob: Job? = null

    // Bluetooth variables
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var connectedDevice: BluetoothDevice? = null
    private val ESP32_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard SPP UUID

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val BLUETOOTH_PERMISSION_REQUEST_CODE = 1002

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

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        updateBluetoothStatus("No conectado", false)
        checkPermissions()
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
        sendLocationButton = findViewById(R.id.send_location_btn)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnClearLog = findViewById(R.id.btnClearLog)
        btnToggleLog = findViewById(R.id.btnToggleLog)
        btnScanBluetooth = findViewById(R.id.btnScanBluetooth)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        tvBluetoothStatus = findViewById(R.id.tvBluetoothStatus)
        scrollView = findViewById(R.id.scrollView)

        tvDeviceId.text = "🆔 $deviceId"
        tvStatus.text = "📡 Modo: Envío a ESP32 via Bluetooth"

        sendLocationButton.isEnabled = false
        sendLocationButton.text = "Esperando conexión BT..."
    }

    private fun setupClickListeners() {
        sendLocationButton.setOnClickListener {
            if (isSendingToESP32) {
                stopPeriodicSending()
            } else {
                startPeriodicSending()
            }
        }

        btnDisconnect.setOnClickListener {
            disconnectBluetooth()
        }

        btnClearLog.setOnClickListener {
            tvLog.text = ""
        }

        btnToggleLog.setOnClickListener {
            toggleLogVisibility()
        }

        btnScanBluetooth.setOnClickListener {
            scanAndConnectBluetooth()
        }
    }

    private fun toggleLogVisibility() {
        isLogVisible = !isLogVisible
        scrollView.visibility = if (isLogVisible) View.VISIBLE else View.GONE
        btnToggleLog.text = if (isLogVisible) "Ocultar Log" else "Mostrar Log"
    }

    private fun checkPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        // Location permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Bluetooth permissions (depends on Android version)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startLocationUpdates()
            } else {
                coordsTextView.text = "⚠️ Se requieren permisos"
                Toast.makeText(this, "Permisos requeridos", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun scanAndConnectBluetooth() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth no disponible", Toast.LENGTH_SHORT).show()
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, "Por favor, activa el Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter!!.bondedDevices

            if (pairedDevices.isNullOrEmpty()) {
                Toast.makeText(this, "No hay dispositivos emparejados", Toast.LENGTH_SHORT).show()
                addToLog("⚠️ No hay dispositivos Bluetooth emparejados")
                return
            }

            val deviceNames = pairedDevices.map { "${it.name} (${it.address})" }.toTypedArray()
            val devices = pairedDevices.toList()

            AlertDialog.Builder(this)
                .setTitle("Seleccionar ESP32")
                .setItems(deviceNames) { _, which ->
                    connectToDevice(devices[which])
                }
                .setNegativeButton("Cancelar", null)
                .show()

        } catch (e: SecurityException) {
            Toast.makeText(this, "Permisos de Bluetooth requeridos", Toast.LENGTH_SHORT).show()
            addToLog("❌ Error de permisos Bluetooth")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        addToLog("════════════════════════════════════════")
        addToLog("🔵 Conectando a ${device.name}")
        addToLog("📍 MAC: ${device.address}")

        scope.launch(Dispatchers.IO) {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(ESP32_UUID)
                bluetoothSocket?.connect()
                outputStream = bluetoothSocket?.outputStream
                connectedDevice = device

                withContext(Dispatchers.Main) {
                    updateBluetoothStatus("Conectado: ${device.name}", true)
                    addToLog("✅ Conexión Bluetooth establecida")
                    addToLog("════════════════════════════════════════")
                    sendLocationButton.isEnabled = true
                    sendLocationButton.text = "▶️ Iniciar Transmisión"
                    btnScanBluetooth.visibility = Button.GONE
                }

            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    updateBluetoothStatus("Error de conexión", false)
                    addToLog("❌ Error conectando: ${e.message}")
                    Toast.makeText(this@SendActivity, "Error al conectar", Toast.LENGTH_SHORT).show()
                }
            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) {
                    addToLog("❌ Error de permisos: ${e.message}")
                }
            }
        }
    }

    private fun disconnectBluetooth() {
        stopPeriodicSending()

        scope.launch(Dispatchers.IO) {
            try {
                outputStream?.close()
                bluetoothSocket?.close()
                outputStream = null
                bluetoothSocket = null
                connectedDevice = null

                withContext(Dispatchers.Main) {
                    updateBluetoothStatus("Desconectado", false)
                    addToLog("🔴 Bluetooth desconectado")
                    sendLocationButton.isEnabled = false
                    sendLocationButton.text = "Esperando conexión BT..."
                    btnScanBluetooth.visibility = Button.VISIBLE
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    addToLog("⚠️ Error al desconectar: ${e.message}")
                }
            }
        }
    }

    private fun startPeriodicSending() {
        if (currentLocation == null) {
            Toast.makeText(this, "Ubicación no disponible", Toast.LENGTH_SHORT).show()
            return
        }

        if (bluetoothSocket == null || !bluetoothSocket!!.isConnected) {
            Toast.makeText(this, "Bluetooth no conectado", Toast.LENGTH_SHORT).show()
            return
        }

        isSendingToESP32 = true
        updateSendingUI(true)

        addToLog("════════════════════════════════════════")
        addToLog("🚀 Iniciando transmisión GPS a ESP32")
        addToLog("🆔 Device ID: $deviceId")
        addToLog("🔵 Vía Bluetooth")
        addToLog("════════════════════════════════════════")

        sendJob = scope.launch {
            var sendCount = 0
            while (isSendingToESP32) {
                try {
                    sendCount++
                    currentLocation?.let { location ->
                        runOnUiThread {
                            addToLog("\n📤 Envío #$sendCount")
                        }
                        sendLocationViaBluetooth(location, sendCount)
                    }

                    var remainingSeconds = 1 // Enviar cada 2 segundos para mejor actualización
                    while (remainingSeconds > 0 && isSendingToESP32) {
                        runOnUiThread {
                            sendLocationButton.text = "⏳ Próximo en ${remainingSeconds}s"
                        }
                        delay(1000)
                        remainingSeconds--
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        addToLog("❌ Error en envío: ${e.message}")
                    }
                    if (e is IOException) {
                        // Connection lost
                        runOnUiThread {
                            disconnectBluetooth()
                            Toast.makeText(this@SendActivity, "Conexión Bluetooth perdida", Toast.LENGTH_SHORT).show()
                        }
                        break
                    }
                }
            }
        }
    }

    private suspend fun sendLocationViaBluetooth(location: Location, sendCount: Int) {
        withContext(Dispatchers.IO) {
            try {
                val lat = location.latitude
                val lng = location.longitude

                // Format: deviceID,latitude,longitude\n
                val message = "$deviceId,%.6f,%.6f\n".format(Locale.US, lat, lng)

                runOnUiThread {
                    addToLog("   📍 Ubicación: ${"%.6f".format(lat)}, ${"%.6f".format(lng)}")
                    addToLog("   📤 Enviando a ESP32...")
                }

                outputStream?.write(message.toByteArray())
                outputStream?.flush()

                runOnUiThread {
                    addToLog("   ✅ Enviado exitosamente (${message.length} bytes)")
                }

            } catch (e: IOException) {
                runOnUiThread {
                    addToLog("   ❌ Error de envío: ${e.message}")
                }
                throw e
            } catch (e: Exception) {
                runOnUiThread {
                    addToLog("   ❌ Error: ${e.message}")
                    Log.e("SendActivity", "Error en envío Bluetooth", e)
                }
            }
        }
    }

    private fun stopPeriodicSending() {
        isSendingToESP32 = false
        sendJob?.cancel()
        sendJob = null
        updateSendingUI(false)
        addToLog("\n⏹️  Transmisión detenida")
    }

    private fun updateSendingUI(sending: Boolean) {
        if (sending) {
            sendLocationButton.text = "⏹️ Detener Transmisión"
            sendLocationButton.setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, null))
            btnDisconnect.visibility = Button.VISIBLE
        } else {
            sendLocationButton.text = "▶️ Iniciar Transmisión"
            sendLocationButton.setBackgroundColor(resources.getColor(android.R.color.holo_green_dark, null))
            btnDisconnect.visibility = Button.GONE
        }
    }

    private fun startLocationUpdates() {
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                currentLocation = location
                updateLocationDisplay(location)
            }

            override fun onProviderEnabled(provider: String) {
                Log.d("SendActivity", "Provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Log.d("SendActivity", "Provider disabled: $provider")
                coordsTextView.text = "⚠️ GPS deshabilitado"
                stopPeriodicSending()
            }
        }

        try {
            coordsTextView.text = "🔍 Obteniendo ubicación..."

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L, // Update every second
                    1f,
                    locationListener!!
                )
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
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
            }

        } catch (e: SecurityException) {
            coordsTextView.text = "❌ Error de permisos"
            Log.e("SendActivity", "Security exception: ${e.message}")
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
            if (isSendingToESP32) {
                append(" | 🔵 Transmitiendo")
            } else {
                append(" | 🔴 Detenido")
            }
        }

        coordsTextView.text = locationText
        Log.d("SendActivity", "Location updated: Lat=$lat, Lng=$lng")
    }

    private fun updateBluetoothStatus(status: String, connected: Boolean) {
        isConnected = connected
        tvBluetoothStatus.text = if (connected) "🔵 $status" else "⚪ $status"
        tvBluetoothStatus.setTextColor(
            if (connected)
                resources.getColor(android.R.color.holo_blue_dark, null)
            else
                resources.getColor(android.R.color.darker_gray, null)
        )
    }

    private fun addToLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "[$timestamp] $message\n"
        tvLog.append(logEntry)

        if (!isLogVisible && isSendingToESP32) {
            toggleLogVisibility()
        }

        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicSending()
        disconnectBluetooth()
        locationListener?.let {
            locationManager.removeUpdates(it)
        }
        scope.cancel()
    }
}