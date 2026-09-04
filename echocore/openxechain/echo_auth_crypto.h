#ifndef ECHO_AUTH_CRYPTO_H
#define ECHO_AUTH_CRYPTO_H

#include <stddef.h>
#include <stdint.h>

#include "echo_auth_state.h"

#define ECHO_AUTH_DOMAIN_BYTES 16U
#define ECHO_AUTH_RESPONSE_TRANSCRIPT_BYTES 56U

static inline void echo_auth_write_be64(uint8_t out[8], uint64_t value) {
    out[0] = (uint8_t)(value >> 56U);
    out[1] = (uint8_t)(value >> 48U);
    out[2] = (uint8_t)(value >> 40U);
    out[3] = (uint8_t)(value >> 32U);
    out[4] = (uint8_t)(value >> 24U);
    out[5] = (uint8_t)(value >> 16U);
    out[6] = (uint8_t)(value >> 8U);
    out[7] = (uint8_t)value;
}

static inline uint64_t echo_auth_read_be64(const uint8_t in[8]) {
    return ((uint64_t)in[0] << 56U) |
           ((uint64_t)in[1] << 48U) |
           ((uint64_t)in[2] << 40U) |
           ((uint64_t)in[3] << 32U) |
           ((uint64_t)in[4] << 24U) |
           ((uint64_t)in[5] << 16U) |
           ((uint64_t)in[6] << 8U) |
           (uint64_t)in[7];
}

static inline int echo_auth_bytes_all_zero(const uint8_t *bytes, size_t length) {
    uint8_t any = 0U;
    size_t i;
    if (bytes == NULL) return 1;
    for (i = 0U; i < length; ++i) any = (uint8_t)(any | bytes[i]);
    return any == 0U ? 1 : 0;
}

static inline int echo_auth_constant_time_equal(
    const uint8_t *left,
    const uint8_t *right,
    size_t length
) {
    volatile uint8_t difference = 0U;
    size_t i;
    if (left == NULL || right == NULL) return 0;
    for (i = 0U; i < length; ++i) {
        difference = (uint8_t)(difference | (uint8_t)(left[i] ^ right[i]));
    }
    return difference == 0U ? 1 : 0;
}

static inline int echo_auth_make_response_transcript(
    uint8_t out[ECHO_AUTH_RESPONSE_TRANSCRIPT_BYTES],
    uint64_t session_id,
    const uint8_t challenge[ECHO_AUTH_CHALLENGE_BYTES],
    uint64_t counter,
    uint64_t requested_capabilities
) {
    static const uint8_t domain[ECHO_AUTH_DOMAIN_BYTES] = {
        'E','C','H','O','3','6','0','-','A','U','T','H','-','V','1','!'
    };
    size_t i;

    if (out == NULL || challenge == NULL || session_id == UINT64_C(0) ||
        counter == UINT64_C(0) ||
        (requested_capabilities & ~ECHO_AUTH_CAP_ALL) != UINT64_C(0)) {
        return -1;
    }

    for (i = 0U; i < ECHO_AUTH_DOMAIN_BYTES; ++i) out[i] = domain[i];
    echo_auth_write_be64(out + 16U, session_id);
    for (i = 0U; i < ECHO_AUTH_CHALLENGE_BYTES; ++i) out[24U + i] = challenge[i];
    echo_auth_write_be64(out + 40U, counter);
    echo_auth_write_be64(out + 48U, requested_capabilities);
    return 0;
}

#endif
