#ifndef ECHO_PATH_POLICY_H
#define ECHO_PATH_POLICY_H

#include <stddef.h>
#include <stdint.h>

#define ECHO_PATH_MAX 512U

static inline int echo_path_ascii_equal_ci(char a, char b) {
    if (a >= 'A' && a <= 'Z') a = (char)(a + ('a' - 'A'));
    if (b >= 'A' && b <= 'Z') b = (char)(b + ('a' - 'A'));
    return a == b;
}

static inline int echo_path_has_allowed_root(const char *path, size_t length) {
    static const char hdd1[] = "Hdd1:";
    size_t i;

    if (path == NULL || length < 5U) return 0;
    for (i = 0U; i < 5U; ++i) {
        if (!echo_path_ascii_equal_ci(path[i], hdd1[i])) return 0;
    }
    return 1;
}

/*
 * Read-only path gate for v1. Accepts Hdd1:/... using either slash style.
 * It does not mutate/canonicalize; callers translate only after this validator
 * succeeds. Rejects control bytes, duplicate separators, dot/dotdot segments,
 * ':' outside the device prefix, and paths without an explicit Hdd1 root.
 *
 * fHdd is intentionally NOT accepted here: it is an FTPdll namespace observed
 * over the network, not a native kernel alias we have proven safe for Nt* I/O.
 */
static inline int echo_path_is_safe_readonly(const char *path, size_t length) {
    size_t i;
    size_t segment_start;

    if (path == NULL || length < 7U || length > ECHO_PATH_MAX) return 0;
    if (!echo_path_has_allowed_root(path, length)) return 0;
    if (path[5] != '/' && path[5] != '\\') return 0;

    segment_start = 6U;
    for (i = 6U; i <= length; ++i) {
        int at_end = (i == length);
        char ch = at_end ? '/' : path[i];

        if (!at_end) {
            unsigned char uch = (unsigned char)ch;
            if (uch < 0x20U || uch == 0x7FU || ch == ':') return 0;
        }

        if (ch == '/' || ch == '\\') {
            size_t segment_len = i - segment_start;
            if (segment_len == 0U) return 0;
            if (segment_len == 1U && path[segment_start] == '.') return 0;
            if (segment_len == 2U &&
                path[segment_start] == '.' &&
                path[segment_start + 1U] == '.') return 0;
            segment_start = i + 1U;
        }
    }
    return 1;
}

#endif
