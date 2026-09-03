#include <stddef.h>
#include <stdint.h>

#include "echo_protocol.h"
#include "echo_xnet_abi.h"

/*
 * EchoCore Phase 1 hardware bootstrap.
 *
 * Hardware-test v2 adds local XNotify checkpoints so a real console can tell us
 * exactly how far startup got. It remains intentionally tiny and fail-closed:
 * - Xbox title caller only (1)
 * - one TCP listener on port 36000
 * - one client
 * - one strict EchoLink PING -> PONG exchange
 * - bounded 30 second connection window
 * - no heap, filesystem, launch, NAND or memory-control APIs
 * - always closes sockets and returns to the loader
 */

#define ECHO_CALLER_TITLE 1U
#define ECHO_AF_INET 2U
#define ECHO_SOCK_STREAM 1U
#define ECHO_INVALID_SOCKET 0xFFFFFFFFU
#define ECHO_PORT_HIGH 0x8CU /* 36000 == 0x8CA0 */
#define ECHO_PORT_LOW 0xA0U
#define ECHO_SOL_SOCKET 0xFFFFU
#define ECHO_SO_REUSEADDR 0x0004U
#define ECHO_FIONBIO 0x8004667EU
#define ECHO_ACCEPT_POLL_MS 100U
#define ECHO_ACCEPT_POLLS 300U
#define ECHO_KERNEL_MODE 0U
#define ECHO_NOT_ALERTABLE 0U
#define ECHO_NOTIFY_CONSOLE_MESSAGE 34U
#define ECHO_XUSER_INDEX_ANY 0xFFU
#define ECHO_XNOTIFY_SYSTEM UINT64_C(1)
#define ECHO_NOTICE_CAPACITY 64U

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
extern void XNotifyQueueUI(
    uint32_t notification_type,
    uint32_t user_index,
    uint64_t areas,
    uint16_t *display_text,
    void *context_data
);

static uint16_t g_echo_notice[ECHO_NOTICE_CAPACITY];

/*
 * Volatile is intentional: this bootstrap links without libc. It prevents an
 * optimizer from turning the loop into an implicit memset dependency.
 */
static void echo_zero(void *buffer, size_t length) {
    volatile uint8_t *bytes = (volatile uint8_t *)buffer;
    size_t i;
    for (i = 0; i < length; ++i) {
        bytes[i] = 0U;
    }
}

static void echo_delay_ms(uint32_t milliseconds) {
    /* KeDelayExecutionThread uses relative 100 ns intervals when negative. */
    int64_t interval = -(int64_t)milliseconds * 10000LL;
    (void)KeDelayExecutionThread(ECHO_KERNEL_MODE, ECHO_NOT_ALERTABLE, &interval);
}

static void echo_notify_ascii(const char *text) {
    uint32_t i = 0U;
    if (text == NULL) return;
    echo_zero(g_echo_notice, sizeof(g_echo_notice));
    while (text[i] != '\0' && i + 1U < ECHO_NOTICE_CAPACITY) {
        g_echo_notice[i] = (uint16_t)(uint8_t)text[i];
        ++i;
    }
    g_echo_notice[i] = 0U;
    XNotifyQueueUI(
        ECHO_NOTIFY_CONSOLE_MESSAGE,
        ECHO_XUSER_INDEX_ANY,
        ECHO_XNOTIFY_SYSTEM,
        g_echo_notice,
        NULL
    );
}

static void echo_notify_failure(const char *text) {
    echo_notify_ascii(text);
    echo_delay_ms(3000U);
}

static int echo_recv_exact(uint32_t socket_handle, void *buffer, uint32_t length) {
    uint8_t *cursor = (uint8_t *)buffer;
    uint32_t total = 0U;

    while (total < length) {
        int received = NetDll_recv(
            ECHO_CALLER_TITLE,
            socket_handle,
            cursor + total,
            length - total,
            0U
        );
        if (received <= 0 || (uint32_t)received > length - total) {
            return -1;
        }
        total += (uint32_t)received;
    }
    return 0;
}

static int echo_send_exact(uint32_t socket_handle, const void *buffer, uint32_t length) {
    const uint8_t *cursor = (const uint8_t *)buffer;
    uint32_t total = 0U;

    while (total < length) {
        int sent = NetDll_send(
            ECHO_CALLER_TITLE,
            socket_handle,
            cursor + total,
            length - total,
            0U
        );
        if (sent <= 0 || (uint32_t)sent > length - total) {
            return -1;
        }
        total += (uint32_t)sent;
    }
    return 0;
}

static int echo_handle_ping(uint32_t client) {
    uint8_t header[ECHO_HEADER_BYTES];
    uint8_t nonce[ECHO_PING_PAYLOAD_BYTES];

    if (echo_recv_exact(client, header, ECHO_HEADER_BYTES) != 0) {
        return -1;
    }
    if (echo_validate_ping_header(header) != 0) {
        return -1;
    }
    if (echo_recv_exact(client, nonce, ECHO_PING_PAYLOAD_BYTES) != 0) {
        return -1;
    }

    echo_make_pong_header(header);

    if (echo_send_exact(client, header, ECHO_HEADER_BYTES) != 0) {
        return -1;
    }
    if (echo_send_exact(client, nonce, ECHO_PING_PAYLOAD_BYTES) != 0) {
        return -1;
    }

    return 0;
}

