package com.wake.dtn.service

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.assertEquals
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
}
