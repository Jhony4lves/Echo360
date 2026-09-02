#ifndef ECHO_TEST_XBOXKRNL_TYPES_H
#define ECHO_TEST_XBOXKRNL_TYPES_H

#include <stdbool.h>
#include <stdint.h>

typedef int32_t NTSTATUS;
typedef void *HANDLE;
typedef void *HMODULE;

typedef struct _ANSI_STRING {
    uint16_t Length;
    uint16_t MaximumLength;
    char *Buffer;
} ANSI_STRING;

typedef struct _OBJECT_ATTRIBUTES {
    HANDLE root_directory;
    ANSI_STRING *name_ptr;
    uint32_t attributes;
} OBJECT_ATTRIBUTES;

typedef struct _IO_STATUS_BLOCK {
    union {
        NTSTATUS Status;
        void *Pointer;
    };
    uint32_t Information;
} IO_STATUS_BLOCK;

typedef void IO_APC_ROUTINE(
    void *context,
    IO_STATUS_BLOCK *status,
    uint32_t reserved
);

typedef struct _FILE_NETWORK_OPEN_INFORMATION {
    int64_t CreationTime;
    int64_t LastAccessTime;
    int64_t LastWriteTime;
    int64_t ChangeTime;
    int64_t AllocationSize;
    int64_t EndOfFile;
    uint32_t FileAttributes;
    uint32_t pad;
} FILE_NETWORK_OPEN_INFORMATION;

typedef struct _CRYPT_SHA256_STATE {
    uint32_t words[40];
} CRYPT_SHA256_STATE;

typedef struct _MM_TITLE_STATISTICS {
    uint32_t available_pages;
} MM_TITLE_STATISTICS;

typedef struct _MM_QUERY_STATISTICS_RESULT {
    uint32_t size;
    uint32_t total_physical_pages;
    MM_TITLE_STATISTICS title;
} MM_QUERY_STATISTICS_RESULT;

#define STATUS_SUCCESS ((NTSTATUS)0x00000000)
#define STATUS_NO_MORE_FILES ((NTSTATUS)0x80000006u)
#define STATUS_NO_SUCH_FILE ((NTSTATUS)0xC000000Fu)
#define STATUS_OBJECT_NAME_NOT_FOUND ((NTSTATUS)0xC0000034u)
#define STATUS_OBJECT_NAME_COLLISION ((NTSTATUS)0xC0000035u)
#define STATUS_NOT_FOUND ((NTSTATUS)0xC0000225u)
#define STATUS_ACCESS_DENIED ((NTSTATUS)0xC0000022u)
#define STATUS_OBJECT_NAME_INVALID ((NTSTATUS)0xC0000033u)
#define STATUS_INVALID_PARAMETER ((NTSTATUS)0xC000000Du)
#define STATUS_NOT_SUPPORTED ((NTSTATUS)0xC00000BBu)
#define STATUS_NOT_IMPLEMENTED ((NTSTATUS)0xC0000002u)

#define FILE_READ_DATA 0x0001u
#define FILE_WRITE_DATA 0x0002u
#define FILE_LIST_DIRECTORY 0x0001u
#define FILE_READ_ATTRIBUTES 0x0080u
#define DELETE 0x00010000u
#define SYNCHRONIZE 0x00100000u

#define FILE_ATTRIBUTE_DIRECTORY 0x00000010u
#define FILE_ATTRIBUTE_NORMAL 0x00000080u
#define FILE_SHARE_READ 0x00000001u
#define FILE_SHARE_WRITE 0x00000002u
#define FILE_SHARE_DELETE 0x00000004u
#define FILE_OPEN 1u
#define FILE_OVERWRITE_IF 5u
#define FILE_DIRECTORY_FILE 0x00000001u
#define FILE_SYNCHRONOUS_IO_NONALERT 0x00000020u
#define FILE_NON_DIRECTORY_FILE 0x00000040u
#define OBJ_CASE_INSENSITIVE 0x00000040u

#define FileRenameInformation 10u
#define FileEndOfFileInformation 20u
#define FileNetworkOpenInformation 34u

#define FAILED(status) ((NTSTATUS)(status) < 0)

#endif
