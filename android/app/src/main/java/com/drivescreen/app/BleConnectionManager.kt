package com.drivescreen.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.CharacterCodingException
import java.util.ArrayDeque

internal class BleConnectionManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStatusChanged(status: String)
        fun onMessage(message: JSONObject)
        fun onDeviceMessage(text: String)
        fun onMediaCommand(command: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter
    private val assembler = BleProtocol.Assembler()
    private val pendingWrites = ArrayDeque<ByteArray>()
    private var running = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var frameId = 0
    private var messageId = 0
    private var expectedHelloId = -1
    private var handshakeComplete = false
    private var lastInboundAt = 0L

    private val scanTimeout = Runnable {
        stopScan()
        if (running && gatt == null) scheduleScan()
    }
    private val retryScan = Runnable { startScan() }
    private val handshakeTimeout = Runnable {
        if (running && !handshakeComplete && gatt != null) {
            disconnectAndRetry("tempo limite do handshake")
        }
    }
    private val serviceTimeout = Runnable {
        if (running && !handshakeComplete && gatt != null) {
            disconnectAndRetry("tempo limite ao descobrir serviços")
        }
    }
    private val pingTask = object : Runnable {
        override fun run() {
            if (!running || !handshakeComplete) return
            if (android.os.SystemClock.elapsedRealtime() - lastInboundAt > 30_000) {
                disconnectAndRetry("Ligação inativa")
                return
            }
            sendMessage("PING")
            handler.postDelayed(this, 10_000)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (!running || gatt != null) return@post
                stopScan()
                listener.onStatusChanged("a ligar")
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                Log.e(TAG, "BLE scan failed: $errorCode")
                stopScan()
                if (running) scheduleScan()
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS ||
                    newState != BluetoothProfile.STATE_CONNECTED
                ) {
                    try {
                        gatt.close()
                    } catch (exception: SecurityException) {
                        Log.w(TAG, "Sem permissão para fechar GATT após desconexão.", exception)
                    }
                    if (this@BleConnectionManager.gatt === gatt) {
                        this@BleConnectionManager.gatt = null
                    }
                    clearSession()
                    if (running) scheduleScan()
                    return@post
                }
                this@BleConnectionManager.gatt = gatt
                listener.onStatusChanged("a descobrir serviços")
                handler.removeCallbacks(serviceTimeout)
                handler.postDelayed(serviceTimeout, 15_000)
                try {
                    gatt.requestMtu(512)
                    gatt.discoverServices()
                } catch (exception: SecurityException) {
                    Log.e(TAG, "Permissão BLE perdida durante ligação.", exception)
                    disconnectAndRetry("permissão Bluetooth em falta")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    this@BleConnectionManager.mtu = mtu.coerceAtMost(512)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    disconnectAndRetry("falha ao descobrir serviços")
                    return@post
                }
                try {
                    val service = gatt.getService(BleProtocol.serviceUuid)
                    val rxCharacteristic = service?.getCharacteristic(BleProtocol.rxUuid)
                    val txCharacteristic = service?.getCharacteristic(BleProtocol.txUuid)
                    if (rxCharacteristic == null || txCharacteristic == null) {
                        disconnectAndRetry("serviço DriveScreen não encontrado")
                        return@post
                    }
                    rx = rxCharacteristic
                    tx = txCharacteristic
                    if (!gatt.setCharacteristicNotification(txCharacteristic, true)) {
                        disconnectAndRetry("não foi possível ativar notificações BLE")
                        return@post
                    }
                    val descriptor = txCharacteristic.getDescriptor(CCCD_UUID)
                    if (descriptor == null) {
                        disconnectAndRetry("descriptor de notificações em falta")
                        return@post
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        val result = gatt.writeDescriptor(
                            descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        )
                        if (result != BluetoothStatusCodes.SUCCESS) {
                            disconnectAndRetry("falha ao subscrever notificações BLE")
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        if (!gatt.writeDescriptor(descriptor)) {
                            disconnectAndRetry("falha ao subscrever notificações BLE")
                        }
                    }
                } catch (exception: SecurityException) {
                    Log.e(TAG, "Permissão BLE perdida ao descobrir serviços.", exception)
                    disconnectAndRetry("permissão Bluetooth em falta")
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    disconnectAndRetry("falha ao ativar notificações BLE")
                    return@post
                }
                listener.onStatusChanged("ligado; a validar protocolo")
                lastInboundAt = android.os.SystemClock.elapsedRealtime()
                handler.removeCallbacks(serviceTimeout)
                handler.removeCallbacks(handshakeTimeout)
                handler.postDelayed(handshakeTimeout, 10_000)
                expectedHelloId = sendMessage(
                    "HELLO",
                    JSONObject().put("app_version", BuildConfig.VERSION_NAME)
                ) ?: -1
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handler.post {
                if (pendingWrites.isNotEmpty()) pendingWrites.removeFirst()
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    disconnectAndRetry("falha ao escrever dados BLE")
                } else {
                    writeNextFrame()
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value?.copyOf() ?: return
            acceptNotification(characteristic, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            acceptNotification(characteristic, value.copyOf())
        }
    }

    fun start() {
        if (running) return
        if (!hasBluetoothPermissions()) {
            listener.onStatusChanged("permissão Bluetooth em falta")
            return
        }
        if (adapter == null || !adapter.isEnabled) {
            listener.onStatusChanged("Bluetooth desligado")
            return
        }
        running = true
        startScan()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        stopScan()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (exception: SecurityException) {
            Log.w(TAG, "Sem permissão para fechar GATT.", exception)
        }
        gatt = null
        clearSession()
        listener.onStatusChanged("desligado")
    }

    fun sendDiagnostic(text: String) {
        if (!handshakeComplete) {
            listener.onStatusChanged("ligar primeiro para enviar o teste")
            return
        }
        if (text.toByteArray(Charsets.UTF_8).size !in 1..128) {
            listener.onStatusChanged("mensagem de teste: limite de 128 bytes")
            return
        }
        if (sendMessage("DIAGNOSTIC", JSONObject().put("text", text)) != null) {
            listener.onStatusChanged("mensagem de teste enviada")
        }
    }

    fun sendMediaState(payload: JSONObject) {
        if (!handshakeComplete) return
        sendMessage("MEDIA_STATE", payload)
    }

    private fun startScan() {
        if (!running || gatt != null || scanning) return
        if (!hasBluetoothPermissions()) {
            listener.onStatusChanged("permissão Bluetooth em falta")
            return
        }
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            listener.onStatusChanged("scanner BLE indisponível")
            scheduleScan()
            return
        }
        try {
            listener.onStatusChanged("a procurar DriveScreen")
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleProtocol.serviceUuid)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback
            )
            scanning = true
            handler.removeCallbacks(scanTimeout)
            handler.postDelayed(scanTimeout, 15_000)
        } catch (exception: SecurityException) {
            Log.e(TAG, "Sem permissão para iniciar scan BLE.", exception)
            listener.onStatusChanged("permissão de scan em falta")
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (!scanning) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Sem permissão para parar scan BLE.", exception)
        }
        scanning = false
    }

    private fun connect(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) {
            listener.onStatusChanged("permissão Bluetooth em falta")
            return
        }
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) disconnectAndRetry("não foi possível iniciar ligação")
        } catch (exception: SecurityException) {
            Log.e(TAG, "Sem permissão para ligar por BLE.", exception)
            disconnectAndRetry("permissão Bluetooth em falta")
        }
    }

    private fun acceptNotification(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (characteristic.uuid != BleProtocol.txUuid) return
        handler.post {
            try {
                val json = assembler.accept(value) ?: return@post
                lastInboundAt = android.os.SystemClock.elapsedRealtime()
                val message = JSONObject(json)
                when (message.optString("type")) {
                    "ACK" -> {
                        val payload = message.optJSONObject("payload")
                        if (payload?.optInt("ack_id", -1) != expectedHelloId) {
                            disconnectAndRetry("resposta HELLO inválida")
                            return@post
                        }
                        handshakeComplete = true
                        handler.removeCallbacks(handshakeTimeout)
                        listener.onStatusChanged("ligado")
                        listener.onMessage(message)
                        handler.removeCallbacks(pingTask)
                        handler.postDelayed(pingTask, 10_000)
                        sendDiagnostic("DriveScreen Android ligado")
                    }
                    "PONG" -> Unit
                    "ERROR" -> {
                        listener.onMessage(message)
                        if (!handshakeComplete) {
                            disconnectAndRetry(
                                message.optJSONObject("payload")?.optString("code")
                                    ?: "erro no handshake"
                            )
                        }
                    }
                    "DIAGNOSTIC_REPLY" -> {
                        listener.onMessage(message)
                        listener.onDeviceMessage(
                            message.optJSONObject("payload")?.optString("text").orEmpty()
                        )
                    }
                    "MEDIA_CMD" -> {
                        val command = message.optJSONObject("payload")?.optString("cmd").orEmpty()
                        if (command !in setOf("play", "pause", "previous", "next")) {
                            listener.onStatusChanged("comando multimédia desconhecido")
                        } else {
                            listener.onMediaCommand(command)
                        }
                    }
                    else -> listener.onMessage(message)
                }
            } catch (exception: IllegalArgumentException) {
                handleInvalidMessage(exception)
            } catch (exception: JSONException) {
                handleInvalidMessage(exception)
            } catch (exception: CharacterCodingException) {
                handleInvalidMessage(exception)
            }
        }
    }

    private fun handleInvalidMessage(exception: Exception) {
        Log.e(TAG, "Notificação BLE inválida.", exception)
        disconnectAndRetry("mensagem BLE inválida")
    }

    private fun sendMessage(type: String, payload: JSONObject? = null): Int? {
        val characteristic = rx
        if (characteristic == null) {
            listener.onStatusChanged("característica BLE indisponível")
            return null
        }
        val id = messageId++ and 0xFFFF
        val message = BleProtocol.message(type, id, payload)
        try {
            pendingWrites.addAll(BleProtocol.encode(message, frameId++, mtu))
            writeNextFrame(characteristic)
            return id
        } catch (exception: IllegalArgumentException) {
            Log.e(TAG, "Não foi possível codificar mensagem BLE.", exception)
            listener.onStatusChanged("mensagem BLE inválida")
            return null
        }
    }

    private fun writeNextFrame(characteristic: BluetoothGattCharacteristic? = rx) {
        val currentGatt = gatt ?: return
        val currentCharacteristic = characteristic ?: return
        if (pendingWrites.isEmpty()) return
        try {
            @Suppress("DEPRECATION")
            currentCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            currentCharacteristic.value = pendingWrites.first()
            @Suppress("DEPRECATION")
            if (!currentGatt.writeCharacteristic(currentCharacteristic)) {
                disconnectAndRetry("escrita BLE recusada")
            }
        } catch (exception: SecurityException) {
            Log.e(TAG, "Sem permissão para escrever BLE.", exception)
            disconnectAndRetry("permissão Bluetooth em falta")
        }
    }

    private fun disconnectAndRetry(reason: String) {
        listener.onStatusChanged(reason)
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (exception: SecurityException) {
            Log.w(TAG, "Sem permissão para fechar GATT.", exception)
        }
        gatt = null
        clearSession()
        if (running) scheduleScan()
    }

    private fun scheduleScan() {
        if (!running) return
        stopScan()
        listener.onStatusChanged("desligado; nova tentativa em 5 s")
        handler.removeCallbacks(retryScan)
        handler.postDelayed(retryScan, 5_000)
    }

    private fun clearSession() {
        rx = null
        tx = null
        mtu = 23
        expectedHelloId = -1
        handshakeComplete = false
        pendingWrites.clear()
        assembler.reset()
        handler.removeCallbacks(pingTask)
        handler.removeCallbacks(handshakeTimeout)
        handler.removeCallbacks(serviceTimeout)
    }

    private fun hasBluetoothPermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

    private companion object {
        const val TAG = "DriveScreenBLE"
        val CCCD_UUID = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
