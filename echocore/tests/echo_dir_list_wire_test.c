#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../contract/echo_readonly_contract.h"

static void test_header(void) {
    uint8_t header[ECHO_DIR_LIST_HEADER_BYTES];
    echo_ro_make_dir_list_header(header, ECHO_STATUS_LIMIT_REACHED, 9U, 37U);
    assert(header[0] == ECHO_STATUS_LIMIT_REACHED);
    assert(header[1] == 1U);
    assert(echo_ro_read_be16(header + 2U) == 37U);
}

static void test_file_entry(void) {
    uint8_t encoded[64];
    static const uint8_t name[] = {'d','e','f','a','u','l','t','.','x','e','x'};
    uint32_t bytes = echo_ro_write_dir_entry(
        encoded,
        sizeof(encoded),
        ECHO_OBJECT_FILE,
        UINT64_C(0x1122334455667788),
        name,
        (uint16_t)sizeof(name)
    );

    assert(bytes == ECHO_DIR_ENTRY_HEADER_BYTES + sizeof(name));
    assert(encoded[0] == ECHO_OBJECT_FILE);
    assert(encoded[1] == 0U);
    assert(echo_ro_read_be16(encoded + 2U) == sizeof(name));
    assert(echo_ro_read_be64(encoded + 4U) == UINT64_C(0x1122334455667788));
    assert(memcmp(encoded + ECHO_DIR_ENTRY_HEADER_BYTES, name, sizeof(name)) == 0);
}

static void test_directory_size_is_forced_zero(void) {
    uint8_t encoded[32];
    static const uint8_t name[] = {'C','o','n','t','e','n','t'};
    uint32_t bytes = echo_ro_write_dir_entry(
        encoded,
        sizeof(encoded),
        ECHO_OBJECT_DIRECTORY,
        UINT64_MAX,
        name,
        (uint16_t)sizeof(name)
    );
    assert(bytes != 0U);
    assert(echo_ro_read_be64(encoded + 4U) == 0U);
}

static void test_invalid_entries_fail_closed(void) {
    uint8_t encoded[32];
    static const uint8_t good[] = {'x'};
    static const uint8_t with_nul[] = {'a',0U,'b'};

    assert(echo_ro_write_dir_entry(NULL, sizeof(encoded), ECHO_OBJECT_FILE, 1U, good, 1U) == 0U);
    assert(echo_ro_write_dir_entry(encoded, sizeof(encoded), ECHO_OBJECT_NONE, 1U, good, 1U) == 0U);
    assert(echo_ro_write_dir_entry(encoded, sizeof(encoded), ECHO_OBJECT_FILE, 1U, NULL, 1U) == 0U);
    assert(echo_ro_write_dir_entry(encoded, sizeof(encoded), ECHO_OBJECT_FILE, 1U, good, 0U) == 0U);
    assert(echo_ro_write_dir_entry(encoded, 1U, ECHO_OBJECT_FILE, 1U, good, 1U) == 0U);
    assert(echo_ro_write_dir_entry(encoded, sizeof(encoded), ECHO_OBJECT_FILE, 1U, with_nul, 3U) == 0U);
    assert(echo_ro_dir_entry_encoded_size(ECHO_MAX_NAME_BYTES + 1U) == 0U);
}

static void test_max_payload_bound(void) {
    assert(ECHO_DIR_LIST_MAX_PAYLOAD_BYTES ==
        ECHO_DIR_LIST_HEADER_BYTES +
        ECHO_MAX_DIR_ENTRIES * (ECHO_DIR_ENTRY_HEADER_BYTES + ECHO_MAX_NAME_BYTES));
    assert(ECHO_DIR_LIST_MAX_PAYLOAD_BYTES == 68356U);
}

int main(void) {
    test_header();
    test_file_entry();
    test_directory_size_is_forced_zero();
    test_invalid_entries_fail_closed();
    test_max_payload_bound();
    puts("EchoCore DIR_LIST wire tests: OK");
    return 0;
}
