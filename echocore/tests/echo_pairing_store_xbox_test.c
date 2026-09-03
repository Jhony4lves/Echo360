#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include <xecore/xboxkrnl_crypto.h>
#include <xecore/xboxkrnl_io.h>
#include <xecore/xboxkrnl_rtl.h>
#include <xecore/xboxkrnl_types.h>

#include "../openxechain/echo_pairing_store_xbox.c"

#define FAKE_FILE_HANDLE ((HANDLE)(uintptr_t)0x1234U)
#define FAKE_DIR_HANDLE  ((HANDLE)(uintptr_t)0x2345U)
#define FAKE_FAILURE ((NTSTATUS)-1)

static uint8_t g_file[ECHO_PAIRING_RECORD_BYTES];
static uint64_t g_file_size;
static int g_file_exists;
static int g_open_io_failure;
static int g_query_failure;
static int g_read_failure;
static uint32_t g_short_read_bytes;
static uint32_t g_directory_create_calls;
static uint32_t g_file_open_calls;
static uint32_t g_close_calls;
static uint32_t g_writer_open_calls;
static uint32_t g_writer_write_calls;
static uint32_t g_writer_finalize_calls;
static uint32_t g_writer_abort_calls;
static int g_writer_open_result;
static int g_writer_write_result;
static int g_writer_finalize_result;
static char g_writer_path[128];
static uint8_t g_writer_record[ECHO_PAIRING_RECORD_BYTES];
static uint32_t g_writer_record_bytes;
static uint8_t g_generated_secret[ECHO_AUTH_SECRET_BYTES];
static uint8_t g_generated_token[ECHO_PAIRING_TOKEN_BYTES];

static void fake_sha_init(CRYPT_SHA256_STATE *state) {
    memset(state, 0, sizeof(*state));
    state->words[0] = UINT32_C(2166136261);
    state->words[1] = UINT32_C(0x9E3779B9);
}

void XeCryptSha256Init(CRYPT_SHA256_STATE *state) {
    fake_sha_init(state);
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
    const char *path;
    (void)desired_access; (void)allocation_size; (void)file_attributes;
    (void)share_access; (void)create_options;
    assert(handle_out != NULL && attributes != NULL && attributes->name_ptr != NULL);
    path = attributes->name_ptr->Buffer;
    assert(path != NULL);

    if (disposition == FILE_OPEN_IF &&
        (strcmp(path, ECHO_PAIRING_NATIVE_DIR_1) == 0 ||
         strcmp(path, ECHO_PAIRING_NATIVE_DIR_2) == 0)) {
        g_directory_create_calls++;
        *handle_out = FAKE_DIR_HANDLE;
        if (io_status != NULL) { io_status->Status = STATUS_SUCCESS; io_status->Information = 0U; }
        return STATUS_SUCCESS;
    }

    if (disposition == FILE_OPEN && strcmp(path, ECHO_PAIRING_NATIVE_FILE) == 0) {
        g_file_open_calls++;
        if (g_open_io_failure) return FAKE_FAILURE;
        if (!g_file_exists) return STATUS_NO_SUCH_FILE;
        *handle_out = FAKE_FILE_HANDLE;
        if (io_status != NULL) { io_status->Status = STATUS_SUCCESS; io_status->Information = 0U; }
        return STATUS_SUCCESS;
    }

    return FAKE_FAILURE;
}

NTSTATUS NtQueryInformationFile(HANDLE handle, IO_STATUS_BLOCK *io_status, void *information,
                                uint32_t information_length, uint32_t information_class) {
    FILE_NETWORK_OPEN_INFORMATION *info;
    assert(handle == FAKE_FILE_HANDLE);
    assert(information_class == FileNetworkOpenInformation);
    assert(information_length == sizeof(FILE_NETWORK_OPEN_INFORMATION));
    if (g_query_failure) return FAKE_FAILURE;
    info = (FILE_NETWORK_OPEN_INFORMATION *)information;
    memset(info, 0, sizeof(*info));
    info->EndOfFile = (int64_t)g_file_size;
    if (io_status != NULL) { io_status->Status = STATUS_SUCCESS; io_status->Information = information_length; }
    return STATUS_SUCCESS;
}

