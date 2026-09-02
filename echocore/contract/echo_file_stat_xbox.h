#ifndef ECHO_FILE_STAT_XBOX_H
#define ECHO_FILE_STAT_XBOX_H

#include <stddef.h>
#include <stdint.h>

#include "echo_readonly_contract.h"

typedef struct echo_file_stat_result {
    uint8_t status;
    uint8_t object_type;
    uint64_t size;
} echo_file_stat_result;

int echo_xbox_file_stat(
    const char *wire_path,
    size_t wire_path_length,
    echo_file_stat_result *result
);

#endif
