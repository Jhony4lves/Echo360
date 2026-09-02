#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../openxechain/echo_command_auth_xbox.c"

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
    words[2] = UINT32_C(0xCAFEBABE);
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
            (UINT32_C(0x5A5A5A5A) + i * UINT32_C(0x01020304));
        digest[i] = (uint8_t)(word >> ((i & 3U) * 8U));
    }
}

static void make_secret(uint8_t secret[ECHO_AUTH_SECRET_BYTES]) {
    uint32_t i;
    for (i = 0U; i < ECHO_AUTH_SECRET_BYTES; ++i) secret[i] = (uint8_t)(0x40U + i);
}

static void make_authenticated_state(echo_auth_state *state, uint64_t caps) {
    uint8_t challenge[ECHO_AUTH_CHALLENGE_BYTES];
    uint32_t i;
    for (i = 0U; i < ECHO_AUTH_CHALLENGE_BYTES; ++i) challenge[i] = (uint8_t)(i + 1U);
    echo_auth_session_end(state);
    assert(echo_auth_session_begin(state, UINT64_C(0x0102030405060708), challenge) == 0);
    assert(echo_auth_mark_authenticated(state, 1U, caps) == 0);
}

static uint32_t make_envelope(
    uint8_t *out,
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    const echo_auth_state *state,
    uint64_t counter,
    uint8_t type,
    uint32_t request_id,
    const uint8_t *body,
    uint32_t body_length
) {
    uint8_t mac[ECHO_AUTH_HMAC_SHA1_BYTES];
    uint32_t i;
    echo_command_auth_write_be64(out, counter);
    assert(echo_command_auth_xbox_make_mac(
        secret, state, counter, type, 0U, request_id, body, body_length, mac
    ) == 0);
    for (i = 0U; i < ECHO_AUTH_HMAC_SHA1_BYTES; ++i) {
        out[ECHO_COMMAND_AUTH_COUNTER_BYTES + i] = mac[i];
    }
    for (i = 0U; i < body_length; ++i) {
        out[ECHO_COMMAND_AUTH_PREFIX_BYTES + i] = body[i];
    }
    return ECHO_COMMAND_AUTH_PREFIX_BYTES + body_length;
}

static void test_valid_command_commits_counter_and_returns_body(void) {
    echo_auth_state state;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t envelope[64];
    static const uint8_t body[] = {'/','H','d','d','1','/','G','a','m','e','s'};
    const uint8_t *body_out = NULL;
    uint32_t body_length_out = 0U;
    uint32_t envelope_length;

    make_secret(secret);
    make_authenticated_state(&state, ECHO_CAP_READ_FILESYSTEM);
    envelope_length = make_envelope(
        envelope, secret, &state, 2U, 0x14U, UINT32_C(0x11223344),
        body, (uint32_t)sizeof(body)
    );

    assert(echo_command_auth_xbox_verify_and_commit(
        secret, &state, ECHO_CAP_READ_FILESYSTEM, 0x14U, 0U,
        UINT32_C(0x11223344), envelope, envelope_length,
        &body_out, &body_length_out
    ) == 0);
    assert(state.last_rx_counter == 2U);
    assert(body_length_out == sizeof(body));
    assert(memcmp(body_out, body, sizeof(body)) == 0);
}

static void test_replay_is_rejected_before_mac_commit(void) {
    echo_auth_state state;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t envelope[40];
    const uint8_t *body_out = NULL;
    uint32_t body_length_out = 0U;
    uint32_t envelope_length;

    make_secret(secret);
    make_authenticated_state(&state, ECHO_CAP_READ_INFO);
    envelope_length = make_envelope(envelope, secret, &state, 2U, 0x10U, 7U, NULL, 0U);
    assert(echo_command_auth_xbox_verify_and_commit(
        secret, &state, ECHO_CAP_READ_INFO, 0x10U, 0U, 7U,
        envelope, envelope_length, &body_out, &body_length_out
    ) == 0);
    assert(state.last_rx_counter == 2U);
    assert(echo_command_auth_xbox_verify_and_commit(
        secret, &state, ECHO_CAP_READ_INFO, 0x10U, 0U, 7U,
        envelope, envelope_length, &body_out, &body_length_out
    ) == -3);
    assert(state.last_rx_counter == 2U);
}