NTSTATUS NtReadFile(HANDLE handle, HANDLE event_handle, IO_APC_ROUTINE *apc_routine,
                    void *apc_context, IO_STATUS_BLOCK *io_status, void *buffer,
                    uint32_t bytes, int64_t *byte_offset) {
    uint32_t actual = bytes;
    (void)event_handle; (void)apc_routine; (void)apc_context;
    assert(handle == FAKE_FILE_HANDLE);
    assert(byte_offset != NULL && *byte_offset == 0);
    if (g_read_failure) return FAKE_FAILURE;
    if (g_short_read_bytes != 0U && g_short_read_bytes < actual) actual = g_short_read_bytes;
    assert(actual <= sizeof(g_file));
    memcpy(buffer, g_file, actual);
    io_status->Status = STATUS_SUCCESS;
    io_status->Information = actual;
    return STATUS_SUCCESS;
}

NTSTATUS NtClose(HANDLE handle) {
    assert(handle == FAKE_FILE_HANDLE || handle == FAKE_DIR_HANDLE);
    g_close_calls++;
    return STATUS_SUCCESS;
}

int echo_auth_xbox_generate_pairing_secret(uint8_t secret[ECHO_AUTH_SECRET_BYTES]) {
    memcpy(secret, g_generated_secret, ECHO_AUTH_SECRET_BYTES);
    return 0;
}

void echo_transfer_writer_reset(echo_transfer_writer *writer) {
    assert(writer != NULL);
    memset(writer, 0, sizeof(*writer));
    writer->magic = ECHO_TRANSFER_WRITER_MAGIC;
}

int echo_transfer_writer_open(echo_transfer_writer *writer, uint32_t transfer_id,
                              const char *canonical_final_path, uint32_t canonical_final_path_length,
                              uint64_t total_bytes, uint64_t resume_offset,
                              uint8_t *scratch, uint32_t scratch_bytes) {
    (void)transfer_id; (void)resume_offset; (void)scratch; (void)scratch_bytes;
    assert(writer != NULL && canonical_final_path != NULL);
    assert(total_bytes == ECHO_PAIRING_RECORD_BYTES);
    assert(canonical_final_path_length == strlen(ECHO_PAIRING_CANONICAL_PATH));
    g_writer_open_calls++;
    assert(canonical_final_path_length < sizeof(g_writer_path));
    memcpy(g_writer_path, canonical_final_path, canonical_final_path_length);
    g_writer_path[canonical_final_path_length] = '\0';
    return g_writer_open_result;
}

int echo_transfer_writer_write_chunk(echo_transfer_writer *writer, uint64_t offset,
                                     const uint8_t *data, uint32_t data_bytes) {
    (void)writer;
    assert(offset == 0U && data != NULL && data_bytes == ECHO_PAIRING_RECORD_BYTES);
    g_writer_write_calls++;
    memcpy(g_writer_record, data, data_bytes);
    g_writer_record_bytes = data_bytes;
    return g_writer_write_result;
}

int echo_transfer_writer_finalize(echo_transfer_writer *writer,
                                  const uint8_t expected_sha256[ECHO_TRANSFER_SHA256_BYTES]) {
    uint8_t calculated[ECHO_TRANSFER_SHA256_BYTES];
    CRYPT_SHA256_STATE state;
    (void)writer;
    g_writer_finalize_calls++;
    fake_sha_init(&state);
    XeCryptSha256Update(&state, g_writer_record, g_writer_record_bytes);
    XeCryptSha256Final(&state, calculated, sizeof(calculated));
    assert(memcmp(calculated, expected_sha256, sizeof(calculated)) == 0);
    if (g_writer_finalize_result == ECHO_WRITER_OK) {
        memcpy(g_file, g_writer_record, ECHO_PAIRING_RECORD_BYTES);
        g_file_size = ECHO_PAIRING_RECORD_BYTES;
        g_file_exists = 1;
    }
    return g_writer_finalize_result;
}

