#ifndef ECHO_PAIRING_TOKEN_XBOX_H
#define ECHO_PAIRING_TOKEN_XBOX_H

#include <stdint.h>

#include "echo_auth_state.h"
#include "echo_pairing_token.h"

#define ECHO_PAIRING_TOKEN_KDF_DOMAIN "ECHO360-PAIRING-TOKEN-V1"
#define ECHO_PAIRING_TOKEN_KDF_DOMAIN_BYTES 24U

/* Generate a non-zero 128-bit physical pairing token with XeCryptRandom. */
int echo_pairing_token_xbox_generate(
    uint8_t token[ECHO_PAIRING_TOKEN_BYTES]
);

/*
 * Derive the 256-bit Echo360 pairing secret as:
 * SHA-256("ECHO360-PAIRING-TOKEN-V1" || token[16]).
 *
 * Cross-platform test vector:
 * token: 000102030405060708090A0B0C0D0E0F
 * display: 000G4-0R40M-30E20-9185G-R38E1W
 * secret: 7344e42de48a363d0454babae5d527f1bf0b43e319fd18da2062ebf524d050f4
 */
int echo_pairing_token_xbox_derive_secret(
    const uint8_t token[ECHO_PAIRING_TOKEN_BYTES],
    uint8_t secret[ECHO_AUTH_SECRET_BYTES]
);

#endif
