# riy

Minimal Android app (Kotlin) — a **porn blocker** with an automatic
**2-hour device lock**, plus a **WhatsApp Status blocker**. Nothing else.

## What it does

1. **Blocks porn system-wide.** A DNS-filtering VPN service answers lookups for
   adult domains with `0.0.0.0` before any connection is made. It works for
   HTTPS too (the domain never resolves, so no TLS connection can start), and
   enforces Google/Bing/DuckDuckGo/Yandex SafeSearch + YouTube Restricted Mode
   at DNS level.
2. **Locks the device for exactly 2 hours** when an adult-content request is
   detected. The lock is a wall-clock deadline persisted on the device, so it
   survives app restarts, force-stops and reboots. Only the deadline clears it.
3. **Blocks WhatsApp Status / Updates** while leaving WhatsApp itself entirely
   normal — see below.

## WhatsApp Status blocking

**What is stopped:** the Status/Updates tab, opening a Status, playback, and
swiping to the next item.

**What is untouched:** chats, individual messages, groups, calls,
notifications, photos and videos inside chats, search, settings.

### How it is done (and what it deliberately is not)

Official WhatsApp is never modified, patched, decompiled or re-signed; no root
is used; nothing is written to WhatsApp's files or database; its traffic is not
intercepted; and WhatsApp is never blocked as a whole or force-closed.

Instead, a scoped `AccessibilityService` watches **only** `com.whatsapp` and
stops the Status destination the moment it is reached. A confirmed Status screen
is exited with a single back-out, which leaves the user on the previous safe
WhatsApp screen — so the experience is "you tapped Status, it did not open".

### The blocking system is generic

`UiGuardRule` + `UiGuardEngine` are the **single** blocking engine. WhatsApp
Status is one rule (`WhatsAppStatusRule`) registered as one target, so adding
another blocked surface is "another rule", never a second blocker.

| Component | Purpose |
|---|---|
| `guard/UiGuardEngine.kt` | Generic engine: picks the strongest rule verdict, then the safest action |
| `guard/rules/WhatsAppStatusRule.kt` | ALL WhatsApp knowledge: labels, id tokens, weights |
| `guard/StatusBlockerService.kt` | The accessibility entry point — capture, delegate, act |
| `guard/UiGuardCapture.kt` | Reduces a live window to the few properties rules may read |
| `guard/UiGuardStore.kt` | The user's per-target on/off choice |
| `ui/WhatsAppStatusCard.kt` | The dashboard card + accessibility onboarding |

### Detection: weighted indicators, never a coordinate

No screen position such as `x=500, y=100` is used anywhere — `NodeEvidence` has
no coordinates at all. Instead, independent *stable* indicator families are
weighted, and only a total of at least **50** confirms a block:

| Indicator | Weight | Why it survives updates |
|---|---|---|
| status activity component (`com.whatsapp.status.*`) | 70 | a code-level package boundary |
| the Status/Updates nav tab is the **selected** one | 55 | structural selection state |
| resource ids from the status component's own R class | 55 | code-level, survives translations |
| status-tab content labels ("My status", "Add status", …) | 25 each, max 2 | user-visible |
| entry label exactly "Status" / "Updates" / "Statuses" | 30 | user-visible |
| that entry's own id chain contains "status" | 20 | code-level |
| that entry sits in a real bottom-navigation bar | 30 | structural |
| viewer's reply bar with **no** message composer | 20 | structural |

The first three need no translated string, so detection keeps working on builds
that render Status inside the main activity with the main resource package.

### Safety: a false positive is worse than a missed block

- a single weak indicator can never reach the threshold;
- anything found inside a conversation / message container is vetoed outright, so
  chat text, chat rows, chat media and scrolling can never trigger a block;
- a low-confidence "candidate" is logged and then **nothing happens**;
- a tapped entry only *arms* the guard — no blind back-out on a normal screen;
- back-outs are capped per attempt, so WhatsApp can never be walked out of;
- an unrecognised WhatsApp screen does nothing at all.

### Privacy

The service is configured for `com.whatsapp` only and for window-state,
window-content and view-clicked events only. Inspection is bounded to 80 nodes
and 14 levels. Nothing is recorded, screenshotted, typed, persisted or sent
anywhere, and no message text ever reaches the log — the debug lines are
compiled out of release builds entirely (see `app/src/{debug,release}`).

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
| `ui/ProtectionScreen.kt` | The one screen: status + Enable Protection + the Status blocker card |
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
- **WhatsApp Status blocking depends on accessibility access**, which the user
  can revoke at any time in Android Settings, and on WhatsApp's UI keeping a
  recognisable shape. The guard is written to fail *open*: if a WhatsApp version
  is unrecognised, everything is allowed rather than risking a wrong block. A
  determined user can also disable the accessibility service, and Android gives
  no app a guarantee that survives that.
- The guard exits the Status screen with a back-out, so on a very fast device a
  status item can be on screen for a single frame before it is dismissed. It is
  stopped from being played, swiped or read, but this is not a cryptographic
  guarantee — anyone who can revoke accessibility access can defeat any
  accessibility-based blocker.

## Local development

```bash
./gradlew :app:testDebugUnitTest           # JVM unit tests
./gradlew :app:connectedDebugAndroidTest   # instrumented tests (needs a device/emulator)
./gradlew :app:lintDebug                    # static analysis
./gradlew :app:assembleRelease "-PversionTag=v1.0.1"
```

Requires JDK 17+ and an Android SDK (`ANDROID_HOME`).

Debug-only guard logging (`adb logcat -s RiyUiGuard`) is available in debug
builds; see `app/src/debug/java/com/vishal/riy/guard/UiGuardLog.kt`.

## Manual test checklist (WhatsApp)

Normal WhatsApp must be unaffected: open WhatsApp, open a chat, send and receive
a message, open a group, scroll a chat, open a photo and a video from a chat,
place a call, open Settings.

Status must be blocked: tap Status/Updates, try to open a Status, try to swipe
to the next one, reopen it, and repeat several times.
