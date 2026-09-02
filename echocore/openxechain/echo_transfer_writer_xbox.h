#ifndef ECHO_TRANSFER_WRITER_XBOX_H
#define ECHO_TRANSFER_WRITER_XBOX_H

#include <stdint.h>

#include <xecore/xboxkrnl_types.h>

#include "echo_transfer_state.h"

#define ECHO_TRANSFER_SHA256_BYTES 32U
#define ECHO_TRANSFER_NATIVE_PATH_BYTES 320U
#define ECHO_TRANSFER_WRITER_MAGIC UINT32_C(0x45575231) /* EWR1 */

typedef enum echo_transfer_writer_result {
    ECHO_WRITER_OK = 0,
    ECHO_WRITER_INVALID_ARGUMENT = -1,
    ECHO_WRITER_INVALID_PATH = -2,
    ECHO_WRITER_IO_ERROR = -3,
    ECHO_WRITER_RESUME_MISMATCH = -4,
    ECHO_WRITER_HASH_MISMATCH = -5,
    ECHO_WRITER_DESTINATION_EXISTS = -6,
    ECHO_WRITER_INVALID_STATE = -7
} echo_transfer_writer_result;

typedef struct echo_transfer_writer {
    uint32_t magic;
    HANDLE file_handle;
    echo_transfer_state transfer;
    CRYPT_SHA256_STATE sha256;
    char final_native_path[ECHO_TRANSFER_NATIVE_PATH_BYTES];
    uint8_t opened;
} echo_transfer_writer;

/* Must be called once before first use; never closes an active handle. */
void echo_transfer_writer_reset(echo_transfer_writer *writer);

/*
 * Open/create <final>.echo.part and initialize the incremental SHA-256 state.
 * The writer must already have been initialized with reset() and must not have
 * an active transfer. If resume_offset > 0, the existing part file must be
 * exactly that size and is re-hashed using caller-owned scratch memory before
 * any new chunk is accepted. scratch may be NULL for a fresh transfer.
 */
int echo_transfer_writer_open(
    echo_transfer_writer *writer,
    uint32_t transfer_id,
    const char *canonical_final_path,
    uint32_t canonical_final_path_length,
    uint64_t total_bytes,
    uint64_t resume_offset,
    uint8_t *scratch,
    uint32_t scratch_bytes
);

/* Sequential-only; offset must equal the last disk-committed offset. */
int echo_transfer_writer_write_chunk(
    echo_transfer_writer *writer,
    uint64_t offset,
    const uint8_t *data,
    uint32_t data_bytes
);

/* Flush, verify the full SHA-256 and atomically rename the part file. */
int echo_transfer_writer_finalize(
    echo_transfer_writer *writer,
    const uint8_t expected_sha256[ECHO_TRANSFER_SHA256_BYTES]
);

/* Close without deleting the .echo.part file so a later session can resume. */
void echo_transfer_writer_abort(echo_transfer_writer *writer);

#endif
