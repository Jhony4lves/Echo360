#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../openxechain/echo_protocol.h"

static void build_valid_ping(uint8_t header[ECHO_HEADER_BYTES]) {
    echo_make_frame_header(
        header,
        ECHO_TYPE_PING,
        0U,
        ECHO_PING_PAYLOAD_BYTES,
        UINT32_C(0x12345678)
    );
}

static void test_generic_parser_roundtrip(void) {
    uint8_t header[ECHO_HEADER_BYTES];
    echo_frame_header parsed;

    echo_make_frame_header(
        header,
        0x16U,
        UINT16_C(0x1234),
        UINT32_C(0x00010203),
        UINT32_C(0x89ABCDEF)
    );
    assert(echo_parse_frame_header(header, &parsed) == ECHO_FRAME_OK);
    assert(parsed.type == 0x16U);
    assert(parsed.flags == UINT16_C(0x1234));
    assert(parsed.payload_length == UINT32_C(0x00010203));
    assert(parsed.request_id == UINT32_C(0x89ABCDEF));
}

static void test_generic_parser_bounds(void) {
    uint8_t header[ECHO_HEADER_BYTES];
    echo_frame_header parsed;

    echo_make_frame_header(
        header,
        0x10U,
        0U,
        ECHO_FRAME_MAX_PAYLOAD_BYTES,
        1U
    );
    assert(echo_parse_frame_header(header, &parsed) == ECHO_FRAME_OK);
    assert(parsed.payload_length == ECHO_FRAME_MAX_PAYLOAD_BYTES);

    echo_write_be32(header + 8U, ECHO_FRAME_MAX_PAYLOAD_BYTES + 1U);
    assert(echo_parse_frame_header(header, &parsed) == ECHO_FRAME_TOO_LARGE);

    echo_make_frame_header(header, 0x10U, 0U, 0U, 1U);
    header[3] ^= 1U;
    assert(echo_parse_frame_header(header, &parsed) == ECHO_FRAME_BAD_MAGIC);

    echo_make_frame_header(header, 0x10U, 0U, 0U, 1U);
    header[4] = (uint8_t)(ECHO_VERSION + 1U);
    assert(echo_parse_frame_header(header, &parsed) == ECHO_FRAME_BAD_VERSION);

    assert(echo_parse_frame_header(NULL, &parsed) == ECHO_FRAME_INVALID_ARGUMENT);
    assert(echo_parse_frame_header(header, NULL) == ECHO_FRAME_INVALID_ARGUMENT);
}

static void test_bootstrap_ping_contract(void) {
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

    build_valid_ping(header);
    echo_write_be32(header + 8U, ECHO_FRAME_MAX_PAYLOAD_BYTES + 1U);
    assert(echo_validate_ping_header(header) == -4);
}

int main(void) {
    test_generic_parser_roundtrip();
    test_generic_parser_bounds();
    test_bootstrap_ping_contract();
    puts("EchoCore protocol tests: OK");
    return 0;
}
