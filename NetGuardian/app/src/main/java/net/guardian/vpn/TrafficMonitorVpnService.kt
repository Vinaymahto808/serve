package net.guardian.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import net.guardian.MainActivity
import net.guardian.R
import net.guardian.data.*
import net.guardian.threat.ThreatDetector
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel

class TrafficMonitorVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var database: TrafficDatabase
    private lateinit var threatDetector: ThreatDetector
    private var vpnInterface: ParcelFileDescriptor? = null
    private var running = false

    private val trafficBuffer = mutableListOf<TrafficEntity>()
    private val alertBuffer = mutableListOf<AlertEntity>()
    private var lastFlushTime = 0L
    private var flushJob: Job? = null

    companion object {
        const val ACTION_START = "net.guardian.START_VPN"
        const val ACTION_STOP = "net.guardian.STOP_VPN"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "netguardian_monitor"
    }

    override fun onCreate() {
        super.onCreate()
        database = TrafficDatabase.getDatabase(this)
        threatDetector = ThreatDetector(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.vpn_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.vpn_channel_desc) }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun startVpn() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NetGuardian Active")
            .setContentText("Monitoring network traffic")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        val builder = Builder()
            .setSession("NetGuardian Monitor")
            .setBlocking(true)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")

        try {
            vpnInterface = builder.establish()
            running = true
            serviceScope.launch { vpnLoop() }
            flushJob = serviceScope.launch {
                while (isActive) {
                    delay(2000)
                    flushBuffers()
                }
            }
        } catch (e: Exception) {
            Log.e("NetGuardian", "VPN establish failed", e)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopVpn() {
        running = false
        flushJob?.cancel()
        serviceScope.launch { flushBuffers() }
        vpnInterface?.close()
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun vpnLoop() {
        val vpnInput = vpnInterface?.let { FileInputStream(it.fileDescriptor) } ?: return
        val vpnOutput = vpnInterface?.let { FileOutputStream(it.fileDescriptor) } ?: return
        val buffer = ByteBuffer.allocate(32767)

        while (running) {
            try {
                buffer.clear()
                val length = vpnInput.channel.read(buffer)
                if (length <= 0) continue
                buffer.flip()

                val packet = ByteArray(length)
                buffer.get(packet)
                processPacket(packet)

                // Forward to real network
                vpnOutput.write(packet)
                vpnOutput.flush()
            } catch (e: Exception) {
                if (running) Log.w("NetGuardian", "VPN read error", e)
            }
        }
    }

    private suspend fun processPacket(packet: ByteArray) {
        if (packet.size < 20) return
        val version = (packet[0].toInt() shr 4) and 0x0F

        when (version) {
            4 -> parseIpv4(packet)
            6 -> parseIpv6(packet)
        }
    }

    private suspend fun parseIpv4(packet: ByteArray) {
        val protocol = packet[9].toInt() and 0xFF
        val srcIp = "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}." +
                "${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
        val dstIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}." +
                "${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"

        val localIp = getLocalIp()
        if (!srcIp.startsWith("10.0.0.")) return

        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val payloadLen = totalLength - ((packet[0].toInt() and 0x0F) * 4)

        when (protocol) {
            6 -> parseTcp(packet, dstIp, totalLength, payloadLen)
            17 -> parseUdp(packet, dstIp, totalLength, payloadLen)
        }
    }

    private suspend fun parseIpv6(packet: ByteArray) { }

    private suspend fun parseTcp(packet: ByteArray, dstIp: String, totalLen: Int, payloadLen: Int) {
        val ipHdrLen = totalLen - payloadLen
        val tcpHdrLen = ((packet[ipHdrLen + 12].toInt() shr 4) and 0x0F) * 4
        val srcPort = ((packet[ipHdrLen].toInt() and 0xFF) shl 8) or (packet[ipHdrLen + 1].toInt() and 0xFF)
        val dstPort = ((packet[ipHdrLen + 2].toInt() and 0xFF) shl 8) or (packet[ipHdrLen + 3].toInt() and 0xFF)

        if (dstPort == 53) {
            parseDns(packet, dstIp, ipHdrLen + tcpHdrLen, payloadLen - tcpHdrLen)
            return
        }

        val payloadBytes = payloadLen - tcpHdrLen
        val tcpPayload = if (payloadBytes > 0)
            packet.copyOfRange(ipHdrLen + tcpHdrLen, ipHdrLen + tcpHdrLen + minOf(payloadBytes, 64))
        else ByteArray(0)

        logConnection(dstIp, dstPort, "TCP", payloadBytes, "", srcPort, tcpPayload)
    }

    private suspend fun parseUdp(packet: ByteArray, dstIp: String, totalLen: Int, payloadLen: Int) {
        val ipHdrLen = totalLen - payloadLen
        val srcPort = ((packet[ipHdrLen].toInt() and 0xFF) shl 8) or (packet[ipHdrLen + 1].toInt() and 0xFF)
        val dstPort = ((packet[ipHdrLen + 2].toInt() and 0xFF) shl 8) or (packet[ipHdrLen + 3].toInt() and 0xFF)

        if (dstPort == 53) {
            parseDns(packet, dstIp, ipHdrLen + 8, payloadLen - 8)
            return
        }

        val payloadBytes = payloadLen - 8
        val udpPayload = if (payloadBytes > 0)
            packet.copyOfRange(ipHdrLen + 8, ipHdrLen + 8 + minOf(payloadBytes, 64))
        else ByteArray(0)

        logConnection(dstIp, dstPort, "UDP", payloadBytes, "", srcPort, udpPayload)
    }

    private suspend fun parseDns(packet: ByteArray, dnsServer: String, offset: Int, length: Int) {
        if (length < 12) return
        try {
            var pos = offset + 12
            val questions = ((packet[offset + 4].toInt() and 0xFF) shl 8) or
                    (packet[offset + 5].toInt() and 0xFF)
            repeat(questions) {
                val hostname = parseDnsName(packet, pos)
                pos = skipDnsName(packet, pos)
                pos += 4
                if (hostname.isNotEmpty()) {
                    lookupAppForHost(hostname)
                }
            }
        } catch (_: Exception) { }
    }

    private fun parseDnsName(packet: ByteArray, start: Int): String {
        val parts = mutableListOf<String>()
        var pos = start
        while (pos < packet.size) {
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 == 0xC0) { pos += 2; break }
            pos++
            if (pos + len > packet.size) break
            parts.add(String(packet, pos, len))
            pos += len
        }
        return parts.joinToString(".")
    }

    private fun skipDnsName(packet: ByteArray, start: Int): Int {
        var pos = start
        while (pos < packet.size) {
            val len = packet[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if (len and 0xC0 == 0xC0) return pos + 2
            pos += 1 + len
        }
        return pos
    }

    private val dnsCache = mutableMapOf<String, String>()

    private fun lookupAppForHost(hostname: String) { }

    private suspend fun logConnection(
        destIp: String, destPort: Int, protocol: String,
        bytes: Int, hostname: String = "", srcPort: Int = 0,
        payload: ByteArray = ByteArray(0)
    ) {
        if (bytes == 0) return

        val resolvedHost = if (hostname.isEmpty()) {
            val cached = dnsCache.entries.find { it.value == "${destIp}:$destPort" }
            cached?.key ?: destIp
        } else hostname

        val pm = packageManager
        val uid = getActiveUid(srcPort)
        val (appName, pkgName) = if (uid != null)
            resolveAppName(uid) else Pair("System", "android")

        val matched = threatDetector.analyze(destIp, destPort, resolvedHost, payload, pkgName)
        val conn = TrafficEntity(
            appName = appName, packageName = pkgName,
            destIp = resolvedHost, destPort = destPort, protocol = protocol,
            bytesSent = bytes.toLong(), bytesReceived = 0,
            timestamp = System.currentTimeMillis(),
            isSuspicious = matched != null,
            threatType = matched?.threatType ?: ""
        )

        synchronized(trafficBuffer) { trafficBuffer.add(conn) }

        if (matched != null) {
            val alert = AlertEntity(
                appName = appName, packageName = pkgName,
                destIp = resolvedHost, destPort = destPort,
                threatType = matched.threatType,
                description = matched.description,
                timestamp = System.currentTimeMillis()
            )
            synchronized(alertBuffer) { alertBuffer.add(alert) }
        }

        val now = System.currentTimeMillis()
        if (trafficBuffer.size >= 50 || now - lastFlushTime > 1000) {
            flushBuffers()
            lastFlushTime = now
        }
    }

    private fun flushBuffers() {
        val traffic: List<TrafficEntity>
        val alerts: List<AlertEntity>
        synchronized(trafficBuffer) { traffic = trafficBuffer.toList(); trafficBuffer.clear() }
        synchronized(alertBuffer) { alerts = alertBuffer.toList(); alertBuffer.clear() }
        if (traffic.isEmpty() && alerts.isEmpty()) return
        serviceScope.launch {
            if (traffic.isNotEmpty()) database.trafficDao().insertAllConnections(traffic)
            if (alerts.isNotEmpty()) database.trafficDao().insertAllAlerts(alerts)
        }
    }

    private fun getActiveUid(srcPort: Int): Int? {
        return try {
            val procFile = java.io.BufferedReader(
                java.io.InputStreamReader(
                    java.io.FileInputStream("/proc/net/tcp")
                )
            ).use { it.readText() }
            val lines = procFile.lines().drop(1)
            for (line in lines) {
                if (line.isBlank()) continue
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 8) {
                    val localPart = parts[1].split(":")
                    val portHex = localPart.getOrNull(1) ?: continue
                    if (portHex.toIntOrNull(16) == srcPort) {
                        return parts[7].toIntOrNull(16)
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun resolveAppName(uid: Int): Pair<String, String> {
        val pm = packageManager
        val packages = pm.getPackagesForUid(uid) ?: return Pair("Unknown", "unknown")
        for (pkg in packages) {
            try {
                val ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
                return Pair(pm.getApplicationLabel(ai).toString(), pkg)
            } catch (_: Exception) { }
        }
        return Pair(packages.firstOrNull() ?: "Unknown", packages.firstOrNull() ?: "unknown")
    }

    private fun getLocalIp(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address) return addr.hostAddress ?: ""
                }
            }
        } catch (_: Exception) { }
        return ""
    }
}
