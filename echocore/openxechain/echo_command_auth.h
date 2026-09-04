#ifndef ECHO_COMMAND_AUTH_H
#define ECHO_COMMAND_AUTH_H

#include <stddef.h>
#include <stdint.h>

#include "echo_auth_state.h"
#include "echo_protocol.h"

#define ECHO_COMMAND_AUTH_COUNTER_BYTES 8U
#define ECHO_COMMAND_AUTH_MAC_BYTES ECHO_AUTH_HMAC_SHA1_BYTES
#define ECHO_COMMAND_AUTH_PREFIX_BYTES \
    (ECHO_COMMAND_AUTH_COUNTER_BYTES + ECHO_COMMAND_AUTH_MAC_BYTES)
#define ECHO_COMMAND_AUTH_DOMAIN_BYTES 16U

/*
 * Authenticated request payload:
 *   0..7   monotonic counter, big-endian
 *   8..27  HMAC-SHA1
 *   28..   command body
 *
 * MAC transcript, fed incrementally so no body-sized copy is needed:
 *   "ECHO360-CMD-V1!!" (16 bytes)
 *   session_id          (8 bytes, big-endian)
 *   counter             (8 bytes, big-endian)
 *   request metadata    (8 bytes): type, flags(2), body_length(4), reserved(1)
 *   request_id          (4 bytes, big-endian)
 *   body                (body_length bytes)
 *
 * Magic/version are validated by the ordinary EchoLink parser before this
 * layer. Counter + MAC are deliberately excluded from body_length.
 */
#define ECHO_COMMAND_AUTH_META_BYTES 12U

static inline void echo_command_auth_write_be64(uint8_t out[8], uint64_t value) {
    out[0] = (uint8_t)(value >> 56U);
    out[1] = (uint8_t)(value >> 48U);
    out[2] = (uint8_t)(value >> 40U);
    out[3] = (uint8_t)(value >> 32U);
    out[4] = (uint8_t)(value >> 24U);
    out[5] = (uint8_t)(value >> 16U);
    out[6] = (uint8_t)(value >> 8U);
    out[7] = (uint8_t)value;
}

static inline uint64_t echo_command_auth_read_be64(const uint8_t in[8]) {
    return ((uint64_t)in[0] << 56U) |
           ((uint64_t)in[1] << 48U) |
           ((uint64_t)in[2] << 40U) |
           ((uint64_t)in[3] << 32U) |
           ((uint64_t)in[4] << 24U) |
           ((uint64_t)in[5] << 16U) |
           ((uint64_t)in[6] << 8U) |
           (uint64_t)in[7];
}

static inline void echo_command_auth_write_be32(uint8_t out[4], uint32_t value) {
    out[0] = (uint8_t)(value >> 24U);
    out[1] = (uint8_t)(value >> 16U);
    out[2] = (uint8_t)(value >> 8U);
    out[3] = (uint8_t)value;
}

static inline int echo_command_auth_make_meta(
    uint8_t out[ECHO_COMMAND_AUTH_META_BYTES],
    uint8_t type,
    uint16_t flags,
    uint32_t body_length,
    uint32_t request_id
) {
    if (out == NULL || flags != 0U) return -1;
    out[0] = type;
    out[1] = (uint8_t)(flags >> 8U);
    out[2] = (uint8_t)flags;
    out[3] = 0U;
    echo_command_auth_write_be32(out + 4U, body_length);
    echo_command_auth_write_be32(out + 8U, request_id);
    return 0;
}

static inline int echo_command_auth_parse_envelope(
    const uint8_t *payload,
    uint32_t payload_length,
    uint64_t *counter_out,
    const uint8_t **mac_out,
    const uint8_t **body_out,
    uint32_t *body_length_out
) {
    if (payload == NULL || counter_out == NULL || mac_out == NULL ||
        body_out == NULL || body_length_out == NULL ||
        payload_length < ECHO_COMMAND_AUTH_PREFIX_BYTES) {
        return -1;
    }

    *counter_out = echo_command_auth_read_be64(payload);
    if (*counter_out == UINT64_C(0)) return -2;
    *mac_out = payload + ECHO_COMMAND_AUTH_COUNTER_BYTES;
    *body_out = payload + ECHO_COMMAND_AUTH_PREFIX_BYTES;
    *body_length_out = payload_length - ECHO_COMMAND_AUTH_PREFIX_BYTES;
    return 0;
}

#endif
