#include <stddef.h>
#include <stdint.h>

#include "echo_net_server_xbox.h"
#include "echo_session_engine_xbox.h"

#define ECHO_XNCALLER_SYSAPP 2U
#define ECHO_AF_INET 2U
#define ECHO_SOCK_STREAM 1U
#define ECHO_INVALID_SOCKET UINT32_C(0xFFFFFFFF)
#define ECHO_SOL_SOCKET 0xFFFFU
#define ECHO_SO_REUSEADDR 0x0004U
#define ECHO_SO_SNDTIMEO 0x1005U
#define ECHO_SO_RCVTIMEO 0x1006U
#define ECHO_FIONBIO UINT32_C(0x8004667E)
#define ECHO_KERNEL_MODE 0U
#define ECHO_NOT_ALERTABLE 0U

extern int NetDll_XNetStartup(uint32_t caller, void *params);
extern int NetDll_XNetCleanup(uint32_t caller, void *params);
extern int NetDll_WSAStartup(uint32_t caller, uint16_t version, void *data);
extern int NetDll_WSACleanup(uint32_t caller);
extern uint32_t NetDll_socket(uint32_t caller, uint32_t af, uint32_t type, uint32_t protocol);
extern int NetDll_closesocket(uint32_t caller, uint32_t socket_handle);
extern int NetDll_setsockopt(
    uint32_t caller,
    uint32_t socket_handle,
    uint32_t level,
    uint32_t option_name,
    const void *option_value,
    uint32_t option_length
);
extern int NetDll_ioctlsocket(
    uint32_t caller,
    uint32_t socket_handle,
    uint32_t command,
    uint32_t *argument
);
extern int NetDll_bind(uint32_t caller, uint32_t socket_handle, const void *name, uint32_t name_length);
extern int NetDll_listen(uint32_t caller, uint32_t socket_handle, int backlog);
extern uint32_t NetDll_accept(uint32_t caller, uint32_t socket_handle, void *address, uint32_t *address_length);
extern int NetDll_recv(uint32_t caller, uint32_t socket_handle, void *buffer, uint32_t length, uint32_t flags);
extern int NetDll_send(uint32_t caller, uint32_t socket_handle, const void *buffer, uint32_t length, uint32_t flags);
extern int KeDelayExecutionThread(uint32_t processor_mode, uint32_t alertable, int64_t *interval_ptr);

static uint8_t g_echo_server_rx[ECHO_SERVER_BUFFER_BYTES];
static uint8_t g_echo_server_tx[ECHO_SERVER_BUFFER_BYTES];

static void echo_net_zero(void *memory, size_t length) {
    volatile uint8_t *bytes = (volatile uint8_t *)memory;
    size_t i;
    if (bytes == NULL) return;
    for (i = 0U; i < length; ++i) bytes[i] = 0U;
}

static int echo_net_stop_requested(volatile uint32_t *stop_requested) {
    return stop_requested != NULL && *stop_requested != 0U;
}

static void echo_net_delay_ms(uint32_t milliseconds) {
    int64_t interval = -(int64_t)milliseconds * INT64_C(10000);
    (void)KeDelayExecutionThread(ECHO_KERNEL_MODE, ECHO_NOT_ALERTABLE, &interval);
}

static int echo_net_recv_exact(
    uint32_t socket_handle,
    uint8_t *buffer,
    uint32_t length
) {
    uint32_t total = 0U;
    while (total < length) {
        int received = NetDll_recv(
            ECHO_XNCALLER_SYSAPP,
            socket_handle,
            buffer + total,
            length - total,
            0U
        );
        if (received == 0) return ECHO_NET_CLIENT_CLOSED;
        if (received < 0 || (uint32_t)received > length - total) return ECHO_NET_IO_ERROR;
        total += (uint32_t)received;
    }
    return ECHO_NET_OK;
}

static int echo_net_send_exact(
    uint32_t socket_handle,
    const uint8_t *buffer,
    uint32_t length
) {
    uint32_t total = 0U;
    while (total < length) {
        int sent = NetDll_send(
            ECHO_XNCALLER_SYSAPP,
            socket_handle,
            buffer + total,
            length - total,
            0U
        );
        if (sent <= 0 || (uint32_t)sent > length - total) return ECHO_NET_IO_ERROR;
        total += (uint32_t)sent;
    }
    return ECHO_NET_OK;
}

