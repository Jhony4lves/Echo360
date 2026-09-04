#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <xecore/xboxkrnl_crypto.h>
#include <xecore/xboxkrnl_io.h>
#include <xecore/xboxkrnl_rtl.h>
#include <xecore/xboxkrnl_types.h>

#include "../openxechain/echo_transfer_writer_xbox.h"

#define FAKE_FILE_CAPACITY (ECHO_TRANSFER_CHUNK_MAX_BYTES * 2U)
#define FAKE_HANDLE ((HANDLE)(uintptr_t)0x1234U)
#define FAKE_FAILURE ((NTSTATUS)-1)

static uint8_t g_file[FAKE_FILE_CAPACITY];
static uint64_t g_file_size;
static int g_part_exists;
static int g_final_exists;
static uint32_t g_short_write_bytes;
static int g_flush_failure;
static uint32_t g_create_calls;
static uint32_t g_read_calls;
static uint32_t g_write_calls;
static uint32_t g_flush_calls;
static uint32_t g_truncate_calls;
static uint32_t g_rename_calls;
static uint32_t g_close_calls;
static char g_opened_path[ECHO_TRANSFER_NATIVE_PATH_BYTES];

static echo_transfer_writer g_writer;

static void fake_reset(void) {
    memset(g_file, 0, sizeof(g_file));
    g_file_size = 0U;
    g_part_exists = 0;
    g_final_exists = 0;
    g_short_write_bytes = 0U;
    g_flush_failure = 0;
    g_create_calls = 0U;
    g_read_calls = 0U;
    g_write_calls = 0U;
    g_flush_calls = 0U;
    g_truncate_calls = 0U;
    g_rename_calls = 0U;
    g_close_calls = 0U;
    memset(g_opened_path, 0, sizeof(g_opened_path));
    echo_transfer_writer_reset(&g_writer);
}

static void fake_digest(const uint8_t *data, uint32_t bytes, uint8_t out[ECHO_TRANSFER_SHA256_BYTES]) {
    CRYPT_SHA256_STATE state;
    XeCryptSha256Init(&state);
    XeCryptSha256Update(&state, data, bytes);
    XeCryptSha256Final(&state, out, ECHO_TRANSFER_SHA256_BYTES);
}

void RtlInitAnsiString(ANSI_STRING *dest, const char *source) {
    size_t length = strlen(source);
    assert(length <= UINT16_MAX);
    dest->Length = (uint16_t)length;
    dest->MaximumLength = (uint16_t)(length + 1U);
    dest->Buffer = (char *)source;
}

NTSTATUS NtCreateFile(HANDLE *handle_out, uint32_t desired_access, OBJECT_ATTRIBUTES *attributes,
                      IO_STATUS_BLOCK *io_status, uint32_t *allocation_size, uint32_t file_attributes,
                      uint32_t share_access, uint32_t disposition, uint32_t create_options) {
    size_t length;
    (void)desired_access; (void)allocation_size; (void)file_attributes;
    (void)share_access; (void)create_options;
    assert(handle_out != NULL && attributes != NULL && attributes->name_ptr != NULL);
    assert(attributes->name_ptr->Buffer != NULL);
    g_create_calls++;
    length = strlen(attributes->name_ptr->Buffer);
    assert(length < sizeof(g_opened_path));
    memcpy(g_opened_path, attributes->name_ptr->Buffer, length + 1U);
    if (disposition == FILE_OVERWRITE_IF) {
        g_file_size = 0U;
        g_part_exists = 1;
    } else if (disposition == FILE_OPEN) {
        if (!g_part_exists) return FAKE_FAILURE;
    } else return FAKE_FAILURE;
    *handle_out = FAKE_HANDLE;
    if (io_status != NULL) { io_status->Status = STATUS_SUCCESS; io_status->Information = 0U; }
    return STATUS_SUCCESS;
}

NTSTATUS NtQueryInformationFile(HANDLE handle, IO_STATUS_BLOCK *io_status, void *information,
                                uint32_t information_length, uint32_t information_class) {
    FILE_NETWORK_OPEN_INFORMATION *info;
    assert(handle == FAKE_HANDLE);
    assert(information_class == FileNetworkOpenInformation);
    assert(information_length == sizeof(FILE_NETWORK_OPEN_INFORMATION));
    info = (FILE_NETWORK_OPEN_INFORMATION *)information;
    memset(info, 0, sizeof(*info));
    info->EndOfFile = (int64_t)g_file_size;
    if (io_status != NULL) { io_status->Status = STATUS_SUCCESS; io_status->Information = information_length; }
    return STATUS_SUCCESS;
}

