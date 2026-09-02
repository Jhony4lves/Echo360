#ifndef ECHO_PAIRING_STORE_XBOX_H
#define ECHO_PAIRING_STORE_XBOX_H

#include <stdint.h>

#include "echo_auth_state.h"

#define ECHO_PAIRING_STORE_OK 0
#define ECHO_PAIRING_STORE_NOT_FOUND 1
#define ECHO_PAIRING_STORE_CREATED 2
#define ECHO_PAIRING_STORE_INVALID_ARGUMENT -1
#define ECHO_PAIRING_STORE_CORRUPT -2
#define ECHO_PAIRING_STORE_IO_ERROR -3

#define ECHO_PAIRING_CANONICAL_PATH "/Hdd1/Echo360/EchoCore/pairing.dat"

/* Load only; never creates or repairs a missing/corrupt pairing record. */
int echo_pairing_xbox_load_secret(
    uint8_t secret_out[ECHO_AUTH_SECRET_BYTES]
);

/*
 * Load the existing record or create it transactionally if it is genuinely
 * absent. A corrupt record is never overwritten automatically.
 */
int echo_pairing_xbox_ensure_secret(
    uint8_t secret_out[ECHO_AUTH_SECRET_BYTES]
);

#endif
