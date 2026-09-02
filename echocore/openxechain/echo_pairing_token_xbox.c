#include <stddef.h>
#include <stdint.h>

#include <xecore/xboxkrnl_crypto.h>

#include "echo_pairing_token_xbox.h"

#define ECHO_PAIRING_TOKEN_RANDOM_ATTEMPTS 4U

static void echo_pairing_token_zero(void *memory, uint32_t length) {
    volatile uint8_t *bytes = (volatile uint8_t *)memory;
    uint32_t i;
    if (bytes == NULL) return;
    for (i = 0U; i < length; ++i) bytes[i] = 0U;
}

static int echo_pairing_bytes_all_zero(const uint8_t *bytes, uint32_t length) {
    uint8_t any = 0U;
    uint32_t i;
    if (bytes == NULL || length == 0U) return 1;
    for (i = 0U; i < length; ++i) any = (uint8_t)(any | bytes[i]);
    return any == 0U ? 1 : 0;
}

int echo_pairing_token_xbox_generate(
    uint8_t token[ECHO_PAIRING_TOKEN_BYTES]
) {
    uint32_t attempt;
    if (token == NULL) return -1;
    echo_pairing_token_zero(token, ECHO_PAIRING_TOKEN_BYTES);
    for (attempt = 0U; attempt < ECHO_PAIRING_TOKEN_RANDOM_ATTEMPTS; ++attempt) {
        XeCryptRandom(token, ECHO_PAIRING_TOKEN_BYTES);
        if (!echo_pairing_token_is_zero(token)) return 0;
    }
    echo_pairing_token_zero(token, ECHO_PAIRING_TOKEN_BYTES);
    return -2;
}

int echo_pairing_token_xbox_derive_secret(
    const uint8_t token[ECHO_PAIRING_TOKEN_BYTES],
    uint8_t secret[ECHO_AUTH_SECRET_BYTES]
) {
    static const uint8_t domain[ECHO_PAIRING_TOKEN_KDF_DOMAIN_BYTES] =
        ECHO_PAIRING_TOKEN_KDF_DOMAIN;

    if (token == NULL || secret == NULL || echo_pairing_token_is_zero(token)) {
        if (secret != NULL) echo_pairing_token_zero(secret, ECHO_AUTH_SECRET_BYTES);
        return -1;
    }

    echo_pairing_token_zero(secret, ECHO_AUTH_SECRET_BYTES);
    XeCryptSha256(
        domain,
        ECHO_PAIRING_TOKEN_KDF_DOMAIN_BYTES,
        token,
        ECHO_PAIRING_TOKEN_BYTES,
        NULL,
        0U,
        secret,
        ECHO_AUTH_SECRET_BYTES
    );

    return echo_pairing_bytes_all_zero(secret, ECHO_AUTH_SECRET_BYTES) ? -2 : 0;
}