void echo_transfer_writer_abort(echo_transfer_writer *writer) {
    (void)writer;
    g_writer_abort_calls++;
}

static void reset_fake(void) {
    uint32_t i;
    memset(g_file, 0, sizeof(g_file));
    memset(g_writer_record, 0, sizeof(g_writer_record));
    memset(g_writer_path, 0, sizeof(g_writer_path));
    g_file_size = 0U;
    g_file_exists = 0;
    g_open_io_failure = 0;
    g_query_failure = 0;
    g_read_failure = 0;
    g_short_read_bytes = 0U;
    g_directory_create_calls = 0U;
    g_file_open_calls = 0U;
    g_close_calls = 0U;
    g_writer_open_calls = 0U;
    g_writer_write_calls = 0U;
    g_writer_finalize_calls = 0U;
    g_writer_abort_calls = 0U;
    g_writer_open_result = ECHO_WRITER_OK;
    g_writer_write_result = ECHO_WRITER_OK;
    g_writer_finalize_result = ECHO_WRITER_OK;
    g_writer_record_bytes = 0U;
    for (i = 0U; i < ECHO_AUTH_SECRET_BYTES; ++i) g_generated_secret[i] = (uint8_t)(0x40U + i);
    for (i = 0U; i < ECHO_PAIRING_TOKEN_BYTES; ++i) g_generated_token[i] = (uint8_t)(0x10U + i);
}

static void make_valid_record(const uint8_t secret[ECHO_AUTH_SECRET_BYTES]) {
    uint8_t digest[ECHO_PAIRING_DIGEST_BYTES];
    echo_pairing_record_make_prefix(g_file, secret);
    echo_pairing_hash_prefix(g_file, digest);
    memcpy(g_file + ECHO_PAIRING_RECORD_PREFIX_BYTES, digest, sizeof(digest));
    g_file_size = ECHO_PAIRING_RECORD_BYTES;
    g_file_exists = 1;
}

static void make_valid_token_record(const uint8_t token[ECHO_PAIRING_TOKEN_BYTES]) {
    uint8_t digest[ECHO_PAIRING_DIGEST_BYTES];
    echo_pairing_record_make_token_prefix(g_file, token);
    echo_pairing_hash_token_prefix(g_file, digest);
    memcpy(g_file + ECHO_PAIRING_RECORD_PREFIX_BYTES, digest, sizeof(digest));
    g_file_size = ECHO_PAIRING_RECORD_BYTES;
    g_file_exists = 1;
}

static int bytes_all_zero(const uint8_t *bytes, uint32_t length) {
    uint32_t i;
    uint8_t any = 0U;
    for (i = 0U; i < length; ++i) any = (uint8_t)(any | bytes[i]);
    return any == 0U;
}

static void test_valid_record_loads_without_repair(void) {
    uint8_t output[ECHO_AUTH_SECRET_BYTES];
    reset_fake();
    make_valid_record(g_generated_secret);
    memset(output, 0, sizeof(output));
    assert(echo_pairing_xbox_ensure_secret(output) == ECHO_PAIRING_STORE_OK);
    assert(memcmp(output, g_generated_secret, sizeof(output)) == 0);
    assert(g_writer_open_calls == 0U);
    assert(g_file_open_calls == 1U);
}

static void test_missing_record_is_created_transactionally(void) {
    uint8_t output[ECHO_AUTH_SECRET_BYTES];
    reset_fake();
    memset(output, 0, sizeof(output));
    assert(echo_pairing_xbox_ensure_secret(output) == ECHO_PAIRING_STORE_CREATED);
    assert(memcmp(output, g_generated_secret, sizeof(output)) == 0);
    assert(g_directory_create_calls == 2U);
    assert(g_writer_open_calls == 1U && g_writer_write_calls == 1U && g_writer_finalize_calls == 1U);
    assert(strcmp(g_writer_path, ECHO_PAIRING_CANONICAL_PATH) == 0);
    assert(g_file_exists == 1 && g_file_size == ECHO_PAIRING_RECORD_BYTES);
    memset(output, 0, sizeof(output));
    assert(echo_pairing_xbox_load_secret(output) == ECHO_PAIRING_STORE_OK);
    assert(memcmp(output, g_generated_secret, sizeof(output)) == 0);
}

