package com.adam.webcam

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * Serveur TCP : un seul client à la fois (le plus récent remplace l'ancien).
 * Aucun buffer : si le client est trop lent, on jette les frames jusqu'à la prochaine keyframe.
 */
class StreamServer(
    private val encoder: H264Encoder,
    private val onControl: (Int) -> Unit,
    private val onClientChanged: (Boolean) -> Unit,
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    @Volatile private var current: Client? = null
    private val discovery = DiscoveryResponder()

    fun start() {
        running = true
        encoder.onPacket = ::onPacket
        Thread(::acceptLoop, "accept").start()
        discovery.start()
    }

    fun stop() {
        running = false
        discovery.stop()
        encoder.onPacket = null
        serverSocket?.close()
        current?.close()
    }

    private fun acceptLoop() {
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(Protocol.PORT))
            }
        } catch (_: IOException) {
            return
        }
        serverSocket = ss
        while (running) {
            val socket = try { ss.accept() } catch (_: IOException) { break }
            Thread({ handle(socket) }, "client").start()
        }
    }

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        val client = Client(socket)
        try {
            val input = DataInputStream(socket.getInputStream().buffered())
            val hello = Protocol.readPacket(input)
            if (hello.type != Protocol.T_HELLO || hello.payload.firstOrNull()?.toInt() != Protocol.VERSION) return
            // SPS/PPS d'abord, puis on attend une keyframe forcée avant d'envoyer des frames
            encoder.config?.let { client.enqueue(Protocol.pack(Protocol.T_CONFIG, 0, 0, it)) }
            current?.close()
            current = client
            client.start()
            encoder.requestKeyframe()
            onClientChanged(true)
            while (true) {
                val p = Protocol.readPacket(input)
                if (p.type != Protocol.T_CONTROL || p.payload.isEmpty()) continue
                when (val cmd = p.payload[0].toInt()) {
                    Protocol.C_KEYFRAME -> { client.waitKeyframe(); encoder.requestKeyframe() }
                    else -> onControl(cmd)
                }
            }
        } catch (_: IOException) {
        } finally {
            client.close()
            if (current === client) {
                current = null
                onClientChanged(false)
            }
        }
    }

    private fun onPacket(type: Int, keyframe: Boolean, ptsUs: Long, data: ByteArray) {
        val client = current ?: return
        val flags = if (keyframe) Protocol.FLAG_KEYFRAME else 0
        val packet = Protocol.pack(type, flags, ptsUs, data)
        if (type == Protocol.T_CONFIG) client.enqueue(packet) else client.offerFrame(packet, keyframe)
    }

    private inner class Client(private val socket: Socket) {
        private val queue = LinkedBlockingQueue<ByteArray>(8)
        @Volatile private var waitingKey = true
        @Volatile private var closed = false

        fun start() {
            Thread({
                try {
                    val out = socket.getOutputStream().buffered()
                    while (!closed) {
                        out.write(queue.take())
                        if (queue.isEmpty()) out.flush()
                    }
                } catch (_: IOException) {
                } catch (_: InterruptedException) {
                } finally {
                    close()
                }
            }, "sender").start()
        }

        fun enqueue(packet: ByteArray) { queue.offer(packet) }

        fun waitKeyframe() { waitingKey = true }

        fun offerFrame(packet: ByteArray, keyframe: Boolean) {
            if (waitingKey) {
                if (!keyframe) return
                waitingKey = false
            }
            if (!queue.offer(packet)) {
                queue.clear()
                waitingKey = true
                encoder.requestKeyframe()
            }
        }

        fun close() {
            closed = true
            try { socket.close() } catch (_: IOException) {}
        }
    }
}
