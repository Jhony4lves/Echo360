#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define _start echo_test_resident_entry
#include "../openxechain/echo_core_resident.c"
#undef _start

static uint32_t g_create_calls;
static uint32_t g_wait_calls;
static uint32_t g_close_calls;
static uint32_t g_pairing_calls;
static uint32_t g_server_calls;
static uint32_t g_last_creation_flags;
static uint32_t g_last_handle;
static int g_create_status;
static int g_pairing_status;
static int g_server_status;
static void *g_last_xapi_startup;
static void *g_last_start_address;
static void *g_last_start_context;
static uint8_t g_pairing_secret[ECHO_AUTH_SECRET_BYTES];
static uint8_t g_server_secret[ECHO_AUTH_SECRET_BYTES];
static volatile uint32_t *g_server_stop_ptr;

int ExCreateThread(
    uint32_t *handle_ptr,
    uint32_t stack_size,
    uint32_t *thread_id_ptr,
    void *xapi_thread_startup,
    void *start_address,
    void *start_context,
    uint32_t creation_flags
) {
    (void)stack_size;
    (void)thread_id_ptr;
    g_create_calls++;
    g_last_xapi_startup = xapi_thread_startup;
    g_last_start_address = start_address;
    g_last_start_context = start_context;
    g_last_creation_flags = creation_flags;
    if (g_create_status < 0) return g_create_status;
    *handle_ptr = UINT32_C(0x51515151);
    g_last_handle = *handle_ptr;
    return 0;
}

int NtWaitForSingleObjectEx(
    uint32_t object_handle,
    uint32_t wait_mode,
    uint32_t alertable,
    int64_t *timeout_ptr
) {
    assert(object_handle == UINT32_C(0x51515151));
    assert(wait_mode == ECHO_WAIT_MODE_KERNEL);
    assert(alertable == ECHO_NOT_ALERTABLE);
    assert(timeout_ptr == NULL);
    g_wait_calls++;
    return 0;
}

int NtClose(uint32_t handle) {
    assert(handle == UINT32_C(0x51515151));
    g_close_calls++;
    return 0;
}

int echo_pairing_xbox_load_secret(uint8_t secret_out[ECHO_AUTH_SECRET_BYTES]) {
    g_pairing_calls++;
    if (g_pairing_status == ECHO_PAIRING_STORE_OK) {
        memcpy(secret_out, g_pairing_secret, ECHO_AUTH_SECRET_BYTES);
    }
    return g_pairing_status;
}

int echo_xbox_run_paired_readonly_server(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    volatile uint32_t *stop_requested
) {
    g_server_calls++;
    memcpy(g_server_secret, secret, ECHO_AUTH_SECRET_BYTES);
    g_server_stop_ptr = stop_requested;
    return g_server_status;
}

static int bytes_are_zero(const uint8_t *bytes, size_t length) {
    size_t i;
    uint8_t any = 0U;
    for (i = 0U; i < length; ++i) any = (uint8_t)(any | bytes[i]);
    return any == 0U;
}

static void reset_fixture(void) {
    uint32_t i;
    g_create_calls = 0U;
    g_wait_calls = 0U;
    g_close_calls = 0U;
    g_pairing_calls = 0U;
    g_server_calls = 0U;
    g_last_creation_flags = 0U;
    g_last_handle = 0U;
    g_create_status = 0;
    g_pairing_status = ECHO_PAIRING_STORE_NOT_FOUND;
    g_server_status = ECHO_NET_STOPPED;
    g_last_xapi_startup = (void *)(uintptr_t)1U;
    g_last_start_address = NULL;
    g_last_start_context = (void *)(uintptr_t)1U;
    g_server_stop_ptr = NULL;
    memset(g_pairing_secret, 0, sizeof(g_pairing_secret));
    memset(g_server_secret, 0, sizeof(g_server_secret));
    for (i = 0U; i < ECHO_AUTH_SECRET_BYTES; ++i) {
        g_pairing_secret[i] = (uint8_t)(0x80U + i);
    }

    g_echo_resident_stop_requested = 1U;
    g_echo_resident_worker_running = 0U;
    g_echo_resident_thread_handle = ECHO_RESIDENT_THREAD_HANDLE_NONE;
    memset(g_echo_resident_secret, 0, sizeof(g_echo_resident_secret));
    g_echo_resident_last_pairing_status = ECHO_PAIRING_STORE_NOT_FOUND;
    g_echo_resident_last_server_status = ECHO_NET_STOPPED;
}

static void run_captured_worker(void) {
    uint32_t (*worker)(void *);
    assert(g_last_start_address != NULL);
    memcpy(&worker, &g_last_start_address, sizeof(worker));
    assert(worker(g_last_start_context) == 0U);
}

