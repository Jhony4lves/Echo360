#ifndef ECHO_READONLY_DISPATCH_XBOX_H
#define ECHO_READONLY_DISPATCH_XBOX_H

#include <stdint.h>

#include "echo_readonly_dispatch.h"

typedef struct echo_readonly_xbox_response {
    uint8_t response_type;
    uint8_t *payload;
    uint32_t payload_capacity;
    uint32_t payload_length;
} echo_readonly_xbox_response;

#define ECHO_RO_XBOX_OK 0
#define ECHO_RO_XBOX_INVALID_ARGUMENT -1
#define ECHO_RO_XBOX_BUFFER_TOO_SMALL -2
#define ECHO_RO_XBOX_ADAPTER_ERROR -3
#define ECHO_RO_XBOX_UNSUPPORTED_OPERATION -4

/*
 * Execute an already authenticated and validated plan against Xbox adapters.
 * response->payload is caller-owned. In particular, DIR_LIST's bounded maximum
 * payload must live in caller-controlled static/arena storage, never implicitly
 * on a resident worker's stack.
 */
int echo_ro_execute_xbox(
    const echo_readonly_dispatch_plan *plan,
    int resident_plugin,
    echo_readonly_xbox_response *response
);

#endif
