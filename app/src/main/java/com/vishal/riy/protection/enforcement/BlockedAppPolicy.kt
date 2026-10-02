package com.vishal.riy.protection.enforcement

/**
 * Maintainable blocked-app / category policy for protected mode.
 *
 * LAYERED DESIGN (no single-signal dependence):
 *  - Telegram is on an explicit ALLOW list (verified installed + enabled +
 *    launcher-backed). It is NEVER on any denylist.
 *  - TeraBox / file-sharing bypass apps are on a BLOCKED policy resolved by
 *    MULTIPLE metadata signals: known package identities AND package/label
 *    substring heuristics AND launcher presence. A single hardcoded name is
 *    never the only signal.
 *  - Everything else unknown falls back to the existing default-deny
 *    lock-task allowlist (see [AppPolicyResolver]): absence from the allowlist
 *    means BLOCKED during a restriction. This policy only ADDS explicit
 *    allow/block verdicts on top; it never widens the allowlist by itself.
 *
 * Pure Kotlin, no Android imports, fully unit-testable. Adding a new
 * blocked package or category is a data-only change below.
 *
 * What this policy deliberately does NOT do:
 *  - it never inspects message/file CONTENT (no Telegram text, image or video
 *    classification — HTTPS content cannot be classified by package or DNS);
 *  - it never blocks every cloud-storage app globally (Google Drive backup
 *    used by RIY itself stays untouched; only the bypass-category packages
 *    below are blocked, and only while protection is active);
 *  - it never touches DevicePolicyManager itself (enforcement stays in
 *    [AndroidEnforcementEngine] behind the platform boundary).
 */
object BlockedAppPolicy {

    /**
     * Why a package is blocked. Extensible without code-shape changes: add a
     * value plus its package/heuristics entries.
     */
    enum class BlockedCategory {
        /** TeraBox / Dubox cloud-storage apps abused as an adult-content CDN bypass. */
        TERABOX_CLOUD_BYPASS,

        /** Adult-content / file-sharing apps whose primary bypass purpose is known. */
        BYPASS_FILE_SHARING,

        /** Tools whose primary purpose is bypassing restriction (VPN/proxy spoofers). */
        BYPASS_TOOL,
    }

    // ------------------------------------------------------------ TELEGRAM
    // Explicit ALLOW. Verified at resolve time (installed + enabled +
    // launcher activity). Telegram itself is never added to any denylist.

    /**
     * Known Telegram package identities (official + widely used forks).
     * Membership here is necessary but NOT sufficient: the resolver additionally
     * verifies installed + enabled + launcher-backed before allowing.
     */
    val TELEGRAM_PACKAGES: Set<String> = setOf(
        "org.telegram.messenger",
        "org.telegram.plus",
        "org.thunderdog.challegram",
        "org.telegram.messenger.web",
        "nekox.messenger",
    )

    /** True when [packageName] is a known Telegram identity (allow-candidate). */
    fun isTelegramPackage(packageName: String): Boolean =
        TELEGRAM_PACKAGES.contains(packageName.trim())

    // ------------------------------------------------------------ TERABOX
    // BLOCKED during protection. Resolved by metadata, not by one name alone.

    /**
     * Known TeraBox / Dubox package identities. This list is a SEED, not the
     * whole detector: [isTeraBoxPackage] additionally matches substring and
     * label heuristics so repackaged/cloned variants are still caught.
     */
    val TERABOX_PACKAGES: Set<String> = setOf(
        "com.flextech.client.terabox",
        "com.dubox.drive",
        "com.terabox.app",
        "jp.co.flextech.terabox",
    )

    /** Package-name fragments identifying TeraBox-family apps (case-insensitive). */
    private val TERABOX_PACKAGE_HINTS = listOf("terabox", "dubox", "teraboxapp")

    /** Display-label fragments identifying TeraBox-family apps (case-insensitive). */
    private val TERABOX_LABEL_HINTS = listOf("terabox", "dubox")

    /**
     * True when ([packageName], [label]) identifies a TeraBox-family app by
     * ANY metadata signal: exact known identity, package-substring, or
     * label-substring. All comparisons are case-insensitive.
     */
    fun isTeraBoxPackage(packageName: String, label: String? = null): Boolean {
        val pkg = packageName.trim().lowercase()
        if (pkg.isEmpty()) return false
        if (TERABOX_PACKAGES.contains(packageName.trim())) return true
        if (TERABOX_PACKAGE_HINTS.any { pkg.contains(it) }) return true
        val lab = label?.trim()?.lowercase().orEmpty()
        if (lab.isNotEmpty() && TERABOX_LABEL_HINTS.any { lab.contains(it) }) return true
        return false
    }

