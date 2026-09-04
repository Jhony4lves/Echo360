#ifndef ECHO_PROTOCOL_H
#define ECHO_PROTOCOL_H

#include <stddef.h>
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

/*
 * Hard resident-memory/frame ceiling for EchoLink v1. 96 KiB comfortably fits
 * authenticated 64 KiB transfer chunks and the current 68,356-byte maximum
 * DIR_LIST response while preventing a peer from forcing unbounded allocation.
 */
#define ECHO_FRAME_MAX_PAYLOAD_BYTES (96U * 1024U)

#define ECHO_FRAME_OK 0
#define ECHO_FRAME_BAD_MAGIC -1
#define ECHO_FRAME_BAD_VERSION -2
#define ECHO_FRAME_TOO_LARGE -3
#define ECHO_FRAME_INVALID_ARGUMENT -4

typedef struct echo_frame_header {
    uint8_t type;
    uint16_t flags;
    uint32_t payload_length;
    uint32_t request_id;
} echo_frame_header;

static inline uint16_t echo_read_be16(const uint8_t *bytes) {
    return (uint16_t)(((uint16_t)bytes[0] << 8U) | (uint16_t)bytes[1]);
}

static inline uint32_t echo_read_be32(const uint8_t *bytes) {
    return ((uint32_t)bytes[0] << 24U) |
           ((uint32_t)bytes[1] << 16U) |
           ((uint32_t)bytes[2] << 8U) |
           (uint32_t)bytes[3];
}

static inline void echo_write_be16(uint8_t *bytes, uint16_t value) {
    bytes[0] = (uint8_t)(value >> 8U);
    bytes[1] = (uint8_t)value;
}

static inline void echo_write_be32(uint8_t *bytes, uint32_t value) {
    bytes[0] = (uint8_t)(value >> 24U);
    bytes[1] = (uint8_t)(value >> 16U);
    bytes[2] = (uint8_t)(value >> 8U);
    bytes[3] = (uint8_t)value;
}

static inline int echo_parse_frame_header(
    const uint8_t raw[ECHO_HEADER_BYTES],
    echo_frame_header *out
) {
    uint32_t payload_length;

    if (raw == NULL || out == NULL) return ECHO_FRAME_INVALID_ARGUMENT;
    if (raw[0] != ECHO_MAGIC_0 || raw[1] != ECHO_MAGIC_1 ||
        raw[2] != ECHO_MAGIC_2 || raw[3] != ECHO_MAGIC_3) {
        return ECHO_FRAME_BAD_MAGIC;
    }
    if (raw[4] != ECHO_VERSION) return ECHO_FRAME_BAD_VERSION;

    payload_length = echo_read_be32(raw + 8U);
    if (payload_length > ECHO_FRAME_MAX_PAYLOAD_BYTES) return ECHO_FRAME_TOO_LARGE;

    out->type = raw[5];
    out->flags = echo_read_be16(raw + 6U);
    out->payload_length = payload_length;
    out->request_id = echo_read_be32(raw + 12U);
    return ECHO_FRAME_OK;
}

static inline void echo_make_frame_header(
    uint8_t raw[ECHO_HEADER_BYTES],
    uint8_t type,
    uint16_t flags,
    uint32_t payload_length,
    uint32_t request_id
) {
    raw[0] = ECHO_MAGIC_0;
    raw[1] = ECHO_MAGIC_1;
    raw[2] = ECHO_MAGIC_2;
    raw[3] = ECHO_MAGIC_3;
    raw[4] = ECHO_VERSION;
    raw[5] = type;
    echo_write_be16(raw + 6U, flags);
    echo_write_be32(raw + 8U, payload_length);
    echo_write_be32(raw + 12U, request_id);
}

/*
 * Bootstrap PING is deliberately strict. Reserved flags must remain zero and
 * the payload is exactly one opaque 64-bit nonce. The legacy negative return
 * values remain stable because the first host/bootstrap tests depend on them.
 */
static inline int echo_validate_ping_header(const uint8_t header[ECHO_HEADER_BYTES]) {
    echo_frame_header parsed;
    int result = echo_parse_frame_header(header, &parsed);
    if (result == ECHO_FRAME_BAD_MAGIC) return -1;
    if (result == ECHO_FRAME_BAD_VERSION) return -2;
    if (result != ECHO_FRAME_OK) return -4;
    if (parsed.type != ECHO_TYPE_PING) return -2;
    if (parsed.flags != 0U) return -3;
    if (parsed.payload_length != ECHO_PING_PAYLOAD_BYTES) return -4;
    return 0;
}

static inline void echo_make_pong_header(uint8_t header[ECHO_HEADER_BYTES]) {
    header[5] = ECHO_TYPE_PONG;
}

#endif
