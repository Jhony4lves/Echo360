#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../openxechain/echo_session_engine_xbox.c"

static int g_begin_fail;
static int g_verify_fail;
static uint32_t g_begin_calls;
static uint32_t g_verify_calls;
static uint32_t g_pipeline_calls;
static uint8_t g_last_pipeline_type;
static uint32_t g_last_pipeline_request_id;

int echo_auth_xbox_begin_session(echo_auth_state *state) {
    uint8_t challenge[ECHO_AUTH_CHALLENGE_BYTES];
    uint32_t i;
    g_begin_calls++;
    if (g_begin_fail) return -1;
    for (i = 0U; i < ECHO_AUTH_CHALLENGE_BYTES; ++i) {
        challenge[i] = (uint8_t)(0x30U + i + g_begin_calls);
    }
    return echo_auth_session_begin(
        state,
        UINT64_C(0x1000000000000000) + g_begin_calls,
        challenge
    );
}

int echo_auth_xbox_verify_response(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    echo_auth_state *state,
    uint64_t counter,
    uint64_t requested_capabilities,
    const uint8_t expected_mac[ECHO_AUTH_HMAC_SHA1_BYTES]
) {
    (void)secret;
    (void)expected_mac;
    g_verify_calls++;
    if (g_verify_fail) return -2;
    return echo_auth_mark_authenticated(state, counter, requested_capabilities);
}

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
    (void)secret;
    (void)auth;
    (void)flags;
    (void)envelope;
    (void)envelope_length;
    (void)resident_plugin;
    g_pipeline_calls++;
    g_last_pipeline_type = request_type;
    g_last_pipeline_request_id = request_id;
    if (response->payload_capacity < 1U) return ECHO_PIPELINE_EXECUTION_FAILED;
    response->response_type = ECHO_TYPE_CORE_INFO_RESPONSE;
    response->payload[0] = 0x5AU;
    response->payload_length = 1U;
    return ECHO_PIPELINE_OK;
}

static void make_secret(uint8_t secret[ECHO_AUTH_SECRET_BYTES]) {
    uint32_t i;
    for (i = 0U; i < ECHO_AUTH_SECRET_BYTES; ++i) secret[i] = (uint8_t)(i + 1U);
}

static echo_frame_header header(uint8_t type, uint16_t flags, uint32_t length, uint32_t id) {
    echo_frame_header h;
    h.type = type;
    h.flags = flags;
    h.payload_length = length;
    h.request_id = id;
    return h;
}

static void test_ping_is_public_and_state_neutral(void) {
    echo_xbox_session session;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    static const uint8_t nonce[ECHO_PING_PAYLOAD_BYTES] = {1,2,3,4,5,6,7,8};
    uint8_t out[32];
    echo_readonly_xbox_response response = {0U, out, sizeof(out), 0U};
    echo_frame_header ping = header(ECHO_TYPE_PING, 0U, ECHO_PING_PAYLOAD_BYTES, 90U);

    make_secret(secret);
    echo_xbox_session_reset(&session);
    assert(echo_xbox_session_process_frame(
        secret, &session, &ping, nonce, 1, &response
    ) == ECHO_SESSION_ENGINE_OK);
    assert(response.response_type == ECHO_TYPE_PONG);
    assert(response.payload_length == ECHO_PING_PAYLOAD_BYTES);
    assert(memcmp(out, nonce, sizeof(nonce)) == 0);
    assert(session.phase == ECHO_SESSION_PHASE_NEW);
    assert(session.auth.authenticated == 0U);
    assert(session.auth.last_rx_counter == 0U);

    ping.flags = 1U;
    assert(echo_xbox_session_process_frame(
        secret, &session, &ping, nonce, 1, &response
    ) == ECHO_SESSION_ENGINE_PROTOCOL_ERROR);
}

