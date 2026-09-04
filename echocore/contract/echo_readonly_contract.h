#ifndef ECHO_READONLY_CONTRACT_H
#define ECHO_READONLY_CONTRACT_H

#include <stddef.h>
#include <stdint.h>

#define ECHO_RO_CONTRACT_VERSION 1U

/* Additive request/response types; bootstrap 0x01/0x02 and ERROR 0x7F stay unchanged. */
#define ECHO_TYPE_CORE_INFO_REQUEST       0x10U
#define ECHO_TYPE_CORE_INFO_RESPONSE      0x11U
#define ECHO_TYPE_CURRENT_TITLE_REQUEST   0x12U
#define ECHO_TYPE_CURRENT_TITLE_RESPONSE  0x13U
#define ECHO_TYPE_FILE_STAT_REQUEST       0x14U
#define ECHO_TYPE_FILE_STAT_RESPONSE      0x15U
#define ECHO_TYPE_DIR_LIST_REQUEST        0x16U
#define ECHO_TYPE_DIR_LIST_RESPONSE       0x17U
#define ECHO_TYPE_DOCTOR_TELEMETRY_REQUEST  0x18U
#define ECHO_TYPE_DOCTOR_TELEMETRY_RESPONSE 0x19U

#define ECHO_CAP_PING             (UINT64_C(1) << 0)
#define ECHO_CAP_CORE_INFO        (UINT64_C(1) << 1)
#define ECHO_CAP_CURRENT_TITLE    (UINT64_C(1) << 2)
#define ECHO_CAP_FILE_STAT        (UINT64_C(1) << 3)
#define ECHO_CAP_DIR_LIST         (UINT64_C(1) << 4)
#define ECHO_CAP_DOCTOR_TELEMETRY (UINT64_C(1) << 5)
#define ECHO_CAP_READONLY_KNOWN_MASK \
    (ECHO_CAP_PING | ECHO_CAP_CORE_INFO | ECHO_CAP_CURRENT_TITLE | ECHO_CAP_FILE_STAT | \
     ECHO_CAP_DIR_LIST | ECHO_CAP_DOCTOR_TELEMETRY)

#define ECHO_STATUS_OK              0U
#define ECHO_STATUS_NOT_FOUND       1U
#define ECHO_STATUS_ACCESS_DENIED   2U
#define ECHO_STATUS_INVALID_PATH    3U
#define ECHO_STATUS_NOT_DIRECTORY   4U
#define ECHO_STATUS_LIMIT_REACHED   5U
#define ECHO_STATUS_IO_ERROR        6U
#define ECHO_STATUS_UNSUPPORTED     7U

#define ECHO_CORE_INFO_BYTES 32U
#define ECHO_CURRENT_TITLE_BYTES 4U
#define ECHO_FILE_STAT_BYTES 16U
#define ECHO_MAX_PATH_BYTES 512U
#define ECHO_MAX_DIR_ENTRIES 256U
#define ECHO_MAX_NAME_BYTES 255U

#define ECHO_OBJECT_NONE 0U
#define ECHO_OBJECT_FILE 1U
#define ECHO_OBJECT_DIRECTORY 2U

#define ECHO_CORE_STATUS_NETWORK_LINK_ACTIVE (1U << 0)
#define ECHO_CORE_STATUS_RESIDENT_PLUGIN      (1U << 1)

/*
 * DIR_LIST response payload, contract v1:
 *   0  u8 status
 *   1  u8 limit_reached
 *   2  u16 emitted_entries
 *   4  repeated entries:
 *        u8 object_type
 *        u8 reserved = 0
 *        u16 name_length
 *        u64 size (0 for directories)
 *        name_length raw ANSI/UTF-8-compatible bytes, no NUL
 *
 * Worst-case payload is intentionally bounded and caller-owned. Xbox runtime
 * must not place this maximum-size buffer on a small worker-thread stack.
 */
#define ECHO_DIR_LIST_HEADER_BYTES 4U
#define ECHO_DIR_ENTRY_HEADER_BYTES 12U
#define ECHO_DIR_LIST_MAX_PAYLOAD_BYTES \
    (ECHO_DIR_LIST_HEADER_BYTES + \
     ECHO_MAX_DIR_ENTRIES * (ECHO_DIR_ENTRY_HEADER_BYTES + ECHO_MAX_NAME_BYTES))

static inline uint16_t echo_ro_read_be16(const uint8_t *p) {
    return (uint16_t)(((uint16_t)p[0] << 8U) | (uint16_t)p[1]);
}

static inline uint32_t echo_ro_read_be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24U) |
           ((uint32_t)p[1] << 16U) |
           ((uint32_t)p[2] << 8U) |
           (uint32_t)p[3];
}

