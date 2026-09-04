#ifndef ECHO_RUNTIME_INFO_XBOX_H
#define ECHO_RUNTIME_INFO_XBOX_H

#include <stdint.h>

#include "echo_readonly_contract.h"

uint32_t echo_xbox_current_title_id(void);

void echo_xbox_make_current_title_payload(
    uint8_t out[ECHO_CURRENT_TITLE_BYTES]
);

uint32_t echo_xbox_runtime_status_flags(int resident_plugin);

void echo_xbox_make_core_info_payload(
    uint8_t out[ECHO_CORE_INFO_BYTES],
    int resident_plugin
);

#endif
