#include <stddef.h>
#include <stdint.h>

#include "echo_command_auth_xbox.h"
#include "echo_readonly_dispatch.h"
#include "echo_request_pipeline_xbox.h"

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
) {
    uint64_t required_capability = UINT64_C(0);
    const uint8_t *body = NULL;
    uint32_t body_length = 0U;
    echo_readonly_dispatch_plan plan;
    int result;

    if (secret == NULL || auth == NULL || envelope == NULL || response == NULL ||
        response->payload == NULL) {
        return ECHO_PIPELINE_INVALID_ARGUMENT;
    }

    response->response_type = 0U;
    response->payload_length = 0U;

    result = echo_ro_required_auth_capability(request_type, &required_capability);
    if (result != ECHO_RO_DISPATCH_OK) {
        return ECHO_PIPELINE_UNSUPPORTED_TYPE;
    }

    result = echo_command_auth_xbox_verify_and_commit(
        secret,
        auth,
        required_capability,
        request_type,
        flags,
        request_id,
        envelope,
        envelope_length,
        &body,
        &body_length
    );
    if (result != 0) {
        return ECHO_PIPELINE_AUTH_FAILED;
    }

    result = echo_ro_plan_verified_request(
        request_type,
        flags,
        body,
        body_length,
        &plan
    );
    if (result != ECHO_RO_DISPATCH_OK ||
        plan.required_auth_capability != required_capability) {
        return ECHO_PIPELINE_INVALID_BODY;
    }

    result = echo_ro_execute_xbox(&plan, resident_plugin, response);
    if (result != ECHO_RO_XBOX_OK) {
        response->response_type = 0U;
        response->payload_length = 0U;
        return ECHO_PIPELINE_EXECUTION_FAILED;
    }

    return ECHO_PIPELINE_OK;
}