static void assert_tamper_rejected(
    uint8_t verify_type,
    uint32_t verify_request_id,
    int mutate_body
) {
    echo_auth_state state;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t envelope[48];
    static const uint8_t body[] = {0xAAU,0xBBU,0xCCU};
    const uint8_t *body_out = NULL;
    uint32_t body_length_out = 0U;
    uint32_t envelope_length;

    make_secret(secret);
    make_authenticated_state(&state, ECHO_CAP_READ_INFO);
    envelope_length = make_envelope(envelope, secret, &state, 2U, 0x10U, 9U, body, sizeof(body));
    if (mutate_body) envelope[ECHO_COMMAND_AUTH_PREFIX_BYTES + 1U] ^= 0x01U;

    assert(echo_command_auth_xbox_verify_and_commit(
        secret, &state, ECHO_CAP_READ_INFO, verify_type, 0U, verify_request_id,
        envelope, envelope_length, &body_out, &body_length_out
    ) == -4);
    assert(state.last_rx_counter == 1U);
}

static void test_type_request_id_body_and_mac_are_bound(void) {
    echo_auth_state state;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t envelope[40];
    const uint8_t *body_out = NULL;
    uint32_t body_length_out = 0U;
    uint32_t envelope_length;

    assert_tamper_rejected(0x12U, 9U, 0);
    assert_tamper_rejected(0x10U, 10U, 0);
    assert_tamper_rejected(0x10U, 9U, 1);

    make_secret(secret);
    make_authenticated_state(&state, ECHO_CAP_READ_INFO);
    envelope_length = make_envelope(envelope, secret, &state, 2U, 0x10U, 9U, NULL, 0U);
    envelope[12U] ^= 0x80U;
    assert(echo_command_auth_xbox_verify_and_commit(
        secret, &state, ECHO_CAP_READ_INFO, 0x10U, 0U, 9U,
        envelope, envelope_length, &body_out, &body_length_out
    ) == -4);
    assert(state.last_rx_counter == 1U);
}

static void test_missing_capability_fails_without_consuming_counter(void) {
    echo_auth_state state;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t envelope[40];
    const uint8_t *body_out = NULL;
    uint32_t body_length_out = 0U;
    uint32_t envelope_length;

    make_secret(secret);
    make_authenticated_state(&state, ECHO_CAP_READ_INFO);
    envelope_length = make_envelope(envelope, secret, &state, 2U, 0x14U, 3U, NULL, 0U);
    assert(echo_command_auth_xbox_verify_and_commit(
        secret, &state, ECHO_CAP_READ_FILESYSTEM, 0x14U, 0U, 3U,
        envelope, envelope_length, &body_out, &body_length_out
    ) == -1);
    assert(state.last_rx_counter == 1U);
}

static void test_zero_and_truncated_counters_fail_closed(void) {
    echo_auth_state state;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t envelope[ECHO_COMMAND_AUTH_PREFIX_BYTES] = {0};
    const uint8_t *body_out = NULL;
    uint32_t body_length_out = 0U;

    make_secret(secret);
    make_authenticated_state(&state, ECHO_CAP_READ_INFO);
    assert(echo_command_auth_xbox_verify_and_commit(
        secret, &state, ECHO_CAP_READ_INFO, 0x10U, 0U, 1U,
        envelope, sizeof(envelope), &body_out, &body_length_out
    ) == -2);
    assert(echo_command_auth_xbox_verify_and_commit(
        secret, &state, ECHO_CAP_READ_INFO, 0x10U, 0U, 1U,
        envelope, ECHO_COMMAND_AUTH_PREFIX_BYTES - 1U,
        &body_out, &body_length_out
    ) == -2);
    assert(state.last_rx_counter == 1U);
}

int main(void) {
    test_valid_command_commits_counter_and_returns_body();
    test_replay_is_rejected_before_mac_commit();
    test_type_request_id_body_and_mac_are_bound();
    test_missing_capability_fails_without_consuming_counter();
    test_zero_and_truncated_counters_fail_closed();
    puts("EchoCore authenticated command tests: OK");
    return 0;
}
