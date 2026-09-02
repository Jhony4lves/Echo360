#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../openxechain/echo_command_auth_xbox.c"
#include "../openxechain/echo_readonly_dispatch_xbox.c"
#include "../openxechain/echo_request_pipeline_xbox.c"

static uint32_t g_core_calls;
static uint32_t g_stat_calls;
static uint32_t g_dir_calls;
static uint32_t g_doctor_calls;

static uint32_t mock_hmac_mix(uint32_t value, const uint8_t *bytes, uint32_t length) {
    uint32_t i;
    for (i = 0U; i < length; ++i) {
        value ^= bytes[i];
        value *= UINT32_C(16777619);
    }
    return value;
}

void XeCryptHmacShaInit(void *state, const uint8_t *key, uint32_t key_size) {
    uint32_t *words = (uint32_t *)state;
    words[0] = mock_hmac_mix(UINT32_C(2166136261), key, key_size);
    words[1] = key_size;
    words[2] = UINT32_C(0x10203040);
}

void XeCryptHmacShaUpdate(void *state, const uint8_t *input, uint32_t input_size) {
    uint32_t *words = (uint32_t *)state;
    words[0] = mock_hmac_mix(words[0], input, input_size);
    words[1] += input_size;
    words[2] ^= words[0] + input_size;
}

void XeCryptHmacShaFinal(void *state, uint8_t *digest, uint32_t digest_size) {
    uint32_t *words = (uint32_t *)state;
    uint32_t i;
    for (i = 0U; i < digest_size; ++i) {
        uint32_t word = words[i % 3U] ^
            (UINT32_C(0x31415926) + i * UINT32_C(0x01020304));
        digest[i] = (uint8_t)(word >> ((i & 3U) * 8U));
    }
}

void echo_xbox_make_core_info_payload(uint8_t out[ECHO_CORE_INFO_BYTES], int resident_plugin) {
    g_core_calls++;
    echo_ro_make_core_info(
        out,
        UINT32_C(0x00010000),
        UINT32_C(0x20449700),
        UINT32_C(0x465307E4),
        ECHO_CAP_PING | ECHO_CAP_CORE_INFO | ECHO_CAP_CURRENT_TITLE |
            ECHO_CAP_FILE_STAT | ECHO_CAP_DIR_LIST | ECHO_CAP_DOCTOR_TELEMETRY,
        resident_plugin ? ECHO_CORE_STATUS_RESIDENT_PLUGIN : 0U
    );
}

void echo_xbox_make_current_title_payload(uint8_t out[ECHO_CURRENT_TITLE_BYTES]) {
    echo_ro_write_be32(out, UINT32_C(0x465307E4));
}

int echo_xbox_file_stat(
    const char *wire_path,
    size_t wire_path_length,
    echo_file_stat_result *result
) {
    (void)wire_path;
    (void)wire_path_length;
    g_stat_calls++;
    result->status = ECHO_STATUS_OK;
    result->object_type = ECHO_OBJECT_FILE;
    result->size = 1234U;
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
    (void)wire_path;
    (void)wire_path_length;
    (void)max_entries;
    (void)callback;
    (void)callback_context;
    g_dir_calls++;
    result->status = ECHO_STATUS_OK;
    result->limit_reached = 0U;
    result->emitted_entries = 0U;
    return 0;
}

void echo_xbox_make_doctor_telemetry_payload(uint8_t out[ECHO_DOCTOR_TELEMETRY_BYTES]) {
    echo_doctor_memory memory = {ECHO_STATUS_OK, 100U, 200U, 300U};
    echo_doctor_temperature temperature = {ECHO_STATUS_OK, 0x3000U, 0x3100U, 0x3200U, 0x2F00U};
    g_doctor_calls++;
    echo_doctor_make_payload(out, &memory, &temperature);
}

static void make_secret(uint8_t secret[ECHO_AUTH_SECRET_BYTES]) {
    uint32_t i;
    for (i = 0U; i < ECHO_AUTH_SECRET_BYTES; ++i) secret[i] = (uint8_t)(0x60U + i);
}

static void authenticate(echo_auth_state *state, uint64_t caps) {
    uint8_t challenge[ECHO_AUTH_CHALLENGE_BYTES];
    uint32_t i;
    for (i = 0U; i < ECHO_AUTH_CHALLENGE_BYTES; ++i) challenge[i] = (uint8_t)(i + 1U);
    echo_auth_session_end(state);
    assert(echo_auth_session_begin(state, UINT64_C(0x8877665544332211), challenge) == 0);
    assert(echo_auth_mark_authenticated(state, 1U, caps) == 0);
}

