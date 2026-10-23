package com.m3uplayer.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.m3uplayer.app.M3UApp
import com.m3uplayer.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano que mantém o app vivo enquanto há downloads (o Android mata
 * processos em segundo plano). Mostra uma notificação com o progresso e se encerra sozinho.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)
        val manager = (application as M3UApp).downloads
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(manager.items.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (watcher == null) {
            watcher = scope.launch {
                manager.items.collectLatest { items ->
                    if (items.none { it.isActive }) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(items))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(items: List<DownloadInfo>): Notification {
        val active = items.filter { it.isActive }
        val running = active.firstOrNull { it.state == DownloadState.RUNNING }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (active.size <= 1) "Baixando: ${running?.title ?: active.firstOrNull()?.title ?: ""}"
        else "Baixando ${active.size} itens"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(running?.detail ?: "Na fila")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .setProgress(100, ((running?.progress ?: 0f) * 100).toInt(), running?.indeterminate ?: true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
