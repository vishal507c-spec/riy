# riy

Minimal Android app (Kotlin) — a **porn blocker** with an automatic
**2-hour device lock** and a **daily check-in tracker**. Nothing else.

## What it does

1. **Blocks porn system-wide.** A DNS-filtering VPN service answers lookups for
   adult domains with `0.0.0.0` before any connection is made. It works for
   HTTPS too (the domain never resolves, so no TLS connection can start), and
   enforces Google/Bing/DuckDuckGo/Yandex SafeSearch + YouTube Restricted Mode
   at DNS level.
2. **Locks the device for exactly 2 hours** when an adult-content request is
   detected. The lock is a wall-clock deadline persisted on the device, so it
   survives app restarts, force-stops and reboots. Only the deadline clears it.
3. **Tracks daily check-ins** with a private on-device streak calendar and a
   single morning reminder notification.

## WhatsApp Status blocking — removed in v2.9.3

The WhatsApp Status blocker and its Accessibility Service were fully removed
in v2.9.3. RIY no longer declares, requests or enables any Accessibility
Service. Chats, calls and every other app behave normally; adult DNS filtering
and the 2-hour lock are unchanged.

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
| `ui/ProtectionScreen.kt` | The one screen: protection status, Enable Protection, tracker entry |
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

## Adult-site blocking: what is and is not covered

RIY is a **DNS-level** blocker. It can block a domain only when it can read the
QNAME, i.e. on a plain (or system-resolver) DNS query. Everything below follows
from that one fact, and the app reports it rather than hiding it.

**Covered**

- Adult domains from a maintained blocklist (StevenBlack hosts, "porn-only",
  ~77k domains) plus code-level adult-brand/TLD heuristics, matched on the host
  and every subdomain. Refresh with
  `powershell -File tools/blocklist/refresh-blocklist.ps1`.
- Normal Chrome and Incognito **equally** — there is no Incognito-specific code
  path. Both resolve through the same resolver, so both are filtered or neither
  is. Chrome's own DNS cache being separate does not change the outcome,
  because every query is answered by the filter rather than being cached around.
- Encrypted-DNS endpoints are sinkholed by hostname, and traffic to ~50 known
  public/DoH/DoT resolver IPs is captured into the tun and dropped, so apps using
  their own resolver fall back to the filtered system DNS.
- On a Device Owner device, the `no_config_private_dns` restriction removes the
  system "Private DNS" (DoT) toggle, which is the only supported Android control
  for that route.

**Not covered — by design, and shown in the UI**

- **A browser's own "Secure DNS" (DoH).** The QNAME is inside TLS. Blocking it
  would require TLS interception: a private CA installed on the device and
  decrypting browsing traffic. RIY deliberately does not do this — it would
  mean collecting browsing content and would break certificate pinning.
  No Android API exposes another app's DoH setting, so this cannot be detected,
  blocked or measured from inside the app. The dashboard therefore shows
  *"Running — Not Verified"* with an explicit note whenever Device Owner does
  not close that route.
- **Search queries.** Google/Bing/DDG results are filtered *server-side* via
  SafeSearch frontend pinning. The query text itself is HTTPS content and is
  never read. What DNS filtering does do is block the adult **destination**.
- **IPv6 resolvers not on the capture list.** Only the listed resolver IPs are
  routed into the tun; there is no `::/0` default route, because the filter has
  no TCP/IP stack and would blackhole all IPv6 traffic.

Because of the first point, the app never displays "fully protected" purely
because the VPN service started. On start it runs an **end-to-end self-test**
through the real tun: it resolves a canary adult-TLD name (which the matcher
answers locally, so no real adult site is ever contacted) and expects it to be
sinkholed, and resolves `example.com` and expects it to still work. Only a
passing self-test upgrades the dashboard to *"Adult Sites Blocked"*.
- The lock screen cannot be dismissed, backed out of, or cleared by restarting
  the app or rebooting the device. It is **an in-app lock**: while the lock is
  live the blocking stays ON and riy shows the countdown. Android does not let
  a normal SDK app forcibly lock *other* apps' windows (that needs Device Admin
  / lock-task provisioning, which is a deliberately separate mechanism).
## Local development

```bash
./gradlew :app:testDebugUnitTest           # JVM unit tests
./gradlew :app:connectedDebugAndroidTest   # instrumented tests (needs a device/emulator)
./gradlew :app:lintDebug                    # static analysis
./gradlew :app:assembleRelease "-PversionTag=v1.0.1"
```

Requires JDK 17+ and an Android SDK (`ANDROID_HOME`).

## Manual test checklist

- Turn protection on; confirm adult domains are blocked and normal sites load.
- Trigger a detection; confirm the 2-hour countdown survives force-stop/reboot.
- Open the daily tracker and record a day; confirm the morning reminder appears once.

There is no Accessibility Service in v2.9.3 and later.