static uint32_t make_envelope(
    uint8_t *out,
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    const echo_auth_state *state,
    uint64_t counter,
    uint8_t type,
    uint16_t flags,
    uint32_t request_id,
    const uint8_t *body,
    uint32_t body_length
) {
    uint8_t mac[ECHO_AUTH_HMAC_SHA1_BYTES];
    uint32_t i;

    echo_command_auth_write_be64(out, counter);
    assert(echo_command_auth_xbox_make_mac(
        secret, state, counter, type, flags, request_id, body, body_length, mac
    ) == 0);
    for (i = 0U; i < ECHO_AUTH_HMAC_SHA1_BYTES; ++i) {
        out[ECHO_COMMAND_AUTH_COUNTER_BYTES + i] = mac[i];
    }
    for (i = 0U; i < body_length; ++i) {
        out[ECHO_COMMAND_AUTH_PREFIX_BYTES + i] = body[i];
    }
    return ECHO_COMMAND_AUTH_PREFIX_BYTES + body_length;
}

static void test_valid_core_info_and_replay(void) {
    echo_auth_state state;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t envelope[ECHO_COMMAND_AUTH_PREFIX_BYTES];
    uint8_t payload[ECHO_CORE_INFO_BYTES];
    echo_readonly_xbox_response response = {0U, payload, sizeof(payload), 0U};
    uint32_t envelope_length;

    make_secret(secret);
    authenticate(&state, ECHO_AUTH_CAP_READ_INFO);
    envelope_length = make_envelope(
        envelope, secret, &state, 2U, ECHO_TYPE_CORE_INFO_REQUEST, 0U, 77U, NULL, 0U
    );

    assert(echo_xbox_process_authenticated_readonly(
        secret, &state, ECHO_TYPE_CORE_INFO_REQUEST, 0U, 77U,
        envelope, envelope_length, 1, &response
    ) == ECHO_PIPELINE_OK);
    assert(state.last_rx_counter == 2U);
    assert(g_core_calls == 1U);
    assert(response.response_type == ECHO_TYPE_CORE_INFO_RESPONSE);
    assert(response.payload_length == ECHO_CORE_INFO_BYTES);
    assert(echo_ro_read_be16(payload) == ECHO_RO_CONTRACT_VERSION);
    assert((echo_ro_read_be32(payload + 24U) & ECHO_CORE_STATUS_RESIDENT_PLUGIN) != 0U);

    response.payload_length = 99U;
    assert(echo_xbox_process_authenticated_readonly(
        secret, &state, ECHO_TYPE_CORE_INFO_REQUEST, 0U, 77U,
        envelope, envelope_length, 1, &response
    ) == ECHO_PIPELINE_AUTH_FAILED);
    assert(g_core_calls == 1U);
    assert(response.response_type == 0U);
    assert(response.payload_length == 0U);
}

static void test_tamper_never_reaches_filesystem(void) {
    echo_auth_state state;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    static const uint8_t path[] = {'H','d','d','1',':','/','x'};
    uint8_t envelope[64];
    uint8_t payload[ECHO_FILE_STAT_BYTES];
    echo_readonly_xbox_response response = {0U, payload, sizeof(payload), 0U};
    uint32_t length;
    uint32_t calls_before = g_stat_calls;

    make_secret(secret);
    authenticate(&state, ECHO_AUTH_CAP_READ_FILESYSTEM);
    length = make_envelope(
        envelope, secret, &state, 2U, ECHO_TYPE_FILE_STAT_REQUEST, 0U, 4U,
        path, sizeof(path)
    );
    envelope[ECHO_COMMAND_AUTH_PREFIX_BYTES + 6U] ^= 1U;

    assert(echo_xbox_process_authenticated_readonly(
        secret, &state, ECHO_TYPE_FILE_STAT_REQUEST, 0U, 4U,
        envelope, length, 1, &response
    ) == ECHO_PIPELINE_AUTH_FAILED);
    assert(state.last_rx_counter == 1U);
    assert(g_stat_calls == calls_before);
}

