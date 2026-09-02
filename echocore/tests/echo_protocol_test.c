#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../openxechain/echo_protocol.h"

static void build_valid_ping(uint8_t header[ECHO_HEADER_BYTES]) {
    memset(header, 0, ECHO_HEADER_BYTES);
    header[0] = ECHO_MAGIC_0;
    header[1] = ECHO_MAGIC_1;
    header[2] = ECHO_MAGIC_2;
    header[3] = ECHO_MAGIC_3;
    header[4] = ECHO_VERSION;
    header[5] = ECHO_TYPE_PING;
    echo_write_be32(header + 8U, ECHO_PING_PAYLOAD_BYTES);
    echo_write_be32(header + 12U, 0x12345678U);
}

int main(void) {
    uint8_t header[ECHO_HEADER_BYTES];
    uint8_t original[ECHO_HEADER_BYTES];

    build_valid_ping(header);
    assert(echo_validate_ping_header(header) == 0);
    assert(echo_read_be32(header + 12U) == 0x12345678U);

    memcpy(original, header, sizeof(header));
    echo_make_pong_header(header);
    assert(header[5] == ECHO_TYPE_PONG);
    assert(memcmp(header, original, 5U) == 0);
    assert(memcmp(header + 6U, original + 6U, ECHO_HEADER_BYTES - 6U) == 0);

    build_valid_ping(header);
    header[0] ^= 1U;
    assert(echo_validate_ping_header(header) == -1);

    build_valid_ping(header);
    header[4] = (uint8_t)(ECHO_VERSION + 1U);
    assert(echo_validate_ping_header(header) == -2);

    build_valid_ping(header);
    header[5] = ECHO_TYPE_PONG;
    assert(echo_validate_ping_header(header) == -2);

    build_valid_ping(header);
    header[7] = 1U;
    assert(echo_validate_ping_header(header) == -3);

    build_valid_ping(header);
    echo_write_be32(header + 8U, 0U);
    assert(echo_validate_ping_header(header) == -4);

    build_valid_ping(header);
    echo_write_be32(header + 8U, ECHO_PING_PAYLOAD_BYTES + 1U);
    assert(echo_validate_ping_header(header) == -4);

    puts("EchoCore protocol tests: OK");
    return 0;
}
