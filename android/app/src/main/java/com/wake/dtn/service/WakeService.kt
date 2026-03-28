package com.wake.dtn.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class WakeService : Service() {

    // Coroutine scope tied to the service lifetime. Issues #14/#15 will launch work here.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): WakeService = this@WakeService
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        startForeground(NOTIFICATION_ID, NotificationHelper.buildNotification(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: Android will restart this service with a null Intent after being killed
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeService::class.java))
        }
    }
}
