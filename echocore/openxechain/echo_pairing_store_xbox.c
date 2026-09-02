#include <stddef.h>
#include <stdint.h>

#include <xecore/xboxkrnl_crypto.h>
#include <xecore/xboxkrnl_io.h>
#include <xecore/xboxkrnl_rtl.h>
#include <xecore/xboxkrnl_types.h>

#include "echo_auth_crypto_xbox.h"
#include "echo_pairing_record.h"
#include "echo_pairing_store_xbox.h"
#include "echo_transfer_writer_xbox.h"

#define ECHO_PAIRING_NATIVE_DIR_1 "\\Device\\Harddisk0\\Partition1\\Echo360"
#define ECHO_PAIRING_NATIVE_DIR_2 "\\Device\\Harddisk0\\Partition1\\Echo360\\EchoCore"
#define ECHO_PAIRING_NATIVE_FILE  "\\Device\\Harddisk0\\Partition1\\Echo360\\EchoCore\\pairing.dat"
#define ECHO_PAIRING_DOMAIN_BYTES 25U
#define ECHO_PAIRING_TRANSFER_ID_FALLBACK UINT32_C(0x45435052)

/*
 * The pinned xecorelib declares NTSTATUS as uint32_t, but its STATUS_* macros
 * accidentally cast through an undeclared STATUS typedef. Keep the exact
 * values locally until upstream fixes that header, and use FAILED(status)
 * instead of signed comparisons.
 */
#define ECHO_NTSTATUS_NO_SUCH_FILE          ((NTSTATUS)UINT32_C(0xC000000F))
#define ECHO_NTSTATUS_OBJECT_NAME_NOT_FOUND ((NTSTATUS)UINT32_C(0xC0000034))
#define ECHO_NTSTATUS_OBJECT_PATH_NOT_FOUND ((NTSTATUS)UINT32_C(0xC000003A))

static const uint8_t g_echo_pairing_domain[ECHO_PAIRING_DOMAIN_BYTES] = {
    'E','C','H','O','3','6','0','-','P','A','I','R','I','N','G','-',
    'R','E','C','O','R','D','-','V','1'
};

static void echo_pairing_zero(void *memory, size_t length) {
    volatile uint8_t *bytes = (volatile uint8_t *)memory;
    size_t i;
    if (bytes == NULL) return;
    for (i = 0U; i < length; ++i) bytes[i] = 0U;
}

static void echo_pairing_copy(uint8_t *dest, const uint8_t *src, uint32_t length) {
    uint32_t i;
    for (i = 0U; i < length; ++i) dest[i] = src[i];
}

static int echo_pairing_secret_is_zero(const uint8_t secret[ECHO_AUTH_SECRET_BYTES]) {
    uint8_t any = 0U;
    uint32_t i;
    if (secret == NULL) return 1;
    for (i = 0U; i < ECHO_AUTH_SECRET_BYTES; ++i) any = (uint8_t)(any | secret[i]);
    return any == 0U ? 1 : 0;
}

static int echo_pairing_status_is_missing(NTSTATUS status) {
    return status == ECHO_NTSTATUS_NO_SUCH_FILE ||
           status == ECHO_NTSTATUS_OBJECT_NAME_NOT_FOUND ||
           status == ECHO_NTSTATUS_OBJECT_PATH_NOT_FOUND;
}

static void echo_pairing_hash_prefix(
    const uint8_t prefix[ECHO_PAIRING_RECORD_PREFIX_BYTES],
    uint8_t digest[ECHO_PAIRING_DIGEST_BYTES]
) {
    CRYPT_SHA256_STATE state;
    echo_pairing_zero(&state, sizeof(state));
    XeCryptSha256Init(&state);
    XeCryptSha256Update(&state, g_echo_pairing_domain, ECHO_PAIRING_DOMAIN_BYTES);
    XeCryptSha256Update(&state, prefix, ECHO_PAIRING_RECORD_PREFIX_BYTES);
    XeCryptSha256Final(&state, digest, ECHO_PAIRING_DIGEST_BYTES);
    echo_pairing_zero(&state, sizeof(state));
}

static void echo_pairing_hash_record(
    const uint8_t record[ECHO_PAIRING_RECORD_BYTES],
    uint8_t digest[ECHO_PAIRING_DIGEST_BYTES]
) {
    CRYPT_SHA256_STATE state;
    echo_pairing_zero(&state, sizeof(state));
    XeCryptSha256Init(&state);
    XeCryptSha256Update(&state, record, ECHO_PAIRING_RECORD_BYTES);
    XeCryptSha256Final(&state, digest, ECHO_PAIRING_DIGEST_BYTES);
    echo_pairing_zero(&state, sizeof(state));
}

