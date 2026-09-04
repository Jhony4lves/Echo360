#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../openxechain/echo_readonly_dispatch_xbox.c"

static int g_file_stat_fail;
static int g_dir_list_fail;
static uint32_t g_core_info_calls;
static uint32_t g_current_title_calls;
static uint32_t g_doctor_calls;

void echo_xbox_make_core_info_payload(uint8_t out[ECHO_CORE_INFO_BYTES], int resident_plugin) {
    uint32_t i;
    g_core_info_calls++;
    for (i = 0U; i < ECHO_CORE_INFO_BYTES; ++i) out[i] = (uint8_t)(i + (resident_plugin ? 0x40U : 0U));
}

void echo_xbox_make_current_title_payload(uint8_t out[ECHO_CURRENT_TITLE_BYTES]) {
    g_current_title_calls++;
    echo_ro_write_be32(out, UINT32_C(0x465307E4));
}

int echo_xbox_file_stat(
    const char *wire_path,
    size_t wire_path_length,
    echo_file_stat_result *result
) {
    (void)wire_path;
    (void)wire_path_length;
    if (g_file_stat_fail) return -1;
    result->status = ECHO_STATUS_OK;
    result->object_type = ECHO_OBJECT_FILE;
    result->size = UINT64_C(0x123456789ABC);
    return 0;
}

int echo_xbox_dir_list(
    const char *wire_path,
    size_t wire_path_length,
    uint16_t max_entries,
    echo_directory_entry_callback callback,
    void *callback_context,
    echo_directory_list_result *result
) {
    echo_directory_entry first;
    echo_directory_entry second;
    (void)wire_path;
    (void)wire_path_length;
    if (g_dir_list_fail) return -2;
    assert(max_entries >= 2U);

    memset(&first, 0, sizeof(first));
    memcpy(first.name, "Content", 7U);
    first.name_length = 7U;
    first.object_type = ECHO_OBJECT_DIRECTORY;
    first.size = 999U;

    memset(&second, 0, sizeof(second));
    memcpy(second.name, "default.xex", 11U);
    second.name_length = 11U;
    second.object_type = ECHO_OBJECT_FILE;
    second.size = 4096U;

    if (callback(&first, callback_context) != 0) return -2;
    if (callback(&second, callback_context) != 0) return -2;
    result->status = ECHO_STATUS_LIMIT_REACHED;
    result->limit_reached = 1U;
    result->emitted_entries = 2U;
    return 0;
}

void echo_xbox_make_doctor_telemetry_payload(uint8_t out[ECHO_DOCTOR_TELEMETRY_BYTES]) {
    uint32_t i;
    g_doctor_calls++;
    for (i = 0U; i < ECHO_DOCTOR_TELEMETRY_BYTES; ++i) out[i] = (uint8_t)(0x80U + i);
}

static echo_readonly_dispatch_plan make_plan(
    uint8_t type,
    const uint8_t *body,
    uint32_t body_length
) {
    echo_readonly_dispatch_plan plan;
    assert(echo_ro_plan_verified_request(type, 0U, body, body_length, &plan) == 0);
    return plan;
}

static void test_fixed_responses(void) {
    uint8_t payload[128];
    echo_readonly_xbox_response response = {0U, payload, sizeof(payload), 0U};
    echo_readonly_dispatch_plan plan;

    plan = make_plan(ECHO_TYPE_CORE_INFO_REQUEST, NULL, 0U);
    assert(echo_ro_execute_xbox(&plan, 1, &response) == 0);
    assert(response.response_type == ECHO_TYPE_CORE_INFO_RESPONSE);
    assert(response.payload_length == ECHO_CORE_INFO_BYTES);
    assert(payload[0] == 0x40U);
    assert(g_core_info_calls == 1U);

    plan = make_plan(ECHO_TYPE_CURRENT_TITLE_REQUEST, NULL, 0U);
    assert(echo_ro_execute_xbox(&plan, 1, &response) == 0);
    assert(response.response_type == ECHO_TYPE_CURRENT_TITLE_RESPONSE);
    assert(response.payload_length == ECHO_CURRENT_TITLE_BYTES);
    assert(echo_ro_read_be32(payload) == UINT32_C(0x465307E4));
    assert(g_current_title_calls == 1U);

    plan = make_plan(ECHO_TYPE_DOCTOR_TELEMETRY_REQUEST, NULL, 0U);
    assert(echo_ro_execute_xbox(&plan, 1, &response) == 0);
    assert(response.response_type == ECHO_TYPE_DOCTOR_TELEMETRY_RESPONSE);
    assert(response.payload_length == ECHO_DOCTOR_TELEMETRY_BYTES);
    assert(payload[0] == 0x80U);
    assert(g_doctor_calls == 1U);
}