    // --------------------------------------- OTHER BLOCKED / BYPASS ENTRIES
    // Minimal, auditable seed. Unknown APK-installed apps are ALREADY blocked
    // by default-deny (absent from the allowlist); these entries exist so the
    // policy names the bypass category explicitly and stays extendable.

    /**
     * Explicitly blocked packages beyond the TeraBox family, mapped to their
     * category. Kept deliberately small: default-deny already blocks every
     * unknown app during a restriction, so only packages worth NAMING (audit
     * trail, diagnostics, hide/suspend priority) belong here.
     */
    val KNOWN_BLOCKED_PACKAGES: Map<String, BlockedCategory> = mapOf(
        // TeraBox family (also caught by heuristics above; listed for audit).
        "com.flextech.client.terabox" to BlockedCategory.TERABOX_CLOUD_BYPASS,
        "com.dubox.drive" to BlockedCategory.TERABOX_CLOUD_BYPASS,
        "com.terabox.app" to BlockedCategory.TERABOX_CLOUD_BYPASS,
        "jp.co.flextech.terabox" to BlockedCategory.TERABOX_CLOUD_BYPASS,
    )

    /**
     * Category for ([packageName], [label]), or null when this policy names no
     * explicit verdict. Null does NOT mean allowed — the allowlist still
     * decides; it only means "no explicit block entry".
     */
    fun blockedCategoryFor(packageName: String, label: String? = null): BlockedCategory? {
        KNOWN_BLOCKED_PACKAGES[packageName.trim()]?.let { return it }
        if (isTeraBoxPackage(packageName, label)) return BlockedCategory.TERABOX_CLOUD_BYPASS
        return null
    }

    /** True when ([packageName], [label]) is explicitly blocked by this policy. */
    fun isBlockedPackage(packageName: String, label: String? = null): Boolean =
        blockedCategoryFor(packageName, label) != null

    // ------------------------------------------------------- UPDATE SAFETY
    // RIY's own legitimate update flow must never be treated as an
    // "unauthorized APK": same-package updates (PACKAGE_REPLACED for RIY's own
    // package) are always exempt from block/hide/suspend verdicts.

    /** True when [packageName] is RIY itself (always exempt from blocking). */
    fun isSelfPackage(packageName: String, riyPackageName: String): Boolean =
        packageName.trim() == riyPackageName.trim()

    // ------------------------------------------------------- NETWORK BYPASS
    // DNS-level secondary protection. These hostnames are blocked by the VPN
    // filter so alternate-DNS / DoH routes fall back to the filtered resolver.
    // This never inspects content — it only removes the bypass transport.
    // Telegram hostnames are deliberately ABSENT (Telegram stays reachable).

    /**
     * File-sharing / bypass CDN base domains neutralized at DNS while
     * protection is active. Subdomains match automatically (blocklist rule
     * semantics). Kept separate from the adult [com.vishal.riy.blocker.Blocklist]
     * so diagnostics can distinguish "bypass transport blocked" from
     * "adult domain blocked".
     */
    val BYPASS_CDN_BASE_DOMAINS: Set<String> = setOf(
        "terabox.com",
        "teraboxapp.com",
        "dubox.com",
        "duboxcloud.com",
    )

    /**
     * Well-known encrypted-DNS endpoints whose hostnames are sinkholed so apps
     * using their own DoH/DoT fall back to the filtered system DNS. Numeric-IP
     * DoH can still bypass a split-tunnel VPN (documented limitation); the
     * Device Owner user restrictions (DISALLOW_CONFIG_PRIVATE_DNS /
     * DISALLOW_CONFIG_VPN) are the primary control for that path.
     */
    val ENCRYPTED_DNS_BYPASS_HOSTS: Set<String> = setOf(
        "dns.google",
        "dns.google.com",
        "cloudflare-dns.com",
        "one.one.one.one",
        "dns.adguard.com",
        "doh.opendns.com",
        "dns.quad9.net",
    )

    /** True when [domain] is a bypass-transport hostname (CDN or encrypted DNS). */
    fun isBypassDomain(domain: String?): Boolean {
        if (domain.isNullOrBlank()) return false
        val host = domain.trim().lowercase()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").removeSuffix(".").substringBefore('/')
        if (!host.contains('.')) return false
        val all = BYPASS_CDN_BASE_DOMAINS + ENCRYPTED_DNS_BYPASS_HOSTS
        if (host in all) return true
        var current = host
        while (true) {
            val dot = current.indexOf('.')
            if (dot <= 0 || dot == current.length - 1) break
            current = current.substring(dot + 1)
            if (current in all) return true
        }
        return false
    }
}
