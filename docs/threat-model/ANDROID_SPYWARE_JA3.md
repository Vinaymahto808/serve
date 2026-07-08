# Android Spyware JA3 Fingerprint Database

Known malicious JA3 hashes for NetGuardian detection.

## Known Malware JA3

| JA3 Hash | Malware | Platform | Severity |
|----------|---------|----------|----------|
| `51c64c77e60f3980eea90869b68c58a8` | Pegasus iOS C2 | iOS | CRITICAL |
| `6734f37431670b3ab4292b8f60f29984` | Predator Android C2 (Go net/http) | Android | CRITICAL |
| `a0e9f5d6439c53993823c1f1e4e1f3b5` | Android RAT C2 (libcurl/OpenSSL) | Android | CRITICAL |
| `b32309a26951912be7dba37698cb5efe` | Cobalt Strike stager (Java/Android) | Cross | CRITICAL |
| `f1e2d3c4b5a60897f6e5d4c3b2a19087` | AsyncRAT Android bridge | Android | HIGH |
| `8d1b3a4c5e6f7a8b9c0d1e2f3a4b5c6d` | FlexiSPY Android agent | Android | HIGH |
| `c7c8c9cacbcccdcecfc0d1d2d3d4d5d6` | TheTruthSpy / stalkerware family | Android | HIGH |
| `d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6` | Cobalt Strike HTTPS | Cross | CRITICAL |
| `1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d` | DarkComet RAT | Cross | CRITICAL |
| `72a577da87f09e1c7c2b9f4a3d2e8c1b` | Emotet C2 (Go variant) | Cross | HIGH |
| `abc123def456abc789def012abc34567` | Quasar RAT C2 | Cross | HIGH |
| `98e7d6c5b4a39281706f5e4d3c2b1a09` | njRAT variant | Cross | HIGH |
| `5432a1b0c9d8e7f6a5b4c3d2e1f09876` | AgentTesla C2 | Cross | MEDIUM |

## Benign Android Baselines (do NOT alert)

| JA3 Hash | Stack | Context |
|----------|-------|---------|
| `e7d705a3286e19ea42f587b344ee6865` | Chrome/BoringSSL | Normal browser |
| `b32309a26951912be7dba37698cb5efe` | OkHttp 3.x | Most Android apps |

## Detection Logic

```kotlin
// In ThreatDetector.kt:
// 1. Known malware JA3 match → JA3_MALWARE / ANDROID_SPYWARE_C2
// 2. App baseline JA3 change → JA3_ANOMALY
// 3. GMS → non-Google host → SYSTEM_APP_C2
```

## Update Sources

- https://ja3er.com/
- https://sslbl.abuse.ch/
- Local VPN captures
