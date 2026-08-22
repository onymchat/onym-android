package app.onym.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * The receiving end of the content-free wake. The backend sends a
 * DATA-ONLY FCM message carrying nothing — no message, no sender, no
 * chat, no collapse key worth reading — so everything shown here is
 * rendered locally from string resources.
 *
 * Deliberately graph-free and cheap: FCM may spin the process up just
 * for this delivery, and building [OnymApplication.dependencies] here
 * would pay the whole composition root for a one-line notification.
 * `onNewToken` therefore crosses into the app only through
 * [PushTokenRelay]; `onMessageReceived` touches nothing but the
 * notification manager. No sync is started either — the wake's only
 * job is the nudge, because opening the app replays the inbox in full
 * (relay REQs carry no `since`).
 *
 * Nothing about the delivery is logged (no-activity-logging policy:
 * a push arrival IS message metadata).
 */
class PushMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushTokenRelay.offer(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (!canNotify()) return
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(app.onym.android.strings.R.string.push_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(app.onym.android.strings.R.string.push_notification_title))
            .setContentText(getString(app.onym.android.strings.R.string.push_notification_body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        // One stable id: several wakes collapse into one "New message"
        // entry instead of a stack that would count someone's messages
        // for anyone glancing at the lock screen.
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    companion object {
        const val CHANNEL_ID = "messages"
        private const val NOTIFICATION_ID = 1
    }
}
