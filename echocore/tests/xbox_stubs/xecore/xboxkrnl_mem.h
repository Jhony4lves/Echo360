#ifndef ECHO_TEST_XBOXKRNL_MEM_H
#define ECHO_TEST_XBOXKRNL_MEM_H

#include "xboxkrnl_types.h"

bool MmIsAddressValid(void *address);
NTSTATUS MmQueryStatistics(MM_QUERY_STATISTICS_RESULT *stats);

#endif
