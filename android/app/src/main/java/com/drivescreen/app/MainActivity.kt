package com.drivescreen.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.net.Uri
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "DriveScreen"
    }

    private lateinit var connectionStatus: TextView
    private lateinit var diagnosticResult: TextView
    private lateinit var deviceMessage: TextView
    private lateinit var diagnosticInput: EditText
    private lateinit var mediaAccessStatus: TextView
    private lateinit var mediaStatus: TextView
    private lateinit var navigationStatus: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var privacySwitch: Switch
    private lateinit var connectionManager: BleConnectionManager
    private lateinit var mediaSessionManager: MediaSessionManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val phoneStatusRefresh = object : Runnable {
        override fun run() {
            if (bleConnected) sendNavigationState()
            mainHandler.postDelayed(this, 30_000L)
        }
    }
    private val listenerComponent
        get() = ComponentName(this, DriveScreenNotificationListenerService::class.java)

    private var bleConnected = false
    private var sessionListenerRegistered = false
    private var mediaAccessPromptShown = false
    private var privacyMode = true
    private var latestNotifications: List<DriveNotification> = emptyList()
    private var latestNavigation: NavigationUpdate? = null
    private var activeController: MediaController? = null
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMediaState()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateMediaState()
        }

        override fun onSessionDestroyed() {
            refreshActiveSessions()
        }
    }

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            selectActiveSession(controllers.orEmpty())
        }

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
        mediaAccessStatus = findViewById(R.id.media_access_status)
        mediaStatus = findViewById(R.id.media_status)
        navigationStatus = findViewById(R.id.navigation_status)
        notificationStatus = findViewById(R.id.notification_status)
        privacySwitch = findViewById(R.id.notification_privacy_switch)
        mediaSessionManager = getSystemService(MediaSessionManager::class.java)

        connectionManager = BleConnectionManager(
            context = applicationContext,
            listener = object : BleConnectionManager.Listener {
                override fun onStatusChanged(status: String) {
                    bleConnected = connectionManager.isConnected
                    connectionStatus.text = "Estado BLE: $status"
                    if (bleConnected) {
                        updateMediaState()
                        sendNavigationState()
                        connectionManager.sendNotificationList(latestNotifications, privacyMode)
                    }
                }

                override fun onMessage(message: JSONObject) {
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

                override fun onMediaCommand(command: String) {
                    controlActiveSession(command)
                }
            }
        )

        findViewById<Button>(R.id.connect_button).setOnClickListener {
            connectWithPermissions()
        }
        findViewById<Button>(R.id.start_navigation_button).setOnClickListener {
            startOsmAndNavigation()
        }
        findViewById<Button>(R.id.media_access_button).setOnClickListener {
            openNotificationAccessSettings()
        }
        privacySwitch.isChecked = privacyMode
        privacySwitch.setOnCheckedChangeListener { _, enabled ->
            privacyMode = enabled
            sendNotificationState()
        }
        findViewById<Button>(R.id.send_button).setOnClickListener {
            val text = diagnosticInput.text.toString().trim()
            if (text.isEmpty()) {
                diagnosticResult.text = "Teste BLE: escreve uma mensagem primeiro"
            } else {
                connectionManager.sendDiagnostic(text)
            }
        }
        mainHandler.postDelayed(phoneStatusRefresh, 30_000L)
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationAccess()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(phoneStatusRefresh)
        DriveScreenNotificationStore.clearObserver()
        unregisterSessionListener()
        activeController?.unregisterCallback(controllerCallback)
        connectionManager.stop()
        super.onDestroy()
    }

    private fun refreshNotificationAccess() {
        if (!hasNotificationListenerAccess()) {
            unregisterSessionListener()
            setActiveController(null)
            mediaAccessStatus.text = "Multimédia: acesso não autorizado"
            latestNotifications = emptyList()
            latestNavigation = null
            sendNotificationState()
            sendNavigationState()
            if (!mediaAccessPromptShown) {
                mediaAccessPromptShown = true
                showMediaAccessPrompt()
            }
            return
        }

        mediaAccessStatus.text = "Multimédia: acesso autorizado"
        DriveScreenNotificationStore.observe { items, navigation ->
            mainHandler.post {
                latestNotifications = items
                latestNavigation = navigation
                sendNotificationState()
                sendNavigationState()
            }
        }
        registerSessionListener()
        refreshActiveSessions()
    }

    private fun sendNotificationState() {
        notificationStatus.text = when {
            !hasNotificationListenerAccess() -> "Notificações: acesso não autorizado"
            latestNotifications.isEmpty() -> "Notificações: nenhuma recente"
            privacyMode -> "Notificações: ${latestNotifications.size} — conteúdo oculto"
            else -> "Notificações: ${latestNotifications.size} — conteúdo visível"
        }
        if (bleConnected) {
            connectionManager.sendNotificationList(latestNotifications, privacyMode)
        }
    }

    private fun sendNavigationState() {
        val update = latestNavigation
        navigationStatus.text = when {
            !hasNotificationListenerAccess() -> "Ativa o acesso a notificações para ler instruções"
            update == null -> "Sem instruções de navegação recentes"
            update.source == "REAL" -> "Instrução recebida de ${update.provider}"
            else -> "Navegação ativa; instrução ainda não reconhecida"
        }
        if (bleConnected) {
            val clock = SimpleDateFormat("HH:mm", Locale.US).format(Date())
            val locationMode = Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            )
            val gpsEnabled = locationMode != Settings.Secure.LOCATION_MODE_OFF
            connectionManager.sendNavigationUpdate(update, clock, gpsEnabled)
        }
    }

    private fun showMediaAccessPrompt() {
        AlertDialog.Builder(this)
            .setTitle("Ativar multimédia e notificações")
            .setMessage(
                "O Android exige uma autorização especial nas Definições para a app " +
                    "poder ler sessões multimédia e notificações para as apresentar " +
                    "no DriveScreen. O conteúdo das notificações é ocultado por " +
                    "predefinição; podes alterar a privacidade nesta aplicação."
            )
            .setPositiveButton("Abrir Definições") { _, _ ->
                openNotificationAccessSettings()
            }
            .setNegativeButton("Agora não", null)
            .show()
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (exception: ActivityNotFoundException) {
            mediaAccessStatus.text = "Não foi possível abrir as Definições de notificações"
        }
    }

    private fun hasNotificationListenerAccess(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        return enabledListeners.split(':').any { it == listenerComponent.flattenToString() }
    }

    private fun registerSessionListener() {
        if (sessionListenerRegistered) return
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                activeSessionsListener,
                listenerComponent,
                mainHandler
            )
            sessionListenerRegistered = true
        } catch (exception: SecurityException) {
            mediaAccessStatus.text = "Multimédia: acesso ao sistema recusado"
        }
    }

    private fun unregisterSessionListener() {
        if (!sessionListenerRegistered) return
        mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener)
        sessionListenerRegistered = false
    }

    private fun refreshActiveSessions() {
        if (!hasNotificationListenerAccess()) {
            setActiveController(null)
            return
        }
        try {
            selectActiveSession(mediaSessionManager.getActiveSessions(listenerComponent))
        } catch (exception: SecurityException) {
            mediaAccessStatus.text = "Multimédia: acesso ao sistema recusado"
            setActiveController(null)
        }
    }

    private fun selectActiveSession(controllers: List<MediaController>) {
        val selected = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull {
            it.playbackState != null || it.metadata != null
        }
        setActiveController(selected)
    }

    private fun setActiveController(controller: MediaController?) {
        if (activeController?.sessionToken == controller?.sessionToken) {
            updateMediaState()
            return
        }
        activeController?.unregisterCallback(controllerCallback)
        activeController = controller
        activeController?.registerCallback(controllerCallback, mainHandler)
        updateMediaState()
    }

    private fun updateMediaState() {
        val controller = activeController
        val metadata = controller?.metadata
        val playback = controller?.playbackState
        val active = controller != null && (metadata != null || playback != null)
        val playing = playback?.state == PlaybackState.STATE_PLAYING

        mediaStatus.text = when {
            !hasNotificationListenerAccess() -> "Autoriza o acesso para ler sessões multimédia"
            !active -> "Sem sessão multimédia ativa"
            playing -> "A reproduzir"
            else -> "Sessão ativa em pausa"
        }

        val payload = JSONObject()
            .put("active", active)
            .put("playing", playing)
            .put("source", if (active) "REAL" else "NONE")
        if (active && metadata != null) {
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?.let { payload.put("title", truncateUtf8(it, 96)) }
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?.let { payload.put("artist", truncateUtf8(it, 96)) }
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
                ?.let { payload.put("album", truncateUtf8(it, 96)) }
            metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                .takeIf { it > 0L }
                ?.let { payload.put("duration_ms", it) }
        }
        if (active && playback != null && playback.position >= 0L) {
            payload.put("position_ms", playback.position)
        }
        if (active && controller != null) {
            payload.put("app", truncateUtf8(controller.packageName, 48))
        }
        if (bleConnected) {
            connectionManager.sendMediaState(payload)
        }
    }

    private fun startOsmAndNavigation() {
        val destination = findViewById<EditText>(R.id.navigation_destination)
            .text
            .toString()
            .trim()
        if (destination.isEmpty()) {
            navigationStatus.text = "Escreve um destino para iniciar a navegação"
            return
        }

        val osmandPackage = listOf("net.osmand.plus", "net.osmand").firstOrNull { packageName ->
            try {
                packageManager.getApplicationInfo(packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
        if (osmandPackage == null) {
            navigationStatus.text = "Instala o OsmAnd para iniciar a navegação"
            return
        }

        val navigationUri = Uri.parse("google.navigation:q=${Uri.encode(destination)}")
        val intent = Intent(Intent.ACTION_VIEW, navigationUri).setPackage(osmandPackage)
        try {
            startActivity(intent)
            navigationStatus.text = "Destino enviado ao OsmAnd; confirma a rota na aplicação"
        } catch (exception: ActivityNotFoundException) {
            Log.e(TAG, "O OsmAnd instalado não aceita o pedido de navegação.", exception)
            navigationStatus.text = "Não foi possível abrir a navegação no OsmAnd"
        }
    }

    private fun controlActiveSession(command: String) {
        val controller = activeController
        val playback = controller?.playbackState
        if (controller == null || playback == null) {
            mediaStatus.text = "Sem sessão compatível para controlar"
            return
        }

        val requiredAction = when (command) {
            "play" -> PlaybackState.ACTION_PLAY
            "pause" -> PlaybackState.ACTION_PAUSE
            "previous" -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
            "next" -> PlaybackState.ACTION_SKIP_TO_NEXT
            else -> return
        }
        if (playback.actions and requiredAction == 0L) {
            mediaStatus.text = "A sessão ativa não suporta este comando"
            return
        }

        try {
            when (command) {
                "play" -> controller.transportControls.play()
                "pause" -> controller.transportControls.pause()
                "previous" -> controller.transportControls.skipToPrevious()
                "next" -> controller.transportControls.skipToNext()
            }
        } catch (exception: SecurityException) {
            mediaStatus.text = "O Android recusou o controlo multimédia"
        }
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        var end = value.length
        while (end > 0 && value.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) {
            end--
            if (end > 0 && Character.isHighSurrogate(value[end - 1])) end--
        }
        return value.substring(0, end)
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
