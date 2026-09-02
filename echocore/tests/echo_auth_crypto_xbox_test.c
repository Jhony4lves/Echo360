#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../openxechain/echo_auth_crypto_xbox.c"

static uint32_t g_random_calls;
static uint32_t g_random_zero_calls;
static uint8_t g_random_generation;

void XeCryptRandom(uint8_t *random, uint32_t random_size) {
    uint32_t i;
    g_random_calls++;
    if (g_random_zero_calls != 0U) {
        g_random_zero_calls--;
        for (i = 0U; i < random_size; ++i) random[i] = 0U;
        return;
    }
    for (i = 0U; i < random_size; ++i) {
        random[i] = (uint8_t)(g_random_generation + (uint8_t)i + 1U);
    }
    g_random_generation++;
}

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
    words[2] = UINT32_C(0x13579BDF);
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
            (UINT32_C(0xA5A5A5A5) + i * UINT32_C(0x01020304));
        digest[i] = (uint8_t)(word >> ((i & 3U) * 8U));
    }
}

static void reset_random(void) {
    g_random_calls = 0U;
    g_random_zero_calls = 0U;
    g_random_generation = 0U;
}

static void test_transcript_is_canonical_big_endian(void) {
    uint8_t transcript[ECHO_AUTH_RESPONSE_TRANSCRIPT_BYTES];
    uint8_t challenge[ECHO_AUTH_CHALLENGE_BYTES];
    uint8_t i;
    static const uint8_t domain[ECHO_AUTH_DOMAIN_BYTES] = {
        'E','C','H','O','3','6','0','-','A','U','T','H','-','V','1','!'
    };
    static const uint8_t session_be[8] = {1U,2U,3U,4U,5U,6U,7U,8U};
    static const uint8_t counter_be[8] = {0x11U,0x12U,0x13U,0x14U,0x15U,0x16U,0x17U,0x18U};
    uint64_t caps = ECHO_CAP_READ_INFO | ECHO_CAP_READ_FILESYSTEM;

    for (i = 0U; i < ECHO_AUTH_CHALLENGE_BYTES; ++i) challenge[i] = (uint8_t)(0x20U + i);
    assert(echo_auth_make_response_transcript(
        transcript,
        UINT64_C(0x0102030405060708),
        challenge,
        UINT64_C(0x1112131415161718),
        caps
    ) == 0);

    assert(memcmp(transcript, domain, sizeof(domain)) == 0);
    assert(memcmp(transcript + 16U, session_be, sizeof(session_be)) == 0);
    assert(memcmp(transcript + 24U, challenge, sizeof(challenge)) == 0);
    assert(memcmp(transcript + 40U, counter_be, sizeof(counter_be)) == 0);
    assert(echo_auth_read_be64(transcript + 48U) == caps);
}

static void test_pairing_secret_retries_zero_rng(void) {
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];

    reset_random();
    g_random_zero_calls = 1U;
    assert(echo_auth_xbox_generate_pairing_secret(secret) == 0);
    assert(g_random_calls == 2U);
    assert(!echo_auth_bytes_all_zero(secret, sizeof(secret)));

    reset_random();
    g_random_zero_calls = ECHO_AUTH_RANDOM_ATTEMPTS;
    assert(echo_auth_xbox_generate_pairing_secret(secret) == -2);
    assert(g_random_calls == ECHO_AUTH_RANDOM_ATTEMPTS);
    assert(echo_auth_bytes_all_zero(secret, sizeof(secret)));
}

static void test_session_rng_populates_id_and_challenge(void) {
    echo_auth_state state;
    uint8_t expected_random[8U + ECHO_AUTH_CHALLENGE_BYTES];
    uint32_t i;

    reset_random();
    echo_auth_session_end(&state);
    for (i = 0U; i < sizeof(expected_random); ++i) expected_random[i] = (uint8_t)(i + 1U);

    assert(echo_auth_xbox_begin_session(&state) == 0);
    assert(g_random_calls == 1U);
    assert(state.session_id == echo_auth_read_be64(expected_random));
    assert(memcmp(state.challenge, expected_random + 8U, ECHO_AUTH_CHALLENGE_BYTES) == 0);
    assert(state.challenge_active == 1U);
    assert(state.authenticated == 0U);
}

static void make_fixed_secret(uint8_t secret[ECHO_AUTH_SECRET_BYTES]) {
    uint32_t i;
    for (i = 0U; i < ECHO_AUTH_SECRET_BYTES; ++i) secret[i] = (uint8_t)(0x80U + i);
}

static void test_valid_mac_authenticates_and_scrubs_challenge(void) {
    echo_auth_state state;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t mac[ECHO_AUTH_HMAC_SHA1_BYTES];
    uint8_t zero[ECHO_AUTH_CHALLENGE_BYTES] = {0};
    uint64_t caps = ECHO_CAP_READ_INFO | ECHO_CAP_READ_FILESYSTEM;

    reset_random();
    make_fixed_secret(secret);
    echo_auth_session_end(&state);
    assert(echo_auth_xbox_begin_session(&state) == 0);
    assert(echo_auth_xbox_make_response_mac(secret, &state, 1U, caps, mac) == 0);
    assert(echo_auth_xbox_verify_response(secret, &state, 1U, caps, mac) == 0);
    assert(state.authenticated == 1U);
    assert(state.challenge_active == 0U);
    assert(state.last_rx_counter == 1U);
    assert((state.capabilities & caps) == caps);
    assert((state.capabilities & ECHO_CAP_PING) != 0U);
    assert(memcmp(state.challenge, zero, sizeof(zero)) == 0);
}

static void test_tampered_mac_fails_without_consuming_challenge(void) {
    echo_auth_state state;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t mac[ECHO_AUTH_HMAC_SHA1_BYTES];
    uint8_t challenge_before[ECHO_AUTH_CHALLENGE_BYTES];
    uint64_t caps = ECHO_CAP_READ_INFO;

    reset_random();
    make_fixed_secret(secret);
    echo_auth_session_end(&state);
    assert(echo_auth_xbox_begin_session(&state) == 0);
    memcpy(challenge_before, state.challenge, sizeof(challenge_before));
    assert(echo_auth_xbox_make_response_mac(secret, &state, 9U, caps, mac) == 0);
    mac[7] ^= 0x5AU;
    assert(echo_auth_xbox_verify_response(secret, &state, 9U, caps, mac) == -2);
    assert(state.authenticated == 0U);
    assert(state.challenge_active == 1U);
    assert(state.last_rx_counter == 0U);
    assert(memcmp(state.challenge, challenge_before, sizeof(challenge_before)) == 0);
}

static void test_unknown_capability_is_rejected_before_mac(void) {
    echo_auth_state state;
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t mac[ECHO_AUTH_HMAC_SHA1_BYTES];
    uint64_t unknown = UINT64_C(1) << 63U;

    reset_random();
    make_fixed_secret(secret);
    echo_auth_session_end(&state);
    assert(echo_auth_xbox_begin_session(&state) == 0);
    assert(echo_auth_xbox_make_response_mac(secret, &state, 1U, unknown, mac) == -1);
    assert(state.authenticated == 0U && state.challenge_active == 1U);
}

int main(void) {
    test_transcript_is_canonical_big_endian();
    test_pairing_secret_retries_zero_rng();
    test_session_rng_populates_id_and_challenge();
    test_valid_mac_authenticates_and_scrubs_challenge();
    test_tampered_mac_fails_without_consuming_challenge();
    test_unknown_capability_is_rejected_before_mac();
    puts("EchoCore Xbox auth crypto tests: OK");
    return 0;
}
