package desu.inugram.helpers.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import desu.inugram.InuConfig
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.ui.LaunchActivity

// entiny: keeps the tunnel engine alive when Telegram is backgrounded — without a foreground
// service Android freezes/kills the process minutes after leaving the app and the tunnel drops.
class TunnelForegroundService : Service(), NotificationCenter.NotificationCenterDelegate {

    companion object {
        private const val CHANNEL_ID = "inu_tunnel"
        private const val NOTIFICATION_ID = 42139

        @JvmStatic
        fun refresh(context: Context) {
            val want = InuConfig.BUILT_IN_TUNNEL.value && BuiltInTunnelHelper.isActive()
            val intent = Intent(context, TunnelForegroundService::class.java)
            if (want) {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } else {
                context.stopService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.InuBuiltInTunnel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged)
        postNotification()
    }

    override fun didReceivedNotification(id: Int, account: Int, args: Any...) {
        if (!InuConfig.BUILT_IN_TUNNEL.value || !BuiltInTunnelHelper.isActive()) stopSelf()
        else postNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!InuConfig.BUILT_IN_TUNNEL.value || !BuiltInTunnelHelper.isActive()) {
            stopSelf()
            return START_NOT_STICKY
        }
        postNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(NotificationCenter.proxySettingsChanged)
        super.onDestroy()
    }

    private fun postNotification() {
        val launch = PendingIntent.getActivity(
            this, 0,
            Intent(this, LaunchActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = getString(R.string.InuBuiltInTunnel)
        val text = BuiltInTunnelHelper.stateText()
        val notification: Notification =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.inu_tabler_shield_lock)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(launch)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setSmallIcon(R.drawable.inu_tabler_shield_lock)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(launch)
                    .build()
            }
        startForeground(NOTIFICATION_ID, notification)
    }
}
