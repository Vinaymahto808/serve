package net.guardian.threat

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

data class ThreatMatch(
    val threatType: String,
    val description: String,
    val severity: ThreatSeverity
)

enum class ThreatSeverity { LOW, MEDIUM, HIGH, CRITICAL }

class ThreatDetector(private val context: Context) {

    private val appJA3Whitelist = ConcurrentHashMap<String, MutableSet<String>>()
    private val ja3Alerted = ConcurrentHashMap<String, Boolean>()
    private val suspiciousAppConnections = ConcurrentHashMap<String, Boolean>()

    private val criticalApps = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.plus" to "Telegram Plus",
        "com.snapchat.android" to "Snapchat",
        "com.facebook.orca" to "Facebook Messenger",
        "com.instagram.android" to "Instagram",
        "com.google.android.apps.messaging" to "Google Messages",
        "com.skype.raider" to "Skype",
        "com.viber.voip" to "Viber",
        "com.signal" to "Signal",
        "com.discord" to "Discord"
    )

    private val knownAppServers = mapOf(
        "com.whatsapp" to listOf("whatsapp.net", "fbcdn.net", "facebook.com", "whatsapp.com"),
        "org.telegram.messenger" to listOf("telegram.org", "t.me", "cdn-telegram.org"),
        "com.snapchat.android" to listOf("snapchat.com", "sc-cdn.net"),
        "com.facebook.orca" to listOf("facebook.com", "fbcdn.net", "messenger.com"),
        "com.instagram.android" to listOf("instagram.com", "fbcdn.net", "cdninstagram.com"),
        "com.signal" to listOf("signal.org", "whispersystems.org"),
        "com.discord" to listOf("discord.com", "discordapp.net", "discord.gg")
    )

    private val systemCriticalApps = mapOf(
        "com.google.android.gms" to listOf(
            "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com",
            "gvt1.com", "gvt2.com", "firebaseio.com", "crashlytics.com"
        ),
        "com.android.vending" to listOf("google.com", "googleapis.com", "play-fe.googleapis.com")
    )

    private val allSensitiveApps by lazy {
        criticalApps.keys + systemCriticalApps.keys
    }

    private val knownMaliciousIps = setOf(
        "185.234.72.0/24", "103.235.46.0/24",
        "45.227.252.0/24", "91.121.87.0/24"
    )

    private val knownDataExfilPatterns = listOf(
        "pastebin.com", "transfer.sh", "file.io",
        "api.telegram.org", "discord.com/api"
    )

    private val suspiciousPorts = mapOf(
        21 to "FTP - Unencrypted file transfer",
        23 to "Telnet - Unencrypted remote access",
        25 to "SMTP - Potential email exfiltration",
        // 53 to "DNS - Tunneling possible",
        445 to "SMB - Potential lateral movement",
        1080 to "SOCKS proxy - Possible C2 tunnel",
        1433 to "MSSQL - Database exfiltration risk",
        3306 to "MySQL - Database exfiltration risk",
        3389 to "RDP - Remote desktop (abuse risk)",
        4444 to "Metasploit default - Possible C2",
        5555 to "Android debug - Unusual outbound",
        8080 to "HTTP proxy - Possible C2 tunnel",
        8443 to "HTTPS alt - Possible C2 traffic"
    )

    private val knownTrackerDomains = listOf(
        "google-analytics.com", "doubleclick.net",
        "facebook.net", "app-measurement.com",
        "adjust.com", "appsflyer.com"
    )

    private val remoteAccessPorts = mapOf(
        22 to "SSH - Remote shell",
        23 to "Telnet - Unencrypted remote shell",
        3389 to "RDP - Windows Remote Desktop",
        5900 to "VNC - Remote desktop (port 5900)",
        5901 to "VNC - Remote desktop (port 5901)",
        5938 to "TeamViewer - Remote desktop",
        7070 to "AnyDesk - Remote desktop"
    )

    private val binaryMagicBytes = mapOf(
        "7F454C46" to listOf("ELF binary (Android native/C++ .so file)", "HIGH"),
        "FEEDFACE" to listOf("Mach-O 32-bit binary (iOS)", "HIGH"),
        "FEEDFACF" to listOf("Mach-O 64-bit binary (iOS)", "HIGH"),
        "0F0D0D0A" to listOf("Python bytecode (.pyc) transfer", "MEDIUM"),
        "4D5A" to listOf("PE executable (.exe) download", "HIGH"),
        "504B0304" to listOf("ZIP archive - possible APK payload", "MEDIUM"),
        "1F8B08" to listOf("GZip compressed payload", "LOW"),
        "2F2F0A" to listOf("JavaScript bundle (// comment)", "LOW"),
        "3C21444F" to listOf("HTML doctype (possible phishing page)", "LOW")
    )

    private val maliciousUserAgents = mapOf(
        "python-requests" to "Python HTTP library - Possible Python-based C2",
        "go-http-client" to "Go HTTP client - Possible Go-based RAT",
        "node-fetch" to "Node.js fetch - Possible Node.js backend C2",
        "okhttp/3" to "OkHttp (Android) - Check for malicious app",
        "libcurl" to "libcurl - Common in C++ malware C2",
        "net/http" to "Go net/http - Possible Go-based C2",
        "nginx/" to "Nginx - Possible reverse proxy C2",
        "Microsoft-CryptoAPI" to "Windows CryptoAPI - Suspicious TLS pattern"
    )

    private val payloadSignatures = listOf(
        Pair("RFB 003", "VNC handshake"),
        Pair("RFB 004", "VNC handshake"),
        Pair("RFB 005", "VNC handshake"),
        Pair("SSH-2.0", "SSH handshake"),
        Pair("SSH-1.99", "SSH handshake"),
        Pair(byteArrayOf(0x03, 0x00, 0x00, 0x2E, 0x0E, 0x00), "RDP handshake"),
        Pair("TV", "TeamViewer handshake"),
        Pair("AnyDesk", "AnyDesk handshake")
    )

    fun analyze(destIp: String, destPort: Int, hostname: String, payload: ByteArray = ByteArray(0), packageName: String = ""): ThreatMatch? {
        val isPrivate = try {
            val addr = InetAddress.getByName(destIp)
            addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
        } catch (_: Exception) { false }

        if (isPrivate) return null

        val hostLower = hostname.lowercase()

        if (destPort == 443 && payload.size > 42 && (payload[0].toInt() and 0xFF) == 0x16) {
            val ja3 = JA3Fingerprint.generate(payload)
            ja3?.let { result ->
                val isSensitive = packageName in allSensitiveApps
                JA3Fingerprint.checkWithPackage(result.ja3Hash, packageName, isSensitive)?.let { match ->
                    return match
                }
                if (packageName.isNotEmpty()) {
                    val known = appJA3Whitelist.getOrPut(packageName) { mutableSetOf() }
                    if (known.isNotEmpty() && !known.contains(result.ja3Hash) && ja3Alerted.putIfAbsent("${packageName}:${result.ja3Hash}", true) == null) {
                        return ThreatMatch("JA3_ANOMALY",
                            "$packageName using unusual TLS fingerprint (${result.ja3Hash.take(16)}...) - possible C2 injection",
                            if (isSensitive) ThreatSeverity.CRITICAL else ThreatSeverity.HIGH)
                    }
                    known.add(result.ja3Hash)
                }
            }
        }

        if (packageName in criticalApps && destPort == 443) {
            val appName = criticalApps[packageName] ?: packageName
            if (hostLower.isNotEmpty()) {
                val knownServers = knownAppServers[packageName] ?: emptyList()
                val isKnownServer = knownServers.any { hostLower.contains(it) }
                if (!isKnownServer && suspiciousAppConnections.putIfAbsent("$packageName:$hostLower", true) == null) {
                    return ThreatMatch("APP_C2_CONNECTION",
                        "$appName connecting to non-standard server: $hostLower:$destPort — possible C2 injection",
                        ThreatSeverity.CRITICAL)
                }
            }
        }

        if (packageName in systemCriticalApps && destPort == 443 && hostLower.isNotEmpty()) {
            val known = systemCriticalApps[packageName] ?: emptyList()
            if (!known.any { hostLower.contains(it) }
                && suspiciousAppConnections.putIfAbsent("$packageName:$hostLower", true) == null) {
                val appShort = packageName.removePrefix("com.google.android.")
                return ThreatMatch("SYSTEM_APP_C2",
                    "$packageName → non-Google host $hostLower:$destPort — possible spyware posing as $appShort",
                    ThreatSeverity.CRITICAL)
            }
        }

        if (destPort != 443 && payload.isNotEmpty()) {
            val hex = payload.take(minOf(8, payload.size))
                .joinToString("") { "%02X".format(it) }
            for ((sig, info) in binaryMagicBytes) {
                if (hex.startsWith(sig)) {
                    return ThreatMatch("BINARY_DOWNLOAD", "${info[0]} to $hostname:$destPort",
                        ThreatSeverity.valueOf(info[1]))
                }
            }
            val body = try { payload.toString(Charsets.ISO_8859_1) } catch (_: Exception) { "" }
            for ((ua, desc) in maliciousUserAgents) {
                if (body.contains(ua, ignoreCase = true)) {
                    val severity = if (ua == "python-requests" || ua == "go-http-client") 
                        ThreatSeverity.MEDIUM else ThreatSeverity.LOW
                    return ThreatMatch("C2_USER_AGENT", "$desc detected: $hostname:$destPort", severity)
                }
            }
        }

        remoteAccessPorts[destPort]?.let { desc ->
            return ThreatMatch(
                "REMOTE_ACCESS_PORT", "Remote access on port $destPort: $desc to $hostname",
                ThreatSeverity.HIGH
            )
        }

        if (payload.isNotEmpty()) {
            for ((sig, desc) in payloadSignatures) {
                val match = when (sig) {
                    is String -> {
                        val prefix = payload.take(sig.length).map { it.toInt().toChar() }.joinToString("")
                        prefix == sig
                    }
                    is ByteArray -> {
                        if (payload.size < sig.size) false
                        else sig.indices.all { payload[it] == sig[it] }
                    }
                    else -> false
                }
                if (match) {
                    return ThreatMatch(
                        "REMOTE_ACCESS_PAYLOAD", "Detected $desc in connection to $hostname:$destPort",
                        ThreatSeverity.HIGH
                    )
                }
            }
        }

        val knownMalware = knownMaliciousIps.any { prefix ->
            destIp.startsWith(prefix.substringBefore("."))
        }
        if (knownMalware) {
            return ThreatMatch(
                "KNOWN_MALICIOUS_IP", "Connection to known malicious IP: $destIp",
                ThreatSeverity.CRITICAL
            )
        }

        suspiciousPorts[destPort]?.let { desc ->
            return ThreatMatch(
                "SUSPICIOUS_PORT", "$desc (port $destPort) to $hostname",
                if (destPort == 25) ThreatSeverity.HIGH else ThreatSeverity.MEDIUM
            )
        }

        knownDataExfilPatterns.forEach { pattern ->
            if (hostLower.contains(pattern)) {
                return ThreatMatch(
                    "DATA_EXFILTRATION", "Possible data exfiltration to $hostname",
                    ThreatSeverity.HIGH
                )
            }
        }

        knownTrackerDomains.forEach { tracker ->
            if (hostLower.contains(tracker)) {
                return ThreatMatch(
                    "TRACKER", "Connection to known tracker: $hostname",
                    ThreatSeverity.LOW
                )
            }
        }

        return null
    }
}
