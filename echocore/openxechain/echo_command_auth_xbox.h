#ifndef ECHO_COMMAND_AUTH_XBOX_H
#define ECHO_COMMAND_AUTH_XBOX_H

#include <stdint.h>

#include "echo_auth_state.h"

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
);

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
);

#endif