static void test_begin_creates_fresh_challenge_and_rebegin_replaces_it(void) {
    echo_xbox_session session;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t out[64];
    uint8_t first_challenge[ECHO_SESSION_CHALLENGE_BYTES];
    echo_readonly_xbox_response response = {0U, out, sizeof(out), 0U};
    echo_frame_header begin = header(ECHO_TYPE_SESSION_BEGIN_REQUEST, 0U, 0U, 1U);

    make_secret(secret);
    echo_xbox_session_reset(&session);
    g_begin_fail = 0;
    assert(echo_xbox_session_process_frame(
        secret, &session, &begin, NULL, 1, &response
    ) == ECHO_SESSION_ENGINE_OK);
    assert(session.phase == ECHO_SESSION_PHASE_CHALLENGE_SENT);
    assert(response.response_type == ECHO_TYPE_SESSION_CHALLENGE_RESPONSE);
    assert(response.payload_length == ECHO_SESSION_CHALLENGE_BYTES);
    memcpy(first_challenge, out, sizeof(first_challenge));

    assert(echo_xbox_session_process_frame(
        secret, &session, &begin, NULL, 1, &response
    ) == ECHO_SESSION_ENGINE_OK);
    assert(session.phase == ECHO_SESSION_PHASE_CHALLENGE_SENT);
    assert(memcmp(first_challenge, out, sizeof(first_challenge)) != 0);
}

static void build_auth_payload(
    uint8_t payload[ECHO_SESSION_AUTH_REQUEST_BYTES],
    uint64_t counter,
    uint64_t caps
) {
    uint32_t i;
    echo_session_write_be64(payload, counter);
    echo_session_write_be64(payload + 8U, caps);
    for (i = 0U; i < ECHO_AUTH_HMAC_SHA1_BYTES; ++i) payload[16U + i] = (uint8_t)(0xA0U + i);
}

static void test_auth_success_then_command(void) {
    echo_xbox_session session;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t auth_payload[ECHO_SESSION_AUTH_REQUEST_BYTES];
    uint8_t out[128];
    uint8_t envelope[32] = {0};
    echo_readonly_xbox_response response = {0U, out, sizeof(out), 0U};
    echo_frame_header begin = header(ECHO_TYPE_SESSION_BEGIN_REQUEST, 0U, 0U, 2U);
    echo_frame_header auth = header(ECHO_TYPE_SESSION_AUTH_REQUEST, 0U, ECHO_SESSION_AUTH_REQUEST_BYTES, 3U);
    echo_frame_header command = header(ECHO_TYPE_CORE_INFO_REQUEST, 0U, sizeof(envelope), 0x44556677U);

    make_secret(secret);
    echo_xbox_session_reset(&session);
    assert(echo_xbox_session_process_frame(secret, &session, &begin, NULL, 1, &response) == 0);

    build_auth_payload(auth_payload, 7U, ECHO_AUTH_CAP_READ_INFO | ECHO_AUTH_CAP_READ_FILESYSTEM);
    g_verify_fail = 0;
    assert(echo_xbox_session_process_frame(
        secret, &session, &auth, auth_payload, 1, &response
    ) == ECHO_SESSION_ENGINE_OK);
    assert(session.phase == ECHO_SESSION_PHASE_AUTHENTICATED);
    assert(session.auth.authenticated == 1U);
    assert(session.auth.last_rx_counter == 7U);
    assert(response.response_type == ECHO_TYPE_SESSION_AUTH_RESPONSE);
    assert(response.payload_length == ECHO_SESSION_AUTH_RESPONSE_BYTES);
    assert(out[0] == ECHO_SESSION_STATUS_OK);
    assert(echo_session_read_be64(out + 8U) == session.auth.capabilities);
    assert(echo_session_read_be64(out + 16U) == 7U);

    assert(echo_xbox_session_process_frame(
        secret, &session, &command, envelope, 1, &response
    ) == ECHO_SESSION_ENGINE_OK);
    assert(g_pipeline_calls > 0U);
    assert(g_last_pipeline_type == ECHO_TYPE_CORE_INFO_REQUEST);
    assert(g_last_pipeline_request_id == UINT32_C(0x44556677));
    assert(response.payload_length == 1U && out[0] == 0x5AU);
}