static int echo_net_send_response(
    uint32_t client_socket,
    uint32_t request_id,
    const echo_readonly_xbox_response *response
) {
    uint8_t header[ECHO_HEADER_BYTES];
    int result;

    if (response == NULL || response->payload == NULL || response->response_type == 0U ||
        response->payload_length > response->payload_capacity ||
        response->payload_length > ECHO_FRAME_MAX_PAYLOAD_BYTES) {
        return ECHO_NET_PROTOCOL_ERROR;
    }

    echo_make_frame_header(
        header,
        response->response_type,
        0U,
        response->payload_length,
        request_id
    );
    result = echo_net_send_exact(client_socket, header, ECHO_HEADER_BYTES);
    if (result != ECHO_NET_OK) return result;
    if (response->payload_length == 0U) return ECHO_NET_OK;
    return echo_net_send_exact(
        client_socket,
        response->payload,
        response->payload_length
    );
}

int echo_xbox_serve_paired_client(
    uint32_t client_socket,
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    uint8_t *rx_buffer,
    uint32_t rx_capacity,
    uint8_t *tx_buffer,
    uint32_t tx_capacity,
    volatile uint32_t *stop_requested
) {
    echo_xbox_session session;
    uint32_t frame_count = 0U;

    if (client_socket == ECHO_INVALID_SOCKET || secret == NULL ||
        rx_buffer == NULL || tx_buffer == NULL ||
        rx_capacity < ECHO_SERVER_BUFFER_BYTES ||
        tx_capacity < ECHO_SERVER_BUFFER_BYTES) {
        return ECHO_NET_INVALID_ARGUMENT;
    }

    echo_xbox_session_reset(&session);

    while (!echo_net_stop_requested(stop_requested)) {
        uint8_t raw_header[ECHO_HEADER_BYTES];
        echo_frame_header request;
        echo_readonly_xbox_response response;
        const uint8_t *payload = NULL;
        int result;
        int session_result;

        if (frame_count >= ECHO_SERVER_MAX_FRAMES_PER_CLIENT) {
            echo_xbox_session_reset(&session);
            return ECHO_NET_FRAME_LIMIT;
        }
        frame_count++;

        result = echo_net_recv_exact(client_socket, raw_header, ECHO_HEADER_BYTES);
        if (result != ECHO_NET_OK) {
            echo_xbox_session_reset(&session);
            return result;
        }
        if (echo_parse_frame_header(raw_header, &request) != ECHO_FRAME_OK ||
            request.payload_length > rx_capacity) {
            echo_xbox_session_reset(&session);
            return ECHO_NET_PROTOCOL_ERROR;
        }

        if (request.payload_length != 0U) {
            result = echo_net_recv_exact(
                client_socket,
                rx_buffer,
                request.payload_length
            );
            if (result != ECHO_NET_OK) {
                echo_xbox_session_reset(&session);
                return result;
            }
            payload = rx_buffer;
        }

        response.response_type = 0U;
        response.payload = tx_buffer;
        response.payload_capacity = tx_capacity;
        response.payload_length = 0U;

        session_result = echo_xbox_session_process_frame(
            secret,
            &session,
            &request,
            payload,
            1,
            &response
        );

        if (response.response_type != 0U) {
            result = echo_net_send_response(
                client_socket,
                request.request_id,
                &response
            );
            if (result != ECHO_NET_OK) {
                echo_xbox_session_reset(&session);
                return result;
            }
        }

        if (session_result != ECHO_SESSION_ENGINE_OK) {
            echo_xbox_session_reset(&session);
            return ECHO_NET_SESSION_ERROR;
        }
    }

    echo_xbox_session_reset(&session);
    return ECHO_NET_STOPPED;
}

static int echo_net_configure_client(uint32_t client_socket) {
    uint32_t blocking = 0U;
    uint32_t timeout = ECHO_SERVER_SOCKET_TIMEOUT_MS;

    if (NetDll_ioctlsocket(
            ECHO_XNCALLER_SYSAPP,
            client_socket,
            ECHO_FIONBIO,
            &blocking
        ) != 0) {
        return ECHO_NET_IO_ERROR;
    }
    if (NetDll_setsockopt(
            ECHO_XNCALLER_SYSAPP,
            client_socket,
            ECHO_SOL_SOCKET,
            ECHO_SO_RCVTIMEO,
            &timeout,
            sizeof(timeout)
        ) != 0) {
        return ECHO_NET_IO_ERROR;
    }
    if (NetDll_setsockopt(
            ECHO_XNCALLER_SYSAPP,
            client_socket,
            ECHO_SOL_SOCKET,
            ECHO_SO_SNDTIMEO,
            &timeout,
            sizeof(timeout)
        ) != 0) {
        return ECHO_NET_IO_ERROR;
    }
    return ECHO_NET_OK;
}

