package net.guardian.ui.apps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background

data class AppTrafficItem(
    val appName: String,
    val packageName: String,
    val totalBytes: Long,
    val threatCount: Int
)

@Composable
fun AppsScreen(apps: List<AppTrafficItem>) {
    if (apps.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No app traffic data yet",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(apps.sortedByDescending { it.totalBytes }) { app ->
            Card(modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (app.threatCount > 0) MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            ),
                        contentAlignment = Alignment.Center
                    ) { Text(app.appName.take(2), fontWeight = FontWeight.Bold, fontSize = 14.sp) }

                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.appName, fontWeight = FontWeight.Medium)
                        Text(app.packageName, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatBytes(app.totalBytes),
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        if (app.threatCount > 0) {
                            Text("${app.threatCount} alerts",
                                color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
}
