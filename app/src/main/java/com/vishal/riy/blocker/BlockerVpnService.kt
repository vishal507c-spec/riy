package com.vishal.riy.blocker

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vishal.riy.R
import com.vishal.riy.protection.intelligence.ProtectionIntelligence
import com.vishal.riy.protection.recovery.DefaultRecoveryService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * DNS-filtering VPN service — the actual protection engine.
 *
 * How it works (no HTTPS interception, nothing is decrypted or inspected):
 *  1. The tun interface is created with the system DNS pointed at a virtual
 *     resolver (10.111.222.3) and /32 routes ONLY for that resolver plus the
 *     well-known public resolver IPs (8.8.8.8, 1.1.1.1, ...). All other
 *     traffic bypasses the VPN entirely, so battery cost stays near zero.
 *  2. DNS queries arriving on the tun (UDP port 53) are parsed; if the queried
 *     domain matches the adult [Blocklist], an immediate 0.0.0.0/::/NXDOMAIN
 *     answer is written back, so the browser/app never connects (works for
 *     HTTPS too — the domain simply never resolves).
 *  3. Allowed queries are relayed to a real upstream resolver over a
 *     `protect()`ed socket. Apps that hardcode a public resolver are also
 *     captured because those IPs are routed into the tun; non-DNS traffic to
 *     those IPs (DoH/DoT) is dropped, which makes such apps fall back to the
 *     filtered system DNS.
 *
 * State is reported through [BlockerState] only — the UI shows exactly what
 * this service does.
 */
class BlockerVpnService : VpnService() {

    private val running = AtomicBoolean(false)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var scope: CoroutineScope = newScope()
    private val upstreamPermits = Semaphore(64)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var retryAttempt = 0

    /**
     * THE Phase 10 intelligence front-end. It wraps the production
     * [com.vishal.riy.protection.ProtectionEventProcessor] graph and adds the
     * observation/correlation tier in front of it — one shared pipeline, one
     * shared state store, one shared lock store and one shared enforcement
     * engine, not a second copy of any of them.
     */
    private val intelligence: ProtectionIntelligence by lazy {
        ProtectionIntelligence.forContext(this)
    }