int echo_xbox_run_paired_readonly_server(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    volatile uint32_t *stop_requested
) {
    uint8_t wsa_data[0x200];
    uint8_t listen_address[16];
    uint8_t peer_address[16];
    uint32_t server = ECHO_INVALID_SOCKET;
    uint32_t reuse_address = 1U;
    uint32_t nonblocking = 1U;
    int xnet_started = 0;
    int wsa_started = 0;
    int final_result = ECHO_NET_STARTUP_ERROR;

    if (secret == NULL || stop_requested == NULL) return ECHO_NET_INVALID_ARGUMENT;

    echo_net_zero(wsa_data, sizeof(wsa_data));
    echo_net_zero(listen_address, sizeof(listen_address));
    echo_net_zero(peer_address, sizeof(peer_address));
    echo_net_zero(g_echo_server_rx, sizeof(g_echo_server_rx));
    echo_net_zero(g_echo_server_tx, sizeof(g_echo_server_tx));

    listen_address[0] = 0U;
    listen_address[1] = ECHO_AF_INET;
    listen_address[2] = (uint8_t)(ECHO_SERVER_PORT >> 8U);
    listen_address[3] = (uint8_t)ECHO_SERVER_PORT;

    if (NetDll_XNetStartup(ECHO_XNCALLER_SYSAPP, NULL) != 0) goto cleanup;
    xnet_started = 1;
    if (NetDll_WSAStartup(ECHO_XNCALLER_SYSAPP, 0x0202U, wsa_data) != 0) goto cleanup;
    wsa_started = 1;

    server = NetDll_socket(ECHO_XNCALLER_SYSAPP, ECHO_AF_INET, ECHO_SOCK_STREAM, 0U);
    if (server == ECHO_INVALID_SOCKET) goto cleanup;

    if (NetDll_setsockopt(
            ECHO_XNCALLER_SYSAPP,
            server,
            ECHO_SOL_SOCKET,
            ECHO_SO_REUSEADDR,
            &reuse_address,
            sizeof(reuse_address)
        ) != 0) goto cleanup;

    if (NetDll_bind(
            ECHO_XNCALLER_SYSAPP,
            server,
            listen_address,
            sizeof(listen_address)
        ) != 0) goto cleanup;
    if (NetDll_listen(ECHO_XNCALLER_SYSAPP, server, 1) != 0) goto cleanup;
    if (NetDll_ioctlsocket(
            ECHO_XNCALLER_SYSAPP,
            server,
            ECHO_FIONBIO,
            &nonblocking
        ) != 0) goto cleanup;

    final_result = ECHO_NET_OK;
    while (!echo_net_stop_requested(stop_requested)) {
        uint32_t peer_address_length = sizeof(peer_address);
        uint32_t client = NetDll_accept(
            ECHO_XNCALLER_SYSAPP,
            server,
            peer_address,
            &peer_address_length
        );

        if (client == ECHO_INVALID_SOCKET) {
            echo_net_delay_ms(ECHO_SERVER_ACCEPT_POLL_MS);
            continue;
        }

        if (echo_net_configure_client(client) == ECHO_NET_OK) {
            (void)echo_xbox_serve_paired_client(
                client,
                secret,
                g_echo_server_rx,
                sizeof(g_echo_server_rx),
                g_echo_server_tx,
                sizeof(g_echo_server_tx),
                stop_requested
            );
        }
        (void)NetDll_closesocket(ECHO_XNCALLER_SYSAPP, client);
    }

    final_result = ECHO_NET_STOPPED;

cleanup:
    echo_net_zero(g_echo_server_rx, sizeof(g_echo_server_rx));
    echo_net_zero(g_echo_server_tx, sizeof(g_echo_server_tx));
    if (server != ECHO_INVALID_SOCKET) {
        (void)NetDll_closesocket(ECHO_XNCALLER_SYSAPP, server);
    }
    if (wsa_started) (void)NetDll_WSACleanup(ECHO_XNCALLER_SYSAPP);
    if (xnet_started) (void)NetDll_XNetCleanup(ECHO_XNCALLER_SYSAPP, NULL);
    return final_result;
}
