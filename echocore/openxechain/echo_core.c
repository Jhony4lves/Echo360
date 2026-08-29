#include <stddef.h>
#include <stdint.h>

/*
 * EchoCore Phase 1 hardware bootstrap.
 *
 * Intentionally tiny and fail-closed:
 * - Xbox title caller only (1)
 * - one TCP listener on port 36000
 * - one client
 * - one EchoLink PING -> PONG exchange
 * - no heap, filesystem, launch, NAND or memory-control APIs
 * - always closes sockets and returns to the loader
 *
 * The networking prototypes below match the public XAM export ABI used by
 * xecorelib. The pinned OpenXeChain xecorelib revision supplies the import
 * stubs at link time.
 */

#define ECHO_CALLER_TITLE 1U
#define ECHO_AF_INET 2U
#define ECHO_SOCK_STREAM 1U
#define ECHO_INVALID_SOCKET 0xFFFFFFFFU

#define ECHO_MAGIC_0 0x45U /* E */
#define ECHO_MAGIC_1 0x43U /* C */
#define ECHO_MAGIC_2 0x48U /* H */
#define ECHO_MAGIC_3 0x4FU /* O */
#define ECHO_VERSION 1U
#define ECHO_TYPE_PING 0x01U
#define ECHO_TYPE_PONG 0x02U
#define ECHO_HEADER_BYTES 16U
#define ECHO_BOOTSTRAP_MAX_PAYLOAD 64U
#define ECHO_PORT_HIGH 0x8CU /* 36000 == 0x8CA0 */
#define ECHO_PORT_LOW 0xA0U

extern int NetDll_XNetStartup(uint32_t caller, void *params);
extern int NetDll_XNetCleanup(uint32_t caller, void *params);
extern int NetDll_WSAStartup(uint32_t caller, uint16_t version, void *data);
extern int NetDll_WSACleanup(uint32_t caller);
extern uint32_t NetDll_socket(uint32_t caller, uint32_t af, uint32_t type, uint32_t protocol);
extern int NetDll_closesocket(uint32_t caller, uint32_t socket_handle);
extern int NetDll_bind(uint32_t caller, uint32_t socket_handle, const void *name, uint32_t name_length);
extern int NetDll_listen(uint32_t caller, uint32_t socket_handle, int backlog);
extern uint32_t NetDll_accept(uint32_t caller, uint32_t socket_handle, void *address, uint32_t *address_length);
extern int NetDll_recv(uint32_t caller, uint32_t socket_handle, void *buffer, uint32_t length, uint32_t flags);
extern int NetDll_send(uint32_t caller, uint32_t socket_handle, const void *buffer, uint32_t length, uint32_t flags);

static void echo_zero(void *buffer, size_t length) {
    uint8_t *bytes = (uint8_t *)buffer;
    size_t i;
    for (i = 0; i < length; ++i) {
        bytes[i] = 0;
    }
}

static uint32_t echo_read_be32(const uint8_t *bytes) {
    return ((uint32_t)bytes[0] << 24U) |
           ((uint32_t)bytes[1] << 16U) |
           ((uint32_t)bytes[2] << 8U) |
           (uint32_t)bytes[3];
}

static int echo_recv_exact(uint32_t socket_handle, void *buffer, uint32_t length) {
    uint8_t *cursor = (uint8_t *)buffer;
    uint32_t total = 0;

    while (total < length) {
        int received = NetDll_recv(
            ECHO_CALLER_TITLE,
            socket_handle,
            cursor + total,
            length - total,
            0U
        );
        if (received <= 0) {
            return -1;
        }
        total += (uint32_t)received;
    }
    return 0;
}

static int echo_send_exact(uint32_t socket_handle, const void *buffer, uint32_t length) {
    const uint8_t *cursor = (const uint8_t *)buffer;
    uint32_t total = 0;

    while (total < length) {
        int sent = NetDll_send(
            ECHO_CALLER_TITLE,
            socket_handle,
            cursor + total,
            length - total,
            0U
        );
        if (sent <= 0) {
            return -1;
        }
        total += (uint32_t)sent;
    }
    return 0;
}

