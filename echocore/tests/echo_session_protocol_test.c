#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../openxechain/echo_session_protocol.h"

static void test_begin_contract(void) {
    echo_frame_header header = {
        ECHO_TYPE_SESSION_BEGIN_REQUEST,
        0U,
        0U,
        77U
    };

    assert(echo_session_validate_begin(&header) == 0);
    header.flags = 1U;
    assert(echo_session_validate_begin(&header) == -1);
    header.flags = 0U;
    header.payload_length = 1U;
    assert(echo_session_validate_begin(&header) == -1);
    header.payload_length = 0U;
    header.type = ECHO_TYPE_SESSION_AUTH_REQUEST;
    assert(echo_session_validate_begin(&header) == -1);
    assert(echo_session_validate_begin(NULL) == -1);
}

static void test_challenge_payload(void) {
    echo_auth_state state;
    uint8_t challenge[ECHO_AUTH_CHALLENGE_BYTES];
    uint8_t payload[ECHO_SESSION_CHALLENGE_BYTES];
    uint32_t i;

    for (i = 0U; i < ECHO_AUTH_CHALLENGE_BYTES; ++i) challenge[i] = (uint8_t)(0xA0U + i);
    echo_auth_session_end(&state);
    assert(echo_auth_session_begin(&state, UINT64_C(0x1122334455667788), challenge) == 0);
    assert(echo_session_make_challenge_payload(&state, payload) == 0);
    assert(echo_session_read_be64(payload) == UINT64_C(0x1122334455667788));
    assert(memcmp(payload + 8U, challenge, sizeof(challenge)) == 0);

    state.authenticated = 1U;
    assert(echo_session_make_challenge_payload(&state, payload) == -1);
}

static void test_auth_request_contract(void) {
    echo_frame_header header = {
        ECHO_TYPE_SESSION_AUTH_REQUEST,
        0U,
        ECHO_SESSION_AUTH_REQUEST_BYTES,
        9U
    };
    uint8_t payload[ECHO_SESSION_AUTH_REQUEST_BYTES] = {0};
    uint64_t counter;
    uint64_t caps;
    const uint8_t *mac;
    uint32_t i;

    echo_session_write_be64(payload, UINT64_C(5));
    echo_session_write_be64(
        payload + 8U,
        ECHO_AUTH_CAP_READ_INFO | ECHO_AUTH_CAP_READ_FILESYSTEM
    );
    for (i = 0U; i < ECHO_AUTH_HMAC_SHA1_BYTES; ++i) payload[16U + i] = (uint8_t)(i + 1U);

    assert(echo_session_parse_auth_request(
        &header, payload, &counter, &caps, &mac
    ) == 0);
    assert(counter == 5U);
    assert(caps == (ECHO_AUTH_CAP_READ_INFO | ECHO_AUTH_CAP_READ_FILESYSTEM));
    assert(mac == payload + 16U);

    echo_session_write_be64(payload, 0U);
    assert(echo_session_parse_auth_request(&header, payload, &counter, &caps, &mac) == -2);
    echo_session_write_be64(payload, 5U);

    echo_session_write_be64(payload + 8U, ECHO_AUTH_CAP_WRITE_FILESYSTEM);
    assert(echo_session_parse_auth_request(&header, payload, &counter, &caps, &mac) == -2);
    echo_session_write_be64(payload + 8U, ECHO_AUTH_CAP_READ_INFO);

    header.flags = 1U;
    assert(echo_session_parse_auth_request(&header, payload, &counter, &caps, &mac) == -1);
    header.flags = 0U;
    header.payload_length--;
    assert(echo_session_parse_auth_request(&header, payload, &counter, &caps, &mac) == -1);
}

static void test_auth_response_is_full_width(void) {
    uint8_t payload[ECHO_SESSION_AUTH_RESPONSE_BYTES];
    uint64_t future_cap = UINT64_C(1) << 40U;
    uint32_t i;

    echo_session_make_auth_response(
        payload,
        ECHO_SESSION_STATUS_OK,
        future_cap | ECHO_AUTH_CAP_READ_INFO,
        UINT64_C(0x0102030405060708)
    );
    assert(payload[0] == ECHO_SESSION_STATUS_OK);
    for (i = 1U; i < 8U; ++i) assert(payload[i] == 0U);
    assert(echo_session_read_be64(payload + 8U) == (future_cap | ECHO_AUTH_CAP_READ_INFO));
    assert(echo_session_read_be64(payload + 16U) == UINT64_C(0x0102030405060708));
    assert(ECHO_SESSION_AUTH_RESPONSE_BYTES == 24U);
}

int main(void) {
    test_begin_contract();
    test_challenge_payload();
    test_auth_request_contract();
    test_auth_response_is_full_width();
    puts("EchoCore paired-session protocol tests: OK");
    return 0;
}
