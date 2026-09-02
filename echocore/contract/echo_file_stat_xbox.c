#include <stddef.h>
#include <stdint.h>

#include <xecore/xboxkrnl_io.h>
#include <xecore/xboxkrnl_rtl.h>
#include <xecore/xboxkrnl_types.h>

#include "echo_file_stat_xbox.h"
#include "echo_path_policy.h"

/* Pinned xecorelib's STATUS_* macros use an undeclared STATUS typedef. */
#define ECHO_NTSTATUS_NO_SUCH_FILE           ((NTSTATUS)0xC000000FU)
#define ECHO_NTSTATUS_INVALID_PARAMETER      ((NTSTATUS)0xC000000DU)
#define ECHO_NTSTATUS_NOT_IMPLEMENTED        ((NTSTATUS)0xC0000002U)
#define ECHO_NTSTATUS_ACCESS_DENIED          ((NTSTATUS)0xC0000022U)
#define ECHO_NTSTATUS_OBJECT_NAME_INVALID    ((NTSTATUS)0xC0000033U)
#define ECHO_NTSTATUS_OBJECT_NAME_NOT_FOUND  ((NTSTATUS)0xC0000034U)
#define ECHO_NTSTATUS_OBJECT_PATH_NOT_FOUND  ((NTSTATUS)0xC000003AU)
#define ECHO_NTSTATUS_NOT_SUPPORTED          ((NTSTATUS)0xC00000BBU)
#define ECHO_NTSTATUS_NOT_A_DIRECTORY        ((NTSTATUS)0xC0000103U)
#define ECHO_NTSTATUS_NOT_FOUND              ((NTSTATUS)0xC0000225U)

static uint8_t echo_stat_status_from_ntstatus(NTSTATUS status) {
    switch (status) {
        case ECHO_NTSTATUS_NO_SUCH_FILE:
        case ECHO_NTSTATUS_OBJECT_NAME_NOT_FOUND:
        case ECHO_NTSTATUS_NOT_FOUND:
        case ECHO_NTSTATUS_OBJECT_PATH_NOT_FOUND:
            return ECHO_STATUS_NOT_FOUND;
        case ECHO_NTSTATUS_ACCESS_DENIED:
            return ECHO_STATUS_ACCESS_DENIED;
        case ECHO_NTSTATUS_NOT_A_DIRECTORY:
            return ECHO_STATUS_NOT_DIRECTORY;
        case ECHO_NTSTATUS_OBJECT_NAME_INVALID:
        case ECHO_NTSTATUS_INVALID_PARAMETER:
            return ECHO_STATUS_INVALID_PATH;
        case ECHO_NTSTATUS_NOT_SUPPORTED:
        case ECHO_NTSTATUS_NOT_IMPLEMENTED:
            return ECHO_STATUS_UNSUPPORTED;
        default:
            return ECHO_STATUS_IO_ERROR;
    }
}

int echo_xbox_file_stat(
    const char *wire_path,
    size_t wire_path_length,
    echo_file_stat_result *result
) {
    char native_path[ECHO_NATIVE_PATH_MAX];
    ANSI_STRING name;
    OBJECT_ATTRIBUTES attributes;
    FILE_NETWORK_OPEN_INFORMATION info;
    NTSTATUS status;

    if (result == NULL) return -1;
    result->status = ECHO_STATUS_INVALID_PATH;
    result->object_type = ECHO_OBJECT_NONE;
    result->size = UINT64_C(0);

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
    attributes.root_directory = 0U;
    attributes.name_ptr = &name;
    attributes.attributes = OBJ_CASE_INSENSITIVE;

    status = NtQueryFullAttributesFile(&attributes, &info);
    if (FAILED(status)) {
        result->status = echo_stat_status_from_ntstatus(status);
        return 0;
    }

    result->status = ECHO_STATUS_OK;
    result->object_type =
        (info.FileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0U
            ? ECHO_OBJECT_DIRECTORY
            : ECHO_OBJECT_FILE;
    result->size = info.EndOfFile > 0 ? (uint64_t)info.EndOfFile : UINT64_C(0);
    return 0;
}