    /**
     * Network-level SafeSearch enforcement: search engines are DNS-pinned to
     * their own "safe" frontends (valid certificates, server-side filtering).
     */
    private val safeSearchEnforcer = SafeSearchEnforcer(
        scope = { scope },
        resolve = { name, type ->
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                queryUpstreams(SafeSearchRules.buildDnsQuery(name, type))
            }
        },
    )

    /** Boot-time establish retry: system VPN state may not be ready yet. */
    private val retryRunnable = Runnable {
        if (running.get()) return@Runnable
        if (BlockerStateStore(this).isProtectionWanted()) {
            Log.i(TAG, "retrying VPN establish ($retryAttempt/$MAX_BOOT_RETRIES)")
            startFiltering(allowRetry = true)
        } else {
            stopSelf()
        }
    }

    /**
     * Forwarding coroutines must NEVER take the whole process down: any
     * unexpected error is logged and the query is simply dropped.
     */
    private fun newScope(): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "background DNS task failed: ${e.javaClass.simpleName}: ${e.message}")
        },
    )

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() requires startForeground() promptly.
        startForegroundInternal()
        return when (intent?.action) {
            ACTION_STOP -> {
                BlockerStateStore(this).setProtectionWanted(false)
                stopFiltering()
                android.app.Service.START_NOT_STICKY
            }
            ACTION_START, VpnService.SERVICE_INTERFACE -> {
                if (intent.action == VpnService.SERVICE_INTERFACE) {
                    // System-initiated start (Always-On VPN): remember intent.
                    BlockerStateStore(this).setProtectionWanted(true)
                }
                startFiltering(allowRetry = intent.getBooleanExtra(EXTRA_ALLOW_RETRY, false))
                android.app.Service.START_STICKY
            }
            else -> {
                // START_STICKY redelivery (null intent) after process death:
                // restart only when the user's persisted choice was ON.
                if (BlockerStateStore(this).isProtectionWanted()) {
                    Log.i(TAG, "sticky restart; restoring protection")
                    // PHASE 6 — the process died and was respawned, so the
                    // in-memory expectation of a restriction is gone while the
                    // persisted session and the LockEngine deadline survive.
                    // Reconcile both directions BEFORE filtering resumes: a
                    // live restriction is re-applied, an expired one cleared,
                    // and bypass packages are re-neutralized. Single entry
                    // point (existing orchestrator + bypass sweep); touches no
                    // deadline and creates no second state.
                    runCatching {
                        com.vishal.riy.protection.integrity.ProtectionReconciler.reconcileAll(
                            this, "vpn_sticky_restart",
                        )
                    }.onFailure { e ->
                        Log.w(TAG, "recovery on sticky restart failed: ${e.message}")
                    }
                    startFiltering(allowRetry = true)
                    android.app.Service.START_STICKY
                } else {
                    stopSelf()
                    android.app.Service.START_NOT_STICKY
                }
            }
        }
    }

    override fun onRevoke() {
        // VPN consent revoked, Always-On disabled, or another VPN took over:
        // protection is gone — report that honestly. The security event is
        // recorded (no content, only the fact of revocation); recovery on next
        // foreground/boot restores protection when the persisted session is
        // still live.
        Log.w(TAG, "VPN revoked by system/user")
        runCatching {
            com.vishal.riy.protection.events.PrefsProtectionEventStore(this).append(
                com.vishal.riy.protection.events.ProtectionLogEvent(
                    eventId = "vpn-revoke-${System.currentTimeMillis()}",
                    type = com.vishal.riy.protection.events.ProtectionLogEvent.Type.INTEGRITY_MISMATCH,
                    timestamp = System.currentTimeMillis(),
                    message = "VPN revoked by system/user; protection OFF until restored",
                ),
            )
        }
        BlockerStateStore(this).setProtectionWanted(false)
        stopFiltering()
    }

    override fun onDestroy() {
        running.set(false)
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- state

    private fun startFiltering(allowRetry: Boolean = false) {
        if (running.get() && vpnInterface != null) {
            // Already live: keep the evidence we already have. Re-announcing
            // CONNECTED here would wipe the verified/unverified distinction.
            Log.i(TAG, "already running; keeping phase=${BlockerState.current().phase}")
            return
        }
        if (running.get()) {
            // The flag outlived the interface (the filter thread died without
            // clearing it). Trusting the flag alone made every later "Enable
            // Protection" tap a silent no-op, so recover the real state first.
            Log.w(TAG, "filter flagged running but the tun is gone; re-establishing")
            running.set(false)
            runCatching { vpnInterface?.close() }
            vpnInterface = null
        }
        // Fresh (user-initiated) starts reset the retry counter; retries must
        // keep counting up or they would loop forever.
        if (!allowRetry) retryAttempt = 0
        BlockerState.update(BlockerState.Phase.CONNECTING)

        val fd = try {
            establishVpn()
        } catch (e: Exception) {
            Log.e(TAG, "VPN establish threw", e)
            null
        }
        if (fd == null) {
            // establish() returns null when VPN permission was revoked, another
            // VPN holds the slot, or (right after boot) the system VPN state is
            // not initialized yet. Retry a few times before giving up.
            if (allowRetry && retryAttempt < MAX_BOOT_RETRIES &&
                BlockerStateStore(this).isProtectionWanted()
            ) {
                retryAttempt++
                Log.w(TAG, "VPN establish failed; scheduling retry $retryAttempt/$MAX_BOOT_RETRIES")
                BlockerState.update(
                    BlockerState.Phase.CONNECTING,
                    "system starting; VPN activation will retry shortly",
                )
                mainHandler.removeCallbacks(retryRunnable)
                mainHandler.postDelayed(retryRunnable, RETRY_DELAY_MS)
                return
            }
            Log.e(TAG, "VPN establish failed (permission revoked or another VPN active)")
            fail(
                "VPN permission missing or another VPN is active",
                BlockerState.FailureKind.VPN_PERMISSION_MISSING,
            )
            return
        }

        running.set(true)
        vpnInterface = fd
        scope = newScope()
        // The tun is up but the filter cannot answer anything yet: the blocklist
        // is large enough that parsing it must never happen on the main thread,
        // and until it is loaded the device is NOT protected. Reporting
        // CONNECTED here is exactly the "protected because the service started"
        // lie this app must not tell.
        BlockerState.update(BlockerState.Phase.INITIALIZING)
        applyEncryptedDnsPolicy()
        worker = thread(name = "riy-dns-filter", isDaemon = true) {
            val blocklist = try {
                loadBlocklist()
            } catch (e: Exception) {
                Log.e(TAG, "blocklist load failed", e)
                fail("filter initialization failed", BlockerState.FailureKind.FILTER_INIT_FAILED)
                return@thread
            }
            if (!running.get()) return@thread // stopped while we were loading
            Log.i(TAG, "protection active (${blocklist.ruleCount} blocklist rules, SafeSearch enforced)")
            BlockerState.update(BlockerState.Phase.CONNECTED, ruleCount = blocklist.ruleCount)
            // Prime the SafeSearch VIP cache and verify ourselves in the
            // background: the filter is live but NOT yet proven, and the UI must
            // say so until the self-test comes back.
            publishFilterState(blocklist.ruleCount)
            scope.launch { runCatching { safeSearchEnforcer.refreshAll() } }
            runSelfTest(blocklist.ruleCount)
            runFilterLoop(fd, blocklist)
        }
    }

    /**
     * Records what the filter has actually proven so far, never more than that.
     * Kept in one place so no code path can publish a reassuring state.
     */
    /**
     * Runs the end-to-end self-test and publishes ONLY what it measured. A
     * self-test that cannot reach the filter downgrades the state rather than
     * leaving a stale "verified" claim on screen.
     */
    private fun runSelfTest(ruleCount: Int) {
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                FilterSelfTest(VPN_DNS_ADDRESS).run(ruleCount)
            }
            if (!running.get()) return@launch
            publishFilterState(ruleCount, report)
        }
    }

    /**
     * Records what the filter has actually proven so far, never more than that.
     * Single publish point so no code path can show a reassuring state.
     */
    private fun publishFilterState(ruleCount: Int, report: SelfTestReport? = null) {
        val phase = BlockerState.current().phase
        val health = report?.health() ?: BlockerState.healthFor(phase)
        val privateDnsConfigured = EncryptedDnsInspector.isPrivateDnsConfigured(this)
        val privateDnsRestricted = isPrivateDnsRestricted()
        BlockerState.update(
            phase = phase,
            health = health,
            ruleCount = ruleCount,
            selfTestSummary = report?.summary(),
            privateDnsConfigured = privateDnsConfigured,
            privateDnsRestricted = privateDnsRestricted,
        )
        val exposure = EncryptedDnsExposure(privateDnsConfigured, privateDnsRestricted, report?.verified == true)
        Log.i(
            TAG,
            "filter state: rules=$ruleCount health=$health bypassPossible=${exposure.bypassPossible}",
        )
    }

    /**
     * Applies the one supported platform control that closes the system-level
     * encrypted-DNS route (Android "Private DNS" / DoT): a Device Owner user
     * restriction. Deliberately NOT applied without Device Owner, and fully
     * reversible. A per-app DoH client inside Chrome stays invisible to every
     * Android API — that limitation is reported, never papered over.
     */
    private fun applyEncryptedDnsPolicy() {
        val applied = EncryptedDnsPolicy.apply(this)
        Log.i(TAG, "private DNS restriction applied=$applied")
    }

    /** Reads back whether the private-DNS restriction is genuinely in force. */
    private fun isPrivateDnsRestricted(): Boolean = EncryptedDnsPolicy.isRestricted(this)

    private fun stopFiltering() {
        if (running.getAndSet(false)) {
            Log.i(TAG, "stopping protection")
        }
        mainHandler.removeCallbacks(retryRunnable)
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        worker?.let { runCatching { it.join(500) } }
        worker = null
        runCatching { scope.cancel() }
        scope = newScope()
        BlockerState.update(BlockerState.Phase.OFF)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(
        reason: String,
        kind: BlockerState.FailureKind = BlockerState.FailureKind.VPN_DISCONNECTED,
    ) {
        mainHandler.removeCallbacks(retryRunnable)
        // The user's ON choice is PRESERVED: the FAILED phase itself signals
        // "not protected" honestly, and the app self-heals on next open so a
        // transient boot race or another VPN letting go can recover.
        BlockerState.update(BlockerState.Phase.FAILED, reason, kind)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ vpn

    private fun establishVpn(): ParcelFileDescriptor? {
        // Addresses that belong to the OS's own Private DNS (DoT) resolver.
        // Capturing these is what made the whole phone report "No internet":
        // Android applies Private DNS to the VPN network too, so breaking these
        // breaks the system resolver, and the connectivity probe then fails.
        val exempt = EncryptedDnsInspector.privateDnsAddressesToExempt(this)
        if (exempt.isNotEmpty()) {
            Log.i(TAG, "private DNS active; not intercepting ${exempt.size} resolver address(es)")
        }
        val builder = Builder()
            .setSession(getString(R.string.blocker_vpn_session))
            .setMtu(VPN_MTU)
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_DNS_ADDRESS)
            .addRoute(VPN_DNS_ADDRESS, 32)
        for (ip in INTERCEPTED_RESOLVERS_V4) {
            if (ip !in exempt) builder.addRoute(ip, 32)
        }
        try {
            builder.addAddress(VPN_ADDRESS_V6, 128)
            for (ip in INTERCEPTED_RESOLVERS_V6) {
                if (ip !in exempt) builder.addRoute(ip, 128)
            }
        } catch (e: Exception) {
            // IPv6 interception is best-effort; devices without v6 support in
            // VPN fall back to IPv4 DNS, which is filtered anyway.
            Log.w(TAG, "IPv6 interception unavailable: ${e.message}")
        }
        return builder.establish()
    }

    private fun loadBlocklist(): Blocklist {
        val rules = assets.open(BLOCKLIST_ASSET).bufferedReader().useLines { Blocklist.parseRules(it) }
        return Blocklist(rules)
    }

    // ------------------------------------------------------------ read loop

    private fun runFilterLoop(fd: ParcelFileDescriptor, blocklist: Blocklist) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (running.get()) {
            val length = try {
                input.read(buffer)
            } catch (e: IOException) {
                Log.i(TAG, "tun read failed (interface closed): ${e.message}")
                break // interface closed (stop/revoke) — the only real EOF signal
            }
            if (length <= 0) {
                // Some kernels/emulators return 0 (EAGAIN-as-0) when the tun
                // has no pending packet. This is NOT an EOF: a true teardown
                // surfaces as an IOException once the fd is closed. Sleep and
                // keep the loop alive so DNS never blackholes during idle gaps.
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                continue
            }
            try {
                processPacket(buffer, length, output, blocklist)
            } catch (e: Exception) {
                // Never let a malformed packet kill the filter.
                Log.w(TAG, "packet processing error: ${e.javaClass.simpleName}")
            }
        }
        Log.i(TAG, "filter loop exited")
    }

    private fun processPacket(
        buffer: ByteArray,
        length: Int,
        output: FileOutputStream,
        blocklist: Blocklist,
    ) {
        val parsed = IpPacket.parseUdpDns(buffer, length) ?: return // non-DNS: drop
        val dns = buffer.copyOfRange(parsed.dnsStart, parsed.dnsStart + parsed.dnsLength)
        val question = DnsProtocol.question(dns) ?: return // malformed DNS: drop
        // Sinkholed so apps using their own DoH/DoT fall back to the filtered
        // resolver. This is transport removal, NOT content classification: it
        // never arms a detection, never inspects message/media content, and
        // Telegram hostnames are deliberately never in this set.
        //
        // ONE EXCEPTION: the device's own configured Private DNS hostname is
        // left resolvable. Android resolves it to bootstrap DoT for the whole
        // device, so blocking it killed ALL name resolution and the phone
        // reported "No internet" on a healthy Wi-Fi.
        if (!EncryptedDnsInspector.isDevicePrivateDnsHost(this, question.name) &&
            com.vishal.riy.protection.enforcement.BlockedAppPolicy.isBypassDomain(question.name)
        ) {
            val response = DnsProtocol.buildBlockedResponse(dns) ?: return
            Log.i(TAG, "bypass-transport blocked (no detection): '${question.name}'")
            writeReply(output, IpPacket.buildReply(parsed, response))
            return
        }
        if (blocklist.contains(question.name)) {
            val response = DnsProtocol.buildBlockedResponse(dns) ?: return
            // The self-test canary is RIY's OWN probe. It is still blocked (that
            // is what proves enforcement), but it must never be handed to the
            // detection pipeline: arming a protection event from the app's own
            // health check would be a false positive manufactured by us.
            if (!SelfTestCanaries.isSelfTestQuery(question.name)) {
                recordPornDetection(question.name)
            }
            writeReply(output, IpPacket.buildReply(parsed, response))
        } else {
            // SafeSearch pinning answers locally; everything else is forwarded.
            val safeAnswer = safeSearchEnforcer.answerFor(dns, question)
            if (safeAnswer != null) {
                writeReply(output, IpPacket.buildReply(parsed, safeAnswer))
            } else {
                scope.launch { forwardAndReply(dns, parsed, output) }
            }
        }
    }

    // ------------------------------------------------------------- detection

    /**
     * THE DETECTION POINT hands one observation to the intelligence layer and
     * nothing more. The blocking answer itself (0.0.0.0) has already been
     * written by [processPacket]; every consequence of this observation — the
     * corroboration verdict, the risk grade, the policy decision, the session,
     * the 2-hour deadline and the platform restriction — is owned further down
     * the existing chain.
     *
     * This method therefore owns DETECTION ONLY: it computes no deadline, holds
     * no state machine, performs no correlation itself and never touches
     * DevicePolicyManager. A null return is the honest "recorded but not yet
     * evidence" answer for an uncorroborated ambiguous signal — the false
     * positive guarantee — and never weakens the DNS block that already
     * happened. A failure inside the pipeline never weakens it either.
     */
    private fun recordPornDetection(domain: String) {
        runCatching {
            val result = intelligence.onAdultDomainLookup(domain)
            if (result == null) {
                Log.i(TAG, "adult-content observation recorded (not yet evidence): '$domain'")
            } else {
                Log.i(
                    TAG,
                    "adult-content detection: '$domain' -> ${result.decision.nextState} " +
                        "(evidence=${result.decision.assessment?.evidenceType}, " +
                        "enforcement=${result.enforcementResult})",
                )
            }
        }.onFailure { e ->
            Log.w(TAG, "protection pipeline failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun writeReply(output: FileOutputStream, packet: ByteArray) {
        try {
            synchronized(output) {
                output.write(packet)
                output.flush()
            }
        } catch (_: IOException) {
            // interface gone — loop will exit
        }
    }

    // ------------------------------------------------------------- upstream

    private suspend fun forwardAndReply(
        dns: ByteArray,
        parsed: IpPacket.ParsedUdpDns,
        output: FileOutputStream,
    ) {
        upstreamPermits.withPermit {
            val response = queryUpstreams(dns)
                ?: DnsProtocol.buildServFail(dns) // no upstream reachable: fail fast, never hang
                ?: return@withPermit
            writeReply(output, IpPacket.buildReply(parsed, response))
        }
    }

    /** Tries each upstream; handles truncated/oversized responses via TCP retry. */
    private fun queryUpstreams(dns: ByteArray): ByteArray? {
        for (upstream in UPSTREAM_DNS) {
            val udpResponse = queryUdp(upstream, dns) ?: continue
            if (!DnsProtocol.isTruncated(udpResponse) && udpResponse.size <= MAX_UDP_DNS_RESPONSE) {
                return udpResponse
            }
            // Truncated or larger than what we can safely write into the tun:
            // re-query the same resolver over TCP for the complete answer.
            val tcpResponse = queryTcp(upstream, dns)
            if (tcpResponse != null) {
                return if (tcpResponse.size <= MAX_UDP_DNS_RESPONSE) {
                    tcpResponse
                } else {
                    DnsProtocol.buildTruncatedResponse(dns)
                }
            }
            // TCP retry failed — fall through to the next upstream.
        }
        return null
    }

    private fun queryUdp(upstream: String, dns: ByteArray): ByteArray? {
        val socket = DatagramSocket()
        return try {
            if (!protect(socket)) return null
            socket.soTimeout = UPSTREAM_TIMEOUT_MS
            // Numeric literal: getByName() does NOT trigger a DNS lookup here.
            val address = InetAddress.getByName(upstream)
            socket.send(DatagramPacket(dns, dns.size, address, DNS_PORT))
            val buffer = ByteArray(MAX_UDP_RESPONSE_BUFFER)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            buffer.copyOf(packet.length)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null // timeout / unreachable / network down — try next upstream
        } finally {
            socket.close()
        }
    }

    private fun queryTcp(upstream: String, dns: ByteArray): ByteArray? {
        val socket = Socket()
        return try {
            if (!protect(socket)) return null
            socket.soTimeout = UPSTREAM_TIMEOUT_MS
            socket.connect(
                InetSocketAddress(InetAddress.getByName(upstream), DNS_PORT),
                UPSTREAM_TIMEOUT_MS,
            )
            val out = socket.getOutputStream()
            out.write(byteArrayOf(((dns.size shr 8) and 0xFF).toByte(), (dns.size and 0xFF).toByte()))
            out.write(dns)
            out.flush()
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 512))
            val length = input.readUnsignedShort()
            if (length < DnsProtocol.HEADER_SIZE) return null
            val response = ByteArray(length)
            input.readFully(response)
            response
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    // --------------------------------------------------------- notification

    private fun startForegroundInternal() {
        // A channel MUST exist before posting, otherwise startForeground
        // throws "Bad notification" and kills the process (Android O+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                getString(R.string.blocker_notification_channel),
                android.app.NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            getSystemService(android.app.NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.blocker_notification_title))
            .setContentText(getString(R.string.blocker_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, com.vishal.riy.MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val TAG = "BlockerVpn"
        const val ACTION_START = "com.vishal.riy.blocker.ACTION_START"
        const val ACTION_STOP = "com.vishal.riy.blocker.ACTION_STOP"

        private const val CHANNEL_ID = "blocker_protection"
        private const val NOTIFICATION_ID = 1001
        private const val VPN_MTU = 1500
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val VPN_DNS_ADDRESS = "10.111.222.3"
        private const val VPN_ADDRESS_V6 = "fd00:52:52:52::1"
        private const val DNS_PORT = 53
        private const val UPSTREAM_TIMEOUT_MS = 4000
        private const val MAX_UDP_DNS_RESPONSE = 1400
        private const val MAX_UDP_RESPONSE_BUFFER = 8192
        private const val READ_BUFFER_SIZE = 32768
        private const val BLOCKLIST_ASSET = "blocklist.txt"
        private const val EXTRA_ALLOW_RETRY = "allow_retry"
        private const val MAX_BOOT_RETRIES = 12
        private const val RETRY_DELAY_MS = 10_000L

        /** Real upstream resolvers used for allowed queries. */
        private val UPSTREAM_DNS = arrayOf("1.1.1.1", "8.8.8.8")

        /**
         * Public resolver IPs routed into the tun so that (a) apps hardcoding
         * a resolver are still filtered and (b) encrypted-DNS traffic to these
         * providers (DoT/853, DoH/443, HTTPS/443) is DROPPED, forcing those
         * apps back onto the filtered system DNS.
         *
         * This is best-effort by nature: it is an IP list, so a DoH client
         * pointed at a provider that is not listed here is not captured. That
         * gap is exactly why the dashboard reports "filter running, bypass
         * protection unverified" instead of claiming full protection — see
         * [EncryptedDnsExposure]. The list is deliberately wide because the cost
         * of a false capture is only that those specific IPs are unreachable
         * while protection is on.
         */
        private val INTERCEPTED_RESOLVERS_V4 = listOf(
            "8.8.8.8", "8.8.4.4",               // Google
            "1.1.1.1", "1.0.0.1",               // Cloudflare
            "9.9.9.9", "149.112.112.112",       // Quad9
            "9.9.9.10",                         // Quad9 (unfiltered)
            "208.67.222.222", "208.67.220.220", // OpenDNS
            "208.67.222.238",                   // OpenDNS (FamilyShield)
            "94.140.14.14", "94.140.15.15",     // AdGuard
            "94.140.14.15",                     // AdGuard
            "185.228.168.9", "185.228.169.9",   // CleanBrowsing
            "185.228.168.10", "185.228.169.10", // CleanBrowsing (security)
            "45.90.28.0", "45.90.30.0",         // NextDNS (common endpoints)
            "103.86.96.100",                    // NextDNS
            "76.76.2.0",                        // ControlD
            "194.242.2.2",                      // Mullvad
            "185.222.222.222", "185.222.223.223", // DNS.SB
            "64.6.64.6", "64.6.65.6",           // Verisign
            "77.88.8.8", "77.88.8.1",           // Yandex
            "114.114.114.114", "114.114.115.115",
            "223.5.5.5", "223.6.6.6",
            "119.29.29.29",
            "180.76.76.76",
            "156.154.70.1", "156.154.71.1",
            "4.2.2.1", "4.2.2.2", "4.2.2.3", "4.2.2.4", "4.2.2.5", "4.2.2.6", // Level3
            "84.200.69.80", "84.200.82.80",     // DNS.WATCH
            "8.26.56.26", "8.20.247.20",        // Comodo
            "216.146.35.35", "216.146.36.36",   // Dyn
            "10.0.2.3", // Android emulator goldfish DNS (harmless on real devices)
        )
        private val INTERCEPTED_RESOLVERS_V6 = listOf(
            "2001:4860:4860::8888", "2001:4860:4860::8844", // Google
            "2606:4700:4700::1111", "2606:4700:4700::1001", // Cloudflare
            "2620:fe::fe", "2620:fe::9",                    // Quad9
            "2620:fe::10",                                  // Quad9 (unfiltered)
            "2620:119:35::35", "2620:119:53::53",           // OpenDNS
            "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff",       // AdGuard
            "2a07:a8c0::12:3456",                           // NextDNS
            "2606:1a40::12:3456",                           // NextDNS
        )

        /**
         * Starts the protection service (foreground service on O+).
         * Establish failures arm a retry loop: right after boot the system
         * VPN state may not be ready yet (and some OEMs/AVDs fail
         * background-started establishes) — a 2-minute retry window covers
         * both without user interaction. The status stays honest throughout:
         * CONNECTING while retrying, FAILED only after all attempts.
         */
        fun start(context: Context, allowRetry: Boolean = true) {
            val intent = Intent(context, BlockerVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ALLOW_RETRY, allowRetry)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stops protection and records the user's choice as OFF. */
        fun stop(context: Context) {
            BlockerStateStore(context).setProtectionWanted(false)
            try {
                context.startService(
                    Intent(context, BlockerVpnService::class.java).setAction(ACTION_STOP),
                )
            } catch (e: Exception) {
                // Background start restrictions; the service will stop via
                // onRevoke/self-heal paths otherwise.
                Log.w(TAG, "stop request not delivered: ${e.message}")
            }
        }
    }
}
