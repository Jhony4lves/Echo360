#include <stddef.h>
#include <stdint.h>

#include "echo_auth_crypto_xbox.h"
#include "echo_request_pipeline_xbox.h"
#include "echo_session_engine_xbox.h"
#include "echo_session_protocol.h"

void echo_xbox_session_reset(echo_xbox_session *session) {
    if (session == NULL) return;
    echo_auth_session_end(&session->auth);
    session->phase = ECHO_SESSION_PHASE_NEW;
}

static int echo_session_write_small_response(
    echo_readonly_xbox_response *response,
    uint8_t type,
    const uint8_t *payload,
    uint32_t payload_length
) {
    uint32_t i;
    if (response == NULL || response->payload == NULL || payload == NULL) {
        return ECHO_SESSION_ENGINE_INVALID_ARGUMENT;
    }
    if (response->payload_capacity < payload_length) {
        response->response_type = 0U;
        response->payload_length = 0U;
        return ECHO_SESSION_ENGINE_BUFFER_TOO_SMALL;
    }
    for (i = 0U; i < payload_length; ++i) response->payload[i] = payload[i];
    response->response_type = type;
    response->payload_length = payload_length;
    return ECHO_SESSION_ENGINE_OK;
}

int echo_xbox_session_process_frame(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    echo_xbox_session *session,
    const echo_frame_header *request,
    const uint8_t *request_payload,
    int resident_plugin,
    echo_readonly_xbox_response *response
) {
    if (secret == NULL || session == NULL || request == NULL || response == NULL ||
        response->payload == NULL ||
        (request->payload_length != 0U && request_payload == NULL)) {
        return ECHO_SESSION_ENGINE_INVALID_ARGUMENT;
    }

    response->response_type = 0U;
    response->payload_length = 0U;

    if (request->type == ECHO_TYPE_PING) {
        if (request->flags != 0U || request->payload_length != ECHO_PING_PAYLOAD_BYTES) {
            return ECHO_SESSION_ENGINE_PROTOCOL_ERROR;
        }
        return echo_session_write_small_response(
            response,
            ECHO_TYPE_PONG,
            request_payload,
            ECHO_PING_PAYLOAD_BYTES
        );
    }

    if (request->type == ECHO_TYPE_SESSION_BEGIN_REQUEST) {
        uint8_t challenge_payload[ECHO_SESSION_CHALLENGE_BYTES];

        if (echo_session_validate_begin(request) != 0) {
            return ECHO_SESSION_ENGINE_PROTOCOL_ERROR;
        }
        /* A new begin always invalidates any previous connection-local state. */
        echo_xbox_session_reset(session);
        if (echo_auth_xbox_begin_session(&session->auth) != 0 ||
            echo_session_make_challenge_payload(
                &session->auth,
                challenge_payload
            ) != 0) {
            echo_xbox_session_reset(session);
            return ECHO_SESSION_ENGINE_PROTOCOL_ERROR;
        }
        session->phase = ECHO_SESSION_PHASE_CHALLENGE_SENT;
        return echo_session_write_small_response(
            response,
            ECHO_TYPE_SESSION_CHALLENGE_RESPONSE,
            challenge_payload,
            ECHO_SESSION_CHALLENGE_BYTES
        );
    }

    if (request->type == ECHO_TYPE_SESSION_AUTH_REQUEST) {
        uint64_t counter;
        uint64_t capabilities;
        const uint8_t *mac;
        uint8_t auth_response[ECHO_SESSION_AUTH_RESPONSE_BYTES];
        int parse_result;
        int verify_result;

        if (session->phase != ECHO_SESSION_PHASE_CHALLENGE_SENT) {
            return ECHO_SESSION_ENGINE_PROTOCOL_ERROR;
        }
        parse_result = echo_session_parse_auth_request(
            request,
            request_payload,
            &counter,
            &capabilities,
            &mac
        );
        if (parse_result != 0) {
            echo_session_make_auth_response(
                auth_response,
                ECHO_SESSION_STATUS_PROTOCOL_ERROR,
                UINT64_C(0),
                UINT64_C(0)
            );
            echo_xbox_session_reset(session);
            (void)echo_session_write_small_response(
                response,
                ECHO_TYPE_SESSION_AUTH_RESPONSE,
                auth_response,
                ECHO_SESSION_AUTH_RESPONSE_BYTES
            );
            return ECHO_SESSION_ENGINE_AUTH_DENIED;
        }

        verify_result = echo_auth_xbox_verify_response(
            secret,
            &session->auth,
            counter,
            capabilities,
            mac
        );
        if (verify_result != 0) {
            echo_session_make_auth_response(
                auth_response,
                ECHO_SESSION_STATUS_DENIED,
                UINT64_C(0),
                UINT64_C(0)
            );
            echo_xbox_session_reset(session);
            (void)echo_session_write_small_response(
                response,
                ECHO_TYPE_SESSION_AUTH_RESPONSE,
                auth_response,
                ECHO_SESSION_AUTH_RESPONSE_BYTES
            );
            return ECHO_SESSION_ENGINE_AUTH_DENIED;
        }

        session->phase = ECHO_SESSION_PHASE_AUTHENTICATED;
        echo_session_make_auth_response(
            auth_response,
            ECHO_SESSION_STATUS_OK,
            session->auth.capabilities,
            session->auth.last_rx_counter
        );
        return echo_session_write_small_response(
            response,
            ECHO_TYPE_SESSION_AUTH_RESPONSE,
            auth_response,
            ECHO_SESSION_AUTH_RESPONSE_BYTES
        );
    }

    if (request->type >= ECHO_TYPE_CORE_INFO_REQUEST &&
        request->type <= ECHO_TYPE_DOCTOR_TELEMETRY_RESPONSE) {
        int pipeline_result;

        if (session->phase != ECHO_SESSION_PHASE_AUTHENTICATED ||
            session->auth.authenticated == 0U) {
            return ECHO_SESSION_ENGINE_AUTH_DENIED;
        }
        pipeline_result = echo_xbox_process_authenticated_readonly(
            secret,
            &session->auth,
            request->type,
            request->flags,
            request->request_id,
            request_payload,
            request->payload_length,
            resident_plugin,
            response
        );
        return pipeline_result == ECHO_PIPELINE_OK
            ? ECHO_SESSION_ENGINE_OK
            : ECHO_SESSION_ENGINE_PIPELINE_ERROR;
    }

    return ECHO_SESSION_ENGINE_PROTOCOL_ERROR;
}
