#include <stddef.h>
#include <stdint.h>

#include "echo_readonly_dispatch_xbox.h"
#include "../contract/echo_dir_list_xbox.h"
#include "../contract/echo_doctor_telemetry_xbox.h"
#include "../contract/echo_file_stat_xbox.h"
#include "../contract/echo_runtime_info_xbox.h"

typedef struct echo_dir_encode_context {
    uint8_t *payload;
    uint32_t capacity;
    uint32_t offset;
} echo_dir_encode_context;

static int echo_ro_require_capacity(
    const echo_readonly_xbox_response *response,
    uint32_t required
) {
    if (response == NULL || response->payload == NULL) return ECHO_RO_XBOX_INVALID_ARGUMENT;
    return response->payload_capacity >= required
        ? ECHO_RO_XBOX_OK
        : ECHO_RO_XBOX_BUFFER_TOO_SMALL;
}

static int echo_ro_encode_dir_entry(
    const echo_directory_entry *entry,
    void *context
) {
    echo_dir_encode_context *encode = (echo_dir_encode_context *)context;
    uint32_t written;

    if (entry == NULL || encode == NULL || encode->offset > encode->capacity) return -1;

    written = echo_ro_write_dir_entry(
        encode->payload + encode->offset,
        encode->capacity - encode->offset,
        entry->object_type,
        entry->size,
        (const uint8_t *)entry->name,
        entry->name_length
    );
    if (written == 0U) return -1;
    encode->offset += written;
    return 0;
}

int echo_ro_execute_xbox(
    const echo_readonly_dispatch_plan *plan,
    int resident_plugin,
    echo_readonly_xbox_response *response
) {
    int capacity_result;

    if (plan == NULL || response == NULL || response->payload == NULL ||
        plan->operation == ECHO_RO_OP_NONE || plan->response_type == 0U) {
        return ECHO_RO_XBOX_INVALID_ARGUMENT;
    }

    response->response_type = plan->response_type;
    response->payload_length = 0U;

    switch (plan->operation) {
        case ECHO_RO_OP_CORE_INFO:
            capacity_result = echo_ro_require_capacity(response, ECHO_CORE_INFO_BYTES);
            if (capacity_result != 0) return capacity_result;
            echo_xbox_make_core_info_payload(response->payload, resident_plugin);
            response->payload_length = ECHO_CORE_INFO_BYTES;
            return ECHO_RO_XBOX_OK;

        case ECHO_RO_OP_CURRENT_TITLE:
            capacity_result = echo_ro_require_capacity(response, ECHO_CURRENT_TITLE_BYTES);
            if (capacity_result != 0) return capacity_result;
            echo_xbox_make_current_title_payload(response->payload);
            response->payload_length = ECHO_CURRENT_TITLE_BYTES;
            return ECHO_RO_XBOX_OK;

        case ECHO_RO_OP_FILE_STAT: {
            echo_file_stat_result stat_result;
            capacity_result = echo_ro_require_capacity(response, ECHO_FILE_STAT_BYTES);
            if (capacity_result != 0) return capacity_result;
            if (plan->path == NULL || plan->path_length == 0U ||
                echo_xbox_file_stat(
                    (const char *)plan->path,
                    (size_t)plan->path_length,
                    &stat_result
                ) != 0) {
                return ECHO_RO_XBOX_ADAPTER_ERROR;
            }
            echo_ro_make_file_stat(
                response->payload,
                stat_result.status,
                stat_result.object_type,
                stat_result.size
            );
            response->payload_length = ECHO_FILE_STAT_BYTES;
            return ECHO_RO_XBOX_OK;
        }

        case ECHO_RO_OP_DIR_LIST: {
            echo_directory_list_result list_result;
            echo_dir_encode_context encode;
            uint32_t worst_case;
            int list_call;

            if (plan->path == NULL || plan->path_length == 0U ||
                plan->max_entries == 0U || plan->max_entries > ECHO_MAX_DIR_ENTRIES) {
                return ECHO_RO_XBOX_INVALID_ARGUMENT;
            }

            worst_case = ECHO_DIR_LIST_HEADER_BYTES +
                (uint32_t)plan->max_entries *
                (ECHO_DIR_ENTRY_HEADER_BYTES + ECHO_MAX_NAME_BYTES);
            capacity_result = echo_ro_require_capacity(response, worst_case);
            if (capacity_result != 0) return capacity_result;

            encode.payload = response->payload;
            encode.capacity = response->payload_capacity;
            encode.offset = ECHO_DIR_LIST_HEADER_BYTES;

            list_call = echo_xbox_dir_list(
                (const char *)plan->path,
                (size_t)plan->path_length,
                plan->max_entries,
                echo_ro_encode_dir_entry,
                &encode,
                &list_result
            );
            if (list_call != 0) return ECHO_RO_XBOX_ADAPTER_ERROR;

            echo_ro_make_dir_list_header(
                response->payload,
                list_result.status,
                list_result.limit_reached,
                list_result.emitted_entries
            );
            response->payload_length = encode.offset;
            return ECHO_RO_XBOX_OK;
        }

        case ECHO_RO_OP_DOCTOR_TELEMETRY:
            capacity_result = echo_ro_require_capacity(response, ECHO_DOCTOR_TELEMETRY_BYTES);
            if (capacity_result != 0) return capacity_result;
            echo_xbox_make_doctor_telemetry_payload(response->payload);
            response->payload_length = ECHO_DOCTOR_TELEMETRY_BYTES;
            return ECHO_RO_XBOX_OK;

        default:
            return ECHO_RO_XBOX_UNSUPPORTED_OPERATION;
    }
}
