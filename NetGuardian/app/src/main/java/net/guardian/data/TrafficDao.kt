package net.guardian.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrafficDao {
    @Insert
    suspend fun insertConnection(traffic: TrafficEntity)

    @Insert
    suspend fun insertAllConnections(traffic: List<TrafficEntity>)

    @Insert
    suspend fun insertAlert(alert: AlertEntity)

    @Insert
    suspend fun insertAllAlerts(alerts: List<AlertEntity>)

    @Query("SELECT * FROM connections ORDER BY timestamp DESC LIMIT 500")
    fun getAllConnections(): Flow<List<TrafficEntity>>

    @Query("SELECT * FROM alerts ORDER BY timestamp DESC LIMIT 200")
    fun getAllAlerts(): Flow<List<AlertEntity>>

    @Query("SELECT SUM(bytesSent + bytesReceived) FROM connections")
    fun getTotalBytes(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM connections WHERE isSuspicious = 1")
    fun getSuspiciousCount(): Flow<Int>

    @Query("SELECT DISTINCT appName, packageName, SUM(bytesSent + bytesReceived) as totalBytes FROM connections GROUP BY packageName ORDER BY totalBytes DESC")
    fun getAppTraffic(): Flow<List<AppTrafficSummary>>

    @Query("UPDATE alerts SET isRead = 1 WHERE id = :id")
    suspend fun markAlertRead(id: Long)

    @Query("DELETE FROM connections WHERE timestamp < :before")
    suspend fun purgeOldConnections(before: Long)

    @Query("DELETE FROM alerts")
    suspend fun clearAlerts()
}

data class AppTrafficSummary(
    val appName: String,
    val packageName: String,
    val totalBytes: Long
)
