# Changelog

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

