package dev.martin.cubicai.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds the paired PC on the home network by its mDNS announcement (`_cubicai._tcp`), for when the router has
 * given it a new address. Only a PC announcing the paired certificate's fingerprint counts, and the connection
 * that follows is pinned to that certificate anyway, so a look-alike on the network gets nowhere.
 */
class LanFinder(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Blocks for up to [timeoutMs]; returns "https://address:port" (or "https://[v6]:port") of the PC, or null.
     * Addresses in [skip] (they were just tried and didn't answer) are passed over for the next one.
     */
    fun find(fingerprint: String, skip: Set<String> = emptySet(), timeoutMs: Long = 4000): String? {
        val nsd = nsd ?: return null
        val found = AtomicReference<String?>(null)
        val done = CountDownLatch(1)

        fun accept(info: NsdServiceInfo, addresses: List<InetAddress>) {
            val fp = info.attributes["fp"]?.decodeToString()
            if (fp == null || !fp.equals(fingerprint, ignoreCase = true)) return
            // The PC announces every address it has (Docker, IPv6, Tailscale); the gateway only lets home ones in.
            // Home IPv4 addresses come first; the IPv6 ones (not link-local, not Tailscale's) are for when the router
            // has stopped passing IPv4 between devices, which is also when the IPv4 one is in [skip].
            val v4 = addresses.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }
                .map { "https://${it.hostAddress}:${info.port}" }
            val v6 = addresses.filterIsInstance<Inet6Address>().filter {
                !it.isLinkLocalAddress && !it.isLoopbackAddress && !it.hostAddress.orEmpty().startsWith("fd7a:115c:a1e0:", ignoreCase = true)
            }.map { "https://[${it.hostAddress?.substringBefore('%')}]:${info.port}" }
            val url = (v4 + v6).firstOrNull { it !in skip } ?: return
            if (found.compareAndSet(null, url)) done.countDown()
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                if (Build.VERSION.SDK_INT >= 34) {
                    nsd.registerServiceInfoCallback(service, executor, object : NsdManager.ServiceInfoCallback {
                        override fun onServiceUpdated(info: NsdServiceInfo) {
                            accept(info, info.hostAddresses)
                            runCatching { nsd.unregisterServiceInfoCallback(this) }
                        }
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                        override fun onServiceLost() = Unit
                        override fun onServiceInfoCallbackUnregistered() = Unit
                    })
                } else {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(info: NsdServiceInfo) = accept(info, listOfNotNull(info.host))
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    })
                }
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = done.countDown()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        return try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
            found.get()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_cubicai._tcp"
    }
}
