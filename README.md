# riy

Minimal Android app (Kotlin) — a **porn blocker** with an automatic
**2-hour device lock**. Nothing else.

## What it does

1. **Blocks porn system-wide.** A DNS-filtering VPN service answers lookups for
   adult domains with `0.0.0.0` before any connection is made. It works for
   HTTPS too (the domain never resolves, so no TLS connection can start), and
   enforces Google/Bing/DuckDuckGo/Yandex SafeSearch + YouTube Restricted Mode
   at DNS level.
2. **Locks the device for exactly 2 hours** when an adult-content request is
   detected. The lock is a wall-clock deadline persisted on the device, so it
   survives app restarts, force-stops and reboots. Only the deadline clears it.

## How each core piece works

| Component | Purpose |
|---|---|
| `blocker/BlockerVpnService.kt` | The filtering VPN: parses DNS on the tun, blocks adult queries, records detections |
| `blocker/Blocklist.kt` | Adult-domain matching (blocklist + zero-false-positive heuristics) |
| `blocker/SafeSearch.kt` | DNS-pins search engines to their safe frontends (server-side filtering) |
| `blocker/DnsProtocol.kt`, `IpPacket.kt` | DNS / IP-UDP parse & reply building |
| `blocker/BootReceiver.kt` | Restores protection after a reboot |
| `lock/LockEngine.kt` | The 2-hour lock: deadline math, dedup, expiry — pure JVM |
| `lock/LockStore.kt` | Persists the lock deadline (SharedPreferences) |
| `lock/LockViewModel.kt` | Live countdown for the UI |
| `ui/ProtectionScreen.kt` | The one screen: status + Enable Protection |
| `ui/LockScreen.kt` | Non-dismissible lock screen with countdown |
| `MainActivity.kt` | Entry point + protection self-heal |

## The detection point (honest note about Android limits)

A keyword typed in the user's **own browser** (Chrome, etc.) travels inside
encrypted HTTPS. No non-MITM Android app can read it — riy does **not** pretend
to. What riy *can* reliably see, because it runs the filtering VPN, is the
**DNS lookup that must happen before any porn site is reached**. That lookup is
matched against the adult blocklist; the match simultaneously blocks the
connection (for HTTP **and** HTTPS) and arms the 2-hour lock. This is the only
technically valid control point available to a normal SDK-constrained app.

## Android limitations (no fake 100% claims)

- A determined user can always revoke the VPN consent or uninstall the app —
  Android gives no app an unkillable protection guarantee.
- SafeSearch enforcement is DNS-based; it cannot control a browser using its
  own encrypted DNS (e.g. Chrome with "secure DNS" pointed elsewhere), although
  such lookups are routed into the filter where possible.
- The lock screen cannot be dismissed, backed out of, or cleared by restarting
  the app or rebooting the device. It is **an in-app lock**: while the lock is
  live the blocking stays ON and riy shows the countdown. Android does not let
  a normal SDK app forcibly lock *other* apps' windows (that needs Device Admin
  / lock-task provisioning, which is a deliberately separate mechanism).

## Local development

```bash
./gradlew :app:testDebugUnitTest           # JVM unit tests
./gradlew :app:connectedDebugAndroidTest   # instrumented tests (needs a device/emulator)
./gradlew :app:assembleRelease "-PversionTag=v1.0.1"
```

Requires JDK 17+ and an Android SDK (`ANDROID_HOME`).
