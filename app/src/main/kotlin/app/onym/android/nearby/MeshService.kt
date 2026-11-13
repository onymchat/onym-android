package app.onym.android.nearby

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.onym.android.MainActivity
import app.onym.android.PushMessagingService
import app.onym.android.R

/**
 * Keeps the process — and so the Bluetooth radio — alive while the mesh is
 * on, with the ongoing notification Android requires for it. The work
 * itself lives in [MeshController]; this service only holds the process
 * in the foreground.
 */
class MeshService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel(this)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            // A single-color vector: a foreground service whose
            // notification icon Android rejects crashes the app.
            .setSmallIcon(R.drawable.ic_stat_onym_nearby)
            .setContentTitle(getString(app.onym.android.strings.R.string.mesh_service_title))
            .setContentText(getString(app.onym.android.strings.R.string.mesh_service_body))
            .setOngoing(true)
            .setContentIntent(openApp(this))
            .build()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            // Missing Bluetooth permission (14+ checks it for this type)
            // or started from the background (12+). The mesh still runs
            // while the app is open.
            Log.w("OnymMesh", "couldn't enter the foreground", e)
            stopSelf()
        }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "mesh"
        private const val NOTIFICATION_ID = 7041

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
            } catch (e: Exception) {
                Log.w("OnymMesh", "couldn't start the mesh service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshService::class.java))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(app.onym.android.strings.R.string.mesh_service_title),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}

/**
 * A message heard over Bluetooth gets no push — it never touched a relay —
 * so the app raises its own, content-free like the pushes, and only when no
 * Onym screen is open.
 */
object MeshArrivalNotifier {
    private const val NOTIFICATION_ID = 7042

    fun notify(context: Context) {
        if (PushMessagingService.appInForeground) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        PushMessagingService.ensureChannel(context)
        val notification = NotificationCompat.Builder(context, PushMessagingService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_onym_message)
            .setContentTitle(context.getString(app.onym.android.strings.R.string.push_notification_title))
            .setContentText(context.getString(app.onym.android.strings.R.string.push_notification_body))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}

private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)
