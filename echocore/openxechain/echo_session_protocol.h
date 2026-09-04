#ifndef ECHO_SESSION_PROTOCOL_H
#define ECHO_SESSION_PROTOCOL_H

#include <stddef.h>
#include <stdint.h>

#include "echo_auth_crypto.h"
#include "echo_auth_state.h"
#include "echo_protocol.h"

/* Paired-session control plane. Read-only commands start at 0x10. */
#define ECHO_TYPE_SESSION_BEGIN_REQUEST       0x08U
#define ECHO_TYPE_SESSION_CHALLENGE_RESPONSE  0x09U
#define ECHO_TYPE_SESSION_AUTH_REQUEST        0x0AU
#define ECHO_TYPE_SESSION_AUTH_RESPONSE       0x0BU

#define ECHO_SESSION_CHALLENGE_BYTES (8U + ECHO_AUTH_CHALLENGE_BYTES)
#define ECHO_SESSION_AUTH_REQUEST_BYTES (8U + 8U + ECHO_AUTH_HMAC_SHA1_BYTES)
#define ECHO_SESSION_AUTH_RESPONSE_BYTES 24U

#define ECHO_SESSION_STATUS_OK 0U
#define ECHO_SESSION_STATUS_DENIED 1U
#define ECHO_SESSION_STATUS_PROTOCOL_ERROR 2U

/* Current resident v1 server intentionally grants read-only authority only. */
#define ECHO_AUTH_CAP_READONLY_SERVER \
    (ECHO_AUTH_CAP_PING | ECHO_AUTH_CAP_READ_INFO | ECHO_AUTH_CAP_READ_FILESYSTEM)

static inline uint64_t echo_session_read_be64(const uint8_t *p) {
    return ((uint64_t)echo_read_be32(p) << 32U) |
           (uint64_t)echo_read_be32(p + 4U);
}

static inline void echo_session_write_be64(uint8_t *p, uint64_t value) {
    echo_write_be32(p, (uint32_t)(value >> 32U));
    echo_write_be32(p + 4U, (uint32_t)value);
}

/* SESSION_BEGIN has no payload/flags. */
static inline int echo_session_validate_begin(
    const echo_frame_header *header
) {
    if (header == NULL) return -1;
    if (header->type != ECHO_TYPE_SESSION_BEGIN_REQUEST ||
        header->flags != 0U || header->payload_length != 0U) {
        return -1;
    }
    return 0;
}

/* Challenge payload: BE session_id + raw 16-byte challenge. */
static inline int echo_session_make_challenge_payload(
    const echo_auth_state *state,
    uint8_t out[ECHO_SESSION_CHALLENGE_BYTES]
) {
    size_t i;
    if (state == NULL || out == NULL || state->session_id == UINT64_C(0) ||
        state->challenge_active == 0U || state->authenticated != 0U) {
        return -1;
    }
    echo_session_write_be64(out, state->session_id);
    for (i = 0U; i < ECHO_AUTH_CHALLENGE_BYTES; ++i) {
        out[8U + i] = state->challenge[i];
    }
    return 0;
}

/*
 * AUTH request payload:
 *   0..7   BE counter (must be >0)
 *   8..15  BE requested ECHO_AUTH_CAP_* bits
 *   16..35 HMAC-SHA1 over ECHO360-AUTH-V1! transcript
 */
static inline int echo_session_parse_auth_request(
    const echo_frame_header *header,
    const uint8_t *payload,
    uint64_t *counter_out,
    uint64_t *capabilities_out,
    const uint8_t **mac_out
) {
    uint64_t counter;
    uint64_t capabilities;

    if (header == NULL || payload == NULL || counter_out == NULL ||
        capabilities_out == NULL || mac_out == NULL) {
        return -1;
    }
    if (header->type != ECHO_TYPE_SESSION_AUTH_REQUEST || header->flags != 0U ||
        header->payload_length != ECHO_SESSION_AUTH_REQUEST_BYTES) {
        return -1;
    }

    counter = echo_session_read_be64(payload);
    capabilities = echo_session_read_be64(payload + 8U);
    if (counter == UINT64_C(0) ||
        (capabilities & ~ECHO_AUTH_CAP_READONLY_SERVER) != UINT64_C(0)) {
        return -2;
    }

    *counter_out = counter;
    *capabilities_out = capabilities;
    *mac_out = payload + 16U;
    return 0;
}

/*
 * AUTH response payload:
 *   0      status
 *   1..7   reserved = 0
 *   8..15  BE granted ECHO_AUTH_CAP_* bits
 *   16..23 BE committed counter
 */
static inline void echo_session_make_auth_response(
    uint8_t out[ECHO_SESSION_AUTH_RESPONSE_BYTES],
    uint8_t status,
    uint64_t granted_capabilities,
    uint64_t committed_counter
) {
    uint32_t i;
    out[0] = status;
    for (i = 1U; i < 8U; ++i) out[i] = 0U;
    echo_session_write_be64(out + 8U, granted_capabilities);
    echo_session_write_be64(out + 16U, committed_counter);
}

#endif
