#include <stddef.h>
#include <stdint.h>

#include "echo_auth_crypto_xbox.h"
#include "echo_pairing_store_xbox.h"
#include "echo_protocol.h"
#include "echo_session_protocol.h"
#include "echo_xnet_abi.h"

/*
 * One-shot title-mode hardware authentication probe.
 *
 * Purpose: prove the paired SESSION_BEGIN -> CHALLENGE -> AUTH exchange on a
 * real Xbox before loading EchoCoreResident as a DashLaunch/sysdll plugin.
 *
 * Safety invariants:
 * - title caller only (XNCALLER_TITLE = 1);
 * - load-only pairing.dat: never creates or repairs pairing identity;
 * - one TCP listener on 36000, one client, one auth exchange, then exit;
 * - grants only the resident-v1 read-only auth capability mask;
 * - no filesystem writes, launch, reboot, patch, NAND or arbitrary memory API;
 * - all network waits are bounded and all secret/challenge state is scrubbed.
 */

#define ECHO_CALLER_TITLE 1U
#define ECHO_AF_INET 2U
#define ECHO_SOCK_STREAM 1U
#define ECHO_INVALID_SOCKET UINT32_C(0xFFFFFFFF)
#define ECHO_SOL_SOCKET 0xFFFFU
#define ECHO_SO_REUSEADDR 0x0004U
#define ECHO_SO_SNDTIMEO 0x1005U
#define ECHO_SO_RCVTIMEO 0x1006U
#define ECHO_FIONBIO UINT32_C(0x8004667E)
#define ECHO_ACCEPT_POLL_MS 100U
#define ECHO_ACCEPT_POLLS 600U
#define ECHO_SOCKET_TIMEOUT_MS 5000U
#define ECHO_KERNEL_MODE 0U
#define ECHO_NOT_ALERTABLE 0U
#define ECHO_AUTH_PROBE_DATA_ANCHOR UINT32_C(0x45434150) /* "ECAP" */

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

static volatile uint32_t g_echo_auth_probe_data_anchor = ECHO_AUTH_PROBE_DATA_ANCHOR;

static void echo_auth_probe_zero(void *memory, size_t length) {
    volatile uint8_t *bytes = (volatile uint8_t *)memory;
    size_t i;
    if (bytes == NULL) return;
    for (i = 0U; i < length; ++i) bytes[i] = 0U;
}

static void echo_auth_probe_delay_ms(uint32_t milliseconds) {
    int64_t interval = -(int64_t)milliseconds * INT64_C(10000);
    (void)KeDelayExecutionThread(ECHO_KERNEL_MODE, ECHO_NOT_ALERTABLE, &interval);
}

static int echo_auth_probe_recv_exact(uint32_t socket_handle, uint8_t *buffer, uint32_t length) {
    uint32_t total = 0U;
    while (total < length) {
        int received = NetDll_recv(
            ECHO_CALLER_TITLE,
            socket_handle,
            buffer + total,
            length - total,
            0U
        );
        if (received <= 0 || (uint32_t)received > length - total) return -1;
        total += (uint32_t)received;
    }
    return 0;
}

static int echo_auth_probe_send_exact(
    uint32_t socket_handle,
    const uint8_t *buffer,
    uint32_t length
) {
    uint32_t total = 0U;
    while (total < length) {
        int sent = NetDll_send(
            ECHO_CALLER_TITLE,
            socket_handle,
            buffer + total,
            length - total,
            0U
        );
        if (sent <= 0 || (uint32_t)sent > length - total) return -1;
        total += (uint32_t)sent;
    }
    return 0;
}

static int echo_auth_probe_recv_frame(
    uint32_t socket_handle,
    echo_frame_header *header_out,
    uint8_t *payload,
    uint32_t payload_capacity
) {
    uint8_t raw_header[ECHO_HEADER_BYTES];
    if (header_out == NULL || payload == NULL) return -1;
    if (echo_auth_probe_recv_exact(socket_handle, raw_header, ECHO_HEADER_BYTES) != 0) return -1;
    if (echo_parse_frame_header(raw_header, header_out) != ECHO_FRAME_OK) return -1;
    if (header_out->payload_length > payload_capacity) return -1;
    if (header_out->payload_length != 0U &&
        echo_auth_probe_recv_exact(socket_handle, payload, header_out->payload_length) != 0) {
        return -1;
    }
    return 0;
}

