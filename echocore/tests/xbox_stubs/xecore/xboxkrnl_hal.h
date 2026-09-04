#ifndef ECHO_TEST_XBOXKRNL_HAL_H
#define ECHO_TEST_XBOXKRNL_HAL_H

#include <stdint.h>

void HalSendSMCMessage(uint8_t *message, uint8_t *response);

#endif
