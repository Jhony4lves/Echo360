#ifndef ECHO_TRANSFER_STATE_H
#define ECHO_TRANSFER_STATE_H

#include <stdint.h>

#define ECHO_TRANSFER_CHUNK_MAX_BYTES (64U * 1024U)
#define ECHO_TRANSFER_ACK_INTERVAL_BYTES (1024U * 1024U)

typedef struct echo_transfer_state {
    uint32_t transfer_id;
    uint64_t total_bytes;
    uint64_t committed_bytes;
    uint32_t bytes_since_ack;
    int active;
} echo_transfer_state;

static inline void echo_transfer_reset(echo_transfer_state *state) {
    if (state == 0) return;
    state->transfer_id = 0U;
    state->total_bytes = 0U;
    state->committed_bytes = 0U;
    state->bytes_since_ack = 0U;
    state->active = 0;
}

static inline int echo_transfer_begin(
    echo_transfer_state *state,
    uint32_t transfer_id,
    uint64_t total_bytes,
    uint64_t resume_offset
) {
    if (state == 0 || transfer_id == 0U) return -1;
    if (resume_offset > total_bytes) return -2;

    state->transfer_id = transfer_id;
    state->total_bytes = total_bytes;
    state->committed_bytes = resume_offset;
    state->bytes_since_ack = 0U;
    state->active = 1;
    return 0;
}

/*
 * EchoTransfer v1 is sequential-only. The caller writes the chunk to disk and
 * invokes this function only after NtWriteFile reports success. That makes
 * committed_bytes a resume-safe checkpoint rather than merely bytes received.
 */
static inline int echo_transfer_commit_chunk(
    echo_transfer_state *state,
    uint64_t offset,
    uint32_t chunk_bytes
) {
    uint64_t next;

    if (state == 0 || !state->active) return -1;
    if (chunk_bytes == 0U || chunk_bytes > ECHO_TRANSFER_CHUNK_MAX_BYTES) return -2;
    if (offset != state->committed_bytes) return -3;

    next = state->committed_bytes + (uint64_t)chunk_bytes;
    if (next < state->committed_bytes || next > state->total_bytes) return -4;

    state->committed_bytes = next;
    if (UINT32_MAX - state->bytes_since_ack < chunk_bytes) {
        state->bytes_since_ack = ECHO_TRANSFER_ACK_INTERVAL_BYTES;
    } else {
        state->bytes_since_ack += chunk_bytes;
    }
    return 0;
}

static inline int echo_transfer_ack_due(const echo_transfer_state *state) {
    if (state == 0 || !state->active) return 0;
    if (state->committed_bytes == state->total_bytes) return 1;
    return state->bytes_since_ack >= ECHO_TRANSFER_ACK_INTERVAL_BYTES;
}

static inline void echo_transfer_mark_acked(echo_transfer_state *state) {
    if (state != 0) state->bytes_since_ack = 0U;
}

static inline int echo_transfer_complete(const echo_transfer_state *state) {
    return state != 0 && state->active && state->committed_bytes == state->total_bytes;
}

#endif
