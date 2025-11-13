package com.example.sms

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

class BiometricAuthActivity : AppCompatActivity() {

    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    private lateinit var tvStatus: TextView
    private lateinit var btnAuthenticate: Button
    private lateinit var btnCancel: Button
    private lateinit var btnSetupBiometric: Button

    private var phoneNumber: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_biometric_auth)

        phoneNumber = intent.getStringExtra("phone_number") ?: ""

        initializeViews()
        setupBiometric()
        checkBiometricAvailability()

        // Maximum security: Always require authentication
        // No session timeout - fingerprint required every app launch
    }

    private fun initializeViews() {
        tvStatus = findViewById(R.id.tvAuthStatus)
        btnAuthenticate = findViewById(R.id.btnAuthenticate)
        btnCancel = findViewById(R.id.btnCancel)
        btnSetupBiometric = findViewById(R.id.btnSetupBiometric)

        // Hide setup button by default
        btnSetupBiometric.visibility = Button.GONE

        btnAuthenticate.setOnClickListener {
            showBiometricPrompt()
        }

        btnCancel.setOnClickListener {
            finish()
        }

        btnSetupBiometric.setOnClickListener {
            openBiometricSettings()
        }
    }

    private fun setupBiometric() {
        executor = ContextCompat.getMainExecutor(this)

        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)

                    // User cancelled - that's ok
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        tvStatus.text = "🚫 Autenticación cancelada"
                        return
                    }

                    tvStatus.text = "❌ Error: $errString"
                    Toast.makeText(applicationContext, "Error: $errString", Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    tvStatus.text = "✅ Autenticación exitosa"
                    Toast.makeText(applicationContext, "¡Acceso concedido!", Toast.LENGTH_SHORT).show()

                    // Proceed immediately to SendActivity
                    proceedToSendActivity()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    tvStatus.text = "⚠️ Autenticación fallida. Intenta de nuevo."
                    Toast.makeText(applicationContext, "Autenticación fallida", Toast.LENGTH_SHORT).show()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Autenticación biométrica")
            .setSubtitle("Verifica tu identidad para acceder")
            .setDescription("Usa tu huella digital para acceder a la aplicación de ubicación")
            .setNegativeButtonText("Cancelar")
            .setConfirmationRequired(true)
            .build()
    }

    private fun checkBiometricAvailability() {
        val biometricManager = BiometricManager.from(this)

        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                tvStatus.text = "🔒 Toca el botón para autenticarte"
                btnAuthenticate.isEnabled = true
                // Auto-show biometric prompt
                showBiometricPrompt()
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                tvStatus.text = "❌ No hay sensor biométrico disponible"
                btnAuthenticate.isEnabled = false
                Toast.makeText(this, "Dispositivo sin sensor biométrico", Toast.LENGTH_LONG).show()
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                tvStatus.text = "⚠️ Sensor biométrico no disponible"
                btnAuthenticate.isEnabled = false
                Toast.makeText(this, "Sensor biométrico no disponible", Toast.LENGTH_LONG).show()
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                tvStatus.text = "⚠️ No hay huellas registradas\n\nPor favor registra tu huella digital para continuar"
                btnAuthenticate.isEnabled = false
                btnSetupBiometric.visibility = Button.VISIBLE
                Toast.makeText(this,
                    "Necesitas registrar al menos una huella digital",
                    Toast.LENGTH_LONG).show()
            }
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> {
                tvStatus.text = "⚠️ Actualización de seguridad requerida"
                btnAuthenticate.isEnabled = false
            }
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> {
                tvStatus.text = "❌ Autenticación biométrica no soportada"
                btnAuthenticate.isEnabled = false
            }
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> {
                tvStatus.text = "❓ Estado biométrico desconocido"
                btnAuthenticate.isEnabled = false
            }
        }
    }

    private fun showBiometricPrompt() {
        biometricPrompt.authenticate(promptInfo)
    }

    private fun proceedToSendActivity() {
        val intent = Intent(this, SendActivity::class.java)
        intent.putExtra("phone_number", phoneNumber)
        startActivity(intent)
        finish()
    }

    private fun openBiometricSettings() {
        try {
            // Open the biometric enrollment settings
            val enrollIntent = Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    BiometricManager.Authenticators.BIOMETRIC_STRONG)
            }
            startActivityForResult(enrollIntent, BIOMETRIC_ENROLLMENT_REQUEST)
        } catch (e: Exception) {
            // Fallback to general security settings if biometric enrollment not available
            try {
                val securityIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                startActivity(securityIntent)
                Toast.makeText(this,
                    "Por favor configura tu huella digital en Seguridad",
                    Toast.LENGTH_LONG).show()
            } catch (ex: Exception) {
                Toast.makeText(this,
                    "No se puede abrir la configuración",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val BIOMETRIC_ENROLLMENT_REQUEST = 100
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BIOMETRIC_ENROLLMENT_REQUEST) {
            // Re-check biometric availability after user returns from settings
            checkBiometricAvailability()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check availability in case user enabled/disabled biometrics
        checkBiometricAvailability()
    }
}