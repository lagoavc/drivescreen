package com.drivescreen.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var connectionStatus: TextView
    private lateinit var diagnosticResult: TextView
    private lateinit var deviceMessage: TextView
    private lateinit var diagnosticInput: EditText
    private lateinit var connectionManager: BleConnectionManager

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (requiredPermissions().all { permission ->
                    results[permission] == true ||
                        ContextCompat.checkSelfPermission(this, permission) ==
                        PackageManager.PERMISSION_GRANTED
                }) {
                connectionManager.start()
            } else {
                connectionStatus.text = "Estado BLE: permissão recusada"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectionStatus = findViewById(R.id.connection_status)
        diagnosticResult = findViewById(R.id.diagnostic_result)
        deviceMessage = findViewById(R.id.device_message)
        diagnosticInput = findViewById(R.id.diagnostic_input)

        connectionManager = BleConnectionManager(
            context = applicationContext,
            listener = object : BleConnectionManager.Listener {
                override fun onStatusChanged(status: String) {
                    connectionStatus.text = "Estado BLE: $status"
                }

                override fun onMessage(message: org.json.JSONObject) {
                    when (message.optString("type")) {
                        "DIAGNOSTIC_REPLY" -> {
                            val payload = message.optJSONObject("payload")
                            diagnosticResult.text =
                                "Teste BLE: resposta recebida — ${payload?.optString("text").orEmpty()}"
                        }
                        "ERROR" -> {
                            val payload = message.optJSONObject("payload")
                            diagnosticResult.text =
                                "Teste BLE: erro — ${payload?.optString("code").orEmpty()}"
                        }
                    }
                }

                override fun onDeviceMessage(text: String) {
                    deviceMessage.text = "Última resposta do dispositivo: $text"
                }
            }
        )

        findViewById<Button>(R.id.connect_button).setOnClickListener {
            connectWithPermissions()
        }
        findViewById<Button>(R.id.send_button).setOnClickListener {
            val text = diagnosticInput.text.toString().trim()
            if (text.isEmpty()) {
                diagnosticResult.text = "Teste BLE: escreve uma mensagem primeiro"
            } else {
                connectionManager.sendDiagnostic(text)
            }
        }
    }

    override fun onDestroy() {
        connectionManager.stop()
        super.onDestroy()
    }

    private fun connectWithPermissions() {
        val missing = requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            connectionManager.start()
        } else {
            permissionRequest.launch(missing.toTypedArray())
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}
