#include <stddef.h>
#include <stdint.h>

#include <xecore/xboxkrnl_crypto.h>
#include <xecore/xboxkrnl_io.h>
#include <xecore/xboxkrnl_rtl.h>
#include <xecore/xboxkrnl_types.h>

#include "echo_path.h"
#include "echo_transfer_writer_xbox.h"

#define ECHO_PART_SUFFIX ".echo.part"
#define ECHO_PART_SUFFIX_BYTES 10U

typedef struct echo_file_rename_information {
    uint32_t replace_existing;
    uint32_t root_directory;
    ANSI_STRING file_name;
} echo_file_rename_information;

_Static_assert(
    sizeof(echo_file_rename_information) == 16U,
    "Xbox FILE_RENAME_INFORMATION must be 16 bytes"
);

static void echo_writer_zero(void *memory, size_t length) {
    volatile uint8_t *bytes = (volatile uint8_t *)memory;
    size_t i;
    if (bytes == NULL) return;
    for (i = 0U; i < length; ++i) bytes[i] = 0U;
}

static int echo_writer_digest_equal(
    const uint8_t left[ECHO_TRANSFER_SHA256_BYTES],
    const uint8_t right[ECHO_TRANSFER_SHA256_BYTES]
) {
    uint8_t difference = 0U;
    uint32_t i;
    for (i = 0U; i < ECHO_TRANSFER_SHA256_BYTES; ++i) {
        difference = (uint8_t)(difference | (uint8_t)(left[i] ^ right[i]));
    }
    return difference == 0U ? 1 : 0;
}

static int echo_writer_path_has_leaf(const char *path, uint32_t length) {
    uint32_t i;
    if (path == NULL || length < 3U) return 0;
    for (i = 1U; i < length; ++i) {
        if (path[i] == '/' && i + 1U < length) return 1;
    }
    return 0;
}

static int echo_writer_make_paths(
    echo_transfer_writer *writer,
    const char *canonical,
    uint32_t canonical_length,
    char temp_native[ECHO_TRANSFER_NATIVE_PATH_BYTES]
) {
    size_t final_length;
    uint32_t i;

    if (!echo_writer_path_has_leaf(canonical, canonical_length)) {
        return ECHO_WRITER_INVALID_PATH;
    }

    final_length = echo_path_to_kernel(
        canonical,
        (size_t)canonical_length,
        writer->final_native_path,
        sizeof(writer->final_native_path),
        1
    );
    if (final_length == 0U) return ECHO_WRITER_INVALID_PATH;
    if (final_length + ECHO_PART_SUFFIX_BYTES + 1U > ECHO_TRANSFER_NATIVE_PATH_BYTES) {
        return ECHO_WRITER_INVALID_PATH;
    }

    for (i = 0U; i < (uint32_t)final_length; ++i) {
        temp_native[i] = writer->final_native_path[i];
    }
    for (i = 0U; i < ECHO_PART_SUFFIX_BYTES; ++i) {
        temp_native[final_length + i] = ECHO_PART_SUFFIX[i];
    }
    temp_native[final_length + ECHO_PART_SUFFIX_BYTES] = '\0';
    return ECHO_WRITER_OK;
}

static int echo_writer_query_size(HANDLE file, uint64_t *size_out) {
    FILE_NETWORK_OPEN_INFORMATION info;
    IO_STATUS_BLOCK io_status;
    NTSTATUS status;

    if (size_out == NULL) return ECHO_WRITER_INVALID_ARGUMENT;
    echo_writer_zero(&info, sizeof(info));
    echo_writer_zero(&io_status, sizeof(io_status));

    status = NtQueryInformationFile(
        file,
        &io_status,
        &info,
        (uint32_t)sizeof(info),
        FileNetworkOpenInformation
    );
    if (status < 0 || info.EndOfFile < 0) return ECHO_WRITER_IO_ERROR;
    *size_out = (uint64_t)info.EndOfFile;
    return ECHO_WRITER_OK;
}

