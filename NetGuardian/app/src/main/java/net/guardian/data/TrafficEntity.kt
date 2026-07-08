package net.guardian.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connections")
data class TrafficEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appName: String,
    val packageName: String,
    val destIp: String,
    val destPort: Int,
    val protocol: String,
    val bytesSent: Long,
    val bytesReceived: Long,
    val timestamp: Long,
    val isSuspicious: Boolean,
    val threatType: String = "",
    val country: String = ""
)

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appName: String,
    val packageName: String,
    val destIp: String,
    val destPort: Int,
    val threatType: String,
    val description: String,
    val timestamp: Long,
    val isRead: Boolean = false
)
