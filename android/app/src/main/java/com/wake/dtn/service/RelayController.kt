package com.wake.dtn.service

import android.content.Context

interface RelayController {
    fun start(context: Context)
    fun stop(context: Context)
}

/** Production implementation — delegates straight to [WakeService]. */
class WakeServiceController : RelayController {
    override fun start(context: Context) = WakeService.start(context)
    override fun stop(context: Context) = WakeService.stop(context)
}
