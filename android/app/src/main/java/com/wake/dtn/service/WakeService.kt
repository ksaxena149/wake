package com.wake.dtn.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.wake.dtn.data.BundleReassembler
import com.wake.dtn.data.BundleStoreManager
import com.wake.dtn.data.ReassembledBundle
import com.wake.dtn.data.WakeDatabase
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class WakeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True while the service is alive and its background coroutines are running. */
    val isScopeActive: Boolean get() = scope.isActive

    lateinit var bundleStoreManager: BundleStoreManager
        private set

    lateinit var syncManager: ServerSyncManager
        private set

    private val _latestBundle = MutableStateFlow<ReassembledBundle?>(null)

    /** The most recently reassembled bundle. Observe from [MainViewModel] or UI to render content. */
    val latestBundle: StateFlow<ReassembledBundle?> = _latestBundle.asStateFlow()

    private val _lastSyncTimeMs = MutableStateFlow<Long?>(null)

    /** Wall-clock time of the most recent successful [ServerSyncManager.pollAndFetch] call. */
    val lastSyncTimeMs: StateFlow<Long?> = _lastSyncTimeMs.asStateFlow()

    private val _totalStorageBytesFlow = MutableStateFlow(0L)

    /** Total payload bytes currently held in the bundle store. Updated after each TTL eviction pass. */
    val totalStorageBytesFlow: StateFlow<Long> = _totalStorageBytesFlow.asStateFlow()

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

        // Node identity is ephemeral for Phase 2; persistent Keystore identity is issue #31.
        val nodeId = UUID.randomUUID().toString()
        syncManager = ServerSyncManager(
            httpClient = WakeHttpClient(baseUrl = SERVER_BASE_URL),
            storeManager = bundleStoreManager,
            nodeId = nodeId,
            reassembler = BundleReassembler(bundleStoreManager, filesDir),
        )

        scope.launch {
            syncManager.reassembledBundles.collect { bundle ->
                _latestBundle.value = bundle
            }
        }

        scope.launch {
            while (isActive) {
                try {
                    bundleStoreManager.runTtlEviction()
                    _totalStorageBytesFlow.value = bundleStoreManager.getTotalPayloadBytes()
                } catch (e: Exception) {
                    Log.e(TAG, "TTL eviction failed; will retry next interval", e)
                }
                delay(TTL_CHECK_INTERVAL_MS)
            }
        }

        scope.launch {
            while (isActive) {
                try {
                    syncManager.pollAndFetch()
                    _lastSyncTimeMs.value = System.currentTimeMillis()
                } catch (e: Exception) {
                    Log.e(TAG, "Sync poll failed; will retry next interval", e)
                }
                delay(SYNC_INTERVAL_MS)
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
        const val SYNC_INTERVAL_MS = 30 * 1_000L

        /** Change to your laptop's LAN IP for on-device testing; issue #37 makes this configurable. */
        const val SERVER_BASE_URL = "http://192.168.1.11:8000"

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
