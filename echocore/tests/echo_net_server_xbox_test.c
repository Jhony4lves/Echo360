#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../openxechain/echo_session_protocol.h"
#include "../openxechain/echo_net_server_xbox.c"

static uint8_t g_input[256];
static uint32_t g_input_length;
static uint32_t g_input_offset;
static uint8_t g_output[512];
static uint32_t g_output_length;
static uint32_t g_recv_chunk = 3U;
static uint32_t g_send_chunk = 2U;
static uint32_t g_session_calls;
static uint32_t g_last_caller;
static uint32_t g_close_calls;
static uint32_t g_xnet_start_calls;
static uint32_t g_wsa_start_calls;
static uint32_t g_xnet_cleanup_calls;
static uint32_t g_wsa_cleanup_calls;
static uint32_t g_socket_value = 55U;
static int g_bind_result;
static int g_listen_result;
static int g_setsockopt_result;
static int g_ioctl_result;

int echo_xbox_session_process_frame(
    const uint8_t secret[ECHO_AUTH_SECRET_BYTES],
    echo_xbox_session *session,
    const echo_frame_header *request,
    const uint8_t *request_payload,
    int resident_plugin,
    echo_readonly_xbox_response *response
) {
    (void)secret;
    (void)session;
    (void)resident_plugin;
    g_session_calls++;

    if (request->type == ECHO_TYPE_PING) {
        uint32_t i;
        assert(request->payload_length == ECHO_PING_PAYLOAD_BYTES);
        response->response_type = ECHO_TYPE_PONG;
        response->payload_length = ECHO_PING_PAYLOAD_BYTES;
        for (i = 0U; i < ECHO_PING_PAYLOAD_BYTES; ++i) {
            response->payload[i] = request_payload[i];
        }
        return ECHO_SESSION_ENGINE_OK;
    }

    if (request->type == ECHO_TYPE_SESSION_AUTH_REQUEST) {
        response->response_type = ECHO_TYPE_SESSION_AUTH_RESPONSE;
        response->payload[0] = ECHO_SESSION_STATUS_DENIED;
        response->payload_length = 1U;
        return ECHO_SESSION_ENGINE_AUTH_DENIED;
    }

    return ECHO_SESSION_ENGINE_PROTOCOL_ERROR;
}

void echo_xbox_session_reset(echo_xbox_session *session) {
    if (session != NULL) {
        echo_auth_session_end(&session->auth);
        session->phase = ECHO_SESSION_PHASE_NEW;
    }
}

int NetDll_XNetStartup(uint32_t caller, void *params) {
    (void)params;
    g_last_caller = caller;
    g_xnet_start_calls++;
    return 0;
}

int NetDll_XNetCleanup(uint32_t caller, void *params) {
    (void)params;
    g_last_caller = caller;
    g_xnet_cleanup_calls++;
    return 0;
}

int NetDll_WSAStartup(uint32_t caller, uint16_t version, void *data) {
    (void)version;
    (void)data;
    g_last_caller = caller;
    g_wsa_start_calls++;
    return 0;
}

int NetDll_WSACleanup(uint32_t caller) {
    g_last_caller = caller;
    g_wsa_cleanup_calls++;
    return 0;
}

uint32_t NetDll_socket(uint32_t caller, uint32_t af, uint32_t type, uint32_t protocol) {
    (void)af;
    (void)type;
    (void)protocol;
    g_last_caller = caller;
    return g_socket_value;
}

int NetDll_closesocket(uint32_t caller, uint32_t socket_handle) {
    (void)socket_handle;
    g_last_caller = caller;
    g_close_calls++;
    return 0;
}

int NetDll_setsockopt(
    uint32_t caller,
    uint32_t socket_handle,
    uint32_t level,
    uint32_t option_name,
    const void *option_value,
    uint32_t option_length
) {
    (void)socket_handle;
    (void)level;
    (void)option_name;
    (void)option_value;
    (void)option_length;
    g_last_caller = caller;
    return g_setsockopt_result;
}

int NetDll_ioctlsocket(
    uint32_t caller,
    uint32_t socket_handle,
    uint32_t command,
    uint32_t *argument
) {
    (void)socket_handle;
    (void)command;
    (void)argument;
    g_last_caller = caller;
    return g_ioctl_result;
}

int NetDll_bind(uint32_t caller, uint32_t socket_handle, const void *name, uint32_t name_length) {
    (void)socket_handle;
    (void)name;
    (void)name_length;
    g_last_caller = caller;
    return g_bind_result;
}

int NetDll_listen(uint32_t caller, uint32_t socket_handle, int backlog) {
    (void)socket_handle;
    (void)backlog;
    g_last_caller = caller;
    return g_listen_result;
}

