package com.miller.controltv

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Holds the app's client certificate. The TV remembers this certificate after
 * pairing, so it is generated once and kept in app storage.
 */
class Tls(private val context: Context) {

    private val password = "controltv".toCharArray()
    private val alias = "client"
    private val file get() = File(context.filesDir, "client.p12")

    @Volatile
    private var keyStore: KeyStore? = null

    @Synchronized
    fun keyStore(): KeyStore {
        keyStore?.let { return it }
        val ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            try {
                file.inputStream().use { ks.load(it, password) }
                if (ks.containsAlias(alias)) {
                    keyStore = ks
                    return ks
                }
            } catch (_: Exception) {
                // fall through and regenerate
            }
        }
        val fresh = generate()
        keyStore = fresh
        return fresh
    }

    private fun generate(): KeyStore {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val kp = kpg.generateKeyPair()
        val name = X500Name("CN=atvremote/ControlTV, O=ControlTV, C=VE")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 24L * 3600 * 1000),
            Date(now + 20L * 365 * 24 * 3600 * 1000),
            name,
            kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(alias, kp.private, password, arrayOf(cert))
        file.outputStream().use { ks.store(it, password) }
        return ks
    }

    fun clientCertificate(): X509Certificate = keyStore().getCertificate(alias) as X509Certificate

    /** Forget the certificate; the TV will ask to pair again. */
    @Synchronized
    fun reset() {
        file.delete()
        keyStore = null
    }

    fun connect(host: String, port: Int, timeoutMs: Int = 6000): SSLSocket {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore(), password)
        // The TV uses a self-signed certificate; pairing (the on-screen code) is what
        // proves we talk to the right device, so we accept the TV certificate here.
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, trustAll, SecureRandom())
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.tcpNoDelay = true
        val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.soTimeout = 0
        ssl.startHandshake()
        return ssl
    }
}
