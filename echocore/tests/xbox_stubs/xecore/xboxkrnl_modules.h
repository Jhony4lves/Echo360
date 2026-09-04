#ifndef ECHO_TEST_XBOXKRNL_MODULES_H
#define ECHO_TEST_XBOXKRNL_MODULES_H

#include "xboxkrnl_types.h"

NTSTATUS XexGetModuleHandle(const char *name, HMODULE *out_hmodule);
NTSTATUS XexGetProcedureAddress(
    HMODULE hmodule,
    uint32_t ordinal,
    void **out_function_ptr
);

#endif
