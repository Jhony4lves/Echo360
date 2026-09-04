#ifndef ECHO_NET_SERVER_XBOX_H
#define ECHO_NET_SERVER_XBOX_H

#include <stdint.h>

#include "echo_auth_state.h"
#include "echo_protocol.h"

#define ECHO_SERVER_PORT 36000U
#define ECHO_SERVER_SOCKET_TIMEOUT_MS 5000U
#define ECHO_SERVER_ACCEPT_POLL_MS 100U
#define ECHO_SERVER_MAX_FRAMES_PER_CLIENT 4096U
#define ECHO_SERVER_BUFFER_BYTES ECHO_FRAME_MAX_PAYLOAD_BYTES

#define ECHO_NET_OK 0
#define ECHO_NET_CLIENT_CLOSED 1
#define ECHO_NET_STOPPED 2
#define ECHO_NET_INVALID_ARGUMENT -1
#define ECHO_NET_IO_ERROR -2
#define ECHO_NET_PROTOCOL_ERROR -3
#define ECHO_NET_SESSION_ERROR -4
#define ECHO_NET_FRAME_LIMIT -5
#define ECHO_NET_STARTUP_ERROR -6

/*
 * Serve one already-accepted blocking client. rx/tx buffers are caller-owned
 * and must each be ECHO_SERVER_BUFFER_BYTES, keeping ~192 KiB off the worker
 * stack. The resident server below supplies static BSS buffers.
 */
int echo_xbox_serve_paired_client(
    uint32_t client_socket,
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    uint8_t *rx_buffer,
    uint32_t rx_capacity,
    uint8_t *tx_buffer,
    uint32_t tx_capacity,
    volatile uint32_t *stop_requested
);

/*
 * Run one SYSAPP listener on TCP/36000 until *stop_requested becomes nonzero.
 * Only one client is served at a time in v1; every accepted client gets a fresh
 * connection-local authentication state.
 */
int echo_xbox_run_paired_readonly_server(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    volatile uint32_t *stop_requested
);

#endif