NTSTATUS NtReadFile(HANDLE handle, HANDLE event_handle, IO_APC_ROUTINE *apc_routine,
                    void *apc_context, IO_STATUS_BLOCK *io_status, void *buffer,
                    uint32_t bytes, int64_t *byte_offset) {
    uint64_t offset;
    (void)event_handle; (void)apc_routine; (void)apc_context;
    assert(handle == FAKE_HANDLE && byte_offset != NULL && *byte_offset >= 0);
    offset = (uint64_t)*byte_offset;
    if (offset > g_file_size || (uint64_t)bytes > g_file_size - offset) return FAKE_FAILURE;
    memcpy(buffer, g_file + offset, bytes);
    g_read_calls++;
    io_status->Status = STATUS_SUCCESS; io_status->Information = bytes;
    return STATUS_SUCCESS;
}

NTSTATUS NtWriteFile(HANDLE handle, HANDLE event_handle, IO_APC_ROUTINE *apc_routine,
                     void *apc_context, IO_STATUS_BLOCK *io_status, void *buffer,
                     uint32_t bytes, int64_t *byte_offset) {
    uint64_t offset;
    uint32_t actual = bytes;
    (void)event_handle; (void)apc_routine; (void)apc_context;
    assert(handle == FAKE_HANDLE && byte_offset != NULL && *byte_offset >= 0);
    offset = (uint64_t)*byte_offset;
    if (g_short_write_bytes != 0U && g_short_write_bytes < actual) actual = g_short_write_bytes;
    if (offset > FAKE_FILE_CAPACITY || (uint64_t)actual > (uint64_t)FAKE_FILE_CAPACITY - offset) return FAKE_FAILURE;
    memcpy(g_file + offset, buffer, actual);
    if (offset + actual > g_file_size) g_file_size = offset + actual;
    g_write_calls++;
    io_status->Status = STATUS_SUCCESS; io_status->Information = actual;
    return STATUS_SUCCESS;
}

NTSTATUS NtFlushBuffersFile(HANDLE handle, IO_STATUS_BLOCK *io_status) {
    assert(handle == FAKE_HANDLE);
    g_flush_calls++;
    if (g_flush_failure) return FAKE_FAILURE;
    io_status->Status = STATUS_SUCCESS; io_status->Information = 0U;
    return STATUS_SUCCESS;
}

NTSTATUS NtSetInformationFile(HANDLE handle, IO_STATUS_BLOCK *io_status, void *information,
                              uint32_t information_length, uint32_t information_class) {
    assert(handle == FAKE_HANDLE);
    if (information_class == FileEndOfFileInformation) {
        uint64_t new_size;
        assert(information_length == 8U);
        memcpy(&new_size, information, sizeof(new_size));
        if (new_size > FAKE_FILE_CAPACITY) return FAKE_FAILURE;
        if (new_size > g_file_size) memset(g_file + g_file_size, 0, (size_t)(new_size - g_file_size));
        g_file_size = new_size;
        g_truncate_calls++;
        io_status->Status = STATUS_SUCCESS; io_status->Information = information_length;
        return STATUS_SUCCESS;
    }
    if (information_class == FileRenameInformation) {
        assert(information_length == 16U);
        if (g_final_exists) return STATUS_OBJECT_NAME_COLLISION;
        g_final_exists = 1; g_part_exists = 0; g_rename_calls++;
        io_status->Status = STATUS_SUCCESS; io_status->Information = information_length;
        return STATUS_SUCCESS;
    }
    return FAKE_FAILURE;
}

NTSTATUS NtClose(HANDLE handle) {
    assert(handle == FAKE_HANDLE);
    g_close_calls++;
    return STATUS_SUCCESS;
}

NTSTATUS NtQueryFullAttributesFile(OBJECT_ATTRIBUTES *attributes, FILE_NETWORK_OPEN_INFORMATION *info) {
    (void)attributes; (void)info; return FAKE_FAILURE;
}

void XeCryptSha256Init(CRYPT_SHA256_STATE *state) {
    memset(state, 0, sizeof(*state));
    state->words[0] = UINT32_C(2166136261);
    state->words[1] = UINT32_C(0x9E3779B9);
}

