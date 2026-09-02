#include <stddef.h>
#include <stdint.h>

#include "echo_auth_crypto.h"
#include "echo_command_auth.h"
#include "echo_command_auth_xbox.h"

#define ECHO_XECRYPT_HMAC_SHA_STATE_BYTES 0xB0U

typedef union echo_command_hmac_state {
    uint64_t alignment;
    uint8_t bytes[ECHO_XECRYPT_HMAC_SHA_STATE_BYTES];
} echo_command_hmac_state;

_Static_assert(
    sizeof(echo_command_hmac_state) == ECHO_XECRYPT_HMAC_SHA_STATE_BYTES,
    "Xbox XeCrypt HMAC-SHA state must be 0xB0 bytes"
);

extern void XeCryptHmacShaInit(void *state, const uint8_t *key, uint32_t key_size);
extern void XeCryptHmacShaUpdate(void *state, const uint8_t *input, uint32_t input_size);
extern void XeCryptHmacShaFinal(void *state, uint8_t *digest, uint32_t digest_size);

int echo_command_auth_xbox_make_mac(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    const echo_auth_state *state,
    uint64_t counter,
    uint8_t type,
    uint16_t flags,
    uint32_t request_id,
    const uint8_t *body,
    uint32_t body_length,
    uint8_t out[ECHO_AUTH_HMAC_SHA1_BYTES]
) {
    static const uint8_t domain[ECHO_COMMAND_AUTH_DOMAIN_BYTES] = {
        'E','C','H','O','3','6','0','-','C','M','D','-','V','1','!','!'
    };
    uint8_t session_and_counter[16U];
    uint8_t meta[ECHO_COMMAND_AUTH_META_BYTES];
    echo_command_hmac_state hmac;

    if (secret == NULL || state == NULL || out == NULL ||
        state->authenticated == 0U || state->session_id == UINT64_C(0) ||
        counter == UINT64_C(0) ||
        echo_auth_bytes_all_zero(secret, ECHO_AUTH_SECRET_BYTES) ||
        (body_length != 0U && body == NULL) ||
        echo_command_auth_make_meta(meta, type, flags, body_length, request_id) != 0) {
        return -1;
    }

    echo_command_auth_write_be64(session_and_counter, state->session_id);
    echo_command_auth_write_be64(session_and_counter + 8U, counter);
    echo_auth_zero_bytes(hmac.bytes, sizeof(hmac.bytes));

    XeCryptHmacShaInit(hmac.bytes, secret, ECHO_AUTH_SECRET_BYTES);
    XeCryptHmacShaUpdate(hmac.bytes, domain, ECHO_COMMAND_AUTH_DOMAIN_BYTES);
    XeCryptHmacShaUpdate(hmac.bytes, session_and_counter, sizeof(session_and_counter));
    XeCryptHmacShaUpdate(hmac.bytes, meta, sizeof(meta));
    if (body_length != 0U) {
        XeCryptHmacShaUpdate(hmac.bytes, body, body_length);
    }
    XeCryptHmacShaFinal(hmac.bytes, out, ECHO_AUTH_HMAC_SHA1_BYTES);

    echo_auth_zero_bytes(hmac.bytes, sizeof(hmac.bytes));
    echo_auth_zero_bytes(session_and_counter, sizeof(session_and_counter));
    echo_auth_zero_bytes(meta, sizeof(meta));
    return 0;
}

int echo_command_auth_xbox_verify_and_commit(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    echo_auth_state *state,
    uint64_t required_capability,
    uint8_t type,
    uint16_t flags,
    uint32_t request_id,
    const uint8_t *envelope,
    uint32_t envelope_length,
    const uint8_t **body_out,
    uint32_t *body_length_out
) {
    uint64_t counter;
    const uint8_t *presented_mac;
    const uint8_t *body;
    uint32_t body_length;
    uint8_t expected[ECHO_AUTH_HMAC_SHA1_BYTES];
    int equal;

    if (state == NULL || body_out == NULL || body_length_out == NULL ||
        !echo_auth_has_capability(state, required_capability)) {
        return -1;
    }
    if (echo_command_auth_parse_envelope(
            envelope,
            envelope_length,
            &counter,
            &presented_mac,
            &body,
            &body_length
        ) != 0) {
        return -2;
    }
    if (counter <= state->last_rx_counter) {
        return -3;
    }

    echo_auth_zero_bytes(expected, sizeof(expected));
    if (echo_command_auth_xbox_make_mac(
            secret,
            state,
            counter,
            type,
            flags,
            request_id,
            body,
            body_length,
            expected
        ) != 0) {
        echo_auth_zero_bytes(expected, sizeof(expected));
        return -1;
    }

    equal = echo_auth_constant_time_equal(
        expected,
        presented_mac,
        ECHO_AUTH_HMAC_SHA1_BYTES
    );
    echo_auth_zero_bytes(expected, sizeof(expected));
    if (!equal) {
        return -4;
    }

    if (echo_auth_commit_counter(state, counter) != 0) {
        return -3;
    }

    *body_out = body;
    *body_length_out = body_length;
    return 0;
}
