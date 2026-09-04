package com.jhony4lves.echo360.network.echolink

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object EchoPairingAuth {
    const val TOKEN_BYTES = 16
    const val TOKEN_BASE32_CHARS = 26
    const val CHALLENGE_BYTES = 16
    const val SECRET_BYTES = 32
    const val AUTH_MAC_BYTES = 20
    const val AUTH_REQUEST_BYTES = 36
    const val AUTH_RESPONSE_BYTES = 24

    const val CAP_PING: Long = 1L shl 0
    const val CAP_READ_INFO: Long = 1L shl 1
    const val CAP_READ_FILESYSTEM: Long = 1L shl 2
    const val READONLY_CAPABILITIES: Long = CAP_PING or CAP_READ_INFO or CAP_READ_FILESYSTEM

    private const val BASE32_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val pairingKdfDomain = "ECHO360-PAIRING-TOKEN-V1".toByteArray(Charsets.US_ASCII)
    private val authDomain = "ECHO360-AUTH-V1!".toByteArray(Charsets.US_ASCII)

    fun deriveSecret(displayToken: String): ByteArray {
        val token = decodeToken(displayToken)
        return try {
            MessageDigest.getInstance("SHA-256").run {
                update(pairingKdfDomain)
                digest(token)
            }
        } finally {
            token.fill(0)
        }
    }

    fun decodeToken(displayToken: String): ByteArray {
        val normalized = buildString(displayToken.length) {
            displayToken.forEach { character ->
                if (character != '-' && !character.isWhitespace()) {
                    append(character.uppercaseChar())
                }
            }
        }
        if (normalized.length != TOKEN_BASE32_CHARS) {
            throw EchoLinkPairingTokenException(
                "Token EchoCore inválido: esperado código de 26 caracteres.",
            )
        }

        val output = ByteArray(TOKEN_BYTES)
        var accumulator = 0
        var bits = 0
        var outputIndex = 0

        normalized.forEach { character ->
            val value = when (character) {
                'O' -> 0 // Crockford alias.
                'I', 'L' -> 1 // Crockford aliases.
                else -> BASE32_ALPHABET.indexOf(character)
            }
            if (value !in 0..31) {
                throw EchoLinkPairingTokenException(
                    "Token EchoCore contém caractere inválido: '$character'.",
                )
            }

            accumulator = (accumulator shl 5) or value
            bits += 5
            while (bits >= 8) {
                bits -= 8
                if (outputIndex >= output.size) {
                    throw EchoLinkPairingTokenException("Token EchoCore possui bits excedentes.")
                }
                output[outputIndex++] = ((accumulator shr bits) and 0xff).toByte()
                accumulator = if (bits == 0) 0 else accumulator and ((1 shl bits) - 1)
            }
        }

        if (outputIndex != TOKEN_BYTES || bits != 2 || accumulator != 0) {
            output.fill(0)
            throw EchoLinkPairingTokenException(
                "Token EchoCore possui padding Base32 inválido.",
            )
        }
        if (output.all { it == 0.toByte() }) {
            output.fill(0)
            throw EchoLinkPairingTokenException("Token EchoCore não pode ser zero.")
        }
        return output
    }

    fun makeAuthRequestPayload(
        secret: ByteArray,
        sessionId: Long,
        challenge: ByteArray,
        counter: Long,
        requestedCapabilities: Long = READONLY_CAPABILITIES,
    ): ByteArray {
        require(secret.size == SECRET_BYTES) { "Secret EchoCore deve ter 32 bytes." }
        require(sessionId != 0L) { "sessionId EchoCore não pode ser zero." }
        require(challenge.size == CHALLENGE_BYTES) { "Challenge EchoCore deve ter 16 bytes." }
        require(counter > 0L) { "Contador EchoCore deve ser positivo." }
        require((requestedCapabilities and READONLY_CAPABILITIES.inv()) == 0L) {
            "Capabilities EchoCore fora do contrato read-only v1."
        }

        val transcript = ByteBuffer.allocate(56)
            .order(ByteOrder.BIG_ENDIAN)
            .put(authDomain)
            .putLong(sessionId)
            .put(challenge)
            .putLong(counter)
            .putLong(requestedCapabilities)
            .array()

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val digest = mac.doFinal(transcript)
        transcript.fill(0)

        return ByteBuffer.allocate(AUTH_REQUEST_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(counter)
            .putLong(requestedCapabilities)
            .put(digest)
            .array()
            .also { digest.fill(0) }
    }
}

class EchoLinkPairingTokenException(message: String) : IOException(message)

class EchoLinkAuthenticationException(message: String) : IOException(message)