static int echo_auth_probe_send_frame(
    uint32_t socket_handle,
    uint8_t type,
    uint32_t request_id,
    const uint8_t *payload,
    uint32_t payload_length
) {
    uint8_t raw_header[ECHO_HEADER_BYTES];
    if (payload_length != 0U && payload == NULL) return -1;
    echo_make_frame_header(raw_header, type, 0U, payload_length, request_id);
    if (echo_auth_probe_send_exact(socket_handle, raw_header, ECHO_HEADER_BYTES) != 0) return -1;
    if (payload_length != 0U &&
        echo_auth_probe_send_exact(socket_handle, payload, payload_length) != 0) {
        return -1;
    }
    return 0;
}

static uint32_t echo_auth_probe_accept_bounded(uint32_t server, uint8_t peer_address[16]) {
    uint32_t nonblocking = 1U;
    uint32_t blocking = 0U;
    uint32_t poll;

    if (NetDll_ioctlsocket(ECHO_CALLER_TITLE, server, ECHO_FIONBIO, &nonblocking) != 0) {
        return ECHO_INVALID_SOCKET;
    }

    for (poll = 0U; poll < ECHO_ACCEPT_POLLS; ++poll) {
        uint32_t peer_length = 16U;
        uint32_t client = NetDll_accept(
            ECHO_CALLER_TITLE,
            server,
            peer_address,
            &peer_length
        );
        if (client != ECHO_INVALID_SOCKET) {
            uint32_t timeout = ECHO_SOCKET_TIMEOUT_MS;
            if (NetDll_ioctlsocket(ECHO_CALLER_TITLE, client, ECHO_FIONBIO, &blocking) != 0 ||
                NetDll_setsockopt(
                    ECHO_CALLER_TITLE, client, ECHO_SOL_SOCKET, ECHO_SO_RCVTIMEO,
                    &timeout, sizeof(timeout)
                ) != 0 ||
                NetDll_setsockopt(
                    ECHO_CALLER_TITLE, client, ECHO_SOL_SOCKET, ECHO_SO_SNDTIMEO,
                    &timeout, sizeof(timeout)
                ) != 0) {
                (void)NetDll_closesocket(ECHO_CALLER_TITLE, client);
                return ECHO_INVALID_SOCKET;
            }
            return client;
        }
        echo_auth_probe_delay_ms(ECHO_ACCEPT_POLL_MS);
    }
    return ECHO_INVALID_SOCKET;
}

static int echo_auth_probe_exchange(
    uint32_t client,
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES]
) {
    echo_auth_state state;
    echo_frame_header request;
    uint8_t payload[ECHO_SESSION_AUTH_REQUEST_BYTES];
    uint8_t challenge_payload[ECHO_SESSION_CHALLENGE_BYTES];
    uint8_t response_payload[ECHO_SESSION_AUTH_RESPONSE_BYTES];
    uint64_t counter = UINT64_C(0);
    uint64_t capabilities = UINT64_C(0);
    const uint8_t *mac = NULL;
    int parse_result;
    int verify_result;
    int result = -1;

    echo_auth_probe_zero(&state, sizeof(state));
    echo_auth_probe_zero(&request, sizeof(request));
    echo_auth_probe_zero(payload, sizeof(payload));
    echo_auth_probe_zero(challenge_payload, sizeof(challenge_payload));
    echo_auth_probe_zero(response_payload, sizeof(response_payload));
    echo_auth_session_end(&state);

    if (echo_auth_probe_recv_frame(client, &request, payload, sizeof(payload)) != 0 ||
        echo_session_validate_begin(&request) != 0) {
        goto done;
    }

    if (echo_auth_xbox_begin_session(&state) != 0 ||
        echo_session_make_challenge_payload(&state, challenge_payload) != 0 ||
        echo_auth_probe_send_frame(
            client,
            ECHO_TYPE_SESSION_CHALLENGE_RESPONSE,
            request.request_id,
            challenge_payload,
            ECHO_SESSION_CHALLENGE_BYTES
        ) != 0) {
        goto done;
    }

    echo_auth_probe_zero(&request, sizeof(request));
    echo_auth_probe_zero(payload, sizeof(payload));
    if (echo_auth_probe_recv_frame(client, &request, payload, sizeof(payload)) != 0) goto done;

    parse_result = echo_session_parse_auth_request(
        &request,
        payload,
        &counter,
        &capabilities,
        &mac
    );
    if (parse_result != 0) {
        echo_session_make_auth_response(
            response_payload,
            ECHO_SESSION_STATUS_PROTOCOL_ERROR,
            UINT64_C(0),
            UINT64_C(0)
        );
    } else {
        verify_result = echo_auth_xbox_verify_response(
            secret,
            &state,
            counter,
            capabilities,
            mac
        );
        if (verify_result != 0) {
            echo_session_make_auth_response(
                response_payload,
                ECHO_SESSION_STATUS_DENIED,
                UINT64_C(0),
                UINT64_C(0)
            );
        } else {
            echo_session_make_auth_response(
                response_payload,
                ECHO_SESSION_STATUS_OK,
                state.capabilities,
                state.last_rx_counter
            );
            result = 0;
        }
    }

    if (echo_auth_probe_send_frame(
            client,
            ECHO_TYPE_SESSION_AUTH_RESPONSE,
            request.request_id,
            response_payload,
            ECHO_SESSION_AUTH_RESPONSE_BYTES
        ) != 0) {
        result = -1;
    }

done:
    echo_auth_session_end(&state);
    echo_auth_probe_zero(&state, sizeof(state));
    echo_auth_probe_zero(payload, sizeof(payload));
    echo_auth_probe_zero(challenge_payload, sizeof(challenge_payload));
    echo_auth_probe_zero(response_payload, sizeof(response_payload));
    return result;
}

