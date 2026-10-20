package com.adam.webcam

import android.os.Build
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Répond aux requêtes de découverte du PC (UDP broadcast "WEBCAM?") avec "WEBCAM!<version>|<modèle>".
 * Le PC en déduit l'IP du téléphone (adresse source de la réponse) ; le flux reste en TCP.
 */
class DiscoveryResponder {
    @Volatile private var socket: DatagramSocket? = null

    fun start() {
        Thread(::loop, "discovery").start()
    }

    fun stop() {
        socket?.close()
    }

    private fun loop() {
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
            }
        } catch (_: IOException) {
            return
        }
        socket = s
        val reply = "WEBCAM!${Protocol.VERSION}|${Build.MODEL}".toByteArray()
        val buf = ByteArray(64)
        while (!s.isClosed) {
            val request = DatagramPacket(buf, buf.size)
            try { s.receive(request) } catch (_: IOException) { break }
            if (String(request.data, 0, request.length) == "WEBCAM?") {
                try { s.send(DatagramPacket(reply, reply.size, request.address, request.port)) } catch (_: IOException) {}
            }
        }
    }
}
