#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../openxechain/echo_readonly_dispatch.h"

#ifdef ECHO_CAP_READ_INFO
#error "security capability leaked into public ECHO_CAP namespace"
#endif
#ifdef ECHO_CAP_READ_FILESYSTEM
#error "security capability leaked into public ECHO_CAP namespace"
#endif
#ifdef ECHO_AUTH_CAP_CORE_INFO
#error "public feature capability leaked into ECHO_AUTH_CAP namespace"
#endif

static void test_permission_resolution_is_body_independent(void) {
    uint64_t cap = UINT64_C(0xFFFFFFFFFFFFFFFF);

    assert(echo_ro_required_auth_capability(ECHO_TYPE_CORE_INFO_REQUEST, &cap) == 0);
    assert(cap == ECHO_AUTH_CAP_READ_INFO);
    assert(echo_ro_required_auth_capability(ECHO_TYPE_CURRENT_TITLE_REQUEST, &cap) == 0);
    assert(cap == ECHO_AUTH_CAP_READ_INFO);
    assert(echo_ro_required_auth_capability(ECHO_TYPE_DOCTOR_TELEMETRY_REQUEST, &cap) == 0);
    assert(cap == ECHO_AUTH_CAP_READ_INFO);

    assert(echo_ro_required_auth_capability(ECHO_TYPE_FILE_STAT_REQUEST, &cap) == 0);
    assert(cap == ECHO_AUTH_CAP_READ_FILESYSTEM);
    assert(echo_ro_required_auth_capability(ECHO_TYPE_DIR_LIST_REQUEST, &cap) == 0);
    assert(cap == ECHO_AUTH_CAP_READ_FILESYSTEM);

    assert(echo_ro_required_auth_capability(ECHO_TYPE_FILE_STAT_RESPONSE, &cap) == ECHO_RO_DISPATCH_UNSUPPORTED_TYPE);
    assert(cap == 0U);
    assert(echo_ro_required_auth_capability(ECHO_TYPE_ERROR, &cap) == ECHO_RO_DISPATCH_UNSUPPORTED_TYPE);
    assert(echo_ro_required_auth_capability(ECHO_TYPE_CORE_INFO_REQUEST, NULL) == ECHO_RO_DISPATCH_INVALID_ARGUMENT);
}

static void test_fixed_body_operations(void) {
    echo_readonly_dispatch_plan plan;
    uint8_t byte = 0U;

    assert(echo_ro_plan_verified_request(
        ECHO_TYPE_CORE_INFO_REQUEST, 0U, NULL, 0U, &plan
    ) == 0);
    assert(plan.operation == ECHO_RO_OP_CORE_INFO);
    assert(plan.response_type == ECHO_TYPE_CORE_INFO_RESPONSE);
    assert(plan.required_auth_capability == ECHO_AUTH_CAP_READ_INFO);

    assert(echo_ro_plan_verified_request(
        ECHO_TYPE_CURRENT_TITLE_REQUEST, 0U, NULL, 0U, &plan
    ) == 0);
    assert(plan.operation == ECHO_RO_OP_CURRENT_TITLE);
    assert(plan.response_type == ECHO_TYPE_CURRENT_TITLE_RESPONSE);

    assert(echo_ro_plan_verified_request(
        ECHO_TYPE_DOCTOR_TELEMETRY_REQUEST, 0U, NULL, 0U, &plan
    ) == 0);
    assert(plan.operation == ECHO_RO_OP_DOCTOR_TELEMETRY);
    assert(plan.response_type == ECHO_TYPE_DOCTOR_TELEMETRY_RESPONSE);

    assert(echo_ro_plan_verified_request(
        ECHO_TYPE_CORE_INFO_REQUEST, 0U, &byte, 1U, &plan
    ) == ECHO_RO_DISPATCH_INVALID_BODY);
    assert(plan.operation == ECHO_RO_OP_NONE);

    assert(echo_ro_plan_verified_request(
        ECHO_TYPE_CURRENT_TITLE_REQUEST, 1U, NULL, 0U, &plan
    ) == ECHO_RO_DISPATCH_INVALID_FLAGS);
    assert(plan.operation == ECHO_RO_OP_NONE);
}