static int echo_pairing_create_directory(const char *native_path) {
    ANSI_STRING name;
    OBJECT_ATTRIBUTES attributes;
    IO_STATUS_BLOCK io_status;
    HANDLE handle = (HANDLE)0;
    NTSTATUS status;

    if (native_path == NULL) return ECHO_PAIRING_STORE_INVALID_ARGUMENT;
    RtlInitAnsiString(&name, native_path);
    attributes.root_directory = 0U;
    attributes.name_ptr = &name;
    attributes.attributes = OBJ_CASE_INSENSITIVE;
    echo_pairing_zero(&io_status, sizeof(io_status));

    status = NtCreateFile(
        &handle,
        FILE_READ_DATA | FILE_WRITE_DATA | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
        &attributes,
        &io_status,
        (uint32_t *)0,
        FILE_ATTRIBUTE_DIRECTORY,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        FILE_OPEN_IF,
        FILE_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT
    );
    if (FAILED(status) || handle == (HANDLE)0) return ECHO_PAIRING_STORE_IO_ERROR;
    (void)NtClose(handle);
    return ECHO_PAIRING_STORE_OK;
}

int echo_pairing_xbox_load_secret(
    uint8_t secret_out[ECHO_AUTH_SECRET_BYTES]
) {
    ANSI_STRING name;
    OBJECT_ATTRIBUTES attributes;
    IO_STATUS_BLOCK io_status;
    FILE_NETWORK_OPEN_INFORMATION info;
    HANDLE handle = (HANDLE)0;
    uint8_t record[ECHO_PAIRING_RECORD_BYTES];
    uint8_t calculated[ECHO_PAIRING_DIGEST_BYTES];
    int64_t offset = 0;
    NTSTATUS status;
    int result = ECHO_PAIRING_STORE_IO_ERROR;

    if (secret_out == NULL) return ECHO_PAIRING_STORE_INVALID_ARGUMENT;
    echo_pairing_zero(secret_out, ECHO_AUTH_SECRET_BYTES);
    echo_pairing_zero(record, sizeof(record));
    echo_pairing_zero(calculated, sizeof(calculated));
    echo_pairing_zero(&io_status, sizeof(io_status));
    echo_pairing_zero(&info, sizeof(info));

    RtlInitAnsiString(&name, ECHO_PAIRING_NATIVE_FILE);
    attributes.root_directory = 0U;
    attributes.name_ptr = &name;
    attributes.attributes = OBJ_CASE_INSENSITIVE;

    status = NtCreateFile(
        &handle,
        FILE_READ_DATA | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
        &attributes,
        &io_status,
        (uint32_t *)0,
        FILE_ATTRIBUTE_NORMAL,
        FILE_SHARE_READ,
        FILE_OPEN,
        FILE_NON_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT
    );
    if (FAILED(status) || handle == (HANDLE)0) {
        return echo_pairing_status_is_missing(status)
            ? ECHO_PAIRING_STORE_NOT_FOUND
            : ECHO_PAIRING_STORE_IO_ERROR;
    }

    echo_pairing_zero(&io_status, sizeof(io_status));
    status = NtQueryInformationFile(
        handle,
        &io_status,
        &info,
        (uint32_t)sizeof(info),
        FileNetworkOpenInformation
    );
    if (FAILED(status) || info.EndOfFile != (int64_t)ECHO_PAIRING_RECORD_BYTES) {
        result = FAILED(status) ? ECHO_PAIRING_STORE_IO_ERROR : ECHO_PAIRING_STORE_CORRUPT;
        goto cleanup;
    }

    echo_pairing_zero(&io_status, sizeof(io_status));
    status = NtReadFile(
        handle,
        (HANDLE)0,
        (IO_APC_ROUTINE *)0,
        (void *)0,
        &io_status,
        record,
        ECHO_PAIRING_RECORD_BYTES,
        &offset
    );
    if (FAILED(status) || io_status.Information != ECHO_PAIRING_RECORD_BYTES) {
        result = ECHO_PAIRING_STORE_IO_ERROR;
        goto cleanup;
    }

    if (echo_pairing_record_validate_prefix(record) != 0) {
        result = ECHO_PAIRING_STORE_CORRUPT;
        goto cleanup;
    }
    echo_pairing_hash_prefix(record, calculated);
    if (!echo_pairing_record_digest_equal(calculated, record + ECHO_PAIRING_RECORD_PREFIX_BYTES)) {
        result = ECHO_PAIRING_STORE_CORRUPT;
        goto cleanup;
    }

    echo_pairing_copy(secret_out, record + 8U, ECHO_AUTH_SECRET_BYTES);
    result = ECHO_PAIRING_STORE_OK;

cleanup:
    (void)NtClose(handle);
    echo_pairing_zero(record, sizeof(record));
    echo_pairing_zero(calculated, sizeof(calculated));
    if (result != ECHO_PAIRING_STORE_OK) echo_pairing_zero(secret_out, ECHO_AUTH_SECRET_BYTES);
    return result;
}

