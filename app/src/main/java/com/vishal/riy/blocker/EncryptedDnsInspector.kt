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