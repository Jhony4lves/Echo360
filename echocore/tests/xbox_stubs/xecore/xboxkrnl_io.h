#ifndef ECHO_TEST_XBOXKRNL_IO_H
#define ECHO_TEST_XBOXKRNL_IO_H

#include "xboxkrnl_types.h"

NTSTATUS NtCreateFile(
    HANDLE *, uint32_t, OBJECT_ATTRIBUTES *, IO_STATUS_BLOCK *, uint32_t *,
    uint32_t, uint32_t, uint32_t, uint32_t
);
NTSTATUS NtQueryFullAttributesFile(
    OBJECT_ATTRIBUTES *, FILE_NETWORK_OPEN_INFORMATION *
);
NTSTATUS NtQueryInformationFile(
    HANDLE, IO_STATUS_BLOCK *, void *, uint32_t, uint32_t
);
NTSTATUS NtReadFile(
    HANDLE, HANDLE, IO_APC_ROUTINE *, void *, IO_STATUS_BLOCK *, void *,
    uint32_t, int64_t *
);
NTSTATUS NtWriteFile(
    HANDLE, HANDLE, IO_APC_ROUTINE *, void *, IO_STATUS_BLOCK *, void *,
    uint32_t, int64_t *
);
NTSTATUS NtFlushBuffersFile(HANDLE, IO_STATUS_BLOCK *);
NTSTATUS NtSetInformationFile(
    HANDLE, IO_STATUS_BLOCK *, void *, uint32_t, uint32_t
);
NTSTATUS NtClose(HANDLE);

#endif
