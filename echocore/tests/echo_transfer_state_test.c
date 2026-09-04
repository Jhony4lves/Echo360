#include <assert.h>
#include <stdint.h>
#include <stdio.h>

#include "../openxechain/echo_transfer_state.h"

int main(void) {
    echo_transfer_state state;
    uint32_t i;

    echo_transfer_reset(&state);
    assert(!state.active);
    assert(echo_transfer_begin(&state, 0U, 1024U, 0U) == -1);
    assert(echo_transfer_begin(&state, 1U, 1024U, 2048U) == -2);

    assert(echo_transfer_begin(&state, 7U, 2U * ECHO_TRANSFER_ACK_INTERVAL_BYTES, 0U) == 0);
    assert(state.active);
    assert(!echo_transfer_ack_due(&state));

    assert(echo_transfer_commit_chunk(&state, 1U, 1024U) == -3);
    assert(echo_transfer_commit_chunk(&state, 0U, 0U) == -2);
    assert(echo_transfer_commit_chunk(&state, 0U, ECHO_TRANSFER_CHUNK_MAX_BYTES + 1U) == -2);

    for (i = 0U; i < ECHO_TRANSFER_ACK_INTERVAL_BYTES / ECHO_TRANSFER_CHUNK_MAX_BYTES; ++i) {
        uint64_t offset = (uint64_t)i * ECHO_TRANSFER_CHUNK_MAX_BYTES;
        assert(echo_transfer_commit_chunk(&state, offset, ECHO_TRANSFER_CHUNK_MAX_BYTES) == 0);
    }
    assert(state.committed_bytes == ECHO_TRANSFER_ACK_INTERVAL_BYTES);
    assert(echo_transfer_ack_due(&state));

    echo_transfer_mark_acked(&state);
    assert(!echo_transfer_ack_due(&state));

    for (i = 0U; i < ECHO_TRANSFER_ACK_INTERVAL_BYTES / ECHO_TRANSFER_CHUNK_MAX_BYTES; ++i) {
        uint64_t offset = ECHO_TRANSFER_ACK_INTERVAL_BYTES +
                          (uint64_t)i * ECHO_TRANSFER_CHUNK_MAX_BYTES;
        assert(echo_transfer_commit_chunk(&state, offset, ECHO_TRANSFER_CHUNK_MAX_BYTES) == 0);
    }
    assert(echo_transfer_complete(&state));
    assert(echo_transfer_ack_due(&state));

    echo_transfer_reset(&state);
    assert(echo_transfer_begin(&state, 9U, 4096U, 2048U) == 0);
    assert(state.committed_bytes == 2048U);
    assert(echo_transfer_commit_chunk(&state, 2048U, 2048U) == 0);
    assert(echo_transfer_complete(&state));

    puts("EchoCore transfer state tests: OK");
    return 0;
}
