#ifndef ECHO_DOCTOR_TELEMETRY_XBOX_H
#define ECHO_DOCTOR_TELEMETRY_XBOX_H

#include <stdint.h>

#include "echo_doctor_telemetry.h"

void echo_xbox_read_memory(echo_doctor_memory *memory);
void echo_xbox_read_temperature(echo_doctor_temperature *temperature);
void echo_xbox_make_doctor_telemetry_payload(
    uint8_t out[ECHO_DOCTOR_TELEMETRY_BYTES]
);

#endif
