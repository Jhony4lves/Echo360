#ifndef ECHO_TEST_XBOXKRNL_CRYPTO_H
#define ECHO_TEST_XBOXKRNL_CRYPTO_H

#include "xboxkrnl_types.h"

void XeCryptSha256Init(CRYPT_SHA256_STATE *state);
void XeCryptSha256Update(
    CRYPT_SHA256_STATE *state,
    const uint8_t *data,
    uint32_t bytes
);
void XeCryptSha256Final(
    CRYPT_SHA256_STATE *state,
    uint8_t *digest,
    uint32_t digest_bytes
);

#endif
