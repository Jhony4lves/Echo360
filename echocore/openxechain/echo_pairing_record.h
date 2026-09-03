#ifndef ECHO_PAIRING_RECORD_H
#define ECHO_PAIRING_RECORD_H

#include <stddef.h>
#include <stdint.h>

#include "echo_auth_crypto.h"
#include "echo_pairing_token.h"

#define ECHO_PAIRING_RECORD_VERSION_SECRET 1U
#define ECHO_PAIRING_RECORD_VERSION_TOKEN 2U
#define ECHO_PAIRING_RECORD_BYTES 72U
#define ECHO_PAIRING_DIGEST_BYTES 32U
#define ECHO_PAIRING_RECORD_PREFIX_BYTES 40U
#define ECHO_PAIRING_RECORD_PAYLOAD_OFFSET 8U
#define ECHO_PAIRING_RECORD_TOKEN_RESERVED_OFFSET \
    (ECHO_PAIRING_RECORD_PAYLOAD_OFFSET + ECHO_PAIRING_TOKEN_BYTES)
#define ECHO_PAIRING_RECORD_TOKEN_RESERVED_BYTES \
    (ECHO_PAIRING_RECORD_PREFIX_BYTES - ECHO_PAIRING_RECORD_TOKEN_RESERVED_OFFSET)

static inline int echo_pairing_record_secret_is_zero(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES]
) {
    uint8_t any = 0U;
    uint32_t i;
    if (secret == NULL) return 1;
    for (i = 0U; i < ECHO_AUTH_SECRET_BYTES; ++i) any = (uint8_t)(any | secret[i]);
    return any == 0U ? 1 : 0;
}

/*
 * Pairing record v1 (legacy, 72 bytes):
 *   0..3   ASCII "ECPR"
 *   4..5   BE version = 1
 *   6..7   reserved = 0
 *   8..39  random/derived 256-bit pairing secret
 *   40..71 SHA-256("ECHO360-PAIRING-RECORD-V1" || bytes[0..39])
 *
 * Pairing record v2 (recoverable, same 72-byte envelope):
 *   0..3   ASCII "ECPR"
 *   4..5   BE version = 2
 *   6..7   reserved = 0
 *   8..23  128-bit physical pairing token
 *   24..39 reserved = 0
 *   40..71 SHA-256("ECHO360-PAIRING-RECORD-V2" || bytes[0..39])
 *
 * v2 intentionally persists the token instead of the derived secret so the
 * physically launched pairing XEX can re-display the same code later. This
 * does not widen the local-filesystem threat boundary: v1 already persisted
 * the LAN authentication secret in plaintext. The resident service derives
 * the same 256-bit secret from the token at load time.
 *
 * The digest detects corruption, not a filesystem attacker: anyone who can
 * read/write Hdd1 can replace this record. We intentionally do not derive or
 * encrypt it using CPU/DVD keys. Physical/filesystem compromise is outside the
 * LAN pairing threat boundary.
 */
static inline void echo_pairing_record_make_prefix(
    uint8_t out[ECHO_PAIRING_RECORD_PREFIX_BYTES],
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES]
) {
    uint32_t i;
    out[0] = 'E';
    out[1] = 'C';
    out[2] = 'P';
    out[3] = 'R';
    out[4] = 0U;
    out[5] = ECHO_PAIRING_RECORD_VERSION_SECRET;
    out[6] = 0U;
    out[7] = 0U;
    for (i = 0U; i < ECHO_AUTH_SECRET_BYTES; ++i) {
        out[ECHO_PAIRING_RECORD_PAYLOAD_OFFSET + i] = secret[i];
    }
}

static inline void echo_pairing_record_make_token_prefix(
    uint8_t out[ECHO_PAIRING_RECORD_PREFIX_BYTES],
    const uint8_t token[ECHO_PAIRING_TOKEN_BYTES]
) {
    uint32_t i;
    out[0] = 'E';
    out[1] = 'C';
    out[2] = 'P';
    out[3] = 'R';
    out[4] = 0U;
    out[5] = ECHO_PAIRING_RECORD_VERSION_TOKEN;
    out[6] = 0U;
    out[7] = 0U;
    for (i = 0U; i < ECHO_PAIRING_TOKEN_BYTES; ++i) {
        out[ECHO_PAIRING_RECORD_PAYLOAD_OFFSET + i] = token[i];
    }
    for (i = 0U; i < ECHO_PAIRING_RECORD_TOKEN_RESERVED_BYTES; ++i) {
        out[ECHO_PAIRING_RECORD_TOKEN_RESERVED_OFFSET + i] = 0U;
    }
}

static inline int echo_pairing_record_validate_header(
    const uint8_t record[ECHO_PAIRING_RECORD_BYTES],
    uint8_t expected_version
) {
    if (record == NULL) return -1;
    if (record[0] != 'E' || record[1] != 'C' || record[2] != 'P' || record[3] != 'R') return -2;
    if (record[4] != 0U || record[5] != expected_version ||
        record[6] != 0U || record[7] != 0U) return -3;
    return 0;
}

static inline int echo_pairing_record_validate_prefix(
    const uint8_t record[ECHO_PAIRING_RECORD_BYTES]
) {
    int header = echo_pairing_record_validate_header(record, ECHO_PAIRING_RECORD_VERSION_SECRET);
    if (header != 0) return header;
    if (echo_pairing_record_secret_is_zero(record + ECHO_PAIRING_RECORD_PAYLOAD_OFFSET)) return -4;
    return 0;
}

static inline int echo_pairing_record_validate_token_prefix(
    const uint8_t record[ECHO_PAIRING_RECORD_BYTES]
) {
    uint32_t i;
    int header = echo_pairing_record_validate_header(record, ECHO_PAIRING_RECORD_VERSION_TOKEN);
    if (header != 0) return header;
    if (echo_pairing_token_is_zero(record + ECHO_PAIRING_RECORD_PAYLOAD_OFFSET)) return -4;
    for (i = 0U; i < ECHO_PAIRING_RECORD_TOKEN_RESERVED_BYTES; ++i) {
        if (record[ECHO_PAIRING_RECORD_TOKEN_RESERVED_OFFSET + i] != 0U) return -5;
    }
    return 0;
}

static inline int echo_pairing_record_digest_equal(
    const uint8_t left[ECHO_PAIRING_DIGEST_BYTES],
    const uint8_t right[ECHO_PAIRING_DIGEST_BYTES]
) {
    volatile uint8_t difference = 0U;
    uint32_t i;
    if (left == NULL || right == NULL) return 0;
    for (i = 0U; i < ECHO_PAIRING_DIGEST_BYTES; ++i) {
        difference = (uint8_t)(difference | (uint8_t)(left[i] ^ right[i]));
    }
    return difference == 0U ? 1 : 0;
}

#endif
