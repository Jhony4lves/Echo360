#ifndef ECHO_AUTH_STATE_H
#define ECHO_AUTH_STATE_H

#include <stddef.h>
#include <stdint.h>

#define ECHO_AUTH_CHALLENGE_BYTES 16U
#define ECHO_AUTH_SECRET_BYTES 32U
#define ECHO_AUTH_HMAC_SHA1_BYTES 20U

#define ECHO_CAP_PING             (UINT64_C(1) << 0)
#define ECHO_CAP_READ_INFO        (UINT64_C(1) << 1)
#define ECHO_CAP_READ_FILESYSTEM  (UINT64_C(1) << 2)
#define ECHO_CAP_WRITE_FILESYSTEM (UINT64_C(1) << 3)
#define ECHO_CAP_LAUNCH           (UINT64_C(1) << 4)
#define ECHO_CAP_SYSTEM_CONTROL   (UINT64_C(1) << 5)
#define ECHO_CAP_PATCH            (UINT64_C(1) << 6)
#define ECHO_CAP_ALL (
    ECHO_CAP_PING | ECHO_CAP_READ_INFO | ECHO_CAP_READ_FILESYSTEM | \
    ECHO_CAP_WRITE_FILESYSTEM | ECHO_CAP_LAUNCH | ECHO_CAP_SYSTEM_CONTROL | \
    ECHO_CAP_PATCH
)

/*
 * Authentication policy state only.
 *
 * This deliberately does NOT implement a MAC. Xbox code will use the kernel's
 * XeCrypt HMAC primitive to verify a response before calling
 * echo_auth_mark_authenticated(). Keeping verification and policy separate
 * makes replay/capability behavior host-testable and keeps this header free of
 * platform-specific crypto ABI.
 */
typedef struct echo_auth_state {
    uint64_t session_id;
    uint64_t last_rx_counter;
    uint64_t capabilities;
    uint8_t challenge[ECHO_AUTH_CHALLENGE_BYTES];
    uint8_t challenge_active;
    uint8_t authenticated;
} echo_auth_state;

static void echo_auth_zero_bytes(uint8_t *bytes, size_t length) {
    size_t i;
    if (bytes == NULL) {
        return;
    }
    for (i = 0U; i < length; ++i) {
        bytes[i] = 0U;
    }
}

static void echo_auth_copy_bytes(uint8_t *dest, const uint8_t *src, size_t length) {
    size_t i;
    if (dest == NULL || src == NULL) {
        return;
    }
    for (i = 0U; i < length; ++i) {
        dest[i] = src[i];
    }
}

static void echo_auth_session_end(echo_auth_state *state) {
    if (state == NULL) {
        return;
    }

    state->session_id = UINT64_C(0);
    state->last_rx_counter = UINT64_C(0);
    state->capabilities = ECHO_CAP_PING;
    echo_auth_zero_bytes(state->challenge, ECHO_AUTH_CHALLENGE_BYTES);
    state->challenge_active = 0U;
    state->authenticated = 0U;
}

static int echo_auth_session_begin(
    echo_auth_state *state,
    uint64_t session_id,
    const uint8_t challenge[ECHO_AUTH_CHALLENGE_BYTES]
) {
    if (state == NULL || challenge == NULL || session_id == UINT64_C(0)) {
        return -1;
    }

    echo_auth_session_end(state);
    state->session_id = session_id;
    echo_auth_copy_bytes(state->challenge, challenge, ECHO_AUTH_CHALLENGE_BYTES);
    state->challenge_active = 1U;
    return 0;
}

/*
 * Call only after the platform crypto layer has verified the authentication
 * response against this session id + challenge + counter.
 *
 * Unknown capability bits are rejected rather than ignored. This prevents an
 * older EchoCore from authenticating a capability it does not understand and
 * accidentally changing its meaning in a later protocol version.
 */
static int echo_auth_mark_authenticated(
    echo_auth_state *state,
    uint64_t counter,
    uint64_t granted_capabilities
) {
    if (state == NULL || state->session_id == UINT64_C(0) ||
        state->challenge_active == 0U || state->authenticated != 0U ||
        counter == UINT64_C(0) ||
        (granted_capabilities & ~ECHO_CAP_ALL) != UINT64_C(0)) {
        return -1;
    }

    state->last_rx_counter = counter;
    state->capabilities = granted_capabilities | ECHO_CAP_PING;
    echo_auth_zero_bytes(state->challenge, ECHO_AUTH_CHALLENGE_BYTES);
    state->challenge_active = 0U;
    state->authenticated = 1U;
    return 0;
}

/*
 * Every authenticated command carries a strictly increasing 64-bit counter.
 * A duplicate, lower value, zero, unauthenticated command, or wrap attempt is
 * rejected. The caller must verify the MAC before committing the counter.
 */
static int echo_auth_commit_counter(echo_auth_state *state, uint64_t counter) {
    if (state == NULL || state->authenticated == 0U ||
        counter == UINT64_C(0) || counter <= state->last_rx_counter) {
        return -1;
    }

    state->last_rx_counter = counter;
    return 0;
}

static int echo_auth_has_capability(const echo_auth_state *state, uint64_t capability) {
    if (state == NULL || capability == UINT64_C(0) ||
        (capability & ~ECHO_CAP_ALL) != UINT64_C(0)) {
        return 0;
    }

    if (capability == ECHO_CAP_PING) {
        return 1;
    }

    if (state->authenticated == 0U) {
        return 0;
    }

    return (state->capabilities & capability) == capability ? 1 : 0;
}

#endif
