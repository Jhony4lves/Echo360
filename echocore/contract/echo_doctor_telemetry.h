#ifndef ECHO_DOCTOR_TELEMETRY_H
#define ECHO_DOCTOR_TELEMETRY_H

#include <stdint.h>

#include "echo_readonly_contract.h"

#define ECHO_DOCTOR_TELEMETRY_VERSION 1U
#define ECHO_DOCTOR_TELEMETRY_BYTES 48U
#define ECHO_DOCTOR_COMPONENT_MEMORY      (1U << 0)
#define ECHO_DOCTOR_COMPONENT_TEMPERATURE (1U << 1)
#define ECHO_DOCTOR_TEMPERATURE_UNIT_CELSIUS 1U
#define ECHO_XBOX_PAGE_BYTES UINT64_C(4096)

typedef struct echo_doctor_memory {
    uint8_t status;
    uint64_t free_bytes;
    uint64_t used_bytes;
    uint64_t total_bytes;
} echo_doctor_memory;

typedef struct echo_doctor_temperature {
    uint8_t status;
    uint16_t cpu_q8_8;
    uint16_t gpu_q8_8;
    uint16_t memory_q8_8;
    uint16_t case_q8_8;
} echo_doctor_temperature;

/*
 * Fixed 48-byte payload, big-endian on the wire:
 *   0  u16 telemetry_version
 *   2  u16 present_components
 *   4  u8  memory_status
 *   5  u8  temperature_status
 *   6  u8  temperature_unit (1 = Celsius)
 *   7  u8  reserved = 0
 *   8  u64 free_bytes
 *  16  u64 used_bytes
 *  24  u64 total_bytes
 *  32  u16 CPU raw Q8.8 Celsius
 *  34  u16 GPU raw Q8.8 Celsius
 *  36  u16 EDRAM/memory raw Q8.8 Celsius
 *  38  u16 motherboard/case raw Q8.8 Celsius
 *  40  u32 source page size in bytes (4096 when memory is present)
 *  44  u32 reserved = 0
 *
 * A component bit is set only when that component status is ECHO_STATUS_OK.
 * This preserves the Android Doctor model's independent source availability.
 */
static inline void echo_doctor_make_payload(
    uint8_t out[ECHO_DOCTOR_TELEMETRY_BYTES],
    const echo_doctor_memory *memory,
    const echo_doctor_temperature *temperature
) {
    uint16_t present = 0U;
    uint8_t memory_status = ECHO_STATUS_UNSUPPORTED;
    uint8_t temperature_status = ECHO_STATUS_UNSUPPORTED;
    uint64_t free_bytes = 0U;
    uint64_t used_bytes = 0U;
    uint64_t total_bytes = 0U;
    uint16_t cpu = 0U;
    uint16_t gpu = 0U;
    uint16_t mem = 0U;
    uint16_t box = 0U;
    uint32_t page_size = 0U;

    if (memory != 0) {
        memory_status = memory->status;
        if (memory->status == ECHO_STATUS_OK) {
            present |= ECHO_DOCTOR_COMPONENT_MEMORY;
            free_bytes = memory->free_bytes;
            used_bytes = memory->used_bytes;
            total_bytes = memory->total_bytes;
            page_size = (uint32_t)ECHO_XBOX_PAGE_BYTES;
        }
    }

    if (temperature != 0) {
        temperature_status = temperature->status;
        if (temperature->status == ECHO_STATUS_OK) {
            present |= ECHO_DOCTOR_COMPONENT_TEMPERATURE;
            cpu = temperature->cpu_q8_8;
            gpu = temperature->gpu_q8_8;
            mem = temperature->memory_q8_8;
            box = temperature->case_q8_8;
        }
    }

    echo_ro_write_be16(out + 0U, ECHO_DOCTOR_TELEMETRY_VERSION);
    echo_ro_write_be16(out + 2U, present);
    out[4] = memory_status;
    out[5] = temperature_status;
    out[6] = ECHO_DOCTOR_TEMPERATURE_UNIT_CELSIUS;
    out[7] = 0U;
    echo_ro_write_be64(out + 8U, free_bytes);
    echo_ro_write_be64(out + 16U, used_bytes);
    echo_ro_write_be64(out + 24U, total_bytes);
    echo_ro_write_be16(out + 32U, cpu);
    echo_ro_write_be16(out + 34U, gpu);
    echo_ro_write_be16(out + 36U, mem);
    echo_ro_write_be16(out + 38U, box);
    echo_ro_write_be32(out + 40U, page_size);
    echo_ro_write_be32(out + 44U, 0U);
}

#endif
