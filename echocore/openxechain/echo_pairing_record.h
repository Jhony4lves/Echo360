#ifndef ECHO_PAIRING_RECORD_H
#define ECHO_PAIRING_RECORD_H

#include <stddef.h>
#include <stdint.h>

#include "echo_auth_crypto.h"

#define ECHO_PAIRING_RECORD_VERSION 1U
#define ECHO_PAIRING_RECORD_BYTES 72U
#define ECHO_PAIRING_DIGEST_BYTES 32U
#define ECHO_PAIRING_RECORD_PREFIX_BYTES 40U

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
 * Pairing record v1 (72 bytes):
 *   0..3   ASCII "ECPR"
 *   4..5   BE version = 1
 *   6..7   reserved = 0
 *   8..39  random 256-bit pairing secret
 *   40..71 SHA-256("ECHO360-PAIRING-RECORD-V1" || bytes[0..39])
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
    out[5] = ECHO_PAIRING_RECORD_VERSION;
    out[6] = 0U;
    out[7] = 0U;
    for (i = 0U; i < ECHO_AUTH_SECRET_BYTES; ++i) out[8U + i] = secret[i];
}

static inline int echo_pairing_record_validate_prefix(
    const uint8_t record[ECHO_PAIRING_RECORD_BYTES]
) {
    if (record == NULL) return -1;
    if (record[0] != 'E' || record[1] != 'C' || record[2] != 'P' || record[3] != 'R') return -2;
    if (record[4] != 0U || record[5] != ECHO_PAIRING_RECORD_VERSION ||
        record[6] != 0U || record[7] != 0U) return -3;
    if (echo_pairing_record_secret_is_zero(record + 8U)) return -4;
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