static void test_file_stat_path_is_verified_body(void) {
    echo_readonly_dispatch_plan plan;
    static const uint8_t path[] = {'/','H','d','d','1','/','G','a','m','e','s'};
    static const uint8_t bad_path[] = {'/','H','d','d','1',0,'x'};

    assert(echo_ro_plan_verified_request(
        ECHO_TYPE_FILE_STAT_REQUEST, 0U, path, sizeof(path), &plan
    ) == 0);
    assert(plan.operation == ECHO_RO_OP_FILE_STAT);
    assert(plan.response_type == ECHO_TYPE_FILE_STAT_RESPONSE);
    assert(plan.required_auth_capability == ECHO_AUTH_CAP_READ_FILESYSTEM);
    assert(plan.path == path);
    assert(plan.path_length == sizeof(path));

    assert(echo_ro_plan_verified_request(
        ECHO_TYPE_FILE_STAT_REQUEST, 0U, NULL, 0U, &plan
    ) == ECHO_RO_DISPATCH_INVALID_BODY);
    assert(echo_ro_plan_verified_request(
        ECHO_TYPE_FILE_STAT_REQUEST, 0U, bad_path, sizeof(bad_path), &plan
    ) == ECHO_RO_DISPATCH_INVALID_BODY);
}

static void test_dir_list_v1_body_contract(void) {
    echo_readonly_dispatch_plan plan;
    uint8_t request[2U + 11U];
    static const uint8_t path[] = {'/','H','d','d','1','/','G','a','m','e','s'};

    request[0] = 0U;
    request[1] = 32U;
    memcpy(request + 2U, path, sizeof(path));

    assert(echo_ro_plan_verified_request(
        ECHO_TYPE_DIR_LIST_REQUEST, 0U, request, sizeof(request), &plan
    ) == 0);
    assert(plan.operation == ECHO_RO_OP_DIR_LIST);
    assert(plan.response_type == ECHO_TYPE_DIR_LIST_RESPONSE);
    assert(plan.required_auth_capability == ECHO_AUTH_CAP_READ_FILESYSTEM);
    assert(plan.max_entries == 32U);
    assert(plan.path_length == sizeof(path));
    assert(memcmp(plan.path, path, sizeof(path)) == 0);

    request[0] = 0U;
    request[1] = 0U;
    assert(echo_ro_plan_verified_request(
        ECHO_TYPE_DIR_LIST_REQUEST, 0U, request, sizeof(request), &plan
    ) == ECHO_RO_DISPATCH_INVALID_BODY);

    request[0] = 1U;
    request[1] = 1U; /* 257 > ECHO_MAX_DIR_ENTRIES */
    assert(echo_ro_plan_verified_request(
        ECHO_TYPE_DIR_LIST_REQUEST, 0U, request, sizeof(request), &plan
    ) == ECHO_RO_DISPATCH_INVALID_BODY);

    assert(echo_ro_plan_verified_request(
        ECHO_TYPE_DIR_LIST_REQUEST, 0U, request, 2U, &plan
    ) == ECHO_RO_DISPATCH_INVALID_BODY);
}

static void test_unknown_and_null_fail_closed(void) {
    echo_readonly_dispatch_plan plan;

    assert(echo_ro_plan_verified_request(0x66U, 0U, NULL, 0U, &plan) == ECHO_RO_DISPATCH_UNSUPPORTED_TYPE);
    assert(plan.operation == ECHO_RO_OP_NONE);
    assert(echo_ro_plan_verified_request(ECHO_TYPE_CORE_INFO_REQUEST, 0U, NULL, 0U, NULL) == ECHO_RO_DISPATCH_INVALID_ARGUMENT);
}

int main(void) {
    test_permission_resolution_is_body_independent();
    test_fixed_body_operations();
    test_file_stat_path_is_verified_body();
    test_dir_list_v1_body_contract();
    test_unknown_and_null_fail_closed();
    puts("EchoCore read-only dispatch planner tests: OK");
    return 0;
}
