#ifndef ECHO_REQUEST_PIPELINE_XBOX_H
#define ECHO_REQUEST_PIPELINE_XBOX_H

#include <stdint.h>

#include "echo_auth_state.h"
#include "echo_readonly_dispatch_xbox.h"

#define ECHO_PIPELINE_OK 0
#define ECHO_PIPELINE_INVALID_ARGUMENT -1
#define ECHO_PIPELINE_UNSUPPORTED_TYPE -2
#define ECHO_PIPELINE_AUTH_FAILED -3
#define ECHO_PIPELINE_INVALID_BODY -4
#define ECHO_PIPELINE_EXECUTION_FAILED -5

/*
 * Process one already-framed EchoLink request payload:
 *   authenticated envelope -> MAC/capability/replay verification ->
 *   verified-body planner -> Xbox adapter execution -> response payload.
 *
 * PING/PONG and the physical/manual pairing handshake intentionally remain
 * outside this function. This pipeline is for authenticated read-only v1
 * commands only.
 */
int echo_xbox_process_authenticated_readonly(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    echo_auth_state *auth,
    uint8_t request_type,
    uint16_t flags,
    uint32_t request_id,
    const uint8_t *envelope,
    uint32_t envelope_length,
    int resident_plugin,
    echo_readonly_xbox_response *response
);

#endif
