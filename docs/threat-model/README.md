# NetGuardian Threat Model

## Architecture

```
Attacker ──malicious media──► WhatsApp/Telegram
                               │
                               ▼ (zero-click exploit in memory)
                            C2 Payload Download
                               │
                               ▼
                         ┌─────────────────────┐
                         │  NetGuardian VPN    │
                         │  (packet capture)   │
                         └─────┬───────────────┘
                               │
                    ┌──────────┼──────────┐
                    ▼          ▼          ▼
              JA3 Match    App Allowlist  Magic Bytes
              (database)   (anomaly)      (ELF/PE)
                    │          │            │
                    └──────────┼────────────┘
                               ▼
                        CRITICAL ALERT
                        (dashboard + Room DB)
```

## Detection Layers

| Layer | Method | Example |
|-------|--------|---------|
| 1 | Malware JA3 hash database | `51c64c77...` → Pegasus |
| 2 | Per-app JA3 baseline anomaly | WhatsApp uses new fingerprint |
| 3 | Per-app server allowlist | WhatsApp → non-whatsapp.net |
| 4 | System app domain allowlist | GMS → non-google.com |
| 5 | Binary magic bytes in HTTP | `7F454C46` → ELF download |
| 6 | HTTP User-Agent analysis | `python-requests` in Android app |
| 7 | Remote access port/payload | VNC/SSH/RDP handshake |

## Files

- `ANDROID_SPYWARE_JA3.md` — JA3 hash database
- `PEGASUS_CHAIN.md` — iOS zero-click chain (TODO)
- `ANDROID_CHAIN.md` — Android zero-click chain (TODO)

## Source

- `app/.../threat/JA3Fingerprint.kt` — JA3 generation + database
- `app/.../threat/ThreatDetector.kt` — all detection rules
- `app/.../vpn/TrafficMonitorVpnService.kt` — packet capture