static inline uint64_t echo_ro_read_be64(const uint8_t *p) {
    return ((uint64_t)echo_ro_read_be32(p) << 32U) |
           (uint64_t)echo_ro_read_be32(p + 4U);
}

static inline void echo_ro_write_be16(uint8_t *p, uint16_t v) {
    p[0] = (uint8_t)(v >> 8U);
    p[1] = (uint8_t)v;
}

static inline void echo_ro_write_be32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)(v >> 24U);
    p[1] = (uint8_t)(v >> 16U);
    p[2] = (uint8_t)(v >> 8U);
    p[3] = (uint8_t)v;
}

static inline void echo_ro_write_be64(uint8_t *p, uint64_t v) {
    echo_ro_write_be32(p, (uint32_t)(v >> 32U));
    echo_ro_write_be32(p + 4U, (uint32_t)v);
}

/*
 * CORE_INFO response, fixed 32-byte payload:
 *   0  u16 contract_version
 *   2  u16 reserved (zero)
 *   4  u32 echocore_build
 *   8  u32 system_version_raw (XamGetSystemVersion)
 *  12  u32 current_title_id (XamGetCurrentTitleId)
 *  16  u64 capability_bits
 *  24  u32 status_flags
 *  28  u32 reserved (zero)
 */
static inline void echo_ro_make_core_info(
    uint8_t out[ECHO_CORE_INFO_BYTES],
    uint32_t echocore_build,
    uint32_t system_version_raw,
    uint32_t current_title_id,
    uint64_t capabilities,
    uint32_t status_flags
) {
    echo_ro_write_be16(out + 0U, ECHO_RO_CONTRACT_VERSION);
    echo_ro_write_be16(out + 2U, 0U);
    echo_ro_write_be32(out + 4U, echocore_build);
    echo_ro_write_be32(out + 8U, system_version_raw);
    echo_ro_write_be32(out + 12U, current_title_id);
    echo_ro_write_be64(out + 16U, capabilities & ECHO_CAP_READONLY_KNOWN_MASK);
    echo_ro_write_be32(out + 24U, status_flags);
    echo_ro_write_be32(out + 28U, 0U);
}

/* FILE_STAT response, fixed 16-byte payload: status, object_type, reserved, size. */
static inline void echo_ro_make_file_stat(
    uint8_t out[ECHO_FILE_STAT_BYTES],
    uint8_t status,
    uint8_t object_type,
    uint64_t size
) {
    out[0] = status;
    out[1] = object_type;
    echo_ro_write_be16(out + 2U, 0U);
    echo_ro_write_be32(out + 4U, 0U);
    echo_ro_write_be64(out + 8U, size);
}

static inline void echo_ro_make_dir_list_header(
    uint8_t out[ECHO_DIR_LIST_HEADER_BYTES],
    uint8_t status,
    uint8_t limit_reached,
    uint16_t emitted_entries
) {
    out[0] = status;
    out[1] = limit_reached != 0U ? 1U : 0U;
    echo_ro_write_be16(out + 2U, emitted_entries);
}

static inline uint32_t echo_ro_dir_entry_encoded_size(uint16_t name_length) {
    if (name_length == 0U || name_length > ECHO_MAX_NAME_BYTES) return 0U;
    return ECHO_DIR_ENTRY_HEADER_BYTES + (uint32_t)name_length;
}

static inline uint32_t echo_ro_write_dir_entry(
    uint8_t *out,
    uint32_t capacity,
    uint8_t object_type,
    uint64_t size,
    const uint8_t *name,
    uint16_t name_length
) {
    uint32_t encoded_size = echo_ro_dir_entry_encoded_size(name_length);
    uint32_t i;

    if (out == NULL || name == NULL || encoded_size == 0U || capacity < encoded_size ||
        (object_type != ECHO_OBJECT_FILE && object_type != ECHO_OBJECT_DIRECTORY)) {
        return 0U;
    }

    out[0] = object_type;
    out[1] = 0U;
    echo_ro_write_be16(out + 2U, name_length);
    echo_ro_write_be64(out + 4U, object_type == ECHO_OBJECT_DIRECTORY ? UINT64_C(0) : size);
    for (i = 0U; i < (uint32_t)name_length; ++i) {
        if (name[i] == 0U) return 0U;
        out[ECHO_DIR_ENTRY_HEADER_BYTES + i] = name[i];
    }
    return encoded_size;
}

static inline int echo_ro_validate_path_payload(const uint8_t *payload, uint32_t payload_length) {
    uint32_t i;
    if (payload == NULL || payload_length == 0U || payload_length > ECHO_MAX_PATH_BYTES) {
        return -1;
    }
    for (i = 0U; i < payload_length; ++i) {
        if (payload[i] == 0U) {
            return -2;
        }
    }
    return 0;
}

#endif
