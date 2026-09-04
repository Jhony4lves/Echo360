#ifndef ECHO_PAIRING_STORE_XBOX_H
#define ECHO_PAIRING_STORE_XBOX_H

#include <stdint.h>

#include "echo_auth_state.h"
#include "echo_pairing_token.h"

#define ECHO_PAIRING_STORE_OK 0
#define ECHO_PAIRING_STORE_NOT_FOUND 1
#define ECHO_PAIRING_STORE_CREATED 2
#define ECHO_PAIRING_STORE_TOKEN_UNAVAILABLE 3
#define ECHO_PAIRING_STORE_INVALID_ARGUMENT -1
#define ECHO_PAIRING_STORE_CORRUPT -2
#define ECHO_PAIRING_STORE_IO_ERROR -3

#define ECHO_PAIRING_CANONICAL_PATH "/Hdd1/Echo360/EchoCore/pairing.dat"

/* Load the LAN authentication secret from either legacy v1 or recoverable v2. */
int echo_pairing_xbox_load_secret(
    uint8_t secret_out[ECHO_AUTH_SECRET_BYTES]
);

/*
 * Load the physical token from a recoverable v2 record. A valid legacy v1
 * record returns ECHO_PAIRING_STORE_TOKEN_UNAVAILABLE and is never rewritten.
 */
int echo_pairing_xbox_load_token(
    uint8_t token_out[ECHO_PAIRING_TOKEN_BYTES]
);

/*
 * Persist an already-derived Echo360 pairing secret transactionally using the
 * legacy v1 record. Kept for maintenance/bootstrap compatibility.
 */
int echo_pairing_xbox_store_secret(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES]
);

/*
 * Persist a physical pairing token transactionally using recoverable record
 * v2. The resident service derives the same 256-bit auth secret when loading.
 */
int echo_pairing_xbox_store_token(
    const uint8_t token[ECHO_PAIRING_TOKEN_BYTES]
);

/*
 * Load the existing record or create a random v1 identity transactionally if
 * it is genuinely absent. Kept for maintenance/bootstrap tools. The resident
 * plugin deliberately does not call this function. A corrupt record is never
 * overwritten automatically.
 */
int echo_pairing_xbox_ensure_secret(
    uint8_t secret_out[ECHO_AUTH_SECRET_BYTES]
);

#endif