int echo_pairing_xbox_store_secret(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES]
) {
    echo_transfer_writer writer;
    uint8_t record[ECHO_PAIRING_RECORD_BYTES];
    uint8_t record_digest[ECHO_PAIRING_DIGEST_BYTES];
    uint8_t file_digest[ECHO_PAIRING_DIGEST_BYTES];
    uint32_t transfer_id;
    int writer_opened = 0;
    int result = ECHO_PAIRING_STORE_IO_ERROR;

    if (secret == NULL || echo_pairing_secret_is_zero(secret)) {
        return ECHO_PAIRING_STORE_INVALID_ARGUMENT;
    }

    echo_pairing_zero(record, sizeof(record));
    echo_pairing_zero(record_digest, sizeof(record_digest));
    echo_pairing_zero(file_digest, sizeof(file_digest));
    echo_transfer_writer_reset(&writer);

    if (echo_pairing_create_directory(ECHO_PAIRING_NATIVE_DIR_1) != ECHO_PAIRING_STORE_OK ||
        echo_pairing_create_directory(ECHO_PAIRING_NATIVE_DIR_2) != ECHO_PAIRING_STORE_OK) {
        goto cleanup;
    }

    echo_pairing_record_make_prefix(record, secret);
    echo_pairing_hash_prefix(record, record_digest);
    echo_pairing_copy(
        record + ECHO_PAIRING_RECORD_PREFIX_BYTES,
        record_digest,
        ECHO_PAIRING_DIGEST_BYTES
    );
    echo_pairing_hash_record(record, file_digest);

    transfer_id = ((uint32_t)secret[0] << 24U) |
                  ((uint32_t)secret[1] << 16U) |
                  ((uint32_t)secret[2] << 8U) |
                  (uint32_t)secret[3];
    if (transfer_id == 0U) transfer_id = ECHO_PAIRING_TRANSFER_ID_FALLBACK;

    if (echo_transfer_writer_open(
            &writer,
            transfer_id,
            ECHO_PAIRING_CANONICAL_PATH,
            (uint32_t)(sizeof(ECHO_PAIRING_CANONICAL_PATH) - 1U),
            ECHO_PAIRING_RECORD_BYTES,
            0U,
            NULL,
            0U
        ) != ECHO_WRITER_OK) {
        goto cleanup;
    }
    writer_opened = 1;

    if (echo_transfer_writer_write_chunk(
            &writer,
            0U,
            record,
            ECHO_PAIRING_RECORD_BYTES
        ) != ECHO_WRITER_OK) {
        goto cleanup;
    }
    if (echo_transfer_writer_finalize(&writer, file_digest) != ECHO_WRITER_OK) {
        goto cleanup;
    }

    writer_opened = 0;
    result = ECHO_PAIRING_STORE_CREATED;

cleanup:
    if (writer_opened) echo_transfer_writer_abort(&writer);
    echo_pairing_zero(record, sizeof(record));
    echo_pairing_zero(record_digest, sizeof(record_digest));
    echo_pairing_zero(file_digest, sizeof(file_digest));
    return result;
}

static int echo_pairing_xbox_create_secret(
    uint8_t secret_out[ECHO_AUTH_SECRET_BYTES]
) {
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    int result;

    if (secret_out == NULL) return ECHO_PAIRING_STORE_INVALID_ARGUMENT;
    echo_pairing_zero(secret, sizeof(secret));
    echo_pairing_zero(secret_out, ECHO_AUTH_SECRET_BYTES);

    if (echo_auth_xbox_generate_pairing_secret(secret) != 0) {
        result = ECHO_PAIRING_STORE_IO_ERROR;
        goto cleanup;
    }

    result = echo_pairing_xbox_store_secret(secret);
    if (result != ECHO_PAIRING_STORE_CREATED) goto cleanup;

    echo_pairing_copy(secret_out, secret, ECHO_AUTH_SECRET_BYTES);

cleanup:
    echo_pairing_zero(secret, sizeof(secret));
    if (result != ECHO_PAIRING_STORE_CREATED) echo_pairing_zero(secret_out, ECHO_AUTH_SECRET_BYTES);
    return result;
}

int echo_pairing_xbox_ensure_secret(
    uint8_t secret_out[ECHO_AUTH_SECRET_BYTES]
) {
    int result;
    if (secret_out == NULL) return ECHO_PAIRING_STORE_INVALID_ARGUMENT;
    result = echo_pairing_xbox_load_secret(secret_out);
    if (result == ECHO_PAIRING_STORE_OK) return result;
    if (result != ECHO_PAIRING_STORE_NOT_FOUND) return result;
    return echo_pairing_xbox_create_secret(secret_out);
}