static void test_command_before_auth_is_denied(void) {
    echo_xbox_session session;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t out[32];
    uint8_t envelope[1] = {0};
    echo_readonly_xbox_response response = {0U, out, sizeof(out), 0U};
    echo_frame_header command = header(ECHO_TYPE_CORE_INFO_REQUEST, 0U, sizeof(envelope), 4U);
    uint32_t before = g_pipeline_calls;

    make_secret(secret);
    echo_xbox_session_reset(&session);
    assert(echo_xbox_session_process_frame(
        secret, &session, &command, envelope, 1, &response
    ) == ECHO_SESSION_ENGINE_AUTH_DENIED);
    assert(g_pipeline_calls == before);
}

static void test_bad_auth_resets_session_and_returns_denied(void) {
    echo_xbox_session session;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t auth_payload[ECHO_SESSION_AUTH_REQUEST_BYTES];
    uint8_t out[64];
    echo_readonly_xbox_response response = {0U, out, sizeof(out), 0U};
    echo_frame_header begin = header(ECHO_TYPE_SESSION_BEGIN_REQUEST, 0U, 0U, 5U);
    echo_frame_header auth = header(ECHO_TYPE_SESSION_AUTH_REQUEST, 0U, ECHO_SESSION_AUTH_REQUEST_BYTES, 6U);

    make_secret(secret);
    echo_xbox_session_reset(&session);
    assert(echo_xbox_session_process_frame(secret, &session, &begin, NULL, 1, &response) == 0);
    build_auth_payload(auth_payload, 2U, ECHO_AUTH_CAP_READ_INFO);
    g_verify_fail = 1;
    assert(echo_xbox_session_process_frame(
        secret, &session, &auth, auth_payload, 1, &response
    ) == ECHO_SESSION_ENGINE_AUTH_DENIED);
    assert(session.phase == ECHO_SESSION_PHASE_NEW);
    assert(session.auth.authenticated == 0U);
    assert(response.response_type == ECHO_TYPE_SESSION_AUTH_RESPONSE);
    assert(out[0] == ECHO_SESSION_STATUS_DENIED);
    g_verify_fail = 0;
}

static void test_disallowed_write_capability_is_protocol_denied(void) {
    echo_xbox_session session;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t auth_payload[ECHO_SESSION_AUTH_REQUEST_BYTES];
    uint8_t out[64];
    echo_readonly_xbox_response response = {0U, out, sizeof(out), 0U};
    echo_frame_header begin = header(ECHO_TYPE_SESSION_BEGIN_REQUEST, 0U, 0U, 7U);
    echo_frame_header auth = header(ECHO_TYPE_SESSION_AUTH_REQUEST, 0U, ECHO_SESSION_AUTH_REQUEST_BYTES, 8U);

    make_secret(secret);
    echo_xbox_session_reset(&session);
    assert(echo_xbox_session_process_frame(secret, &session, &begin, NULL, 1, &response) == 0);
    build_auth_payload(auth_payload, 2U, ECHO_AUTH_CAP_WRITE_FILESYSTEM);
    assert(echo_xbox_session_process_frame(
        secret, &session, &auth, auth_payload, 1, &response
    ) == ECHO_SESSION_ENGINE_AUTH_DENIED);
    assert(session.phase == ECHO_SESSION_PHASE_NEW);
    assert(out[0] == ECHO_SESSION_STATUS_PROTOCOL_ERROR);
}

static void test_small_control_response_buffer_fails_closed(void) {
    echo_xbox_session session;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t out[1];
    echo_readonly_xbox_response response = {0U, out, sizeof(out), 0U};
    echo_frame_header begin = header(ECHO_TYPE_SESSION_BEGIN_REQUEST, 0U, 0U, 9U);

    make_secret(secret);
    echo_xbox_session_reset(&session);
    assert(echo_xbox_session_process_frame(
        secret, &session, &begin, NULL, 1, &response
    ) == ECHO_SESSION_ENGINE_BUFFER_TOO_SMALL);
    assert(response.response_type == 0U);
    assert(response.payload_length == 0U);
}

int main(void) {
    test_ping_is_public_and_state_neutral();
    test_begin_creates_fresh_challenge_and_rebegin_replaces_it();
    test_auth_success_then_command();
    test_command_before_auth_is_denied();
    test_bad_auth_resets_session_and_returns_denied();
    test_disallowed_write_capability_is_protocol_denied();
    test_small_control_response_buffer_fails_closed();
    puts("EchoCore resident session engine tests: OK");
    return 0;
}
