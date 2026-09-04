#ifndef ECHO_XNET_ABI_H
#define ECHO_XNET_ABI_H

#include <stdint.h>

/*
 * Minimal audited Xbox 360 XNet startup ABI used by EchoCore.
 *
 * Public XDK-era/homebrew code models XNetStartupParams as thirteen one-byte
 * fields. Passing NULL is not a valid substitute on real hardware: the title
 * networking stack expects cfgSizeOfStruct and the security mode here.
 */
typedef struct echo_xnet_startup_params {
    uint8_t cfg_size_of_struct;
    uint8_t cfg_flags;
    uint8_t cfg_sock_max_dgram_sockets;
    uint8_t cfg_sock_max_stream_sockets;
    uint8_t cfg_sock_default_recv_bufsize_in_k;
    uint8_t cfg_sock_default_send_bufsize_in_k;
    uint8_t cfg_key_reg_max;
    uint8_t cfg_sec_reg_max;
    uint8_t cfg_qos_data_limit_div4;
    uint8_t cfg_qos_probe_timeout_in_seconds;
    uint8_t cfg_qos_probe_retries;
    uint8_t cfg_qos_srv_max_simultaneous_responses;
    uint8_t cfg_qos_pair_wait_time_in_seconds;
} echo_xnet_startup_params;

_Static_assert(sizeof(echo_xnet_startup_params) == 13U,
               "Xbox XNetStartupParams ABI must be 13 bytes");

#define ECHO_XNET_STARTUP_BYPASS_SECURITY 0x01U

/* Undocumented-but-established XNet developer socket options used by RGH/JTAG
 * homebrew to permit ordinary unencrypted LAN traffic to PC/phone peers. */
#define ECHO_XNET_SO_INSECURE 0x5801U
#define ECHO_XNET_SO_BYPASS_ENCRYPTION 0x5802U

static inline void echo_xnet_prepare_startup(echo_xnet_startup_params *params) {
    volatile uint8_t *bytes;
    uint32_t i;
    if (params == (echo_xnet_startup_params *)0) return;
    bytes = (volatile uint8_t *)params;
    for (i = 0U; i < (uint32_t)sizeof(*params); ++i) bytes[i] = 0U;
    params->cfg_size_of_struct = (uint8_t)sizeof(*params);
    params->cfg_flags = ECHO_XNET_STARTUP_BYPASS_SECURITY;
    params->cfg_sock_default_recv_bufsize_in_k = 64U;
    params->cfg_sock_default_send_bufsize_in_k = 64U;
}

#endif
