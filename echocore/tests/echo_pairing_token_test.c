#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../openxechain/echo_pairing_token.h"

static void test_known_vector(void) {
    uint8_t token[ECHO_PAIRING_TOKEN_BYTES];
    char encoded[ECHO_PAIRING_TOKEN_BASE32_CHARS + 1U];
    char display[ECHO_PAIRING_TOKEN_DISPLAY_CAPACITY];
    uint32_t i;

    for (i = 0U; i < ECHO_PAIRING_TOKEN_BYTES; ++i) token[i] = (uint8_t)i;

    assert(echo_pairing_token_encode_base32(token, encoded) == 0);
    assert(strcmp(encoded, "000G40R40M30E209185GR38E1W") == 0);
    assert(echo_pairing_token_format_display(token, display) == 0);
    assert(strcmp(display, "000G4-0R40M-30E20-9185G-R38E1W") == 0);
}

static void test_zero_token_is_rejected(void) {
    uint8_t token[ECHO_PAIRING_TOKEN_BYTES] = {0};
    char encoded[ECHO_PAIRING_TOKEN_BASE32_CHARS + 1U];
    char display[ECHO_PAIRING_TOKEN_DISPLAY_CAPACITY];
    assert(echo_pairing_token_is_zero(token) == 1);
    assert(echo_pairing_token_encode_base32(token, encoded) != 0);
    assert(echo_pairing_token_format_display(token, display) != 0);
}

int main(void) {
    test_known_vector();
    test_zero_token_is_rejected();
    puts("EchoCore physical pairing token tests: OK");
    return 0;
}
