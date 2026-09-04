#ifndef ECHO_READONLY_DISPATCH_H
#define ECHO_READONLY_DISPATCH_H

#include <stddef.h>
#include <stdint.h>

#include "echo_auth_state.h"
#include "../contract/echo_readonly_contract.h"

/*
 * The planner deliberately contains no Xbox ABI and performs no I/O. It is the
 * boundary between authenticated EchoLink framing and platform adapters.
 * Callers resolve the required auth capability from the request type, verify
 * the authenticated command envelope, then plan/validate the verified body.
 */
typedef enum echo_readonly_operation {
    ECHO_RO_OP_NONE = 0,
    ECHO_RO_OP_CORE_INFO = 1,
    ECHO_RO_OP_CURRENT_TITLE = 2,
    ECHO_RO_OP_FILE_STAT = 3,
    ECHO_RO_OP_DIR_LIST = 4,
    ECHO_RO_OP_DOCTOR_TELEMETRY = 5
} echo_readonly_operation;

typedef struct echo_readonly_dispatch_plan {
    echo_readonly_operation operation;
    uint8_t response_type;
    uint64_t required_auth_capability;
    const uint8_t *path;
    uint32_t path_length;
    uint16_t max_entries;
} echo_readonly_dispatch_plan;

#define ECHO_RO_DISPATCH_OK 0
#define ECHO_RO_DISPATCH_INVALID_ARGUMENT -1
#define ECHO_RO_DISPATCH_UNSUPPORTED_TYPE -2
#define ECHO_RO_DISPATCH_INVALID_BODY -3
#define ECHO_RO_DISPATCH_INVALID_FLAGS -4

static inline void echo_ro_dispatch_plan_reset(echo_readonly_dispatch_plan *plan) {
    if (plan == NULL) return;
    plan->operation = ECHO_RO_OP_NONE;
    plan->response_type = 0U;
    plan->required_auth_capability = UINT64_C(0);
    plan->path = NULL;
    plan->path_length = 0U;
    plan->max_entries = 0U;
}

/*
 * Resolve permission without touching the untrusted body. This is intentionally
 * safe to call before MAC verification so command-auth knows which permission
 * must be present. Body parsing belongs in echo_ro_plan_verified_request().
 */
static inline int echo_ro_required_auth_capability(
    uint8_t request_type,
    uint64_t *required_capability
) {
    uint64_t capability;

    if (required_capability == NULL) return ECHO_RO_DISPATCH_INVALID_ARGUMENT;

    switch (request_type) {
        case ECHO_TYPE_CORE_INFO_REQUEST:
        case ECHO_TYPE_CURRENT_TITLE_REQUEST:
        case ECHO_TYPE_DOCTOR_TELEMETRY_REQUEST:
            capability = ECHO_AUTH_CAP_READ_INFO;
            break;
        case ECHO_TYPE_FILE_STAT_REQUEST:
        case ECHO_TYPE_DIR_LIST_REQUEST:
            capability = ECHO_AUTH_CAP_READ_FILESYSTEM;
            break;
        default:
            *required_capability = UINT64_C(0);
            return ECHO_RO_DISPATCH_UNSUPPORTED_TYPE;
    }

    *required_capability = capability;
    return ECHO_RO_DISPATCH_OK;
}

/*
 * Plan a body only AFTER the authenticated envelope has been verified.
 * DIR_LIST request body is frozen for contract v1 as:
 *   0..1  u16 max_entries, big-endian, 1..ECHO_MAX_DIR_ENTRIES
 *   2..N  canonical path bytes, no NUL
 */
static inline int echo_ro_plan_verified_request(
    uint8_t request_type,
    uint16_t flags,
    const uint8_t *body,
    uint32_t body_length,
    echo_readonly_dispatch_plan *plan
) {
    uint64_t required_capability;

    if (plan == NULL) return ECHO_RO_DISPATCH_INVALID_ARGUMENT;
    echo_ro_dispatch_plan_reset(plan);

    if (flags != 0U) return ECHO_RO_DISPATCH_INVALID_FLAGS;
    if (echo_ro_required_auth_capability(request_type, &required_capability) != 0) {
        return ECHO_RO_DISPATCH_UNSUPPORTED_TYPE;
    }

    plan->required_auth_capability = required_capability;

    switch (request_type) {
        case ECHO_TYPE_CORE_INFO_REQUEST:
            if (body_length != 0U) return ECHO_RO_DISPATCH_INVALID_BODY;
            plan->operation = ECHO_RO_OP_CORE_INFO;
            plan->response_type = ECHO_TYPE_CORE_INFO_RESPONSE;
            return ECHO_RO_DISPATCH_OK;

        case ECHO_TYPE_CURRENT_TITLE_REQUEST:
            if (body_length != 0U) return ECHO_RO_DISPATCH_INVALID_BODY;
            plan->operation = ECHO_RO_OP_CURRENT_TITLE;
            plan->response_type = ECHO_TYPE_CURRENT_TITLE_RESPONSE;
            return ECHO_RO_DISPATCH_OK;

        case ECHO_TYPE_FILE_STAT_REQUEST:
            if (echo_ro_validate_path_payload(body, body_length) != 0) {
                return ECHO_RO_DISPATCH_INVALID_BODY;
            }
            plan->operation = ECHO_RO_OP_FILE_STAT;
            plan->response_type = ECHO_TYPE_FILE_STAT_RESPONSE;
            plan->path = body;
            plan->path_length = body_length;
            return ECHO_RO_DISPATCH_OK;

        case ECHO_TYPE_DIR_LIST_REQUEST: {
            uint16_t max_entries;
            const uint8_t *path;
            uint32_t path_length;

            if (body == NULL || body_length < 3U) {
                return ECHO_RO_DISPATCH_INVALID_BODY;
            }
            max_entries = echo_ro_read_be16(body);
            path = body + 2U;
            path_length = body_length - 2U;
            if (max_entries == 0U || max_entries > ECHO_MAX_DIR_ENTRIES ||
                echo_ro_validate_path_payload(path, path_length) != 0) {
                return ECHO_RO_DISPATCH_INVALID_BODY;
            }
            plan->operation = ECHO_RO_OP_DIR_LIST;
            plan->response_type = ECHO_TYPE_DIR_LIST_RESPONSE;
            plan->path = path;
            plan->path_length = path_length;
            plan->max_entries = max_entries;
            return ECHO_RO_DISPATCH_OK;
        }

        case ECHO_TYPE_DOCTOR_TELEMETRY_REQUEST:
            if (body_length != 0U) return ECHO_RO_DISPATCH_INVALID_BODY;
            plan->operation = ECHO_RO_OP_DOCTOR_TELEMETRY;
            plan->response_type = ECHO_TYPE_DOCTOR_TELEMETRY_RESPONSE;
            return ECHO_RO_DISPATCH_OK;

        default:
            return ECHO_RO_DISPATCH_UNSUPPORTED_TYPE;
    }
}

#endif
