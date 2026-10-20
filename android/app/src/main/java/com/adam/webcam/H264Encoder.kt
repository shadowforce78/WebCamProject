package com.adam.webcam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface

/** Encodeur H.264 matériel alimenté par une Surface ; sort des NAL en Annex B. */
class H264Encoder(
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface

    /** Appelé depuis le thread de l'encodeur : (type de paquet, keyframe, pts en µs, données). */
    @Volatile var onPacket: ((Int, Boolean, Long, ByteArray) -> Unit)? = null

    /** SPS/PPS, disponible dès que l'encodeur a démarré. */
    @Volatile var config: ByteArray? = null
        private set

    private var thread: Thread? = null

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            // Pas de B-frames : profil baseline
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start() {
        codec.start()
        thread = Thread(::drain, "encoder-drain").also { it.start() }
    }

    fun requestKeyframe() {
        try {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: IllegalStateException) {
        }
    }

    fun stop() {
        onPacket = null
        try { codec.signalEndOfInputStream() } catch (_: IllegalStateException) {}
        thread?.join(1000)
        codec.stop()
        codec.release()
        inputSurface.release()
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = try { codec.dequeueOutputBuffer(info, 100_000) } catch (_: IllegalStateException) { return }
            if (index < 0) continue
            val buf = codec.getOutputBuffer(index)
            if (buf != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(bytes)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = bytes
                    onPacket?.invoke(Protocol.T_CONFIG, false, info.presentationTimeUs, bytes)
                } else {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    onPacket?.invoke(Protocol.T_FRAME, key, info.presentationTimeUs, bytes)
                }
            }
            codec.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }
}