static void test_recoverable_token_round_trip(void) {
    uint8_t token[ECHO_PAIRING_TOKEN_BYTES];
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t expected_secret[ECHO_AUTH_SECRET_BYTES];

    reset_fake();
    assert(echo_pairing_xbox_store_token(g_generated_token) == ECHO_PAIRING_STORE_CREATED);
    assert(g_file_exists == 1 && g_file_size == ECHO_PAIRING_RECORD_BYTES);
    assert(g_file[5] == ECHO_PAIRING_RECORD_VERSION_TOKEN);
    assert(g_directory_create_calls == 2U);
    assert(g_writer_open_calls == 1U && g_writer_write_calls == 1U && g_writer_finalize_calls == 1U);

    memset(token, 0, sizeof(token));
    assert(echo_pairing_xbox_load_token(token) == ECHO_PAIRING_STORE_OK);
    assert(memcmp(token, g_generated_token, sizeof(token)) == 0);

    memset(secret, 0, sizeof(secret));
    memset(expected_secret, 0, sizeof(expected_secret));
    assert(echo_pairing_derive_secret_from_token(g_generated_token, expected_secret) == 0);
    assert(echo_pairing_xbox_load_secret(secret) == ECHO_PAIRING_STORE_OK);
    assert(memcmp(secret, expected_secret, sizeof(secret)) == 0);
}

static void test_legacy_record_reports_token_unavailable(void) {
    uint8_t token[ECHO_PAIRING_TOKEN_BYTES];
    reset_fake();
    make_valid_record(g_generated_secret);
    memset(token, 0xAA, sizeof(token));
    assert(echo_pairing_xbox_load_token(token) == ECHO_PAIRING_STORE_TOKEN_UNAVAILABLE);
    assert(bytes_all_zero(token, sizeof(token)));
    assert(g_writer_open_calls == 0U);
}

static void test_token_record_corruption_fails_closed(void) {
    uint8_t token[ECHO_PAIRING_TOKEN_BYTES];
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];

    reset_fake();
    make_valid_token_record(g_generated_token);
    g_file[ECHO_PAIRING_RECORD_TOKEN_RESERVED_OFFSET] = 1U;
    memset(token, 0xAA, sizeof(token));
    memset(secret, 0xAA, sizeof(secret));
    assert(echo_pairing_xbox_load_token(token) == ECHO_PAIRING_STORE_CORRUPT);
    assert(echo_pairing_xbox_load_secret(secret) == ECHO_PAIRING_STORE_CORRUPT);
    assert(bytes_all_zero(token, sizeof(token)));
    assert(bytes_all_zero(secret, sizeof(secret)));
    assert(g_writer_open_calls == 0U);
}

static void test_corruption_never_autorepairs(void) {
    uint8_t output[ECHO_AUTH_SECRET_BYTES];
    reset_fake();
    make_valid_record(g_generated_secret);
    g_file[0] ^= 1U;
    memset(output, 0xAA, sizeof(output));
    assert(echo_pairing_xbox_ensure_secret(output) == ECHO_PAIRING_STORE_CORRUPT);
    assert(bytes_all_zero(output, sizeof(output)));
    assert(g_writer_open_calls == 0U && g_directory_create_calls == 0U);

    reset_fake();
    make_valid_record(g_generated_secret);
    g_file[ECHO_PAIRING_RECORD_PREFIX_BYTES] ^= 1U;
    memset(output, 0xAA, sizeof(output));
    assert(echo_pairing_xbox_ensure_secret(output) == ECHO_PAIRING_STORE_CORRUPT);
    assert(bytes_all_zero(output, sizeof(output)));
    assert(g_writer_open_calls == 0U);
}

