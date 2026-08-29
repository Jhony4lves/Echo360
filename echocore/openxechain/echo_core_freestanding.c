/*
 * EchoCore Phase 1 freestanding hardware bootstrap.
 *
 * No C library, heap, filesystem or runtime startup is required. The only
 * imports are the public XAM networking exports supplied by xecorelib's XAM
 * definition file. This is intentionally one-shot for the first RGH test.
 */

typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;

typedef char echo_u16_must_be_2_bytes[(sizeof(u16) == 2) ? 1 : -1];
typedef char echo_u32_must_be_4_bytes[(sizeof(u32) == 4) ? 1 : -1];

#define ECHO_CALLER_TITLE 1U
#define ECHO_AF_INET 2U
#define ECHO_SOCK_STREAM 1U
#define ECHO_INVALID_SOCKET 0xFFFFFFFFU
#define ECHO_VERSION 1U
#define ECHO_TYPE_PING 0x01U
#define ECHO_TYPE_PONG 0x02U
#define ECHO_HEADER_BYTES 16U
#define ECHO_BOOTSTRAP_MAX_PAYLOAD 64U

extern int NetDll_XNetStartup(u32 caller, void *params);
extern int NetDll_XNetCleanup(u32 caller, void *params);
extern int NetDll_WSAStartup(u32 caller, u16 version, void *data);
extern int NetDll_WSACleanup(u32 caller);
extern u32 NetDll_socket(u32 caller, u32 af, u32 type, u32 protocol);
extern int NetDll_closesocket(u32 caller, u32 socket_handle);
extern int NetDll_bind(u32 caller, u32 socket_handle, const void *name, u32 name_length);
extern int NetDll_listen(u32 caller, u32 socket_handle, int backlog);
extern u32 NetDll_accept(u32 caller, u32 socket_handle, void *address, u32 *address_length);
extern int NetDll_recv(u32 caller, u32 socket_handle, void *buffer, u32 length, u32 flags);
extern int NetDll_send(u32 caller, u32 socket_handle, const void *buffer, u32 length, u32 flags);

/* Static storage is zero-initialized by the image loader; no memset needed. */
static u8 g_wsa_data[0x200];
static u8 g_peer_address[16];
static u8 g_header[ECHO_HEADER_BYTES];
static u8 g_payload[ECHO_BOOTSTRAP_MAX_PAYLOAD];
static u8 g_listen_address[16] = {
    0x00U, 0x02U, /* AF_INET as guest big-endian u16 */
    0x8CU, 0xA0U, /* TCP 36000 == 0x8CA0 */
    0x00U, 0x00U, 0x00U, 0x00U, /* INADDR_ANY */
    0x00U, 0x00U, 0x00U, 0x00U,
    0x00U, 0x00U, 0x00U, 0x00U,
};

static u32 echo_read_be32(const u8 *p) {
    return ((u32)p[0] << 24U) |
           ((u32)p[1] << 16U) |
           ((u32)p[2] << 8U) |
           (u32)p[3];
}

static int echo_recv_exact(u32 socket_handle, u8 *buffer, u32 length) {
    u32 total = 0U;
    while (total < length) {
        int received = NetDll_recv(
            ECHO_CALLER_TITLE,
            socket_handle,
            buffer + total,
            length - total,
            0U
        );
        if (received <= 0) {
            return -1;
        }
        total += (u32)received;
    }
    return 0;
}

static int echo_send_exact(u32 socket_handle, const u8 *buffer, u32 length) {
    u32 total = 0U;
    while (total < length) {
        int sent = NetDll_send(
            ECHO_CALLER_TITLE,
            socket_handle,
            buffer + total,
            length - total,
            0U
        );
        if (sent <= 0) {
            return -1;
        }
        total += (u32)sent;
    }
    return 0;
}

static int echo_reply_once(u32 client) {
    u32 payload_length;

    if (echo_recv_exact(client, g_header, ECHO_HEADER_BYTES) != 0) {
        return -1;
    }

    if (g_header[0] != 0x45U ||
        g_header[1] != 0x43U ||
        g_header[2] != 0x48U ||
        g_header[3] != 0x4FU ||
        g_header[4] != ECHO_VERSION ||
        g_header[5] != ECHO_TYPE_PING) {
        return -1;
    }

    payload_length = echo_read_be32(g_header + 8U);
    if (payload_length > ECHO_BOOTSTRAP_MAX_PAYLOAD) {
        return -1;
    }

    if (payload_length != 0U &&
        echo_recv_exact(client, g_payload, payload_length) != 0) {
        return -1;
    }

    /* EchoLink PONG keeps flags, length, request ID and nonce unchanged. */
    g_header[5] = ECHO_TYPE_PONG;

    if (echo_send_exact(client, g_header, ECHO_HEADER_BYTES) != 0) {
        return -1;
    }
    if (payload_length != 0U &&
        echo_send_exact(client, g_payload, payload_length) != 0) {
        return -1;
    }

    return 0;
}

/* OpenXeChain's Xbox target links title executables with /ENTRY:_start. */
void _start(void) {
    u32 server = ECHO_INVALID_SOCKET;
    u32 client = ECHO_INVALID_SOCKET;
    u32 peer_length = 16U;
    int xnet_started = 0;
    int wsa_started = 0;

    if (NetDll_XNetStartup(ECHO_CALLER_TITLE, (void *)0) != 0) {
        goto cleanup;
    }
    xnet_started = 1;

    if (NetDll_WSAStartup(ECHO_CALLER_TITLE, (u16)0x0202U, g_wsa_data) != 0) {
        goto cleanup;
    }
    wsa_started = 1;

    server = NetDll_socket(ECHO_CALLER_TITLE, ECHO_AF_INET, ECHO_SOCK_STREAM, 0U);
    if (server == ECHO_INVALID_SOCKET) {
        goto cleanup;
    }

    if (NetDll_bind(ECHO_CALLER_TITLE, server, g_listen_address, 16U) != 0) {
        goto cleanup;
    }
    if (NetDll_listen(ECHO_CALLER_TITLE, server, 1) != 0) {
        goto cleanup;
    }

    client = NetDll_accept(
        ECHO_CALLER_TITLE,
        server,
        g_peer_address,
        &peer_length
    );
    if (client == ECHO_INVALID_SOCKET) {
        goto cleanup;
    }

    (void)echo_reply_once(client);

cleanup:
    if (client != ECHO_INVALID_SOCKET) {
        (void)NetDll_closesocket(ECHO_CALLER_TITLE, client);
    }
    if (server != ECHO_INVALID_SOCKET) {
        (void)NetDll_closesocket(ECHO_CALLER_TITLE, server);
    }
    if (wsa_started != 0) {
        (void)NetDll_WSACleanup(ECHO_CALLER_TITLE);
    }
    if (xnet_started != 0) {
        (void)NetDll_XNetCleanup(ECHO_CALLER_TITLE, (void *)0);
    }
}
