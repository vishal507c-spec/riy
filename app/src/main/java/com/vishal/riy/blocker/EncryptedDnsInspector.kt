package com.vishal.riy.blocker

import android.content.Context
import android.provider.Settings

/**
 * Reads the OS "Private DNS" (DNS-over-TLS) configuration so the app can report
 * an encrypted-DNS bypass honestly instead of assuming the filter sees
 * everything.
 *
 * IMPORTANT SCOPE LIMIT
 * ---------------------
 * This reads the *system* resolver setting only. A per-application DoH client
 * (Chrome's own "Secure DNS") is not exposed by any public Android API and is
 * therefore invisible here — which is precisely why the UI never claims full
 * protection while the filter is unverified. See [EncryptedDnsExposure].
 */
object EncryptedDnsInspector {

    private const val KEY_PRIVATE_DNS_MODE = "private_dns_mode"
    private const val KEY_PRIVATE_DNS_SPECIFIER = "private_dns_specifier"

    private const val MODE_OFF = 0
    private const val MODE_AUTOMATIC = 1

    /** True when Private DNS points at a specific DNS-over-TLS provider. */
    fun isPrivateDnsConfigured(context: Context): Boolean = runCatching {
        val specifier = readString(context, KEY_PRIVATE_DNS_SPECIFIER)
        val mode = readInt(context, KEY_PRIVATE_DNS_MODE)
        val customProvider = !specifier.isNullOrBlank()
        // Mode "off" with a stale specifier left behind is not an active config.
        customProvider && mode != MODE_OFF
    }.getOrDefault(false)

    /**
     * True when the platform reports Private DNS switched off entirely — the
     * state in which the system resolver is guaranteed to use the VPN's filtered
     * DNS server.
     */
    fun isPrivateDnsOff(context: Context): Boolean = runCatching {
        readInt(context, KEY_PRIVATE_DNS_MODE) == MODE_OFF && readString(context, KEY_PRIVATE_DNS_SPECIFIER).isNullOrBlank()
    }.getOrDefault(false)

    /** True when Private DNS is opportunistic/strict over a known provider. */
    fun isPrivateDnsAutomatic(context: Context): Boolean = runCatching {
        readInt(context, KEY_PRIVATE_DNS_MODE) == MODE_AUTOMATIC
    }.getOrDefault(false)

    /** The Private DNS hostname Android is configured to use, if any. */
    fun privateDnsSpecifier(context: Context): String? = readString(context, KEY_PRIVATE_DNS_SPECIFIER)

    /**
     * The Private DNS addresses that must NOT be captured into the tun.
     *
     * WHY THIS IS NEEDED — this was a real "no internet" bug:
     * Android applies Private DNS (DoT) to the VPN's own network too. RIY routed
     * the well-known DoT server IPs into its tun and dropped them as "non-DNS",
     * which broke Android's resolver for the entire device. The platform then
     * flagged the network `PrivateDnsBroken`, its connectivity validation probe
     * could not resolve, and the phone showed "No internet" even though the
     * Wi-Fi was fine.
     *
     * So the filter must not break the OS resolver it is supposed to protect.
     * These addresses are handed back to Android untouched; everything else is
     * still captured. The cost is stated honestly in the UI: while Private DNS
     * is on and this device does not let RIY disable it, encrypted lookups made
     * by the SYSTEM resolver are not inspectable — which is exactly what the
     * "Running — Not Verified" status exists to say.
     */
    private val KNOWN_PRIVATE_DNS_HOSTS = mapOf(
        "family.cloudflare-dns.com" to listOf(
            "1.1.1.3", "1.0.0.3", "2606:4700:4700::1113", "2606:4700:4700::1003",
        ),
        "security.cloudflare-dns.com" to listOf(
            "1.1.1.3", "1.0.0.3", "2606:4700:4700::1113", "2606:4700:4700::1003",
        ),
        "dns.adguard.com" to listOf("94.140.14.14", "94.140.15.15"),
        "dns.quad9.net" to listOf("9.9.9.9", "9.9.9.10"),
        "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
        "dns.sb" to listOf("185.222.222.222", "185.222.223.223"),
        "dns.nextdns.io" to listOf("45.90.28.0", "45.90.30.0", "103.86.96.100"),
    )

    /**
     * Addresses that belong to the device's own Private DNS configuration and
     * must be excluded from interception. Empty when Private DNS is off, which
     * is the common case and where interception is fully effective.
     */
    fun privateDnsAddressesToExempt(context: Context): Set<String> {
        // Only a strictly-configured (hostname) DoT resolver needs exemptions.
        // "Automatic"/"off" uses no fixed server, so nothing must be spared.
        if (!isPrivateDnsConfigured(context)) return emptySet()
        val specifier = privateDnsSpecifier(context)?.lowercase() ?: return emptySet()
        return KNOWN_PRIVATE_DNS_HOSTS[specifier].orEmpty().toSet()
    }

    /**
     * True when [name] is the device's OWN configured Private DNS host.
     *
     * This one hostname must never be sinkholed. Android resolves it to
     * bootstrap DoT for the entire device; blocking it made Private DNS fail to
     * start, which took down ALL name resolution on the phone and showed up as
     * "No internet" on a perfectly good Wi-Fi connection.
     *
     * Only the system resolver's own bootstrap name is spared. Every other
     * DoH/DoT endpoint stays blocked, and the DoT *addresses* are exempted from
     * the tun for the same reason (see [privateDnsAddressesToExempt]).
     */
    fun isDevicePrivateDnsHost(context: Context, name: String?): Boolean {
        val host = name?.trim()?.lowercase()?.removeSuffix(".") ?: return false
        val specifier = privateDnsSpecifier(context)?.trim()?.lowercase()?.removeSuffix(".")
            ?: return false
        return host == specifier
    }

    /** Human-readable mode for the dashboard; never leaks a hostname. */
    fun describe(context: Context): String = when {
        isPrivateDnsOff(context) -> "Private DNS is off"
        isPrivateDnsAutomatic(context) -> "Private DNS is automatic"
        isPrivateDnsConfigured(context) -> "Private DNS (DoT) is configured on this device"
        else -> "Private DNS state unavailable"
    }

    private fun readInt(context: Context, key: String): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, key, MODE_OFF)
    }.getOrDefault(MODE_OFF)

    private fun readString(context: Context, key: String): String? = runCatching {
        Settings.Global.getString(context.contentResolver, key)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}