void XeCryptSha256Update(CRYPT_SHA256_STATE *state, const uint8_t *data, uint32_t bytes) {
    uint32_t i;
    for (i = 0U; i < bytes; ++i) {
        state->words[0] ^= data[i];
        state->words[0] *= UINT32_C(16777619);
        state->words[1] += (uint32_t)data[i] + (state->words[0] >> 24U);
        state->words[2] ^= state->words[0] + state->words[1];
        state->words[3] += 1U;
    }
}

void XeCryptSha256Final(CRYPT_SHA256_STATE *state, uint8_t *digest, uint32_t digest_bytes) {
    uint32_t i;
    for (i = 0U; i < digest_bytes; ++i) {
        uint32_t word = state->words[i & 3U] ^
            (UINT32_C(0xA5A5A5A5) + i * UINT32_C(0x01010101));
        digest[i] = (uint8_t)(word >> ((i & 3U) * 8U));
    }
}

static void test_fresh_transfer_and_atomic_finalize(void) {
    static const char path[] = "/Hdd1/Games/test.bin";
    static const uint8_t payload[] = {1U,2U,3U,4U,5U,6U,7U,8U};
    uint8_t digest[ECHO_TRANSFER_SHA256_BYTES];
    fake_reset();
    assert((uintptr_t)&g_writer < UINT32_MAX);
    assert(echo_transfer_writer_open(&g_writer, 1U, path, sizeof(path)-1U, sizeof(payload), 0U, NULL, 0U) == ECHO_WRITER_OK);
    assert(g_create_calls == 1U && strstr(g_opened_path, ".echo.part") != NULL);
    assert(echo_transfer_writer_write_chunk(&g_writer, 0U, payload, 3U) == ECHO_WRITER_OK);
    assert(echo_transfer_writer_write_chunk(&g_writer, 1U, payload, 1U) == ECHO_WRITER_INVALID_STATE);
    assert(echo_transfer_writer_write_chunk(&g_writer, 3U, payload+3U, 5U) == ECHO_WRITER_OK);
    assert(g_writer.transfer.committed_bytes == sizeof(payload) && g_file_size == sizeof(payload));
    fake_digest(payload, sizeof(payload), digest);
    assert(echo_transfer_writer_finalize(&g_writer, digest) == ECHO_WRITER_OK);
    assert(g_flush_calls == 1U && g_rename_calls == 1U && g_close_calls == 1U && g_final_exists == 1);
    assert(g_writer.magic == ECHO_TRANSFER_WRITER_MAGIC && g_writer.opened == 0U);
}

static void test_hash_mismatch_never_renames(void) {
    static const char path[] = "/Hdd1/Games/hash.bin";
    static const uint8_t payload[] = {9U,8U,7U,6U};
    uint8_t wrong[ECHO_TRANSFER_SHA256_BYTES] = {0};
    fake_reset();
    assert(echo_transfer_writer_open(&g_writer, 2U, path, sizeof(path)-1U, sizeof(payload), 0U, NULL, 0U) == ECHO_WRITER_OK);
    assert(echo_transfer_writer_write_chunk(&g_writer, 0U, payload, sizeof(payload)) == ECHO_WRITER_OK);
    assert(echo_transfer_writer_finalize(&g_writer, wrong) == ECHO_WRITER_HASH_MISMATCH);
    assert(g_flush_calls == 1U && g_rename_calls == 0U && g_close_calls == 1U && g_part_exists == 1);
}

static void test_existing_destination_fails_closed(void) {
    static const char path[] = "/Hdd1/Games/existing.bin";
    static const uint8_t payload[] = {4U,3U,2U,1U};
    uint8_t digest[ECHO_TRANSFER_SHA256_BYTES];
    fake_reset(); g_final_exists = 1;
    assert(echo_transfer_writer_open(&g_writer, 3U, path, sizeof(path)-1U, sizeof(payload), 0U, NULL, 0U) == ECHO_WRITER_OK);
    assert(echo_transfer_writer_write_chunk(&g_writer, 0U, payload, sizeof(payload)) == ECHO_WRITER_OK);
    fake_digest(payload, sizeof(payload), digest);
    assert(echo_transfer_writer_finalize(&g_writer, digest) == ECHO_WRITER_DESTINATION_EXISTS);
    assert(g_rename_calls == 0U && g_close_calls == 1U && g_part_exists == 1);
}

