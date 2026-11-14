package com.miller.controltv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.ArrayDeque

data class TvDevice(val name: String, val host: String, val port: Int = 6466)

/** Finds Google TV / Android TV devices on the Wi-Fi network via mDNS. */
class Discovery(context: Context, private val onFound: (TvDevice) -> Unit) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        stop()
        lock = wifi.createMulticastLock("controltv").apply {
            setReferenceCounted(false)
            acquire()
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(queue) { queue.add(info) }
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        listener = l
        try {
            nsd.discoverServices("_androidtvremote2._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next: NsdServiceInfo
        synchronized(queue) {
            if (resolving || queue.isEmpty()) return
            resolving = true
            next = queue.poll()!!
        }
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    done()
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress
                    if (host != null) onFound(TvDevice(info.serviceName, host, info.port))
                    done()
                }
            })
        } catch (_: Exception) {
            done()
        }
    }

    private fun done() {
        synchronized(queue) { resolving = false }
        resolveNext()
    }

    fun stop() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
        try { lock?.release() } catch (_: Exception) {}
        lock = null
        synchronized(queue) { queue.clear(); resolving = false }
    }
}
