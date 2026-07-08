package net.guardian.ui.alerts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.guardian.data.AlertEntity

@Composable
fun AlertsScreen(alerts: List<AlertEntity>) {
    if (alerts.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No alerts yet", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(alerts) { alert ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(alert.threatType, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        Text(
                            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                .format(java.util.Date(alert.timestamp)),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(alert.description, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("${alert.appName} \u2022 ${alert.destIp}:${alert.destPort}",
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }
    }
}