static void test_missing_filesystem_permission_fails_before_adapter(void) {
    echo_auth_state state;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    static const uint8_t path[] = {'H','d','d','1',':','/','x'};
    uint8_t envelope[64];
    uint8_t payload[ECHO_FILE_STAT_BYTES];
    echo_readonly_xbox_response response = {0U, payload, sizeof(payload), 0U};
    uint32_t length;
    uint32_t calls_before = g_stat_calls;

    make_secret(secret);
    authenticate(&state, ECHO_AUTH_CAP_READ_INFO);
    length = make_envelope(
        envelope, secret, &state, 2U, ECHO_TYPE_FILE_STAT_REQUEST, 0U, 8U,
        path, sizeof(path)
    );

    assert(echo_xbox_process_authenticated_readonly(
        secret, &state, ECHO_TYPE_FILE_STAT_REQUEST, 0U, 8U,
        envelope, length, 1, &response
    ) == ECHO_PIPELINE_AUTH_FAILED);
    assert(state.last_rx_counter == 1U);
    assert(g_stat_calls == calls_before);
}

static void test_signed_invalid_body_consumes_once_but_never_executes(void) {
    echo_auth_state state;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    static const uint8_t invalid_path[] = {'H','d','d','1',':','/',0U,'x'};
    uint8_t envelope[64];
    uint8_t payload[ECHO_FILE_STAT_BYTES];
    echo_readonly_xbox_response response = {0U, payload, sizeof(payload), 0U};
    uint32_t length;
    uint32_t calls_before = g_stat_calls;

    make_secret(secret);
    authenticate(&state, ECHO_AUTH_CAP_READ_FILESYSTEM);
    length = make_envelope(
        envelope, secret, &state, 2U, ECHO_TYPE_FILE_STAT_REQUEST, 0U, 9U,
        invalid_path, sizeof(invalid_path)
    );

    assert(echo_xbox_process_authenticated_readonly(
        secret, &state, ECHO_TYPE_FILE_STAT_REQUEST, 0U, 9U,
        envelope, length, 1, &response
    ) == ECHO_PIPELINE_INVALID_BODY);
    assert(state.last_rx_counter == 2U);
    assert(g_stat_calls == calls_before);

    assert(echo_xbox_process_authenticated_readonly(
        secret, &state, ECHO_TYPE_FILE_STAT_REQUEST, 0U, 9U,
        envelope, length, 1, &response
    ) == ECHO_PIPELINE_AUTH_FAILED);
    assert(g_stat_calls == calls_before);
}

static void test_execution_failure_is_fail_closed_after_auth(void) {
    echo_auth_state state;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t envelope[ECHO_COMMAND_AUTH_PREFIX_BYTES];
    uint8_t tiny[1];
    echo_readonly_xbox_response response = {0U, tiny, sizeof(tiny), 0U};
    uint32_t length;
    uint32_t calls_before = g_core_calls;

    make_secret(secret);
    authenticate(&state, ECHO_AUTH_CAP_READ_INFO);
    length = make_envelope(
        envelope, secret, &state, 2U, ECHO_TYPE_CORE_INFO_REQUEST, 0U, 10U, NULL, 0U
    );

    assert(echo_xbox_process_authenticated_readonly(
        secret, &state, ECHO_TYPE_CORE_INFO_REQUEST, 0U, 10U,
        envelope, length, 1, &response
    ) == ECHO_PIPELINE_EXECUTION_FAILED);
    assert(state.last_rx_counter == 2U);
    assert(g_core_calls == calls_before);
    assert(response.response_type == 0U);
    assert(response.payload_length == 0U);
}

static void test_unsupported_type_does_not_consume_counter(void) {
    echo_auth_state state;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t envelope[ECHO_COMMAND_AUTH_PREFIX_BYTES] = {0};
    uint8_t payload[32];
    echo_readonly_xbox_response response = {0U, payload, sizeof(payload), 0U};

    make_secret(secret);
    authenticate(&state, ECHO_AUTH_CAP_READ_INFO);
    assert(echo_xbox_process_authenticated_readonly(
        secret, &state, ECHO_TYPE_CORE_INFO_RESPONSE, 0U, 1U,
        envelope, sizeof(envelope), 1, &response
    ) == ECHO_PIPELINE_UNSUPPORTED_TYPE);
    assert(state.last_rx_counter == 1U);
}

int main(void) {
    test_valid_core_info_and_replay();
    test_tamper_never_reaches_filesystem();
    test_missing_filesystem_permission_fails_before_adapter();
    test_signed_invalid_body_consumes_once_but_never_executes();
    test_execution_failure_is_fail_closed_after_auth();
    test_unsupported_type_does_not_consume_counter();
    puts("EchoCore authenticated Xbox request pipeline tests: OK");
    return 0;
}
