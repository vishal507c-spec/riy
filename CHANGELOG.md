# Changelog

## v2.6.1 - 2026-10-02
- Fix release build: correct `Stroke` import for the lock-screen countdown ring (`androidx.compose.ui.graphics.drawscope`)

## v2.6.0 - 2026-10-02
- Advanced shield console UI: breathing status orb (pulses while the filter runs), six tappable guard layers with live Active/Ready/Unavailable statuses plus second-depth detail lines, four-step How-It-Works pipeline, countdown progress ring on the lock screen (same single deadline, no second timer)
- Zero-confusion contract intact: plain human language only, no package names/domains/logs/counts, no Disable/Pause control, lock screen still non-dismissible

## v2.5.0 - 2026-10-02
- Telegram stays usable during protection: new COMMUNICATION allowlist category (verified installed + enabled + launcher-backed Telegram identities, never denylisted, message/media content never inspected)
- TeraBox / file-sharing bypass closed via layered Device Owner enforcement: maintainable blocked-app policy (package + label metadata, not one hardcoded name), hide + suspend of bypass packages, suspension of unauthorized sideloads, unknown-source / Private-DNS / VPN-config user restrictions, install-time + boot + foreground reconciliation through one entry point, bypass-transport DNS sinkholing (TeraBox CDN + encrypted-DNS endpoints, no porn-lock arming)
- RIY self-update flow always exempt (same-package updates verified, never blocked); existing tests untouched, 15 new JVM unit tests (policy, enforcement, DNS filter)

## v2.4.2 - 2026-10-01
- Diagnose Drive login failures instead of swallowing them: sign-in cancel vs failure now logged distinctly; network observer gets the missing ACCESS_NETWORK_STATE permission (fixes register SecurityException on strict OEMs)

## v2.4.1 - 2026-10-01
- Fix Drive login persistence: silent-first session restore (stored + platform + token validation), platform silent sign-in adoption, single-retry 401 token refresh, explicit sign-out — account picker now appears only when re-authentication is genuinely required

## v2.4.0 - 2026-10-01
- Sci-fi cockpit UI: always-dark deep-space theme, neon-cyan holograms, plasma-magenta lockdown, targeting-corner brackets, radar scan-sweep animation, glowing status orb, terminal-monospace countdown
- Synthesized soundboard (offline, bundled WAVs): UI blip, shield engage sweep, lockdown klaxon, release chime — all fire-and-forget, never break protection logic
- Protection strings, icons, state mapping and lock behavior unchanged (zero-confusion contract intact)

## v2.3.1 - 2026-10-01
- Fix scheduler wiring compile error in MainActivity (backup lambda passed as named `backupFn`)

