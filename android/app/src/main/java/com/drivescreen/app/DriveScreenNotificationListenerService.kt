package com.drivescreen.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

internal data class DriveNotification(
    val id: String,
    val app: String,
    val title: String,
    val text: String,
    val timeMs: Long
)

internal data class NavigationUpdate(
    val state: String,
    val source: String,
    val maneuver: String,
    val instruction: String,
    val distanceM: Double?,
    val provider: String
)

internal object DriveScreenNotificationStore {
    @Volatile private var notifications: List<DriveNotification> = emptyList()
    @Volatile private var navigation: NavigationUpdate? = null
    @Volatile private var observer: ((List<DriveNotification>, NavigationUpdate?) -> Unit)? = null

    fun observe(callback: (List<DriveNotification>, NavigationUpdate?) -> Unit) {
        observer = callback
        callback(notifications, navigation)
    }

    fun clearObserver() {
        observer = null
    }

    fun update(items: List<DriveNotification>, navigationUpdate: NavigationUpdate?) {
        notifications = items
        navigation = navigationUpdate
        observer?.invoke(items, navigationUpdate)
    }
}

class DriveScreenNotificationListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "DriveScreenNotifications"
        private const val MAX_NOTIFICATIONS = 10
        private val navigationPackages = mapOf(
            "com.google.android.apps.maps" to "google_maps_notification",
            "net.osmand.plus" to "osmand_notification",
            "net.osmand" to "osmand_notification",
            "com.waze" to "waze_notification"
        )
    }

    private val active = ConcurrentHashMap<String, StatusBarNotification>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications.orEmpty()
            .filter {
                it.packageName != packageName &&
                    it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
            }
            .forEach { active[it.key] = it }
        publishSnapshot()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName ||
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        ) return
        active[sbn.key] = sbn
        publishSnapshot()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        active.remove(sbn.key)
        publishSnapshot()
    }

    private fun publishSnapshot() {
        val entries = active.values.sortedByDescending { it.postTime }
        val items = entries
            .filter { it.notification.flags and Notification.FLAG_ONGOING_EVENT == 0 }
            .take(MAX_NOTIFICATIONS)
            .mapNotNull(::toDriveNotification)
        val nav = entries.firstNotNullOfOrNull(::toNavigationUpdate)
        DriveScreenNotificationStore.update(items, nav)
    }

    private fun toDriveNotification(sbn: StatusBarNotification): DriveNotification? {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ")
        ).filterNotNull().firstOrNull { it.isNotBlank() }.orEmpty()
        if (title.isBlank() && text.isBlank()) return null

        val app = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (exception: android.content.pm.PackageManager.NameNotFoundException) {
            Log.w(TAG, "Não foi possível obter o nome da aplicação ${sbn.packageName}.", exception)
            sbn.packageName
        }
        return DriveNotification(
            id = sbn.key,
            app = app,
            title = title,
            text = text,
            timeMs = sbn.postTime
        )
    }

    private fun toNavigationUpdate(sbn: StatusBarNotification): NavigationUpdate? {
        val provider = navigationPackages[sbn.packageName] ?: return null
        val extras = sbn.notification.extras
        val content = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ")
        ).filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
        val normalizedContent = Normalizer.normalize(content, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(java.util.Locale.ROOT)
        val directions = listOf(
            Regex("\\b(?:vire|virar|vire-se|vira)\\s+a\\s+direita\\b|\\bturn\\s+right\\b") to "right",
            Regex("\\b(?:vire|virar|vire-se|vira)\\s+a\\s+esquerda\\b|\\bturn\\s+left\\b") to "left",
            Regex("\\bmeia\\s+volta\\b|\\binverta\\s+o\\s+sentido\\b|\\bu-turn\\b") to "u_turn",
            Regex("\\b(?:siga|continue)\\s+(?:em\\s+)?frente\\b|\\bcontinue\\s+straight\\b") to "straight"
        )
        val matched = directions.firstOrNull { (pattern, _) -> pattern.containsMatchIn(normalizedContent) }
        val distance = Regex(
            "(\\d+(?:[.,]\\d+)?)\\s*(km|m)\\b",
            RegexOption.IGNORE_CASE
        ).find(content)?.let { match ->
            val value = match.groupValues[1].replace(',', '.').toDoubleOrNull()
            if (match.groupValues[2].equals("km", ignoreCase = true)) {
                value?.times(1000)
            } else {
                value
            }
        }
        if (content.isBlank()) {
            return NavigationUpdate("stale", "NONE", "none", "", null, provider)
        }

        // OsmAnd can phrase maneuvers in ways not covered by the arrow parser.
        // Keep the real notification text flowing to the screen in that case.
        return NavigationUpdate(
            state = "navigating",
            source = "REAL",
            maneuver = matched?.second ?: "none",
            instruction = content,
            distanceM = distance,
            provider = provider
        )
    }
}
