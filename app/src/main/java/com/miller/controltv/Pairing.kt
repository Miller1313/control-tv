package com.miller.controltv

import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * Pairing over port 6467 (Android TV "polo" protocol).
 * Step 1: start() -> the TV shows a 6-character code.
 * Step 2: finish(code) with what the user typed.
 */
/** The typed code is wrong but the TV session is still open: the user can retype it. */
class RetryableCodeException(msg: String) : IOException(msg)

class Pairing(private val tls: Tls, private val host: String, private val port: Int = 6467) {

    private var socket: SSLSocket? = null

    companion object {
        private const val STATUS_OK = 200L
        private const val ENCODING_HEX = 3
        private const val ROLE_INPUT = 1
    }

    private fun wrap(inner: (ProtoWriter) -> Unit): ByteArray {
        val w = ProtoWriter().varint(1, 2).varint(2, 200)
        inner(w)
        return w.toByteArray()
    }

    private fun exchange(s: SSLSocket, payload: ByteArray): ProtoMessage {
        Framing.write(s.outputStream, payload)
        val reply = ProtoMessage.parse(Framing.read(s.inputStream))
        val status = reply.long(2)
        if (status != STATUS_OK) {
            throw IOException(
                when (status) {
                    402L -> "Código incorrecto"
                    401L -> "El TV rechazó la configuración"
                    else -> "El TV respondió con error ($status)"
                }
            )
        }
        return reply
    }

    /** Opens the pairing session. After this the code is shown on the TV. */
    fun start(clientName: String) {
        close()
        val s = tls.connect(host, port)
        socket = s
        s.soTimeout = 15000

        // PairingRequest (field 10): service_name=1, client_name=2
        exchange(s, wrap {
            it.message(10, ProtoWriter().string(1, "atvremote").string(2, clientName))
        })

        // PairingOption (field 20): input_encodings=1, preferred_role=3
        val encoding = ProtoWriter().varint(1, ENCODING_HEX).varint(2, 6)
        exchange(s, wrap {
            it.message(20, ProtoWriter().message(1, encoding).varint(3, ROLE_INPUT))
        })

        // PairingConfiguration (field 30): encoding=1, client_role=2
        val encoding2 = ProtoWriter().varint(1, ENCODING_HEX).varint(2, 6)
        exchange(s, wrap {
            it.message(30, ProtoWriter().message(1, encoding2).varint(2, ROLE_INPUT))
        })
        s.soTimeout = 0
    }

    /** Sends the code typed by the user. Throws if wrong. */
    fun finish(rawCode: String) {
        val s = socket ?: throw IOException("Primero inicia el emparejamiento")
        val code = rawCode.trim().uppercase().replace(" ", "")
        if (code.length != 6 || !code.all { it in "0123456789ABCDEF" }) {
            throw RetryableCodeException("El código debe tener 6 caracteres (0-9, A-F)")
        }
        val clientKey = tls.clientCertificate().publicKey as RSAPublicKey
        val serverCert = s.session.peerCertificates[0] as X509Certificate
        val serverKey = serverCert.publicKey as RSAPublicKey

        val md = MessageDigest.getInstance("SHA-256")
        md.update(unsigned(clientKey.modulus))
        md.update(unsigned(clientKey.publicExponent))
        md.update(unsigned(serverKey.modulus))
        md.update(unsigned(serverKey.publicExponent))
        md.update(hex(code.substring(2)))
        val secret = md.digest()

        val check = code.substring(0, 2).toInt(16)
        if ((secret[0].toInt() and 0xFF) != check) {
            throw RetryableCodeException("Código incorrecto, revisa lo que muestra el TV")
        }

        try {
            s.soTimeout = 15000
            // PairingSecret (field 40): secret=1
            exchange(s, wrap { it.message(40, ProtoWriter().bytes(1, secret)) })
        } finally {
            close()
        }
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun unsigned(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        var i = 0
        while (i < b.size - 1 && b[i].toInt() == 0) i++
        return b.copyOfRange(i, b.size)
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