static void test_attach_creates_one_raw_system_thread(void) {
    reset_fixture();
    assert(echo_test_resident_entry(NULL, ECHO_DLL_PROCESS_ATTACH, NULL) == 1);
    assert(g_create_calls == 1U);
    assert(g_last_creation_flags == ECHO_EX_CREATE_FLAG_SYSTEM);
    assert(g_last_xapi_startup == NULL);
    assert(g_last_start_context == NULL);
    assert(g_last_start_address != NULL);
    assert(g_echo_resident_thread_handle == UINT32_C(0x51515151));
    assert(g_echo_resident_stop_requested == 0U);

    assert(echo_test_resident_entry(NULL, ECHO_DLL_PROCESS_ATTACH, NULL) == 1);
    assert(g_create_calls == 1U);
}

static void test_thread_creation_failure_fails_attach_closed(void) {
    reset_fixture();
    g_create_status = -1;
    memset(g_echo_resident_secret, 0xAA, sizeof(g_echo_resident_secret));
    assert(echo_test_resident_entry(NULL, ECHO_DLL_PROCESS_ATTACH, NULL) == 0);
    assert(g_create_calls == 1U);
    assert(g_echo_resident_stop_requested == 1U);
    assert(g_echo_resident_thread_handle == ECHO_RESIDENT_THREAD_HANDLE_NONE);
    assert(bytes_are_zero(g_echo_resident_secret, sizeof(g_echo_resident_secret)));
}

static void test_missing_pairing_never_starts_listener(void) {
    reset_fixture();
    assert(echo_test_resident_entry(NULL, ECHO_DLL_PROCESS_ATTACH, NULL) == 1);
    g_pairing_status = ECHO_PAIRING_STORE_NOT_FOUND;
    run_captured_worker();
    assert(g_pairing_calls == 1U);
    assert(g_server_calls == 0U);
    assert(g_echo_resident_last_pairing_status == ECHO_PAIRING_STORE_NOT_FOUND);
    assert(g_echo_resident_worker_running == 0U);
    assert(bytes_are_zero(g_echo_resident_secret, sizeof(g_echo_resident_secret)));
}

static void test_valid_pairing_reaches_server_then_secret_is_wiped(void) {
    reset_fixture();
    assert(echo_test_resident_entry(NULL, ECHO_DLL_PROCESS_ATTACH, NULL) == 1);
    g_pairing_status = ECHO_PAIRING_STORE_OK;
    g_server_status = ECHO_NET_IO_ERROR;
    run_captured_worker();
    assert(g_pairing_calls == 1U);
    assert(g_server_calls == 1U);
    assert(memcmp(g_server_secret, g_pairing_secret, ECHO_AUTH_SECRET_BYTES) == 0);
    assert(g_server_stop_ptr == &g_echo_resident_stop_requested);
    assert(g_echo_resident_last_pairing_status == ECHO_PAIRING_STORE_OK);
    assert(g_echo_resident_last_server_status == ECHO_NET_IO_ERROR);
    assert(bytes_are_zero(g_echo_resident_secret, sizeof(g_echo_resident_secret)));
}

static void test_pre_requested_stop_skips_pairing_and_server(void) {
    reset_fixture();
    assert(echo_test_resident_entry(NULL, ECHO_DLL_PROCESS_ATTACH, NULL) == 1);
    g_echo_resident_stop_requested = 1U;
    run_captured_worker();
    assert(g_pairing_calls == 0U);
    assert(g_server_calls == 0U);
    assert(bytes_are_zero(g_echo_resident_secret, sizeof(g_echo_resident_secret)));
}

static void test_detach_requests_stop_joins_closes_and_wipes(void) {
    reset_fixture();
    assert(echo_test_resident_entry(NULL, ECHO_DLL_PROCESS_ATTACH, NULL) == 1);
    memset(g_echo_resident_secret, 0xCC, sizeof(g_echo_resident_secret));
    g_echo_resident_worker_running = 1U;

    assert(echo_test_resident_entry(NULL, ECHO_DLL_PROCESS_DETACH, NULL) == 1);
    assert(g_echo_resident_stop_requested == 1U);
    assert(g_wait_calls == 1U);
    assert(g_close_calls == 1U);
    assert(g_echo_resident_thread_handle == ECHO_RESIDENT_THREAD_HANDLE_NONE);
    assert(g_echo_resident_worker_running == 0U);
    assert(bytes_are_zero(g_echo_resident_secret, sizeof(g_echo_resident_secret)));
}

static void test_thread_notifications_are_noops(void) {
    reset_fixture();
    assert(echo_test_resident_entry(NULL, ECHO_DLL_THREAD_ATTACH, NULL) == 1);
    assert(echo_test_resident_entry(NULL, ECHO_DLL_THREAD_DETACH, NULL) == 1);
    assert(echo_test_resident_entry(NULL, 99U, NULL) == 1);
    assert(g_create_calls == 0U);
    assert(g_wait_calls == 0U);
}

int main(void) {
    test_attach_creates_one_raw_system_thread();
    test_thread_creation_failure_fails_attach_closed();
    test_missing_pairing_never_starts_listener();
    test_valid_pairing_reaches_server_then_secret_is_wiped();
    test_pre_requested_stop_skips_pairing_and_server();
    test_detach_requests_stop_joins_closes_and_wipes();
    test_thread_notifications_are_noops();
    puts("EchoCore resident plugin lifecycle tests: OK");
    return 0;
}
