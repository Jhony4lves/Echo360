#include <stdint.h>

#include <xecore/xboxkrnl_hal.h>
#include <xecore/xboxkrnl_mem.h>
#include <xecore/xboxkrnl_types.h>

#include "echo_doctor_telemetry.h"

#define ECHO_SMC_MESSAGE_BYTES 16U
#define ECHO_SMC_TEMPERATURE_COMMAND 0x07U
#define ECHO_TEMP_MIN_Q8_8 0x0100U
#define ECHO_TEMP_MAX_Q8_8 0x7D00U /* 125.0 C: protocol sanity bound, not a health threshold. */

static uint16_t echo_read_le16(const uint8_t *bytes) {
    return (uint16_t)((uint16_t)bytes[0] | ((uint16_t)bytes[1] << 8U));
}

static int echo_temperature_raw_is_plausible(uint16_t raw) {
    return raw >= ECHO_TEMP_MIN_Q8_8 && raw <= ECHO_TEMP_MAX_Q8_8;
}

void echo_xbox_read_memory(echo_doctor_memory *memory) {
    MM_QUERY_STATISTICS_RESULT stats = {0};
    NTSTATUS status;
    uint64_t total_pages;
    uint64_t free_pages;

    if (memory == 0) return;
    memory->status = ECHO_STATUS_IO_ERROR;
    memory->free_bytes = 0U;
    memory->used_bytes = 0U;
    memory->total_bytes = 0U;

    stats.size = (uint32_t)sizeof(stats);
    status = MmQueryStatistics(&stats);
    if (FAILED(status)) return;

    total_pages = (uint64_t)stats.total_physical_pages;
    free_pages = (uint64_t)stats.title.available_pages;
    if (total_pages == 0U || free_pages > total_pages) return;

    memory->total_bytes = total_pages * ECHO_XBOX_PAGE_BYTES;
    memory->free_bytes = free_pages * ECHO_XBOX_PAGE_BYTES;
    memory->used_bytes = memory->total_bytes - memory->free_bytes;
    memory->status = ECHO_STATUS_OK;
}

void echo_xbox_read_temperature(echo_doctor_temperature *temperature) {
    uint8_t message[ECHO_SMC_MESSAGE_BYTES] = {0};
    uint8_t response[ECHO_SMC_MESSAGE_BYTES] = {0};
    uint16_t cpu;
    uint16_t gpu;
    uint16_t memory;
    uint16_t box;

    if (temperature == 0) return;
    temperature->status = ECHO_STATUS_IO_ERROR;
    temperature->cpu_q8_8 = 0U;
    temperature->gpu_q8_8 = 0U;
    temperature->memory_q8_8 = 0U;
    temperature->case_q8_8 = 0U;

    message[0] = ECHO_SMC_TEMPERATURE_COMMAND;
    HalSendSMCMessage(message, response);

    cpu = echo_read_le16(response + 1U);
    gpu = echo_read_le16(response + 3U);
    memory = echo_read_le16(response + 5U);
    box = echo_read_le16(response + 7U);

    if (!echo_temperature_raw_is_plausible(cpu) ||
        !echo_temperature_raw_is_plausible(gpu) ||
        !echo_temperature_raw_is_plausible(memory) ||
        !echo_temperature_raw_is_plausible(box)) {
        return;
    }

    temperature->cpu_q8_8 = cpu;
    temperature->gpu_q8_8 = gpu;
    temperature->memory_q8_8 = memory;
    temperature->case_q8_8 = box;
    temperature->status = ECHO_STATUS_OK;
}

void echo_xbox_make_doctor_telemetry_payload(
    uint8_t out[ECHO_DOCTOR_TELEMETRY_BYTES]
) {
    echo_doctor_memory memory;
    echo_doctor_temperature temperature;

    echo_xbox_read_memory(&memory);
    echo_xbox_read_temperature(&temperature);
    echo_doctor_make_payload(out, &memory, &temperature);
}
