#ifndef ECHO_PROTOCOL_H
#define ECHO_PROTOCOL_H

#include <stdint.h>

#define ECHO_MAGIC_0 0x45U /* E */
#define ECHO_MAGIC_1 0x43U /* C */
#define ECHO_MAGIC_2 0x48U /* H */
#define ECHO_MAGIC_3 0x4FU /* O */
#define ECHO_VERSION 1U
#define ECHO_TYPE_PING 0x01U
#define ECHO_TYPE_PONG 0x02U
#define ECHO_TYPE_ERROR 0x7FU
#define ECHO_HEADER_BYTES 16U
#define ECHO_PING_PAYLOAD_BYTES 8U

static inline uint16_t echo_read_be16(const uint8_t *bytes) {
    return (uint16_t)(((uint16_t)bytes[0] << 8U) | (uint16_t)bytes[1]);
}

static inline uint32_t echo_read_be32(const uint8_t *bytes) {
    return ((uint32_t)bytes[0] << 24U) |
           ((uint32_t)bytes[1] << 16U) |
           ((uint32_t)bytes[2] << 8U) |
           (uint32_t)bytes[3];
}

static inline void echo_write_be32(uint8_t *bytes, uint32_t value) {
    bytes[0] = (uint8_t)(value >> 24U);
    bytes[1] = (uint8_t)(value >> 16U);
    bytes[2] = (uint8_t)(value >> 8U);
    bytes[3] = (uint8_t)value;
}

/*
 * Bootstrap PING is deliberately strict. Reserved flags must remain zero and
 * the payload is exactly one opaque 64-bit nonce. This keeps the first Xbox
 * parser tiny and makes future protocol growth explicit instead of accidental.
 */
static inline int echo_validate_ping_header(const uint8_t header[ECHO_HEADER_BYTES]) {
    if (header[0] != ECHO_MAGIC_0 ||
        header[1] != ECHO_MAGIC_1 ||
        header[2] != ECHO_MAGIC_2 ||
        header[3] != ECHO_MAGIC_3) {
        return -1;
    }
    if (header[4] != ECHO_VERSION || header[5] != ECHO_TYPE_PING) {
        return -2;
    }
    if (echo_read_be16(header + 6U) != 0U) {
        return -3;
    }
    if (echo_read_be32(header + 8U) != ECHO_PING_PAYLOAD_BYTES) {
        return -4;
    }
    return 0;
}

static inline void echo_make_pong_header(uint8_t header[ECHO_HEADER_BYTES]) {
    header[5] = ECHO_TYPE_PONG;
}

#endif