static int echo_handle_ping(uint32_t client) {
    uint8_t header[ECHO_HEADER_BYTES];
    uint8_t payload[ECHO_BOOTSTRAP_MAX_PAYLOAD];
    uint32_t payload_length;

    if (echo_recv_exact(client, header, ECHO_HEADER_BYTES) != 0) {
        return -1;
    }

    if (header[0] != ECHO_MAGIC_0 ||
        header[1] != ECHO_MAGIC_1 ||
        header[2] != ECHO_MAGIC_2 ||
        header[3] != ECHO_MAGIC_3 ||
        header[4] != ECHO_VERSION ||
        header[5] != ECHO_TYPE_PING) {
        return -1;
    }

    payload_length = echo_read_be32(header + 8U);
    if (payload_length > ECHO_BOOTSTRAP_MAX_PAYLOAD) {
        return -1;
    }

    if (payload_length > 0U && echo_recv_exact(client, payload, payload_length) != 0) {
        return -1;
    }

    /* Preserve flags, payload length and request ID byte-for-byte. */
    header[5] = ECHO_TYPE_PONG;

    if (echo_send_exact(client, header, ECHO_HEADER_BYTES) != 0) {
        return -1;
    }
    if (payload_length > 0U && echo_send_exact(client, payload, payload_length) != 0) {
        return -1;
    }

    return 0;
}

/*
 * OpenXeChain's Xbox 360 linker selects /ENTRY:_start for title executables.
 * Returning is deliberate for this first hardware proof: the bootstrap serves
 * one request and then relinquishes control instead of becoming resident.
 */
void _start(void) {
    uint8_t wsa_data[0x200];
    uint8_t listen_address[16];
    uint8_t peer_address[16];
    uint32_t peer_address_length = 16U;
    uint32_t server = ECHO_INVALID_SOCKET;
    uint32_t client = ECHO_INVALID_SOCKET;
    int xnet_started = 0;
    int wsa_started = 0;

    echo_zero(wsa_data, sizeof(wsa_data));
    echo_zero(listen_address, sizeof(listen_address));
    echo_zero(peer_address, sizeof(peer_address));

    /* sockaddr_in in Xbox guest memory: family, port, address, padding. */
    listen_address[0] = 0x00U;
    listen_address[1] = ECHO_AF_INET;
    listen_address[2] = ECHO_PORT_HIGH;
    listen_address[3] = ECHO_PORT_LOW;
    /* bytes 4..7 remain 0 => INADDR_ANY, bytes 8..15 are padding. */

    if (NetDll_XNetStartup(ECHO_CALLER_TITLE, NULL) != 0) {
        goto cleanup;
    }
    xnet_started = 1;

    if (NetDll_WSAStartup(ECHO_CALLER_TITLE, 0x0202U, wsa_data) != 0) {
        goto cleanup;
    }
    wsa_started = 1;

    server = NetDll_socket(ECHO_CALLER_TITLE, ECHO_AF_INET, ECHO_SOCK_STREAM, 0U);
    if (server == ECHO_INVALID_SOCKET) {
        goto cleanup;
    }

    if (NetDll_bind(ECHO_CALLER_TITLE, server, listen_address, sizeof(listen_address)) != 0) {
        goto cleanup;
    }
    if (NetDll_listen(ECHO_CALLER_TITLE, server, 1) != 0) {
        goto cleanup;
    }

    client = NetDll_accept(
        ECHO_CALLER_TITLE,
        server,
        peer_address,
        &peer_address_length
    );
    if (client == ECHO_INVALID_SOCKET) {
        goto cleanup;
    }

    (void)echo_handle_ping(client);

cleanup:
    if (client != ECHO_INVALID_SOCKET) {
        (void)NetDll_closesocket(ECHO_CALLER_TITLE, client);
    }
    if (server != ECHO_INVALID_SOCKET) {
        (void)NetDll_closesocket(ECHO_CALLER_TITLE, server);
    }
    if (wsa_started) {
        (void)NetDll_WSACleanup(ECHO_CALLER_TITLE);
    }
    if (xnet_started) {
        (void)NetDll_XNetCleanup(ECHO_CALLER_TITLE, NULL);
    }
}
