package com.adam.webcam

import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer

/** Format des paquets, voir docs/protocol.md. */
object Protocol {
    const val PORT = 27183
    const val DISCOVERY_PORT = 27184
    const val VERSION = 1
    private const val MAGIC = 0xCAFE
    private const val MAX_PAYLOAD = 1 shl 20

    const val T_HELLO = 0
    const val T_CONFIG = 1
    const val T_FRAME = 2
    const val T_CONTROL = 3

    const val FLAG_KEYFRAME = 1

    const val C_KEYFRAME = 1
    const val C_SWITCH_CAMERA = 2

    class Packet(val type: Int, val flags: Int, val timestampUs: Long, val payload: ByteArray)

    fun pack(type: Int, flags: Int, timestampUs: Long, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(16 + payload.size).apply {
            putShort(MAGIC.toShort())
            put(type.toByte())
            put(flags.toByte())
            putLong(timestampUs)
            putInt(payload.size)
            put(payload)
        }.array()

    fun readPacket(input: DataInputStream): Packet {
        if (input.readUnsignedShort() != MAGIC) throw IOException("désynchronisation : magic invalide")
        val type = input.readUnsignedByte()
        val flags = input.readUnsignedByte()
        val ts = input.readLong()
        val size = input.readInt()
        if (size < 0 || size > MAX_PAYLOAD) throw IOException("taille de paquet invalide : $size")
        val payload = ByteArray(size)
        input.readFully(payload)
        return Packet(type, flags, ts, payload)
    }
}
