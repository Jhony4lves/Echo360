#include <assert.h>
#include <stdint.h>

#include "echo_doctor_telemetry.h"

int main(void) {
    uint8_t payload[ECHO_DOCTOR_TELEMETRY_BYTES];
    echo_doctor_memory memory = {
        ECHO_STATUS_OK,
        UINT64_C(128) * 1024U * 1024U,
        UINT64_C(384) * 1024U * 1024U,
        UINT64_C(512) * 1024U * 1024U,
    };
    echo_doctor_temperature temperature = {
        ECHO_STATUS_OK,
        0x3C80U, /* 60.5 C */
        0x3D00U, /* 61.0 C */
        0x3900U, /* 57.0 C */
        0x2D40U, /* 45.25 C */
    };

    echo_doctor_make_payload(payload, &memory, &temperature);
    assert(echo_ro_read_be16(payload + 0U) == ECHO_DOCTOR_TELEMETRY_VERSION);
    assert(echo_ro_read_be16(payload + 2U) ==
        (ECHO_DOCTOR_COMPONENT_MEMORY | ECHO_DOCTOR_COMPONENT_TEMPERATURE));
    assert(payload[4] == ECHO_STATUS_OK);
    assert(payload[5] == ECHO_STATUS_OK);
    assert(payload[6] == ECHO_DOCTOR_TEMPERATURE_UNIT_CELSIUS);
    assert(payload[7] == 0U);
    assert(echo_ro_read_be64(payload + 8U) == memory.free_bytes);
    assert(echo_ro_read_be64(payload + 16U) == memory.used_bytes);
    assert(echo_ro_read_be64(payload + 24U) == memory.total_bytes);
    assert(echo_ro_read_be16(payload + 32U) == temperature.cpu_q8_8);
    assert(echo_ro_read_be16(payload + 34U) == temperature.gpu_q8_8);
    assert(echo_ro_read_be16(payload + 36U) == temperature.memory_q8_8);
    assert(echo_ro_read_be16(payload + 38U) == temperature.case_q8_8);
    assert(echo_ro_read_be32(payload + 40U) == 4096U);
    assert(echo_ro_read_be32(payload + 44U) == 0U);

    temperature.status = ECHO_STATUS_IO_ERROR;
    echo_doctor_make_payload(payload, &memory, &temperature);
    assert(echo_ro_read_be16(payload + 2U) == ECHO_DOCTOR_COMPONENT_MEMORY);
    assert(payload[4] == ECHO_STATUS_OK);
    assert(payload[5] == ECHO_STATUS_IO_ERROR);
    assert(echo_ro_read_be16(payload + 32U) == 0U);
    assert(echo_ro_read_be16(payload + 38U) == 0U);

    memory.status = ECHO_STATUS_IO_ERROR;
    temperature.status = ECHO_STATUS_OK;
    echo_doctor_make_payload(payload, &memory, &temperature);
    assert(echo_ro_read_be16(payload + 2U) == ECHO_DOCTOR_COMPONENT_TEMPERATURE);
    assert(echo_ro_read_be64(payload + 8U) == 0U);
    assert(echo_ro_read_be64(payload + 24U) == 0U);
    assert(echo_ro_read_be32(payload + 40U) == 0U);

    echo_doctor_make_payload(payload, 0, 0);
    assert(echo_ro_read_be16(payload + 2U) == 0U);
    assert(payload[4] == ECHO_STATUS_UNSUPPORTED);
    assert(payload[5] == ECHO_STATUS_UNSUPPORTED);
    assert(echo_ro_read_be64(payload + 8U) == 0U);
    assert(echo_ro_read_be16(payload + 32U) == 0U);

    return 0;
}
