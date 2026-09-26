package top.nkbe.npatch.wrappermanager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PackagingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val model get() = (application as WrapperApplication).model

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.task_channel), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification(model.state.value))
        scope.launch {
            model.state.collect { state ->
                if (!state.busy) stopSelf()
                else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) model.cancelWork()
        if (!model.state.value.busy) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        model.cancelWork()
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(state: WrapperState): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, PackagingService::class.java).setAction(CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (state.cancelling) getString(R.string.task_cancelling) else getString(state.stage.labelResource()))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, state.progressPercent(), state.totalBytes <= 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.cancel_task), cancel)
            .build()
    }

    private companion object {
        const val CHANNEL = "packaging"
        const val NOTIFICATION_ID = 1
        const val CANCEL = "top.nkbe.npatch.wrappermanager.CANCEL"
    }
}
