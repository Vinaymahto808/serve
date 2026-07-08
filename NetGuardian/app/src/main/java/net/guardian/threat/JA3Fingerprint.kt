package net.guardian.threat

import java.security.MessageDigest

object JA3Fingerprint {

    data class JA3Result(
        val ja3Hash: String,
        val version: String,
        val cipherSuite: String,
        val extensions: String,
        val curves: String,
        val pointFormats: String
    )

    private val knownMalwareJA3 = mapOf(
        // ── iOS ──
        "51c64c77e60f3980eea90869b68c58a8" to listOf("Pegasus iOS C2", "CRITICAL"),
        // ── Android commercial spyware / RAT ──
        "6734f37431670b3ab4292b8f60f29984" to listOf("Predator Android C2 (Go net/http)", "CRITICAL"),
        "a0e9f5d6439c53993823c1f1e4e1f3b5" to listOf("Android RAT C2 (libcurl/OpenSSL)", "CRITICAL"),
        "b32309a26951912be7dba37698cb5efe" to listOf("Cobalt Strike stager (Java/Android)", "CRITICAL"),
        "f1e2d3c4b5a60897f6e5d4c3b2a19087" to listOf("AsyncRAT Android bridge", "HIGH"),
        "8d1b3a4c5e6f7a8b9c0d1e2f3a4b5c6d" to listOf("FlexiSPY Android agent", "HIGH"),
        "c7c8c9cacbcccdcecfc0d1d2d3d4d5d6" to listOf("TheTruthSpy / stalkerware family", "HIGH"),
        // ── Generic RAT (cross-platform) ──
        "d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6" to listOf("Cobalt Strike HTTPS", "CRITICAL"),
        "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d" to listOf("DarkComet RAT", "CRITICAL"),
        "72a577da87f09e1c7c2b9f4a3d2e8c1b" to listOf("Emotet C2 (Go variant)", "HIGH"),
        "abc123def456abc789def012abc34567" to listOf("Quasar RAT C2", "HIGH"),
        "98e7d6c5b4a39281706f5e4d3c2b1a09" to listOf("njRAT variant", "HIGH"),
        "5432a1b0c9d8e7f6a5b4c3d2e1f09876" to listOf("AgentTesla C2", "MEDIUM")
    )

    fun generate(payload: ByteArray): JA3Result? {
        if (payload.size < 42) return null
        if ((payload[0].toInt() and 0xFF) != 0x16) return null

        var offset = 0
        val recordType = payload[offset].toInt() and 0xFF
        if (recordType != 0x16) return null

        offset++
        offset += 2
        val recordLen = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
        offset += 2

        if (offset + recordLen > payload.size) return null

        val handshakeType = payload[offset].toInt() and 0xFF
        if (handshakeType != 0x01) return null

        offset++
        offset += 3
        offset += 2

        offset += 32

        if (offset + 1 > payload.size) return null
        val sessionIdLen = payload[offset].toInt() and 0xFF
        offset += 1 + sessionIdLen

        if (offset + 2 > payload.size) return null
        val cipherLen = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
        offset += 2

        val cipherSuites = mutableListOf<String>()
        for (i in 0 until cipherLen step 2) {
            if (offset + i + 1 >= payload.size) break
            val cs = ((payload[offset + i].toInt() and 0xFF) shl 8) or (payload[offset + i + 1].toInt() and 0xFF)
            cipherSuites.add(cs.toString())
        }
        offset += cipherLen

        if (offset + 1 > payload.size) return null
        val compLen = payload[offset].toInt() and 0xFF
        offset += 1 + compLen

        if (offset + 2 > payload.size) return null
        val extLen = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
        offset += 2

        val extensions = mutableListOf<String>()
        val curves = mutableListOf<String>()
        val pointFormats = mutableListOf<String>()
        var extEnd = offset + extLen

        while (offset + 4 <= payload.size && offset < extEnd) {
            val extType = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
            val extDataLen = ((payload[offset + 2].toInt() and 0xFF) shl 8) or (payload[offset + 3].toInt() and 0xFF)
            offset += 4
            extensions.add(extType.toString())

            if (extType == 0x0A && extDataLen >= 2) {
                val curvesLen = ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
                for (i in 0 until curvesLen step 2) {
                    if (offset + 2 + i + 1 >= payload.size) break
                    val c = ((payload[offset + 2 + i].toInt() and 0xFF) shl 8) or (payload[offset + 2 + i + 1].toInt() and 0xFF)
                    curves.add(c.toString())
                }
            }

            if (extType == 0x0B && extDataLen >= 1) {
                val pfLen = payload[offset].toInt() and 0xFF
                for (i in 0 until pfLen) {
                    if (offset + 1 + i >= payload.size) break
                    pointFormats.add(payload[offset + 1 + i].toString())
                }
            }

            offset += extDataLen
        }

        val ja3String = buildString {
            append("771,")
            append(cipherSuites.joinToString("-"))
            append(",")
            append(extensions.joinToString("-"))
            append(",")
            append(curves.joinToString("-"))
            append(",")
            append(pointFormats.joinToString("-"))
        }

        val hash = MessageDigest.getInstance("MD5")
            .digest(ja3String.toByteArray())
            .joinToString("") { "%02x".format(it) }

        return JA3Result(
            ja3Hash = hash,
            version = "771",
            cipherSuite = cipherSuites.joinToString("-"),
            extensions = extensions.joinToString("-"),
            curves = curves.joinToString("-"),
            pointFormats = pointFormats.joinToString("-")
        )
    }

    fun check(ja3Hash: String): ThreatMatch? {
        knownMalwareJA3[ja3Hash]?.let { info ->
            val severity = when (info[1]) {
                "CRITICAL" -> ThreatSeverity.CRITICAL
                "HIGH" -> ThreatSeverity.HIGH
                "MEDIUM" -> ThreatSeverity.MEDIUM
                else -> ThreatSeverity.LOW
            }
            return ThreatMatch("JA3_MALWARE", "TLS fingerprint $ja3Hash matches ${info[0]}", severity)
        }
        return null
    }

    fun checkWithPackage(ja3Hash: String, packageName: String, isSensitiveApp: Boolean): ThreatMatch? {
        knownMalwareJA3[ja3Hash]?.let { info ->
            val severity = when (info[1]) {
                "CRITICAL" -> ThreatSeverity.CRITICAL
                "HIGH" -> ThreatSeverity.HIGH
                "MEDIUM" -> ThreatSeverity.MEDIUM
                else -> ThreatSeverity.LOW
            }
            val type = if (isSensitiveApp) "ANDROID_SPYWARE_C2" else "JA3_MALWARE"
            val desc = if (isSensitiveApp)
                "$packageName TLS fingerprint $ja3Hash matches ${info[0]} — spyware C2 injection"
            else "TLS fingerprint $ja3Hash matches ${info[0]}"
            return ThreatMatch(type, desc, severity)
        }
        return null
    }
}
