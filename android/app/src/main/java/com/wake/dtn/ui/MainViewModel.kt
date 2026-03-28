package com.wake.dtn.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.wake.dtn.service.RelayController
import com.wake.dtn.service.WakeServiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(
    private val relay: RelayController = WakeServiceController(),
) : ViewModel() {

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun startRelay(context: Context) {
        relay.start(context)
        _isRunning.value = true
    }

    fun stopRelay(context: Context) {
        relay.stop(context)
        _isRunning.value = false
    }
}
