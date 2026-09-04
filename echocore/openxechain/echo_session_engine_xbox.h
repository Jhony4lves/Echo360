#ifndef ECHO_SESSION_ENGINE_XBOX_H
#define ECHO_SESSION_ENGINE_XBOX_H

#include <stdint.h>

#include "echo_auth_state.h"
#include "echo_protocol.h"
#include "echo_readonly_dispatch_xbox.h"

#define ECHO_SESSION_PHASE_NEW 0U
#define ECHO_SESSION_PHASE_CHALLENGE_SENT 1U
#define ECHO_SESSION_PHASE_AUTHENTICATED 2U

#define ECHO_SESSION_ENGINE_OK 0
#define ECHO_SESSION_ENGINE_INVALID_ARGUMENT -1
#define ECHO_SESSION_ENGINE_PROTOCOL_ERROR -2
#define ECHO_SESSION_ENGINE_AUTH_DENIED -3
#define ECHO_SESSION_ENGINE_PIPELINE_ERROR -4
#define ECHO_SESSION_ENGINE_BUFFER_TOO_SMALL -5

typedef struct echo_xbox_session {
    echo_auth_state auth;
    uint8_t phase;
} echo_xbox_session;

void echo_xbox_session_reset(echo_xbox_session *session);

/*
 * Process one fully-received, already-bounded EchoLink frame.
 *
 * - PING is public and valid in every phase.
 * - SESSION_BEGIN generates a fresh challenge.
 * - SESSION_AUTH validates HMAC and transitions to AUTHENTICATED.
 * - 0x10..0x19 read-only requests require AUTHENTICATED and flow through the
 *   full authenticated request pipeline.
 * - Unknown/control misuse fails closed; caller should close the client.
 *
 * response payload storage is caller-owned and may be a static/arena buffer.
 */
int echo_xbox_session_process_frame(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    echo_xbox_session *session,
    const echo_frame_header *request,
    const uint8_t *request_payload,
    int resident_plugin,
    echo_readonly_xbox_response *response
);

#endif
