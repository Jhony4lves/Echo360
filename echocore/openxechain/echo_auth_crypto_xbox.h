#ifndef ECHO_AUTH_CRYPTO_XBOX_H
#define ECHO_AUTH_CRYPTO_XBOX_H

#include <stdint.h>

#include "echo_auth_state.h"

int echo_auth_xbox_generate_pairing_secret(
    uint8_t secret[ECHO_AUTH_SECRET_BYTES]
);

int echo_auth_xbox_begin_session(echo_auth_state *state);

int echo_auth_xbox_make_response_mac(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    const echo_auth_state *state,
    uint64_t counter,
    uint64_t requested_capabilities,
    uint8_t out[ECHO_AUTH_HMAC_SHA1_BYTES]
);

int echo_auth_xbox_verify_response(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    echo_auth_state *state,
    uint64_t counter,
    uint64_t requested_capabilities,
    const uint8_t presented[ECHO_AUTH_HMAC_SHA1_BYTES]
);

#endif