static int echo_writer_rehash_prefix(
    echo_transfer_writer *writer,
    uint64_t prefix_bytes,
    uint8_t *scratch,
    uint32_t scratch_bytes
) {
    uint64_t offset = 0U;
    IO_STATUS_BLOCK io_status;

    if (prefix_bytes == 0U) return ECHO_WRITER_OK;
    if (scratch == NULL || scratch_bytes == 0U) return ECHO_WRITER_INVALID_ARGUMENT;
    if (scratch_bytes > ECHO_TRANSFER_CHUNK_MAX_BYTES) {
        scratch_bytes = ECHO_TRANSFER_CHUNK_MAX_BYTES;
    }

    while (offset < prefix_bytes) {
        uint64_t remaining = prefix_bytes - offset;
        uint32_t request = remaining > (uint64_t)scratch_bytes
            ? scratch_bytes
            : (uint32_t)remaining;
        int64_t byte_offset = (int64_t)offset;
        NTSTATUS status;

        echo_writer_zero(&io_status, sizeof(io_status));
        status = NtReadFile(
            writer->file_handle,
            (HANDLE)0,
            (IO_APC_ROUTINE *)0,
            (void *)0,
            &io_status,
            scratch,
            request,
            &byte_offset
        );
        if (status < 0 || io_status.Information != request) {
            return ECHO_WRITER_IO_ERROR;
        }

        XeCryptSha256Update(&writer->sha256, scratch, request);
        offset += (uint64_t)request;
    }

    return ECHO_WRITER_OK;
}

void echo_transfer_writer_reset(echo_transfer_writer *writer) {
    if (writer == NULL) return;
    echo_writer_zero(writer, sizeof(*writer));
    echo_transfer_reset(&writer->transfer);
}

void echo_transfer_writer_abort(echo_transfer_writer *writer) {
    if (writer == NULL) return;
    if (writer->opened != 0U && writer->file_handle != (HANDLE)0) {
        (void)NtClose(writer->file_handle);
    }
    echo_transfer_writer_reset(writer);
}

int echo_transfer_writer_open(
    echo_transfer_writer *writer,
    uint32_t transfer_id,
    const char *canonical_final_path,
    uint32_t canonical_final_path_length,
    uint64_t total_bytes,
    uint64_t resume_offset,
    uint8_t *scratch,
    uint32_t scratch_bytes
) {
    char temp_native[ECHO_TRANSFER_NATIVE_PATH_BYTES];
    ANSI_STRING temp_name;
    OBJECT_ATTRIBUTES attributes;
    IO_STATUS_BLOCK io_status;
    uint32_t disposition;
    uint64_t existing_size = 0U;
    NTSTATUS status;
    int result;

    if (writer == NULL || canonical_final_path == NULL || transfer_id == 0U ||
        resume_offset > total_bytes) {
        return ECHO_WRITER_INVALID_ARGUMENT;
    }

    echo_transfer_writer_reset(writer);
    result = echo_writer_make_paths(
        writer,
        canonical_final_path,
        canonical_final_path_length,
        temp_native
    );
    if (result != ECHO_WRITER_OK) {
        echo_transfer_writer_reset(writer);
        return result;
    }

    RtlInitAnsiString(&temp_name, temp_native);
    attributes.root_directory = (HANDLE)0;
    attributes.name_ptr = &temp_name;
    attributes.attributes = OBJ_CASE_INSENSITIVE;
    echo_writer_zero(&io_status, sizeof(io_status));

    disposition = resume_offset == 0U ? FILE_OVERWRITE_IF : FILE_OPEN;
    status = NtCreateFile(
        &writer->file_handle,
        FILE_READ_DATA | FILE_WRITE_DATA | FILE_READ_ATTRIBUTES | DELETE | SYNCHRONIZE,
        &attributes,
        &io_status,
        (uint32_t *)0,
        FILE_ATTRIBUTE_NORMAL,
        FILE_SHARE_READ,
        disposition,
        FILE_NON_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT
    );
    if (status < 0 || writer->file_handle == (HANDLE)0) {
        echo_transfer_writer_reset(writer);
        return ECHO_WRITER_IO_ERROR;
    }
    writer->opened = 1U;

    result = echo_writer_query_size(writer->file_handle, &existing_size);
    if (result != ECHO_WRITER_OK) {
        echo_transfer_writer_abort(writer);
        return result;
    }
    if (existing_size != resume_offset) {
        echo_transfer_writer_abort(writer);
        return ECHO_WRITER_RESUME_MISMATCH;
    }

    if (echo_transfer_begin(
            &writer->transfer,
            transfer_id,
            total_bytes,
            resume_offset
        ) != 0) {
        echo_transfer_writer_abort(writer);
        return ECHO_WRITER_INVALID_STATE;
    }

    XeCryptSha256Init(&writer->sha256);
    result = echo_writer_rehash_prefix(
        writer,
        resume_offset,
        scratch,
        scratch_bytes
    );
    if (result != ECHO_WRITER_OK) {
        echo_transfer_writer_abort(writer);
        return result;
    }

    return ECHO_WRITER_OK;
}

