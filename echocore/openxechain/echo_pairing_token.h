#ifndef ECHO_PAIRING_TOKEN_H
#define ECHO_PAIRING_TOKEN_H

#include <stddef.h>
#include <stdint.h>

#define ECHO_PAIRING_TOKEN_BYTES 16U
#define ECHO_PAIRING_TOKEN_BASE32_CHARS 26U
#define ECHO_PAIRING_TOKEN_DISPLAY_CHARS 30U
#define ECHO_PAIRING_TOKEN_DISPLAY_CAPACITY 31U

/* Crockford Base32: excludes I, L, O and U to reduce transcription mistakes. */
static const char g_echo_pairing_base32_alphabet[33] =
    "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

static inline int echo_pairing_token_is_zero(
    const uint8_t token[ECHO_PAIRING_TOKEN_BYTES]
) {
    uint8_t any = 0U;
    uint32_t i;
    if (token == NULL) return 1;
    for (i = 0U; i < ECHO_PAIRING_TOKEN_BYTES; ++i) any = (uint8_t)(any | token[i]);
    return any == 0U ? 1 : 0;
}

static inline int echo_pairing_token_encode_base32(
    const uint8_t token[ECHO_PAIRING_TOKEN_BYTES],
    char out[ECHO_PAIRING_TOKEN_BASE32_CHARS + 1U]
) {
    uint32_t accumulator = 0U;
    uint32_t bits = 0U;
    uint32_t input = 0U;
    uint32_t output = 0U;

    if (token == NULL || out == NULL || echo_pairing_token_is_zero(token)) return -1;

    while (input < ECHO_PAIRING_TOKEN_BYTES) {
        accumulator = (accumulator << 8U) | token[input++];
        bits += 8U;
        while (bits >= 5U) {
            bits -= 5U;
            out[output++] = g_echo_pairing_base32_alphabet[(accumulator >> bits) & 31U];
            if (bits == 0U) accumulator = 0U;
            else accumulator &= (UINT32_C(1) << bits) - 1U;
        }
    }
    if (bits != 0U) {
        out[output++] = g_echo_pairing_base32_alphabet[(accumulator << (5U - bits)) & 31U];
    }
    if (output != ECHO_PAIRING_TOKEN_BASE32_CHARS) return -2;
    out[output] = '\0';
    return 0;
}

/* Human display: XXXXX-XXXXX-XXXXX-XXXXX-XXXXXX */
static inline int echo_pairing_token_format_display(
    const uint8_t token[ECHO_PAIRING_TOKEN_BYTES],
    char out[ECHO_PAIRING_TOKEN_DISPLAY_CAPACITY]
) {
    char encoded[ECHO_PAIRING_TOKEN_BASE32_CHARS + 1U];
    uint32_t src = 0U;
    uint32_t dst = 0U;
    uint32_t group;

    if (out == NULL || echo_pairing_token_encode_base32(token, encoded) != 0) return -1;
    for (group = 0U; group < 5U; ++group) {
        uint32_t count = group == 4U ? 6U : 5U;
        uint32_t i;
        if (group != 0U) out[dst++] = '-';
        for (i = 0U; i < count; ++i) out[dst++] = encoded[src++];
    }
    if (src != ECHO_PAIRING_TOKEN_BASE32_CHARS || dst != ECHO_PAIRING_TOKEN_DISPLAY_CHARS) return -2;
    out[dst] = '\0';
    return 0;
}

#endif
