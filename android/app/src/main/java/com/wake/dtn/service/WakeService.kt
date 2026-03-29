package com.wake.dtn.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.wake.dtn.data.BundleStoreManager
import com.wake.dtn.data.WakeDatabase
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WakeService : Service() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var bundleStoreManager: BundleStoreManager
        private set

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): WakeService = this@WakeService
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        startForeground(NOTIFICATION_ID, NotificationHelper.buildNotification(this))

        bundleStoreManager = BundleStoreManager(
            context = this,
            dao = WakeDatabase.getInstance(this).bundleDao(),
        )

        scope.launch {
            while (isActive) {
                delay(TTL_CHECK_INTERVAL_MS)
                try {
                    bundleStoreManager.runTtlEviction()
                } catch (e: Exception) {
                    Log.e(TAG, "TTL eviction failed; will retry next interval", e)
                }
            }
        }
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
        private const val TAG = "WakeService"
        const val NOTIFICATION_ID = 1
        const val TTL_CHECK_INTERVAL_MS = 5 * 60 * 1_000L

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
