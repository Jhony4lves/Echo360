#include <stddef.h>
#include <stdint.h>

#include <xecore/xboxkrnl_rtl.h>
#include <xecore/xboxkrnl_types.h>

#include "echo_dir_list_xbox.h"
#include "echo_path_policy.h"

/*
 * Do not include xboxkrnl_io.h here yet.
 *
 * The pinned xecorelib header currently declares the Xbox NtOpenFile ABI
 * without ShareAccess and models FILE_DIRECTORY_INFORMATION.FileName as
 * wchar_t. Public Xbox/Xenia-validated implementations use the six-argument
 * NtOpenFile ABI and an ANSI directory name. Keep the tiny ABI surface we use
 * explicit until upstream headers are corrected and re-audited.
 */
extern NTSTATUS NtOpenFile(
    HANDLE *handle_out,
    uint32_t desired_access,
    OBJECT_ATTRIBUTES *object_attributes,
    IO_STATUS_BLOCK *io_status_block,
    uint32_t share_access,
    uint32_t open_options
);
extern NTSTATUS NtQueryDirectoryFile(
    HANDLE file_handle,
    HANDLE event_handle,
    void *apc_routine,
    void *apc_context,
    IO_STATUS_BLOCK *io_status_block,
    void *file_information,
    uint32_t length,
    ANSI_STRING *file_name,
    uint32_t restart_scan
);
extern NTSTATUS NtClose(HANDLE handle);

#define ECHO_NTSTATUS_OBJECT_PATH_NOT_FOUND ((NTSTATUS)0xC000003AU)
#define ECHO_NTSTATUS_NOT_A_DIRECTORY       ((NTSTATUS)0xC0000103U)
#define ECHO_DIR_QUERY_BUFFER_BYTES 576U

/* Xbox/Xenia layout: fixed 0x40-byte header followed by ANSI bytes. */
typedef struct echo_xbox_directory_information {
    uint32_t next_entry_offset;
    uint32_t file_index;
    int64_t creation_time;
    int64_t last_access_time;
    int64_t last_write_time;
    int64_t change_time;
    int64_t end_of_file;
    int64_t allocation_size;
    uint32_t file_attributes;
    uint32_t file_name_length;
    char file_name[1];
} echo_xbox_directory_information;

_Static_assert(
    offsetof(echo_xbox_directory_information, file_name) == 0x40U,
    "Xbox FILE_DIRECTORY_INFORMATION ANSI name must start at 0x40"
);

static uint8_t echo_dir_status_from_ntstatus(NTSTATUS status) {
    switch (status) {
        case STATUS_NO_SUCH_FILE:
        case STATUS_OBJECT_NAME_NOT_FOUND:
        case STATUS_NOT_FOUND:
        case ECHO_NTSTATUS_OBJECT_PATH_NOT_FOUND:
            return ECHO_STATUS_NOT_FOUND;
        case STATUS_ACCESS_DENIED:
            return ECHO_STATUS_ACCESS_DENIED;
        case ECHO_NTSTATUS_NOT_A_DIRECTORY:
            return ECHO_STATUS_NOT_DIRECTORY;
        case STATUS_OBJECT_NAME_INVALID:
        case STATUS_INVALID_PARAMETER:
            return ECHO_STATUS_INVALID_PATH;
        case STATUS_NOT_SUPPORTED:
        case STATUS_NOT_IMPLEMENTED:
            return ECHO_STATUS_UNSUPPORTED;
        default:
            return ECHO_STATUS_IO_ERROR;
    }
}

static int echo_dir_name_is_dot(const char *name, uint32_t length) {
    if (length == 1U && name[0] == '.') return 1;
    if (length == 2U && name[0] == '.' && name[1] == '.') return 1;
    return 0;
}

static int echo_dir_copy_entry(
    const uint8_t *query_buffer,
    uint32_t returned_bytes,
    echo_directory_entry *entry
) {
    const echo_xbox_directory_information *info;
    uint32_t name_length;
    uint32_t i;

    if (query_buffer == NULL || entry == NULL) return -1;
    if (returned_bytes != 0U && returned_bytes < 0x40U) return -1;

    info = (const echo_xbox_directory_information *)query_buffer;
    name_length = info->file_name_length;

    if (name_length == 0U || name_length > ECHO_MAX_NAME_BYTES) return -2;
    if (0x40U + name_length > ECHO_DIR_QUERY_BUFFER_BYTES) return -1;
    if (returned_bytes != 0U && 0x40U + name_length > returned_bytes) return -1;

    for (i = 0U; i < name_length; ++i) {
        unsigned char ch = (unsigned char)info->file_name[i];
        if (ch == 0U || ch < 0x20U || ch == 0x7FU || ch == '/' || ch == '\\') {
            return -1;
        }
        entry->name[i] = (char)ch;
    }
    entry->name[name_length] = '\0';
    entry->name_length = (uint16_t)name_length;
    entry->reserved = 0U;
    entry->object_type =
        (info->file_attributes & FILE_ATTRIBUTE_DIRECTORY) != 0U
            ? ECHO_OBJECT_DIRECTORY
            : ECHO_OBJECT_FILE;

    if (info->end_of_file < 0) return -1;
    entry->size = entry->object_type == ECHO_OBJECT_DIRECTORY
        ? UINT64_C(0)
        : (uint64_t)info->end_of_file;
    return 0;
}