uint32_t NetDll_accept(uint32_t caller, uint32_t socket_handle, void *address, uint32_t *address_length) {
    (void)socket_handle;
    (void)address;
    (void)address_length;
    g_last_caller = caller;
    return ECHO_INVALID_SOCKET;
}

int NetDll_recv(uint32_t caller, uint32_t socket_handle, void *buffer, uint32_t length, uint32_t flags) {
    uint32_t remaining;
    uint32_t amount;
    (void)socket_handle;
    (void)flags;
    g_last_caller = caller;
    if (g_input_offset >= g_input_length) return 0;
    remaining = g_input_length - g_input_offset;
    amount = length < remaining ? length : remaining;
    if (amount > g_recv_chunk) amount = g_recv_chunk;
    memcpy(buffer, g_input + g_input_offset, amount);
    g_input_offset += amount;
    return (int)amount;
}

int NetDll_send(uint32_t caller, uint32_t socket_handle, const void *buffer, uint32_t length, uint32_t flags) {
    uint32_t amount = length;
    (void)socket_handle;
    (void)flags;
    g_last_caller = caller;
    if (amount > g_send_chunk) amount = g_send_chunk;
    assert(g_output_length + amount <= sizeof(g_output));
    memcpy(g_output + g_output_length, buffer, amount);
    g_output_length += amount;
    return (int)amount;
}

int KeDelayExecutionThread(uint32_t processor_mode, uint32_t alertable, int64_t *interval_ptr) {
    (void)processor_mode;
    (void)alertable;
    (void)interval_ptr;
    return 0;
}

static uint8_t g_rx[ECHO_SERVER_BUFFER_BYTES];
static uint8_t g_tx[ECHO_SERVER_BUFFER_BYTES];

static void reset_io(void) {
    memset(g_input, 0, sizeof(g_input));
    memset(g_output, 0, sizeof(g_output));
    g_input_length = 0U;
    g_input_offset = 0U;
    g_output_length = 0U;
    g_session_calls = 0U;
    g_recv_chunk = 3U;
    g_send_chunk = 2U;
}

static void append_frame(
    uint8_t type,
    uint16_t flags,
    uint32_t request_id,
    const uint8_t *payload,
    uint32_t payload_length
) {
    uint8_t header[ECHO_HEADER_BYTES];
    assert(g_input_length + ECHO_HEADER_BYTES + payload_length <= sizeof(g_input));
    echo_make_frame_header(header, type, flags, payload_length, request_id);
    memcpy(g_input + g_input_length, header, ECHO_HEADER_BYTES);
    g_input_length += ECHO_HEADER_BYTES;
    if (payload_length != 0U) {
        memcpy(g_input + g_input_length, payload, payload_length);
        g_input_length += payload_length;
    }
}

static void make_secret(uint8_t secret[ECHO_AUTH_SECRET_BYTES]) {
    uint32_t i;
    for (i = 0U; i < ECHO_AUTH_SECRET_BYTES; ++i) secret[i] = (uint8_t)(i + 1U);
}

static void test_partial_recv_send_preserves_request_id(void) {
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    static const uint8_t nonce[ECHO_PING_PAYLOAD_BYTES] = {9,8,7,6,5,4,3,2};
    echo_frame_header sent;
    volatile uint32_t stop = 0U;
    int result;

    reset_io();
    make_secret(secret);
    append_frame(ECHO_TYPE_PING, 0U, UINT32_C(0x89ABCDEF), nonce, sizeof(nonce));

    result = echo_xbox_serve_paired_client(
        123U, secret, g_rx, sizeof(g_rx), g_tx, sizeof(g_tx), &stop
    );
    assert(result == ECHO_NET_CLIENT_CLOSED);
    assert(g_session_calls == 1U);
    assert(g_last_caller == ECHO_XNCALLER_SYSAPP);
    assert(g_output_length == ECHO_HEADER_BYTES + ECHO_PING_PAYLOAD_BYTES);
    assert(echo_parse_frame_header(g_output, &sent) == ECHO_FRAME_OK);
    assert(sent.type == ECHO_TYPE_PONG);
    assert(sent.flags == 0U);
    assert(sent.request_id == UINT32_C(0x89ABCDEF));
    assert(sent.payload_length == ECHO_PING_PAYLOAD_BYTES);
    assert(memcmp(g_output + ECHO_HEADER_BYTES, nonce, sizeof(nonce)) == 0);
}

