package com.jhony4lves.echo360.network.echolink

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

object EchoLinkProtocol {
    const val MAGIC: Int = 0x4543484F // ASCII: ECHO
    const val VERSION: Int = 1
    const val HEADER_BYTES: Int = 16
    const val DEFAULT_PORT: Int = 36_000
    const val MAX_CONTROL_PAYLOAD_BYTES: Int = 1024 * 1024

    enum class FrameType(val code: Int) {
        Ping(0x01),
        Pong(0x02),
        Error(0x7f),
        ;

        companion object {
            fun fromCode(code: Int): FrameType = entries.firstOrNull { it.code == code }
                ?: throw EchoLinkProtocolException("Tipo de frame EchoLink desconhecido: $code.")
        }
    }

    data class Frame(
        val type: FrameType,
        val requestId: Int,
        val payload: ByteArray = byteArrayOf(),
        val flags: Int = 0,
        val version: Int = VERSION,
    ) {
        init {
            require(flags in 0..0xffff) { "Flags EchoLink fora do intervalo de 16 bits." }
            require(payload.size <= MAX_CONTROL_PAYLOAD_BYTES) {
                "Payload EchoLink excede o limite de controle."
            }
        }
    }

    fun write(output: DataOutputStream, frame: Frame) {
        output.writeInt(MAGIC)
        output.writeByte(frame.version)
        output.writeByte(frame.type.code)
        output.writeShort(frame.flags)
        output.writeInt(frame.payload.size)
        output.writeInt(frame.requestId)
        output.write(frame.payload)
        output.flush()
    }

    fun read(input: DataInputStream): Frame {
        val magic = input.readInt()
        if (magic != MAGIC) {
            throw EchoLinkProtocolException("Magic EchoLink inválido.")
        }

        val version = input.readUnsignedByte()
        if (version != VERSION) {
            throw EchoLinkProtocolException(
                "Versão EchoLink incompatível: $version (esperada $VERSION).",
            )
        }

        val type = FrameType.fromCode(input.readUnsignedByte())
        val flags = input.readUnsignedShort()
        val payloadLength = input.readInt()
        if (payloadLength !in 0..MAX_CONTROL_PAYLOAD_BYTES) {
            throw EchoLinkProtocolException(
                "Payload EchoLink inválido: $payloadLength bytes.",
            )
        }

        val requestId = input.readInt()
        val payload = ByteArray(payloadLength)
        input.readFully(payload)

        return Frame(
            type = type,
            requestId = requestId,
            payload = payload,
            flags = flags,
            version = version,
        )
    }
}

class EchoLinkProtocolException(message: String) : IOException(message)
