# Changelog

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