static void test_file_stat_and_adapter_failure(void) {
    static const uint8_t path[] = {'H','d','d','1',':','/','G','a','m','e','s'};
    uint8_t payload[ECHO_FILE_STAT_BYTES];
    echo_readonly_xbox_response response = {0U, payload, sizeof(payload), 0U};
    echo_readonly_dispatch_plan plan = make_plan(
        ECHO_TYPE_FILE_STAT_REQUEST, path, sizeof(path)
    );

    g_file_stat_fail = 0;
    assert(echo_ro_execute_xbox(&plan, 1, &response) == 0);
    assert(response.payload_length == ECHO_FILE_STAT_BYTES);
    assert(payload[0] == ECHO_STATUS_OK);
    assert(payload[1] == ECHO_OBJECT_FILE);
    assert(echo_ro_read_be64(payload + 8U) == UINT64_C(0x123456789ABC));

    g_file_stat_fail = 1;
    assert(echo_ro_execute_xbox(&plan, 1, &response) == ECHO_RO_XBOX_ADAPTER_ERROR);
    assert(response.payload_length == 0U);
    g_file_stat_fail = 0;
}

static void test_dir_list_encoding(void) {
    static const uint8_t path[] = {'H','d','d','1',':','/','G','a','m','e','s'};
    uint8_t request[2U + sizeof(path)];
    uint8_t payload[
        ECHO_DIR_LIST_HEADER_BYTES + 2U * (ECHO_DIR_ENTRY_HEADER_BYTES + ECHO_MAX_NAME_BYTES)
    ];
    echo_readonly_xbox_response response = {0U, payload, sizeof(payload), 0U};
    echo_readonly_dispatch_plan plan;
    uint32_t offset;

    request[0] = 0U;
    request[1] = 2U;
    memcpy(request + 2U, path, sizeof(path));
    plan = make_plan(ECHO_TYPE_DIR_LIST_REQUEST, request, sizeof(request));

    g_dir_list_fail = 0;
    assert(echo_ro_execute_xbox(&plan, 1, &response) == 0);
    assert(response.response_type == ECHO_TYPE_DIR_LIST_RESPONSE);
    assert(payload[0] == ECHO_STATUS_LIMIT_REACHED);
    assert(payload[1] == 1U);
    assert(echo_ro_read_be16(payload + 2U) == 2U);

    offset = ECHO_DIR_LIST_HEADER_BYTES;
    assert(payload[offset] == ECHO_OBJECT_DIRECTORY);
    assert(echo_ro_read_be16(payload + offset + 2U) == 7U);
    assert(echo_ro_read_be64(payload + offset + 4U) == 0U);
    assert(memcmp(payload + offset + ECHO_DIR_ENTRY_HEADER_BYTES, "Content", 7U) == 0);
    offset += ECHO_DIR_ENTRY_HEADER_BYTES + 7U;

    assert(payload[offset] == ECHO_OBJECT_FILE);
    assert(echo_ro_read_be16(payload + offset + 2U) == 11U);
    assert(echo_ro_read_be64(payload + offset + 4U) == 4096U);
    assert(memcmp(payload + offset + ECHO_DIR_ENTRY_HEADER_BYTES, "default.xex", 11U) == 0);
    offset += ECHO_DIR_ENTRY_HEADER_BYTES + 11U;
    assert(response.payload_length == offset);

    g_dir_list_fail = 1;
    assert(echo_ro_execute_xbox(&plan, 1, &response) == ECHO_RO_XBOX_ADAPTER_ERROR);
    assert(response.payload_length == 0U);
    g_dir_list_fail = 0;
}

static void test_capacity_and_invalid_plan_fail_closed(void) {
    uint8_t payload[8];
    echo_readonly_xbox_response response = {0U, payload, sizeof(payload), 0U};
    echo_readonly_dispatch_plan plan = make_plan(ECHO_TYPE_CORE_INFO_REQUEST, NULL, 0U);

    assert(echo_ro_execute_xbox(&plan, 1, &response) == ECHO_RO_XBOX_BUFFER_TOO_SMALL);
    assert(response.payload_length == 0U);
    assert(echo_ro_execute_xbox(NULL, 1, &response) == ECHO_RO_XBOX_INVALID_ARGUMENT);

    echo_ro_dispatch_plan_reset(&plan);
    assert(echo_ro_execute_xbox(&plan, 1, &response) == ECHO_RO_XBOX_INVALID_ARGUMENT);
}

int main(void) {
    test_fixed_responses();
    test_file_stat_and_adapter_failure();
    test_dir_list_encoding();
    test_capacity_and_invalid_plan_fail_closed();
    puts("EchoCore Xbox read-only executor tests: OK");
    return 0;
}
