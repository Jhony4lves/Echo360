#include <assert.h>
#include <stdint.h>

#include "../openxechain/echo_auth_state.h"

static int bytes_are_zero(const uint8_t *bytes, uint32_t length) {
    uint32_t i;
    for (i = 0U; i < length; ++i) {
        if (bytes[i] != 0U) {
            return 0;
        }
    }
    return 1;
}

int main(void) {
    echo_auth_state state;
    uint8_t challenge_a[ECHO_AUTH_CHALLENGE_BYTES];
    uint8_t challenge_b[ECHO_AUTH_CHALLENGE_BYTES];
    uint32_t i;
    const uint64_t unknown_capability = UINT64_C(1) << 63;

    echo_auth_session_end(&state);
    for (i = 0U; i < ECHO_AUTH_CHALLENGE_BYTES; ++i) {
        challenge_a[i] = (uint8_t)(i + 1U);
        challenge_b[i] = (uint8_t)(0xF0U - i);
    }

    assert(echo_auth_session_begin((echo_auth_state *)0, UINT64_C(1), challenge_a) == -1);
    assert(echo_auth_session_begin(&state, UINT64_C(0), challenge_a) == -1);
    assert(echo_auth_session_begin(&state, UINT64_C(1), (const uint8_t *)0) == -1);

    assert(echo_auth_session_begin(&state, UINT64_C(0x1122334455667788), challenge_a) == 0);
    assert(state.session_id == UINT64_C(0x1122334455667788));
    assert(state.challenge_active == 1U);
    assert(state.authenticated == 0U);
    assert(state.last_rx_counter == UINT64_C(0));
    for (i = 0U; i < ECHO_AUTH_CHALLENGE_BYTES; ++i) {
        assert(state.challenge[i] == challenge_a[i]);
    }

    assert(echo_auth_session_begin(
        &state,
        UINT64_C(0x8877665544332211),
        state.challenge
    ) == 0);
    assert(state.session_id == UINT64_C(0x8877665544332211));
    for (i = 0U; i < ECHO_AUTH_CHALLENGE_BYTES; ++i) {
        assert(state.challenge[i] == challenge_a[i]);
    }

    assert(echo_auth_session_begin(&state, UINT64_C(0x1122334455667788), challenge_a) == 0);

    assert(echo_auth_has_capability(&state, ECHO_AUTH_CAP_PING) == 1);
    assert(echo_auth_has_capability(&state, ECHO_AUTH_CAP_READ_INFO) == 0);
    assert(echo_auth_has_capability(&state, UINT64_C(0)) == 0);
    assert(echo_auth_has_capability(&state, unknown_capability) == 0);

    assert(echo_auth_mark_authenticated((echo_auth_state *)0, UINT64_C(1), ECHO_AUTH_CAP_READ_INFO) == -1);
    assert(echo_auth_mark_authenticated(&state, UINT64_C(0), ECHO_AUTH_CAP_READ_INFO) == -1);
    assert(echo_auth_mark_authenticated(&state, UINT64_C(1), unknown_capability) == -1);
    assert(state.authenticated == 0U);
    assert(state.challenge_active == 1U);

    assert(echo_auth_mark_authenticated(
        &state,
        UINT64_C(5),
        ECHO_AUTH_CAP_READ_INFO | ECHO_AUTH_CAP_READ_FILESYSTEM
    ) == 0);
    assert(state.authenticated == 1U);
    assert(state.challenge_active == 0U);
    assert(bytes_are_zero(state.challenge, ECHO_AUTH_CHALLENGE_BYTES) == 1);
    assert(state.last_rx_counter == UINT64_C(5));
    assert(echo_auth_has_capability(&state, ECHO_AUTH_CAP_PING) == 1);
    assert(echo_auth_has_capability(&state, ECHO_AUTH_CAP_READ_INFO) == 1);
    assert(echo_auth_has_capability(&state, ECHO_AUTH_CAP_READ_FILESYSTEM) == 1);
    assert(echo_auth_has_capability(&state, ECHO_AUTH_CAP_WRITE_FILESYSTEM) == 0);
    assert(echo_auth_mark_authenticated(&state, UINT64_C(6), ECHO_AUTH_CAP_WRITE_FILESYSTEM) == -1);

    assert(echo_auth_commit_counter(&state, UINT64_C(0)) == -1);
    assert(echo_auth_commit_counter(&state, UINT64_C(5)) == -1);
    assert(echo_auth_commit_counter(&state, UINT64_C(4)) == -1);
    assert(echo_auth_commit_counter(&state, UINT64_C(6)) == 0);
    assert(state.last_rx_counter == UINT64_C(6));
    assert(echo_auth_commit_counter(&state, UINT64_C(6)) == -1);

    assert(echo_auth_session_begin(&state, UINT64_C(2), challenge_b) == 0);
    assert(state.session_id == UINT64_C(2));
    assert(state.authenticated == 0U);
    assert(state.last_rx_counter == UINT64_C(0));
    assert(echo_auth_has_capability(&state, ECHO_AUTH_CAP_READ_INFO) == 0);
    assert(echo_auth_commit_counter(&state, UINT64_C(1)) == -1);

    assert(echo_auth_mark_authenticated(&state, UINT64_MAX, ECHO_AUTH_CAP_WRITE_FILESYSTEM) == 0);
    assert(state.last_rx_counter == UINT64_MAX);
    assert(echo_auth_has_capability(&state, ECHO_AUTH_CAP_WRITE_FILESYSTEM) == 1);
    assert(echo_auth_commit_counter(&state, UINT64_C(1)) == -1);
    assert(echo_auth_commit_counter(&state, UINT64_MAX) == -1);

    echo_auth_session_end(&state);
    assert(state.session_id == UINT64_C(0));
    assert(state.last_rx_counter == UINT64_C(0));
    assert(state.authenticated == 0U);
    assert(state.challenge_active == 0U);
    assert(bytes_are_zero(state.challenge, ECHO_AUTH_CHALLENGE_BYTES) == 1);
    assert(echo_auth_has_capability(&state, ECHO_AUTH_CAP_PING) == 1);
    assert(echo_auth_has_capability(&state, ECHO_AUTH_CAP_WRITE_FILESYSTEM) == 0);

    echo_auth_session_end((echo_auth_state *)0);
    assert(echo_auth_commit_counter((echo_auth_state *)0, UINT64_C(1)) == -1);
    assert(echo_auth_has_capability((const echo_auth_state *)0, ECHO_AUTH_CAP_PING) == 0);

    return 0;
}