## v2.3.0 - 2026-10-01
- Google Drive backup for protection state (cloned from the reference app's data platform): all six SharedPreferences stores (lock deadline, session, events, escalation, observations, VPN intent) snapshot into one canonical SHA-256-bound JSON VAULT in a dedicated `RiyBackup` Drive folder, plus `_latest_verified.json` pointer
- Secretless OAuth (Google Sign-In + `drive.file` scope, no hardcoded secrets); search-before-create folder resolver per account; candidate → verify → promote → confirm publication with anti-rollback and same-generation conflict preservation
- Debounced single-flight auto-backup after every store write; startup auto-restore only on fresh installs (valid local data never overwritten); persistent pending-sync queue replayed on foreground and connectivity return; offline-safe throughout
- 27 new JVM unit tests (snapshot codec, coordinator, folder resolver, scheduler) alongside the existing suite

## v2.2.1 - 2026-10-01
- Restored the GitHub auto-update system removed in v2.0.0 (exact v1.2.1 implementation): startup update check against `GET /repos/{owner}/{repo}/releases/latest`, numeric version comparison, draft/prerelease rejection, pinned-repo APK asset validation, authenticated private-repo download with trusted-CDN redirect allowlist, APK verification (package name + no downgrade) and handoff to the system installer via FileProvider
- Throttled to one lightweight check per interval; every failure mode no-ops gracefully and never breaks the app
- 20 update unit tests (release parsing, version math, download helpers) restored alongside the existing protection test suite

## v2.2.0 - 2026-09-22
- Phase 10 — Multi-signal protection intelligence with evidence correlation and a zero-confusion UI, feeding the existing Phase 1–9 pipeline (no duplicate authority anywhere)
- New `protection/intelligence` layer: `BlocklistMatch` (DEFINITIVE/SUSPECT), `SignalObservation` + `ObservationStore` (max 24 observations, persistence holds only `domain | matchClass | timestamp | policyVersion` — no URLs, queries, messages or search text), `ProtectionCorrelationWindow` (5-min window, 60-s repeat dedup mirroring `LockEngine.DEDUP_WINDOW_MS`), `EvidenceCorrelator`, `ProtectionIntelligence`
- Classification rule: DEFINITIVE (explicit blocklist rule / adult TLD / brand substring) arms immediately; SUSPECT (whole-label token) needs independent corroboration. Ordinary and medical domains (`sussex.ac.uk`, `adultswim.com`, `xxxlutz.com`, `mayoclinic.org`) never match
- Correlation: backward-looking only; corroboration only among SUSPECTs (distinct domain within 5 min, or same domain ≥60 s apart); definitive observations never corroborate suspects; duplicates inside the dedup window produce no new event
- New evidence type `ProtectionEvidenceType.CORROBORATED_ADULT_CONTENT`; `DefaultRiskEngine` gained an exhaustive CONFIRMED arm; `ProtectionEscalationEngine` now qualifies on `isAdultDomainEvidence && isActionable`
- Self-healing `ProtectionConsistency`: DETECT → RECONCILE → RESTORE → READ BACK → VERIFY; a divergent session mirror is repaired from the LockEngine deadline and verified on a second read; an unverifiable repair is reported as an ERROR, never as success; expired session with a leftover deadline is not alarmed; LockEngine is never written
- `DefaultIntegrityEngine` now returns real `policyConsistent`/`sessionConsistent`/`lockTaskConsistent` verdicts (previously hardcoded `true` — genuine defect fixed)
- Zero-confusion UI: titles "You're Protected" / "Restricted Mode" / "Extra Protection" / "Restoring Protection" / "Protection Needs Attention"; precedence mismatch → recovering → HARDENED → restricted → protected; shared `ProtectionDetailsCard`; friendly app labels (never package names); one countdown from `LockEngine.formatRemainingBrief()`; no pause/bypass/disable control anywhere; no developer state name reaches a title
- 315 unit tests + 3 instrumented tests passing; debug (19 MB) and release (13 MB) APKs built locally
- Separate finding: no update checker exists in the tracked tree (deleted in v2.0.0); not resurrected

## v2.1.0 - 2026-09-21
- Phase 7 — Protection UI integration. The Compose UI now observes the REAL backend protection state through a one-way data flow: `authoritative backend → ProtectionStateBridge → ProtectionUiState → Compose UI`. The UI is rendering-only; it can never become a security authority
- New `DefaultProtectionStateBridge`: the single read-only translator combining the protection state store, the LockEngine/LockStore deadline (sole authority), the live enforcement read-back, the integrity engine, the app-policy resolver and the event store. It writes nothing, computes no deadline and exposes no disable/pause/bypass
- `ProtectionUiState` minimally extended with `enforcementStatus` plus derived `isRecovering` / `enforcementVerified` / `enforcementMismatch`. The existing `ProtectionState` enum is reused — no second state machine
- The lock screen now distinguishes Restricted Mode vs Hardened Mode, shows the escalation message and the honest enforcement status. HARDENED reuses the SAME deadline as RESTRICTED — there is deliberately no second timer
- A recovery/reconciling backend shows a non-bypassable "Restoring Protection…" screen; an enforcement mismatch shows "Protection Warning" and is never labelled as protected
- The countdown is derived from the authoritative LockEngine deadline (`LockEngine.remainingMillis`), never recomputed; the display ticker only re-reads it
- Process death / reboot: the UI reconstructs itself from the persisted backend state — a fresh bridge reading the same stores reproduces the screen
- Removed the superseded `LockViewModel` so there is exactly one countdown owner
- 262 unit tests + 3 instrumented tests passing; debug and release builds verified; release APK confirmed to contain no debug test seam

## v2.0.0 - 2026-09-16
- Riy is now exactly one thing: a **porn blocker + automatic 2-hour device lock**. Every unrelated feature is gone — no in-app browser, no address bar/search UI, no image-search tier, no on-device NSFW classifier (model + TFLite dependency removed), no GitHub auto-update, no awareness pause/trigger/metrics flow, no maintenance window, no extra dashboards or settings
- Blocking is unchanged and proven: DNS-filtering VPN answers adult-domain lookups with 0.0.0.0 before any connection (works for HTTPS too, no TLS interception), plus enforced Google/Bing/DuckDuckGo/Yandex SafeSearch and YouTube Restricted Mode at DNS level
- New lock: an adult-domain detection arms a lock of EXACTLY 2 hours from the trigger moment. The deadline is a persisted wall-clock value, so it survives app restart, force-stop and device reboot; only the deadline clears it
- Non-dismissible lock screen (🛡️ Protection Active · HH:MM:SS countdown · "Porn search detected." · "Use this time to step away from the urge."): no close button, no back-button bypass, no notification bypass
- Honest detection point (unchanged, now the only trigger): a keyword typed in external Chrome is inside encrypted HTTPS and invisible to any non-MITM app, so no fake keyword detection exists. What IS detected is the adult-domain DNS lookup that must precede every porn-site visit
- Minimal single-screen UI: protection status (real service state) + Enable Protection; the lock screen replaces it while locked
- 67 unit tests + 3 instrumented tests passing; release build verified


## v1.4.0 - 2026-09-01
- Adult search-keyword policy layer: search-engine URLs (Google, Bing, DuckDuckGo, Yandex, Yahoo, Ecosia, Startpage, Brave, Qwant, Mojeek) are inspected in the app's protected browsing and adult queries are BLOCKED before the results page is ever fetched — a real request-level block with an "Adult Search Blocked" page
- 45+ explicit adult keywords as whole-word tokens (porn, xxx, sex, nude, naked, hentai, rule34, nsfw, erotic, ... ) — lookalikes (Essex, sexton, dickens, analysis) never match
- Context-aware combo rules: hot/sexy/adult + girl/photo/pics/images/woman/women/content block together ("hot photo" blocked; "hot weather", "hot coffee", "adult education", "girl education", "fashion photo" allowed)
- Bypass hardening: URL-encoding (%20, double-encoded %2520), '+' separators, any letter case, extra separators (hot...girl) all normalized and caught
- Unit tests for every requirement example; on-device verified: Google 'hot photo' and Bing 'sexy photo' show "Adult Search Blocked", 'adult education' loads normally
- Honest limitation unchanged: queries typed in external browsers (Chrome) are inside TLS and invisible to any non-MITM app — those searches rely on enforced SafeSearch + DNS layers only


## v1.3.1 - 2026-09-01
- Strict NSFW tier on Google Images result pages: the "highly suggestive but non-explicit" band (0.35-0.60 classifier score) that Google SafeSearch leaves visible ("hot photo" / "sexy photo" results) is now blocked inside the app's protected browsing
- Normal threshold (0.60) everywhere else — fashion/celebrity/beach photography keeps working without false positives
- Verified on-device: hot photo (2), sexy photo (1), hot girl (4), sexy girl (2), nude (2) images blocked live; fashion dress (0) — no false positives; Chrome limitation unchanged (external browsers rely on SafeSearch + DNS layers only)


## v1.3.0 - 2026-09-01
- On-device NSFW image classifier (Yahoo OpenNSFW TFLite, bundled) — images rendered inside the app's WebView are classified at pixel level; explicit imagery is blocked even when it comes from non-adult hosts (e.g. SafeSearch-filtered "hot girl" Google Images results)
- Threshold tuned high (0.60) so swimwear/celebrity/fashion photography stays allowed
- playboy.com added to DNS blocklist
- New test entries: Google Images "hot girl" classifier check, suggestive-photos classifier check, Wikimedia explicit check
- 74 unit tests passing; end-to-end verified on emulator (4 suggestive images blocked live on a Google Images page)


## v1.2.1 - 2026-09-01
- Stricter filtering: suggestive/softcore adult categories added (79 -> 116 blocklist rules)
- Adult brand substring heuristics (xhamster, redtube, chaturbate, onlyfans, rule34, ...) catch mirror TLDs automatically
- New whole-label tokens (nsfw, sexy, boobs, erotic, escort, horny) with false-positive safety (nsfwjs.com, escortedtours.com, sexyhair.com stay allowed)
- In-app WebView URL-level content filter: adult pages and explicit adult assets (images) blocked without any TLS interception
- Google Images explicit searches verified returning SafeSearch-filtered results in Chrome



## v1.2.0 - 2026-09-01
- Google SafeSearch enforced at DNS level (forcesafesearch.google.com VIP pinning) — cannot be disabled from browser/Google settings
- Bing Strict, DuckDuckGo Safe, Yandex Family and YouTube Restricted mode enforced the same way
- Fixed tun idle-EOF bug that could stop DNS filtering on some devices
- Fixed SafeSearch VIP cache to handle CNAME chains and HTTPS-RR hint bypass
- Boot restore hardened: establish retries with backoff (12 x 10s window)
- Bypass hardening: more public resolver IPs routed (Level3, DNS.WATCH, Comodo, Dyn)
- 63 unit tests passing; end-to-end verified on emulator (search, incognito, direct URLs, subdomains, reboot)



## v1.1.0 - 2026-09-01
- Adult content blocker: DNS-filtering VPN service (no HTTPS interception)
- Jetpack Compose UI with real-time Protection ON/OFF status
- In-app "Check / Test Protection" with Content Blocked screen
- Protection persists after app close and is restored after reboot
- Boot-receiver with establish retry; self-heal on app open
- Built-in adult domain blocklist (subdomain + TLD/keyword heuristics)


## v1.0.1 - 2026-08-31
- Verified auto-update check


## v1.0.2 - 2026-08-31
- Auto-update patch