static void test_oversized_frame_rejected_before_session(void) {
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    volatile uint32_t stop = 0U;

    reset_io();
    make_secret(secret);
    echo_make_frame_header(
        g_input,
        ECHO_TYPE_PING,
        0U,
        ECHO_FRAME_MAX_PAYLOAD_BYTES + 1U,
        1U
    );
    g_input_length = ECHO_HEADER_BYTES;
    assert(echo_xbox_serve_paired_client(
        123U, secret, g_rx, sizeof(g_rx), g_tx, sizeof(g_tx), &stop
    ) == ECHO_NET_PROTOCOL_ERROR);
    assert(g_session_calls == 0U);
    assert(g_output_length == 0U);
}

static void test_bad_magic_rejected(void) {
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    volatile uint32_t stop = 0U;

    reset_io();
    make_secret(secret);
    echo_make_frame_header(g_input, ECHO_TYPE_PING, 0U, 0U, 1U);
    g_input[0] ^= 1U;
    g_input_length = ECHO_HEADER_BYTES;
    assert(echo_xbox_serve_paired_client(
        123U, secret, g_rx, sizeof(g_rx), g_tx, sizeof(g_tx), &stop
    ) == ECHO_NET_PROTOCOL_ERROR);
    assert(g_session_calls == 0U);
}

static void test_denied_session_response_is_sent_then_connection_closed(void) {
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t payload[ECHO_SESSION_AUTH_REQUEST_BYTES] = {0};
    echo_frame_header sent;
    volatile uint32_t stop = 0U;

    reset_io();
    make_secret(secret);
    append_frame(
        ECHO_TYPE_SESSION_AUTH_REQUEST,
        0U,
        44U,
        payload,
        sizeof(payload)
    );
    assert(echo_xbox_serve_paired_client(
        123U, secret, g_rx, sizeof(g_rx), g_tx, sizeof(g_tx), &stop
    ) == ECHO_NET_SESSION_ERROR);
    assert(g_session_calls == 1U);
    assert(echo_parse_frame_header(g_output, &sent) == ECHO_FRAME_OK);
    assert(sent.type == ECHO_TYPE_SESSION_AUTH_RESPONSE);
    assert(sent.request_id == 44U);
    assert(sent.payload_length == 1U);
    assert(g_output[ECHO_HEADER_BYTES] == ECHO_SESSION_STATUS_DENIED);
}

static void test_pre_requested_stop_reads_nothing(void) {
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    volatile uint32_t stop = 1U;

    reset_io();
    make_secret(secret);
    assert(echo_xbox_serve_paired_client(
        123U, secret, g_rx, sizeof(g_rx), g_tx, sizeof(g_tx), &stop
    ) == ECHO_NET_STOPPED);
    assert(g_input_offset == 0U);
    assert(g_output_length == 0U);
}

static void test_invalid_buffer_contract(void) {
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    volatile uint32_t stop = 0U;
    make_secret(secret);
    assert(echo_xbox_serve_paired_client(
        123U, secret, g_rx, ECHO_SERVER_BUFFER_BYTES - 1U,
        g_tx, sizeof(g_tx), &stop
    ) == ECHO_NET_INVALID_ARGUMENT);
    assert(echo_xbox_serve_paired_client(
        ECHO_INVALID_SOCKET, secret, g_rx, sizeof(g_rx),
        g_tx, sizeof(g_tx), &stop
    ) == ECHO_NET_INVALID_ARGUMENT);
}

static void test_listener_uses_sysapp_and_cleans_up(void) {
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    volatile uint32_t stop = 1U;
    int result;

    make_secret(secret);
    g_xnet_start_calls = 0U;
    g_wsa_start_calls = 0U;
    g_xnet_cleanup_calls = 0U;
    g_wsa_cleanup_calls = 0U;
    g_close_calls = 0U;
    g_bind_result = 0;
    g_listen_result = 0;
    g_setsockopt_result = 0;
    g_ioctl_result = 0;
    g_socket_value = 55U;

    result = echo_xbox_run_paired_readonly_server(secret, &stop);
    assert(result == ECHO_NET_STOPPED);
    assert(g_last_caller == ECHO_XNCALLER_SYSAPP);
    assert(g_xnet_start_calls == 1U);
    assert(g_wsa_start_calls == 1U);
    assert(g_wsa_cleanup_calls == 1U);
    assert(g_xnet_cleanup_calls == 1U);
    assert(g_close_calls == 1U);
}

int main(void) {
    test_partial_recv_send_preserves_request_id();
    test_oversized_frame_rejected_before_session();
    test_bad_magic_rejected();
    test_denied_session_response_is_sent_then_connection_closed();
    test_pre_requested_stop_reads_nothing();
    test_invalid_buffer_contract();
    test_listener_uses_sysapp_and_cleans_up();
    puts("EchoCore resident Xbox transport tests: OK");
    return 0;
}
