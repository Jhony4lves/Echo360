#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "../openxechain/echo_session_protocol.h"
#include "../openxechain/echo_xnet_abi.h"
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
static uint32_t g_lan_socket;
static uint32_t g_lan_calls;
static uint32_t g_bypass_calls;
static uint32_t g_failed_option;
static int g_xnet_start_result;
static int g_wsa_start_result;
static volatile uint32_t *g_stop_during_io;

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
    const echo_xnet_startup_params *startup = params;
    assert(caller == ECHO_XNCALLER_SYSAPP);
    assert(startup != NULL);
    assert(startup->cfg_size_of_struct == 13U);
    assert(startup->cfg_flags == ECHO_XNET_STARTUP_BYPASS_SECURITY);
    assert(startup->cfg_sock_default_recv_bufsize_in_k == 64U);
    assert(startup->cfg_sock_default_send_bufsize_in_k == 64U);
    g_last_caller = caller;
    g_xnet_start_calls++;
    return g_xnet_start_result;
}

int NetDll_XNetCleanup(uint32_t caller, void *params) {
    (void)params;
    g_last_caller = caller;
    g_xnet_cleanup_calls++;
    return 0;
}

int NetDll_WSAStartup(uint32_t caller, uint16_t version, void *data) {
    assert(caller == ECHO_XNCALLER_SYSAPP);
    assert(version == 0x0202U);
    assert(data != NULL);
    g_last_caller = caller;
    g_wsa_start_calls++;
    return g_wsa_start_result;
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
    assert(caller == ECHO_XNCALLER_SYSAPP);
    assert(level == ECHO_SOL_SOCKET);
    assert(option_length == sizeof(uint32_t));
    assert(option_value != NULL);
    if (option_name == ECHO_XNET_SO_INSECURE) {
        assert(*(const uint32_t *)option_value == 1U);
        g_lan_socket = socket_handle;
        g_lan_calls++;
    } else if (option_name == ECHO_XNET_SO_BYPASS_ENCRYPTION) {
        assert(*(const uint32_t *)option_value == 1U);
        g_bypass_calls++;
    }
    g_last_caller = caller;
    if (option_name == g_failed_option) return -1;
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
    const uint8_t *address = name;
    assert(socket_handle == g_lan_socket);
    assert(g_lan_calls > 0U);
    assert(name_length == 16U);
    assert(address[0] == 0U && address[1] == 2U);
    assert(address[2] == 0x8CU && address[3] == 0xA0U);
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
    if (g_stop_during_io != NULL) *g_stop_during_io = 1U;
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
    if (g_stop_during_io != NULL) *g_stop_during_io = 1U;
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
    g_stop_during_io = NULL;
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

static uint32_t g_ready_calls;
static volatile uint32_t g_listener_stop;

static void stop_ready_listener(void) {
    g_ready_calls++;
    g_listener_stop = 1U;
}

static void reset_listener(void) {
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
    g_lan_socket = 0U;
    g_lan_calls = 0U;
    g_bypass_calls = 0U;
    g_failed_option = 0U;
    g_xnet_start_result = 0;
    g_wsa_start_result = 0;
    g_ready_calls = 0U;
    g_listener_stop = 0U;
}

static void test_listener_uses_sysapp_lan_policy_and_cleans_up(void) {
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    int result;

    make_secret(secret);
    reset_listener();

    result = echo_xbox_run_paired_readonly_server(secret, &g_listener_stop, stop_ready_listener);
    assert(result == ECHO_NET_STOPPED);
    assert(g_last_caller == ECHO_XNCALLER_SYSAPP);
    assert(g_xnet_start_calls == 1U);
    assert(g_wsa_start_calls == 1U);
    assert(g_wsa_cleanup_calls == 1U);
    assert(g_xnet_cleanup_calls == 1U);
    assert(g_close_calls == 1U);
    assert(g_lan_calls == 1U && g_bypass_calls == 1U);
    assert(g_ready_calls == 1U);
}

static void test_optional_bypass_failure_does_not_block_lan(void) {
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    make_secret(secret);
    reset_listener();
    g_failed_option = ECHO_XNET_SO_BYPASS_ENCRYPTION;
    assert(echo_xbox_run_paired_readonly_server(secret, &g_listener_stop, stop_ready_listener)
           == ECHO_NET_STOPPED);
    assert(g_ready_calls == 1U);
    assert(g_lan_calls == 1U && g_bypass_calls == 1U);
}

static void test_required_startup_failures_never_report_ready(void) {
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint32_t scenario;
    static const int expected[] = {
        ECHO_NET_XNET_ERROR, ECHO_NET_WSA_ERROR, ECHO_NET_SOCKET_ERROR,
        ECHO_NET_LAN_ERROR, ECHO_NET_BIND_ERROR, ECHO_NET_LISTEN_ERROR, ECHO_NET_IO_ERROR
    };
    make_secret(secret);
    for (scenario = 0U; scenario < sizeof(expected) / sizeof(expected[0]); ++scenario) {
        reset_listener();
        switch (scenario) {
            case 0U: g_xnet_start_result = -1; break;
            case 1U: g_wsa_start_result = -1; break;
            case 2U: g_socket_value = ECHO_INVALID_SOCKET; break;
            case 3U: g_failed_option = ECHO_XNET_SO_INSECURE; break;
            case 4U: g_bind_result = -1; break;
            case 5U: g_listen_result = -1; break;
            case 6U: g_ioctl_result = -1; break;
        }
        assert(echo_xbox_run_paired_readonly_server(secret, &g_listener_stop, stop_ready_listener)
               == expected[scenario]);
        assert(g_ready_calls == 0U);
        assert(g_close_calls == (scenario >= 3U ? 1U : 0U));
        assert(g_wsa_cleanup_calls == (scenario >= 2U ? 1U : 0U));
        assert(g_xnet_cleanup_calls == (scenario >= 1U ? 1U : 0U));
    }
}

static void test_accepted_socket_gets_lan_policy_and_timeouts(void) {
    reset_listener();
    assert(echo_net_configure_client(123U) == ECHO_NET_OK);
    assert(g_lan_socket == 123U);
    assert(g_lan_calls == 1U && g_bypass_calls == 1U);
    g_failed_option = ECHO_XNET_SO_INSECURE;
    assert(echo_net_configure_client(124U) == ECHO_NET_LAN_ERROR);
}

static void test_stop_interrupts_fragmented_receive(void) {
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t nonce[ECHO_PING_PAYLOAD_BYTES] = {1U};
    volatile uint32_t stop = 0U;
    reset_io();
    make_secret(secret);
    append_frame(ECHO_TYPE_PING, 0U, 1U, nonce, sizeof(nonce));
    g_recv_chunk = 1U;
    g_stop_during_io = &stop;
    assert(echo_xbox_serve_paired_client(123U, secret, g_rx, sizeof(g_rx),
                                       g_tx, sizeof(g_tx), &stop) == ECHO_NET_STOPPED);
    assert(g_input_offset == 1U);
    assert(g_session_calls == 0U && g_output_length == 0U);
    g_stop_during_io = NULL;
}

static void test_stop_interrupts_fragmented_send(void) {
    uint8_t payload[16] = {1U};
    volatile uint32_t stop = 0U;
    reset_io();
    g_send_chunk = 1U;
    g_stop_during_io = &stop;
    assert(echo_net_send_exact(123U, payload, sizeof(payload), &stop) == ECHO_NET_STOPPED);
    assert(g_output_length == 1U);
    g_stop_during_io = NULL;
}

int main(void) {
    test_partial_recv_send_preserves_request_id();
    test_oversized_frame_rejected_before_session();
    test_bad_magic_rejected();
    test_denied_session_response_is_sent_then_connection_closed();
    test_pre_requested_stop_reads_nothing();
    test_invalid_buffer_contract();
    test_listener_uses_sysapp_lan_policy_and_cleans_up();
    test_optional_bypass_failure_does_not_block_lan();
    test_required_startup_failures_never_report_ready();
    test_accepted_socket_gets_lan_policy_and_timeouts();
    test_stop_interrupts_fragmented_receive();
    test_stop_interrupts_fragmented_send();
    puts("EchoCore resident Xbox transport tests: OK");
    return 0;
}
