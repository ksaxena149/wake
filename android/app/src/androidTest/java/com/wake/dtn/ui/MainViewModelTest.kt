package com.wake.dtn.ui

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wake.dtn.service.RelayController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainViewModelTest {

    // No-op controller: tests ViewModel state in isolation from the real service lifecycle.
    private val fakeRelay = object : RelayController {
        override fun start(context: Context) = Unit
        override fun stop(context: Context) = Unit
    }

    private lateinit var viewModel: MainViewModel
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        viewModel = MainViewModel(fakeRelay)
    }

    @Test
    fun initialState_isNotRunning() {
        assertFalse(viewModel.isRunning.value)
    }

    @Test
    fun startRelay_setsIsRunningTrue() {
        viewModel.startRelay(context)
        assertTrue(viewModel.isRunning.value)
    }

    @Test
    fun stopRelay_afterStart_setsIsRunningFalse() {
        viewModel.startRelay(context)
        viewModel.stopRelay(context)
        assertFalse(viewModel.isRunning.value)
    }

    @Test
    fun multipleStarts_stateRemainsTrue() {
        viewModel.startRelay(context)
        viewModel.startRelay(context)
        assertTrue(viewModel.isRunning.value)
    }

    @Test
    fun stopWithoutStart_stateRemainsFalse() {
        viewModel.stopRelay(context)
        assertFalse(viewModel.isRunning.value)
    }
}
