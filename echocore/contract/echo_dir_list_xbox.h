#ifndef ECHO_DIR_LIST_XBOX_H
#define ECHO_DIR_LIST_XBOX_H

#include <stddef.h>
#include <stdint.h>

#include "echo_readonly_contract.h"

typedef struct echo_directory_entry {
    char name[ECHO_MAX_NAME_BYTES + 1U];
    uint16_t name_length;
    uint8_t object_type;
    uint8_t reserved;
    uint64_t size;
} echo_directory_entry;

typedef struct echo_directory_list_result {
    uint8_t status;
    uint8_t limit_reached;
    uint16_t emitted_entries;
} echo_directory_list_result;

typedef int (*echo_directory_entry_callback)(
    const echo_directory_entry *entry,
    void *context
);

/*
 * Enumerate one canonical Hdd1 directory without recursion.
 *
 * The callback is invoked once per retained entry. max_entries must be in
 * 1..ECHO_MAX_DIR_ENTRIES. The implementation performs at most one bounded
 * look-ahead after max_entries so limit_reached means that at least one
 * additional non-dot entry actually existed; hitting the numeric cap alone
 * does not falsely imply truncation.
 *
 * This is deliberately framing-independent. The EchoLink DIR_LIST wire format
 * remains a draft until FILE_STAT and directory enumeration are proven on the
 * physical console.
 */
int echo_xbox_dir_list(
    const char *wire_path,
    size_t wire_path_length,
    uint16_t max_entries,
    echo_directory_entry_callback callback,
    void *callback_context,
    echo_directory_list_result *result
);

#endif
