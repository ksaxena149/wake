package com.wake.dtn

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.wake.dtn.service.WakeService
import com.wake.dtn.ui.MainViewModel
import com.wake.dtn.ui.SearchScreen
import com.wake.dtn.ui.StatusScreen
import com.wake.dtn.ui.WakeScreen
import com.wake.dtn.ui.theme.WakeTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as WakeService.LocalBinder).getService()
            viewModel.setNodeId(service.syncManager.nodeId)
            lifecycleScope.launch {
                service.latestBundle.filterNotNull().collect { viewModel.onBundleArrived(it) }
            }
            lifecycleScope.launch {
                service.lastSyncTimeMs.filterNotNull().collect { viewModel.onSyncTimeUpdated(it) }
            }
            lifecycleScope.launch {
                service.totalStorageBytesFlow.collect { viewModel.onStorageUpdated(it) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            viewModel.setNodeId(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WakeTheme {
                val currentScreen by viewModel.currentScreen.collectAsState()
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = currentScreen == WakeScreen.SEARCH,
                                onClick = { viewModel.navigateTo(WakeScreen.SEARCH) },
                                icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                                label = { Text("Search") },
                            )
                            NavigationBarItem(
                                selected = currentScreen == WakeScreen.STATUS,
                                onClick = { viewModel.navigateTo(WakeScreen.STATUS) },
                                icon = { Icon(Icons.Default.Info, contentDescription = "Status") },
                                label = { Text("Status") },
                            )
                        }
                    },
                ) { innerPadding ->
                    when (currentScreen) {
                        WakeScreen.SEARCH -> SearchScreen(viewModel, Modifier.padding(innerPadding))
                        WakeScreen.STATUS -> StatusScreen(viewModel, Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, WakeService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        super.onStop()
        unbindService(serviceConnection)
        viewModel.setNodeId(null)
    }
}
