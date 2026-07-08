package net.guardian.ui.dashboard

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import net.guardian.data.TrafficDatabase
import net.guardian.vpn.TrafficMonitorVpnService

class DashboardViewModel(
    private val database: TrafficDatabase
) : ViewModel() {

    val isRunning = MutableStateFlow(false)
    val totalBytes = database.trafficDao().getTotalBytes().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0L
    )
    val suspiciousCount = database.trafficDao().getSuspiciousCount().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), 0
    )
    val recentConnections = database.trafficDao().getAllConnections().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
    val appTraffic = database.trafficDao().getAppTraffic().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    fun toggleMonitoring(activity: Activity) {
        if (isRunning.value) {
            stopMonitoring(activity)
        } else {
            startMonitoring(activity)
        }
    }

    private fun startMonitoring(activity: Activity) {
        val intent = Intent(activity, TrafficMonitorVpnService::class.java).apply {
            action = TrafficMonitorVpnService.ACTION_START
        }
        activity.startForegroundService(intent)
        isRunning.value = true
    }

    private fun stopMonitoring(activity: Activity) {
        val intent = Intent(activity, TrafficMonitorVpnService::class.java).apply {
            action = TrafficMonitorVpnService.ACTION_STOP
        }
        activity.startService(intent)
        isRunning.value = false
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
            else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
        }
    }
}
