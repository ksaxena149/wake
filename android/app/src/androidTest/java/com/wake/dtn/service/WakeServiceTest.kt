package com.wake.dtn.service

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WakeServiceTest {

    @get:Rule
    val serviceRule: ServiceTestRule = ServiceTestRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun bindToService(): WakeService {
        val binder = serviceRule.bindService(Intent(context, WakeService::class.java))
        return (binder as WakeService.LocalBinder).getService()
    }

    @Test
    fun bind_returnsLocalBinder() {
        val binder = serviceRule.bindService(Intent(context, WakeService::class.java))
        assertNotNull(binder)
        assertTrue(binder is WakeService.LocalBinder)
    }

    @Test
    fun getService_returnsWakeServiceInstance() {
        val service = bindToService()
        assertNotNull(service)
        assertTrue(service is WakeService)
    }

    @Test
    fun coroutineScope_isActiveAfterBind() {
        val service = bindToService()
        assertTrue(service.isScopeActive)
    }

    @Test
    fun lastSyncTimeMs_initiallyNull() {
        val service = bindToService()
        assertNull(service.lastSyncTimeMs.value)
    }

    @Test
    fun totalStorageBytesFlow_initiallyZero() {
        val service = bindToService()
        assertEquals(0L, service.totalStorageBytesFlow.value)
    }

    @Test
    fun bindOnly_doesNotStartPolling_lastSyncRemainsNull() {
        // BIND_AUTO_CREATE triggers onCreate but NOT onStartCommand.
        // The polling loops must remain dormant until WakeService.start() is called.
        val service = bindToService()
        // lastSyncTimeMs is only set inside the polling loop — it must still be null here.
        assertNull(
            "polling loop must not fire on bind-only; lastSyncTimeMs should be null",
            service.lastSyncTimeMs.value,
        )
    }

    @Test
    fun bindOnly_syncManager_hasValidNodeId() {
        // syncManager is initialised in onCreate which runs on both bind and start.
        val service = bindToService()
        assertFalse(service.syncManager.nodeId.isBlank())
    }

    @Test
    fun stopAction_doesNotThrow_whenServiceNotYetStarted() {
        // Sending ACTION_STOP to a bound-only service should be a safe no-op (isStarted guard).
        bindToService()
        val stopIntent = Intent(context, WakeService::class.java).setAction("com.wake.dtn.action.STOP")
        context.startService(stopIntent)
        // No assertion needed — just verifying no crash/exception is thrown.
    }
}
