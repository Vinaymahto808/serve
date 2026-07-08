package net.guardian.ui.dashboard

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.guardian.data.TrafficEntity

@Composable
fun DashboardScreen(viewModel: DashboardViewModel) {
    val context = LocalContext.current
    val activity = context as Activity
    val totalBytes by viewModel.totalBytes.collectAsState()
    val suspiciousCount by viewModel.suspiciousCount.collectAsState()
    val connections by viewModel.recentConnections.collectAsState()
    val appTraffic by viewModel.appTraffic.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { StatusCard(isRunning, totalBytes ?: 0L, suspiciousCount, viewModel, activity) }
            item { SectionHeader("Top Apps") }
            items(appTraffic.take(5)) { app ->
                AppTrafficRow(app.appName, app.totalBytes, viewModel.formatBytes(app.totalBytes))
            }
            item { SectionHeader("Recent Connections") }
            items(connections.take(20)) { conn ->
                ConnectionRow(conn, viewModel.formatBytes(conn.bytesSent))
            }
        }
    }
}

@Composable
private fun StatusCard(
    isRunning: Boolean, totalBytes: Long, suspiciousCount: Int,
    viewModel: DashboardViewModel, activity: Activity
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("NetGuardian", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatItem("Traffic", viewModel.formatBytes(totalBytes), MaterialTheme.colorScheme.primary)
                StatItem("Alerts", suspiciousCount.toString(), MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.toggleMonitoring(activity) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isRunning) "Stop Monitoring" else "Start Monitoring")
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color,
            fontFamily = FontFamily.Monospace)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground)
}

@Composable
private fun AppTrafficRow(appName: String, bytes: Long, formatted: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) { Text(appName.take(1), fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Text(appName, fontWeight = FontWeight.Medium)
            }
            Text(formatted, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ConnectionRow(conn: TrafficEntity, formatted: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (conn.isSuspicious)
                MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
            else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (conn.isSuspicious) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(conn.destIp, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("${conn.appName} \u2022 ${conn.protocol}:${conn.destPort}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            Text(formatted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
