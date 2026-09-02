#ifndef ECHO_TEST_XBOXKRNL_CRYPTO_H
#define ECHO_TEST_XBOXKRNL_CRYPTO_H

#include "xboxkrnl_types.h"

void XeCryptRandom(uint8_t *random, uint32_t random_size);

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
void XeCryptSha256(
    const uint8_t *input1,
    uint32_t input1_size,
    const uint8_t *input2,
    uint32_t input2_size,
    const uint8_t *input3,
    uint32_t input3_size,
    uint8_t *digest,
    uint32_t digest_size
);

#endif
