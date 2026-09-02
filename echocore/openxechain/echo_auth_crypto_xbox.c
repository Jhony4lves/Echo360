#include <stddef.h>
#include <stdint.h>

#include "echo_auth_crypto.h"
#include "echo_auth_crypto_xbox.h"

/*
 * Audited against the Xbox 360 kernel ABI and Xenia's May 2026 implementation.
 * The pinned xecorelib header has a type typo on XeCryptHmacShaFinal, so this
 * file intentionally owns the tiny ABI surface it needs instead of including
 * xboxkrnl_crypto.h.
 */
#define ECHO_XECRYPT_HMAC_SHA_STATE_BYTES 0xB0U
#define ECHO_AUTH_RANDOM_ATTEMPTS 4U

typedef union echo_xecrypt_hmac_sha_state {
    uint64_t alignment;
    uint8_t bytes[ECHO_XECRYPT_HMAC_SHA_STATE_BYTES];
} echo_xecrypt_hmac_sha_state;

_Static_assert(
    sizeof(echo_xecrypt_hmac_sha_state) == ECHO_XECRYPT_HMAC_SHA_STATE_BYTES,
    "Xbox XeCrypt HMAC-SHA state must be 0xB0 bytes"
);

extern void XeCryptRandom(uint8_t *random, uint32_t random_size);
extern void XeCryptHmacShaInit(void *state, const uint8_t *key, uint32_t key_size);
extern void XeCryptHmacShaUpdate(void *state, const uint8_t *input, uint32_t input_size);
extern void XeCryptHmacShaFinal(void *state, uint8_t *digest, uint32_t digest_size);

int echo_auth_xbox_generate_pairing_secret(
    uint8_t secret[ECHO_AUTH_SECRET_BYTES]
) {
    uint32_t attempt;

    if (secret == NULL) return -1;
    echo_auth_zero_bytes(secret, ECHO_AUTH_SECRET_BYTES);

    for (attempt = 0U; attempt < ECHO_AUTH_RANDOM_ATTEMPTS; ++attempt) {
        XeCryptRandom(secret, ECHO_AUTH_SECRET_BYTES);
        if (!echo_auth_bytes_all_zero(secret, ECHO_AUTH_SECRET_BYTES)) return 0;
    }

    echo_auth_zero_bytes(secret, ECHO_AUTH_SECRET_BYTES);
    return -2;
}

int echo_auth_xbox_begin_session(echo_auth_state *state) {
    uint8_t random_bytes[8U + ECHO_AUTH_CHALLENGE_BYTES];
    uint64_t session_id;
    uint32_t attempt;
    int result = -2;

    if (state == NULL) return -1;
    echo_auth_zero_bytes(random_bytes, sizeof(random_bytes));

    for (attempt = 0U; attempt < ECHO_AUTH_RANDOM_ATTEMPTS; ++attempt) {
        XeCryptRandom(random_bytes, (uint32_t)sizeof(random_bytes));
        session_id = echo_auth_read_be64(random_bytes);
        if (session_id == UINT64_C(0) ||
            echo_auth_bytes_all_zero(
                random_bytes + 8U,
                ECHO_AUTH_CHALLENGE_BYTES
            )) {
            continue;
        }

        result = echo_auth_session_begin(
            state,
            session_id,
            random_bytes + 8U
        );
        break;
    }

    echo_auth_zero_bytes(random_bytes, sizeof(random_bytes));
    return result;
}

int echo_auth_xbox_make_response_mac(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    const echo_auth_state *state,
    uint64_t counter,
    uint64_t requested_capabilities,
    uint8_t out[ECHO_AUTH_HMAC_SHA1_BYTES]
) {
    uint8_t transcript[ECHO_AUTH_RESPONSE_TRANSCRIPT_BYTES];
    echo_xecrypt_hmac_sha_state hmac;
    int result;

    if (secret == NULL || state == NULL || out == NULL ||
        state->challenge_active == 0U || state->authenticated != 0U ||
        echo_auth_bytes_all_zero(secret, ECHO_AUTH_SECRET_BYTES)) {
        return -1;
    }

    result = echo_auth_make_response_transcript(
        transcript,
        state->session_id,
        state->challenge,
        counter,
        requested_capabilities
    );
    if (result != 0) return -1;

    echo_auth_zero_bytes(hmac.bytes, sizeof(hmac.bytes));
    XeCryptHmacShaInit(hmac.bytes, secret, ECHO_AUTH_SECRET_BYTES);
    XeCryptHmacShaUpdate(
        hmac.bytes,
        transcript,
        ECHO_AUTH_RESPONSE_TRANSCRIPT_BYTES
    );
    XeCryptHmacShaFinal(hmac.bytes, out, ECHO_AUTH_HMAC_SHA1_BYTES);

    echo_auth_zero_bytes(hmac.bytes, sizeof(hmac.bytes));
    echo_auth_zero_bytes(transcript, sizeof(transcript));
    return 0;
}

int echo_auth_xbox_verify_response(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    echo_auth_state *state,
    uint64_t counter,
    uint64_t requested_capabilities,
    const uint8_t presented[ECHO_AUTH_HMAC_SHA1_BYTES]
) {
    uint8_t expected[ECHO_AUTH_HMAC_SHA1_BYTES];
    int equal;

    if (presented == NULL) return -1;
    echo_auth_zero_bytes(expected, sizeof(expected));

    if (echo_auth_xbox_make_response_mac(
            secret,
            state,
            counter,
            requested_capabilities,
            expected
        ) != 0) {
        echo_auth_zero_bytes(expected, sizeof(expected));
        return -1;
    }

    equal = echo_auth_constant_time_equal(
        expected,
        presented,
        ECHO_AUTH_HMAC_SHA1_BYTES
    );
    echo_auth_zero_bytes(expected, sizeof(expected));
    if (!equal) return -2;

    return echo_auth_mark_authenticated(
        state,
        counter,
        requested_capabilities
    ) == 0 ? 0 : -1;
}
