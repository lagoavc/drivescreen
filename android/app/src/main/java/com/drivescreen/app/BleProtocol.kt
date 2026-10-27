package com.drivescreen.app

import android.os.SystemClock
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.ceil

internal object BleProtocol {
    val serviceUuid: UUID = UUID.fromString("4a9f5d80-6b13-4c28-9e40-7f6d2b830000")
    val rxUuid: UUID = UUID.fromString("4a9f5d80-6b13-4c28-9e40-7f6d2b830001")
    val txUuid: UUID = UUID.fromString("4a9f5d80-6b13-4c28-9e40-7f6d2b830002")

    const val protocolVersion = "1.1.0"
    const val maxMessageSize = 2048
    const val maxMediaCoverSize = 1024
    private const val headerSize = 8
    private const val coverHeaderSize = 10
    private const val maxFrameSize = 512
    private const val magic0 = 0xD5
    private const val magic1 = 0x53
    private const val coverMagic1 = 0x43

    fun encode(
        message: JSONObject,
        frameId: Int,
        mtu: Int
    ): List<ByteArray> {
        val json = message.toString().toByteArray(StandardCharsets.UTF_8)
        require(json.isNotEmpty() && json.size <= maxMessageSize) {
            "Mensagem JSON excede o limite de $maxMessageSize bytes."
        }

        val effectiveFrameSize = minOf(mtu - 3, maxFrameSize)
        val payloadLimit = effectiveFrameSize - headerSize
        require(payloadLimit > 0) { "MTU BLE insuficiente para o cabeçalho DriveScreen." }

        val fragmentCount = ceil(json.size.toDouble() / payloadLimit).toInt()
        require(fragmentCount in 1..255) { "Mensagem exige demasiados fragmentos." }

        return (0 until fragmentCount).map { index ->
            val offset = index * payloadLimit
            val length = minOf(payloadLimit, json.size - offset)
            ByteBuffer.allocate(headerSize + length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(magic0.toByte())
                .put(magic1.toByte())
                .putShort(frameId.toShort())
                .put(index.toByte())
                .put(fragmentCount.toByte())
                .putShort(json.size.toShort())
                .put(json, offset, length)
                .array()
        }
    }

    fun encodeMediaCover(
        jpeg: ByteArray,
        width: Int,
        height: Int,
        frameId: Int,
        mtu: Int
    ): List<ByteArray> {
        require(width == height && (width == 48 || width == 96)) {
            "A capa deve ser quadrada e ter 48 ou 96 píxeis."
        }
        require(
            jpeg.size in 4..maxMediaCoverSize &&
                jpeg[0] == 0xFF.toByte() &&
                jpeg[1] == 0xD8.toByte() &&
                jpeg[jpeg.lastIndex - 1] == 0xFF.toByte() &&
                jpeg[jpeg.lastIndex] == 0xD9.toByte()
        ) {
            "A capa JPEG está vazia, inválida ou excede o limite."
        }

        val effectiveFrameSize = minOf(mtu - 3, maxFrameSize)
        val payloadLimit = effectiveFrameSize - coverHeaderSize
        require(payloadLimit > 0) { "MTU BLE insuficiente para o cabeçalho da capa." }
        val fragmentCount = ceil(jpeg.size.toDouble() / payloadLimit).toInt()
        require(fragmentCount in 1..255) { "A capa exige demasiados fragmentos." }

        return (0 until fragmentCount).map { index ->
            val offset = index * payloadLimit
            val length = minOf(payloadLimit, jpeg.size - offset)
            ByteBuffer.allocate(coverHeaderSize + length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(magic0.toByte())
                .put(coverMagic1.toByte())
                .putShort(frameId.toShort())
                .put(index.toByte())
                .put(fragmentCount.toByte())
                .putShort(jpeg.size.toShort())
                .put(width.toByte())
                .put(height.toByte())
                .put(jpeg, offset, length)
                .array()
        }
    }

    class Assembler {
        private var frameId = -1
        private var expectedLength = 0
        private var fragmentCount = 0
        private var nextFragment = 0
        private var startedAt = -1L
        private var data = ByteArray(0)
        private var received = 0

        fun accept(frame: ByteArray, now: Long = SystemClock.elapsedRealtime()): String? {
            if (frame.size !in (headerSize + 1)..maxFrameSize ||
                (frame[0].toInt() and 0xFF) != magic0 ||
                (frame[1].toInt() and 0xFF) != magic1
            ) {
                reset()
                throw IllegalArgumentException("Cabeçalho ou tamanho de fragmento inválido.")
            }

            val header = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            header.position(2)
            val incomingId = header.short.toInt() and 0xFFFF
            val index = header.get().toInt() and 0xFF
            val count = header.get().toInt() and 0xFF
            val totalLength = header.short.toInt() and 0xFFFF
            val payloadLength = frame.size - headerSize
            if (count == 0 || index >= count || totalLength !in 1..maxMessageSize) {
                reset()
                throw IllegalArgumentException("Metadados de fragmento inválidos.")
            }

            if (index == 0) {
                reset()
                frameId = incomingId
                expectedLength = totalLength
                fragmentCount = count
                startedAt = now
                data = ByteArray(totalLength)
            } else if (
                startedAt < 0L ||
                now - startedAt > 5_000 ||
                incomingId != frameId ||
                totalLength != expectedLength ||
                count != fragmentCount ||
                index != nextFragment
            ) {
                reset()
                throw IllegalArgumentException("Sequência de fragmentos inválida ou expirada.")
            }

            if (index != nextFragment || payloadLength > expectedLength - received) {
                reset()
                throw IllegalArgumentException("Fragmento fora dos limites declarados.")
            }
            frame.copyInto(data, received, headerSize, frame.size)
            received += payloadLength
            nextFragment++

            if (nextFragment != fragmentCount) return null
            if (received != expectedLength) {
                reset()
                throw IllegalArgumentException("Comprimento da mensagem não corresponde.")
            }

            val complete = data
            reset()
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(complete))
                .toString()
        }

        fun reset() {
            frameId = -1
            expectedLength = 0
            fragmentCount = 0
            nextFragment = 0
            startedAt = -1L
            data = ByteArray(0)
            received = 0
        }
    }

    fun message(type: String, id: Int, payload: JSONObject? = null): JSONObject =
        JSONObject()
            .put("v", protocolVersion)
            .put("type", type)
            .put("id", id and 0xFFFF)
            .apply {
                if (payload != null) put("payload", payload)
            }
}