static void test_wrong_size_and_zero_secret_fail_closed(void) {
    uint8_t output[ECHO_AUTH_SECRET_BYTES];
    uint8_t zero_secret[ECHO_AUTH_SECRET_BYTES] = {0};
    reset_fake();
    make_valid_record(g_generated_secret);
    g_file_size = ECHO_PAIRING_RECORD_BYTES - 1U;
    memset(output, 0xAA, sizeof(output));
    assert(echo_pairing_xbox_ensure_secret(output) == ECHO_PAIRING_STORE_CORRUPT);
    assert(bytes_all_zero(output, sizeof(output)));
    assert(g_writer_open_calls == 0U);

    reset_fake();
    make_valid_record(zero_secret);
    memset(output, 0xAA, sizeof(output));
    assert(echo_pairing_xbox_ensure_secret(output) == ECHO_PAIRING_STORE_CORRUPT);
    assert(bytes_all_zero(output, sizeof(output)));
    assert(g_writer_open_calls == 0U);
}

static void test_io_failures_do_not_create_new_identity(void) {
    uint8_t output[ECHO_AUTH_SECRET_BYTES];
    reset_fake();
    g_open_io_failure = 1;
    memset(output, 0xAA, sizeof(output));
    assert(echo_pairing_xbox_ensure_secret(output) == ECHO_PAIRING_STORE_IO_ERROR);
    assert(bytes_all_zero(output, sizeof(output)));
    assert(g_writer_open_calls == 0U);

    reset_fake();
    make_valid_record(g_generated_secret);
    g_query_failure = 1;
    assert(echo_pairing_xbox_ensure_secret(output) == ECHO_PAIRING_STORE_IO_ERROR);
    assert(bytes_all_zero(output, sizeof(output)));
    assert(g_writer_open_calls == 0U);

    reset_fake();
    make_valid_record(g_generated_secret);
    g_short_read_bytes = ECHO_PAIRING_RECORD_BYTES - 1U;
    assert(echo_pairing_xbox_ensure_secret(output) == ECHO_PAIRING_STORE_IO_ERROR);
    assert(bytes_all_zero(output, sizeof(output)));
    assert(g_writer_open_calls == 0U);
}

static void test_writer_failure_never_returns_secret(void) {
    uint8_t output[ECHO_AUTH_SECRET_BYTES];
    reset_fake();
    g_writer_finalize_result = ECHO_WRITER_IO_ERROR;
    memset(output, 0xAA, sizeof(output));
    assert(echo_pairing_xbox_ensure_secret(output) == ECHO_PAIRING_STORE_IO_ERROR);
    assert(bytes_all_zero(output, sizeof(output)));
    assert(g_writer_finalize_calls == 1U);
    assert(g_file_exists == 0);
}

static void test_null_and_zero_arguments_are_rejected(void) {
    uint8_t zero_token[ECHO_PAIRING_TOKEN_BYTES] = {0};
    reset_fake();
    assert(echo_pairing_xbox_load_secret(NULL) == ECHO_PAIRING_STORE_INVALID_ARGUMENT);
    assert(echo_pairing_xbox_load_token(NULL) == ECHO_PAIRING_STORE_INVALID_ARGUMENT);
    assert(echo_pairing_xbox_store_token(NULL) == ECHO_PAIRING_STORE_INVALID_ARGUMENT);
    assert(echo_pairing_xbox_store_token(zero_token) == ECHO_PAIRING_STORE_INVALID_ARGUMENT);
    assert(echo_pairing_xbox_ensure_secret(NULL) == ECHO_PAIRING_STORE_INVALID_ARGUMENT);
}

int main(void) {
    test_valid_record_loads_without_repair();
    test_missing_record_is_created_transactionally();
    test_recoverable_token_round_trip();
    test_legacy_record_reports_token_unavailable();
    test_token_record_corruption_fails_closed();
    test_corruption_never_autorepairs();
    test_wrong_size_and_zero_secret_fail_closed();
    test_io_failures_do_not_create_new_identity();
    test_writer_failure_never_returns_secret();
    test_null_and_zero_arguments_are_rejected();
    puts("EchoCore persistent pairing store tests: OK");
    return 0;
}