static uint32_t echo_accept_bounded(uint32_t server, uint8_t *peer_address) {
    uint32_t nonblocking = 1U;
    uint32_t blocking = 0U;
    uint32_t poll;

    if (NetDll_ioctlsocket(
            ECHO_CALLER_TITLE,
            server,
            ECHO_FIONBIO,
            &nonblocking
        ) != 0) {
        return ECHO_INVALID_SOCKET;
    }

    for (poll = 0U; poll < ECHO_ACCEPT_POLLS; ++poll) {
        uint32_t peer_address_length = 16U;
        uint32_t client = NetDll_accept(
            ECHO_CALLER_TITLE,
            server,
            peer_address,
            &peer_address_length
        );

        if (client != ECHO_INVALID_SOCKET) {
            /* Accepted sockets may inherit nonblocking state. PING is tiny and
             * simpler/safer as a blocking exact-read after connection exists. */
            if (NetDll_ioctlsocket(
                    ECHO_CALLER_TITLE,
                    client,
                    ECHO_FIONBIO,
                    &blocking
                ) != 0) {
                (void)NetDll_closesocket(ECHO_CALLER_TITLE, client);
                return ECHO_INVALID_SOCKET;
            }
            return client;
        }

        echo_delay_ms(ECHO_ACCEPT_POLL_MS);
    }

    return ECHO_INVALID_SOCKET;
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
    echo_xnet_startup_params xnet_params;
    uint32_t server = ECHO_INVALID_SOCKET;
    uint32_t client = ECHO_INVALID_SOCKET;
    uint32_t socket_true = 1U;
    uint32_t reuse_address = 1U;
    int xnet_started = 0;
    int wsa_started = 0;

    echo_zero(wsa_data, sizeof(wsa_data));
    echo_zero(listen_address, sizeof(listen_address));
    echo_zero(peer_address, sizeof(peer_address));
    echo_xnet_prepare_startup(&xnet_params);

    echo_notify_ascii("EchoCore HW2: start");

    /* sockaddr_in in Xbox guest memory: family, port, address, padding. */
    listen_address[0] = 0x00U;
    listen_address[1] = ECHO_AF_INET;
    listen_address[2] = ECHO_PORT_HIGH;
    listen_address[3] = ECHO_PORT_LOW;
    /* bytes 4..7 remain 0 => INADDR_ANY, bytes 8..15 are padding. */

    if (NetDll_XNetStartup(ECHO_CALLER_TITLE, &xnet_params) != 0) {
        echo_notify_failure("EchoCore HW2: XNet FAIL");
        goto cleanup;
    }
    xnet_started = 1;

    if (NetDll_WSAStartup(ECHO_CALLER_TITLE, 0x0202U, wsa_data) != 0) {
        echo_notify_failure("EchoCore HW2: WSA FAIL");
        goto cleanup;
    }
    wsa_started = 1;

    server = NetDll_socket(ECHO_CALLER_TITLE, ECHO_AF_INET, ECHO_SOCK_STREAM, 0U);
    if (server == ECHO_INVALID_SOCKET) {
        echo_notify_failure("EchoCore HW2: socket FAIL");
        goto cleanup;
    }

    /* RGH/JTAG homebrew must explicitly opt the title socket into ordinary
     * unencrypted LAN traffic so a phone/PC can connect without XNet crypto. */
    if (NetDll_setsockopt(
            ECHO_CALLER_TITLE,
            server,
            ECHO_SOL_SOCKET,
            ECHO_XNET_SO_INSECURE,
            &socket_true,
            sizeof(socket_true)
        ) != 0) {
        echo_notify_failure("EchoCore HW2: 5801 FAIL");
        goto cleanup;
    }
    if (NetDll_setsockopt(
            ECHO_CALLER_TITLE,
            server,
            ECHO_SOL_SOCKET,
            ECHO_XNET_SO_BYPASS_ENCRYPTION,
            &socket_true,
            sizeof(socket_true)
        ) != 0) {
        echo_notify_failure("EchoCore HW2: 5802 FAIL");
        goto cleanup;
    }

    if (NetDll_setsockopt(
            ECHO_CALLER_TITLE,
            server,
            ECHO_SOL_SOCKET,
            ECHO_SO_REUSEADDR,
            &reuse_address,
            sizeof(reuse_address)
        ) != 0) {
        echo_notify_failure("EchoCore HW2: reuse FAIL");
        goto cleanup;
    }

    if (NetDll_bind(ECHO_CALLER_TITLE, server, listen_address, sizeof(listen_address)) != 0) {
        echo_notify_failure("EchoCore HW2: bind FAIL");
        goto cleanup;
    }
    if (NetDll_listen(ECHO_CALLER_TITLE, server, 1) != 0) {
        echo_notify_failure("EchoCore HW2: listen FAIL");
        goto cleanup;
    }

    echo_notify_ascii("EchoCore HW2: LISTEN 36000");
    client = echo_accept_bounded(server, peer_address);
    if (client == ECHO_INVALID_SOCKET) {
        echo_notify_failure("EchoCore HW2: PING timeout");
        goto cleanup;
    }

    if (echo_handle_ping(client) == 0) {
        echo_notify_ascii("EchoCore HW2: PONG OK");
        echo_delay_ms(2000U);
    } else {
        echo_notify_failure("EchoCore HW2: PING FAIL");
    }

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
        (void)NetDll_XNetCleanup(ECHO_CALLER_TITLE, &xnet_params);
    }
    echo_zero(&xnet_params, sizeof(xnet_params));
    echo_zero(g_echo_notice, sizeof(g_echo_notice));
}