int echo_transfer_writer_write_chunk(
    echo_transfer_writer *writer,
    uint64_t offset,
    const uint8_t *data,
    uint32_t data_bytes
) {
    IO_STATUS_BLOCK io_status;
    int64_t byte_offset;
    NTSTATUS status;

    if (writer == NULL || writer->opened == 0U || data == NULL) {
        return ECHO_WRITER_INVALID_ARGUMENT;
    }
    if (data_bytes == 0U || data_bytes > ECHO_TRANSFER_CHUNK_MAX_BYTES ||
        offset != writer->transfer.committed_bytes ||
        offset > writer->transfer.total_bytes ||
        (uint64_t)data_bytes > writer->transfer.total_bytes - offset) {
        return ECHO_WRITER_INVALID_STATE;
    }

    byte_offset = (int64_t)offset;
    echo_writer_zero(&io_status, sizeof(io_status));
    status = NtWriteFile(
        writer->file_handle,
        (HANDLE)0,
        (IO_APC_ROUTINE *)0,
        (void *)0,
        &io_status,
        (void *)data,
        data_bytes,
        &byte_offset
    );
    if (status < 0 || io_status.Information != data_bytes) {
        return ECHO_WRITER_IO_ERROR;
    }

    XeCryptSha256Update(&writer->sha256, data, data_bytes);
    if (echo_transfer_commit_chunk(&writer->transfer, offset, data_bytes) != 0) {
        return ECHO_WRITER_INVALID_STATE;
    }
    return ECHO_WRITER_OK;
}

int echo_transfer_writer_finalize(
    echo_transfer_writer *writer,
    const uint8_t expected_sha256[ECHO_TRANSFER_SHA256_BYTES]
) {
    uint8_t digest[ECHO_TRANSFER_SHA256_BYTES];
    ANSI_STRING final_name;
    echo_file_rename_information rename_info;
    IO_STATUS_BLOCK io_status;
    NTSTATUS status;

    if (writer == NULL || expected_sha256 == NULL || writer->opened == 0U ||
        !echo_transfer_complete(&writer->transfer)) {
        return ECHO_WRITER_INVALID_STATE;
    }

    echo_writer_zero(&io_status, sizeof(io_status));
    status = NtFlushBuffersFile(writer->file_handle, &io_status);
    if (status < 0) {
        echo_transfer_writer_abort(writer);
        return ECHO_WRITER_IO_ERROR;
    }

    echo_writer_zero(digest, sizeof(digest));
    XeCryptSha256Final(&writer->sha256, digest, sizeof(digest));
    if (!echo_writer_digest_equal(digest, expected_sha256)) {
        echo_writer_zero(digest, sizeof(digest));
        echo_transfer_writer_abort(writer);
        return ECHO_WRITER_HASH_MISMATCH;
    }
    echo_writer_zero(digest, sizeof(digest));

    RtlInitAnsiString(&final_name, writer->final_native_path);
    echo_writer_zero(&rename_info, sizeof(rename_info));
    rename_info.replace_existing = 0U;
    rename_info.root_directory = 0U;
    rename_info.file_name = final_name;
    echo_writer_zero(&io_status, sizeof(io_status));

    status = NtSetInformationFile(
        writer->file_handle,
        &io_status,
        &rename_info,
        (uint32_t)sizeof(rename_info),
        FileRenameInformation
    );

    if (status < 0) {
        int result = status == STATUS_OBJECT_NAME_COLLISION
            ? ECHO_WRITER_DESTINATION_EXISTS
            : ECHO_WRITER_IO_ERROR;
        echo_transfer_writer_abort(writer);
        return result;
    }

    (void)NtClose(writer->file_handle);
    echo_transfer_writer_reset(writer);
    return ECHO_WRITER_OK;
}