static NTSTATUS echo_dir_query_one(
    HANDLE directory,
    ANSI_STRING *mask,
    uint32_t restart_scan,
    uint8_t query_buffer[ECHO_DIR_QUERY_BUFFER_BYTES],
    IO_STATUS_BLOCK *io_status
) {
    return NtQueryDirectoryFile(
        directory,
        (HANDLE)0,
        (void *)0,
        (void *)0,
        io_status,
        query_buffer,
        ECHO_DIR_QUERY_BUFFER_BYTES,
        mask,
        restart_scan
    );
}

int echo_xbox_dir_list(
    const char *wire_path,
    size_t wire_path_length,
    uint16_t max_entries,
    echo_directory_entry_callback callback,
    void *callback_context,
    echo_directory_list_result *result
) {
    char native_path[ECHO_NATIVE_PATH_MAX];
    char mask_text[2] = {'*', '\0'};
    ANSI_STRING name;
    ANSI_STRING mask;
    OBJECT_ATTRIBUTES attributes;
    IO_STATUS_BLOCK io_status;
    HANDLE directory = (HANDLE)0;
    uint8_t query_buffer[ECHO_DIR_QUERY_BUFFER_BYTES];
    uint32_t restart_scan = 1U;
    NTSTATUS status;

    if (result == NULL || callback == NULL || max_entries == 0U ||
        max_entries > ECHO_MAX_DIR_ENTRIES) {
        return -1;
    }

    result->status = ECHO_STATUS_INVALID_PATH;
    result->limit_reached = 0U;
    result->emitted_entries = 0U;

    if (echo_path_to_native_hdd1(
            wire_path,
            wire_path_length,
            native_path,
            sizeof(native_path),
            NULL
        ) != 0) {
        return 0;
    }

    RtlInitAnsiString(&name, native_path);
    RtlInitAnsiString(&mask, mask_text);
    attributes.root_directory = (HANDLE)0;
    attributes.name_ptr = &name;
    attributes.attributes = OBJ_CASE_INSENSITIVE;

    status = NtOpenFile(
        &directory,
        FILE_LIST_DIRECTORY | SYNCHRONIZE,
        &attributes,
        &io_status,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        FILE_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT
    );
    if (status < 0) {
        result->status = echo_dir_status_from_ntstatus(status);
        return 0;
    }

    result->status = ECHO_STATUS_OK;

    for (;;) {
        echo_directory_entry entry;
        int copy_result;

        io_status.Status = STATUS_SUCCESS;
        io_status.Information = 0U;
        status = echo_dir_query_one(
            directory,
            &mask,
            restart_scan,
            query_buffer,
            &io_status
        );
        restart_scan = 0U;

        if (status == STATUS_NO_MORE_FILES) break;
        if (status < 0) {
            result->status = echo_dir_status_from_ntstatus(status);
            break;
        }

        copy_result = echo_dir_copy_entry(
            query_buffer,
            io_status.Information,
            &entry
        );
        if (copy_result == -2) {
            result->status = ECHO_STATUS_UNSUPPORTED;
            break;
        }
        if (copy_result != 0) {
            result->status = ECHO_STATUS_IO_ERROR;
            break;
        }
        if (echo_dir_name_is_dot(entry.name, entry.name_length)) continue;

        if (result->emitted_entries >= max_entries) {
            /* This entry is the bounded look-ahead proving truncation. */
            result->limit_reached = 1U;
            result->status = ECHO_STATUS_LIMIT_REACHED;
            break;
        }

        if (callback(&entry, callback_context) != 0) {
            result->status = ECHO_STATUS_IO_ERROR;
            (void)NtClose(directory);
            return -2;
        }
        result->emitted_entries++;
    }

    (void)NtClose(directory);
    return 0;
}