static void test_partial_write_is_trimmed_on_resume(void) {
    static const char path[] = "/Hdd1/Games/resume.bin";
    static const uint8_t payload[] = {10U,11U,12U,13U,14U,15U,16U,17U};
    uint8_t scratch[3];
    uint8_t digest[ECHO_TRANSFER_SHA256_BYTES];
    fake_reset();
    assert(echo_transfer_writer_open(&g_writer, 4U, path, sizeof(path)-1U, sizeof(payload), 0U, NULL, 0U) == ECHO_WRITER_OK);
    assert(echo_transfer_writer_write_chunk(&g_writer, 0U, payload, 4U) == ECHO_WRITER_OK);
    g_short_write_bytes = 2U;
    assert(echo_transfer_writer_write_chunk(&g_writer, 4U, payload+4U, 4U) == ECHO_WRITER_IO_ERROR);
    assert(g_writer.transfer.committed_bytes == 4U && g_file_size == 6U);
    echo_transfer_writer_abort(&g_writer);
    assert(g_part_exists == 1);
    g_short_write_bytes = 0U;
    assert(echo_transfer_writer_open(&g_writer, 5U, path, sizeof(path)-1U, sizeof(payload), 4U, scratch, sizeof(scratch)) == ECHO_WRITER_OK);
    assert(g_truncate_calls == 1U && g_file_size == 4U && g_read_calls == 2U);
    assert(g_writer.transfer.committed_bytes == 4U);
    assert(echo_transfer_writer_write_chunk(&g_writer, 4U, payload+4U, 4U) == ECHO_WRITER_OK);
    fake_digest(payload, sizeof(payload), digest);
    assert(echo_transfer_writer_finalize(&g_writer, digest) == ECHO_WRITER_OK);
    assert(g_file_size == sizeof(payload) && memcmp(g_file, payload, sizeof(payload)) == 0 && g_rename_calls == 1U);
}

static void test_resume_shorter_than_commit_is_rejected(void) {
    static const char path[] = "/Hdd1/Games/short.bin";
    uint8_t scratch[4];
    fake_reset(); g_part_exists = 1; g_file_size = 3U;
    g_file[0]=1U; g_file[1]=2U; g_file[2]=3U;
    assert(echo_transfer_writer_open(&g_writer, 6U, path, sizeof(path)-1U, 8U, 4U, scratch, sizeof(scratch)) == ECHO_WRITER_RESUME_MISMATCH);
    assert(g_truncate_calls == 0U && g_close_calls == 1U);
}

static void test_flush_failure_never_renames(void) {
    static const char path[] = "/Hdd1/Games/flush.bin";
    static const uint8_t payload[] = {1U,1U,2U,3U,5U,8U};
    uint8_t digest[ECHO_TRANSFER_SHA256_BYTES];
    fake_reset();
    assert(echo_transfer_writer_open(&g_writer, 7U, path, sizeof(path)-1U, sizeof(payload), 0U, NULL, 0U) == ECHO_WRITER_OK);
    assert(echo_transfer_writer_write_chunk(&g_writer, 0U, payload, sizeof(payload)) == ECHO_WRITER_OK);
    fake_digest(payload, sizeof(payload), digest); g_flush_failure = 1;
    assert(echo_transfer_writer_finalize(&g_writer, digest) == ECHO_WRITER_IO_ERROR);
    assert(g_rename_calls == 0U && g_close_calls == 1U);
}

static void test_invalid_paths_fail_before_kernel_io(void) {
    static const char root_only[] = "/Hdd1";
    static const char flash_path[] = "/Flash/file.bin";
    fake_reset();
    assert(echo_transfer_writer_open(&g_writer, 8U, root_only, sizeof(root_only)-1U, 1U, 0U, NULL, 0U) == ECHO_WRITER_INVALID_PATH);
    assert(g_create_calls == 0U);
    assert(echo_transfer_writer_open(&g_writer, 9U, flash_path, sizeof(flash_path)-1U, 1U, 0U, NULL, 0U) == ECHO_WRITER_INVALID_PATH);
    assert(g_create_calls == 0U);
}

int main(void) {
    test_fresh_transfer_and_atomic_finalize();
    test_hash_mismatch_never_renames();
    test_existing_destination_fails_closed();
    test_partial_write_is_trimmed_on_resume();
    test_resume_shorter_than_commit_is_rejected();
    test_flush_failure_never_renames();
    test_invalid_paths_fail_before_kernel_io();
    puts("EchoCore transactional writer behavior tests: OK");
    return 0;
}