void _start(void) {
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t wsa_data[0x200];
    uint8_t listen_address[16];
    uint8_t peer_address[16];
    echo_xnet_startup_params xnet_params;
    uint32_t server = ECHO_INVALID_SOCKET;
    uint32_t client = ECHO_INVALID_SOCKET;
    uint32_t socket_true = 1U;
    uint32_t reuse_address = 1U;
    int xnet_started = 0;
    int wsa_started = 0;

    if (g_echo_auth_probe_data_anchor != ECHO_AUTH_PROBE_DATA_ANCHOR) return;

    echo_auth_probe_zero(secret, sizeof(secret));
    echo_auth_probe_zero(wsa_data, sizeof(wsa_data));
    echo_auth_probe_zero(listen_address, sizeof(listen_address));
    echo_auth_probe_zero(peer_address, sizeof(peer_address));
    echo_xnet_prepare_startup(&xnet_params);

    /* A missing/corrupt identity is a hard stop. This probe never creates one. */
    if (echo_pairing_xbox_load_secret(secret) != ECHO_PAIRING_STORE_OK) goto cleanup;

    listen_address[0] = 0U;
    listen_address[1] = ECHO_AF_INET;
    listen_address[2] = (uint8_t)(ECHO_SERVER_PORT >> 8U);
    listen_address[3] = (uint8_t)ECHO_SERVER_PORT;

    if (NetDll_XNetStartup(ECHO_CALLER_TITLE, &xnet_params) != 0) goto cleanup;
    xnet_started = 1;
    if (NetDll_WSAStartup(ECHO_CALLER_TITLE, 0x0202U, wsa_data) != 0) goto cleanup;
    wsa_started = 1;

    server = NetDll_socket(ECHO_CALLER_TITLE, ECHO_AF_INET, ECHO_SOCK_STREAM, 0U);
    if (server == ECHO_INVALID_SOCKET) goto cleanup;

    if (NetDll_setsockopt(
            ECHO_CALLER_TITLE,
            server,
            ECHO_SOL_SOCKET,
            ECHO_XNET_SO_INSECURE,
            &socket_true,
            sizeof(socket_true)
        ) != 0) goto cleanup;

    (void)NetDll_setsockopt(
        ECHO_CALLER_TITLE,
        server,
        ECHO_SOL_SOCKET,
        ECHO_XNET_SO_BYPASS_ENCRYPTION,
        &socket_true,
        sizeof(socket_true)
    );

    if (NetDll_setsockopt(
            ECHO_CALLER_TITLE,
            server,
            ECHO_SOL_SOCKET,
            ECHO_SO_REUSEADDR,
            &reuse_address,
            sizeof(reuse_address)
        ) != 0) goto cleanup;

    if (NetDll_bind(
            ECHO_CALLER_TITLE,
            server,
            listen_address,
            sizeof(listen_address)
        ) != 0 ||
        NetDll_listen(ECHO_CALLER_TITLE, server, 1) != 0) {
        goto cleanup;
    }

    client = echo_auth_probe_accept_bounded(server, peer_address);
    if (client != ECHO_INVALID_SOCKET) {
        (void)echo_auth_probe_exchange(client, secret);
    }

cleanup:
    if (client != ECHO_INVALID_SOCKET) (void)NetDll_closesocket(ECHO_CALLER_TITLE, client);
    if (server != ECHO_INVALID_SOCKET) (void)NetDll_closesocket(ECHO_CALLER_TITLE, server);
    if (wsa_started) (void)NetDll_WSACleanup(ECHO_CALLER_TITLE);
    if (xnet_started) (void)NetDll_XNetCleanup(ECHO_CALLER_TITLE, &xnet_params);
    echo_auth_probe_zero(secret, sizeof(secret));
    echo_auth_probe_zero(&xnet_params, sizeof(xnet_params));
}
