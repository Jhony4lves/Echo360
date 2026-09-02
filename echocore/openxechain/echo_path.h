#ifndef ECHO_PATH_H
#define ECHO_PATH_H

#include <stddef.h>
#include <stdint.h>

typedef enum echo_device_kind {
    ECHO_DEVICE_INVALID = 0,
    ECHO_DEVICE_HDD1 = 1,
    ECHO_DEVICE_USB0 = 2,
    ECHO_DEVICE_FLASH = 3
} echo_device_kind;

typedef struct echo_path_info {
    echo_device_kind device;
    int writable;
} echo_path_info;

static inline int echo_char_is_forbidden(unsigned char c) {
    return c < 0x20U || c == 0x7FU || c == ':' || c == '\\';
}

static inline int echo_segment_equals(const char *start, size_t length, const char *literal) {
    size_t i = 0U;
    while (literal[i] != '\0') {
        if (i >= length || start[i] != literal[i]) return 0;
        ++i;
    }
    return i == length;
}

/*
 * Canonical Echo paths use forward slashes and never contain DOS/NT syntax.
 * Rejecting backslashes, colons, control bytes, '.' and '..' segments keeps a
 * remote client from escaping the selected device namespace.
 */
static inline int echo_validate_canonical_path(const char *path, size_t length) {
    size_t i;
    size_t segment_start;

    if (path == NULL || length < 2U || path[0] != '/') return -1;
    if (path[length - 1U] == '/' && length > 1U) return -2;

    segment_start = 1U;
    for (i = 1U; i <= length; ++i) {
        if (i < length && echo_char_is_forbidden((unsigned char)path[i])) return -3;

        if (i == length || path[i] == '/') {
            size_t segment_length = i - segment_start;
            if (segment_length == 0U) return -4;
            if ((segment_length == 1U && path[segment_start] == '.') ||
                (segment_length == 2U && path[segment_start] == '.' && path[segment_start + 1U] == '.')) {
                return -5;
            }
            segment_start = i + 1U;
        }
    }
    return 0;
}

static inline echo_path_info echo_classify_canonical_path(const char *path, size_t length) {
    echo_path_info result = {ECHO_DEVICE_INVALID, 0};
    size_t end;

    if (echo_validate_canonical_path(path, length) != 0) return result;

    end = 1U;
    while (end < length && path[end] != '/') ++end;

    if (echo_segment_equals(path + 1U, end - 1U, "Hdd1")) {
        result.device = ECHO_DEVICE_HDD1;
        result.writable = 1;
    } else if (echo_segment_equals(path + 1U, end - 1U, "Usb0")) {
        result.device = ECHO_DEVICE_USB0;
        result.writable = 1;
    } else if (echo_segment_equals(path + 1U, end - 1U, "Flash")) {
        result.device = ECHO_DEVICE_FLASH;
        result.writable = 0;
    }
    return result;
}

static inline const char *echo_kernel_prefix(echo_device_kind device) {
    switch (device) {
        case ECHO_DEVICE_HDD1:
            return "\\Device\\Harddisk0\\Partition1";
        case ECHO_DEVICE_USB0:
            return "\\Device\\Mass0";
        case ECHO_DEVICE_FLASH:
            return "\\SystemRoot";
        default:
            return NULL;
    }
}

static inline size_t echo_cstr_length(const char *value) {
    size_t length = 0U;
    while (value[length] != '\0') ++length;
    return length;
}

/* Returns bytes written excluding NUL, or 0 on invalid/insufficient output. */
static inline size_t echo_path_to_kernel(
    const char *canonical,
    size_t canonical_length,
    char *output,
    size_t output_capacity,
    int require_write
) {
    echo_path_info info = echo_classify_canonical_path(canonical, canonical_length);
    const char *prefix;
    size_t prefix_length;
    size_t segment_end;
    size_t suffix_length;
    size_t total;
    size_t i;

    if (info.device == ECHO_DEVICE_INVALID) return 0U;
    if (require_write && !info.writable) return 0U;

    prefix = echo_kernel_prefix(info.device);
    if (prefix == NULL) return 0U;
    prefix_length = echo_cstr_length(prefix);

    segment_end = 1U;
    while (segment_end < canonical_length && canonical[segment_end] != '/') ++segment_end;
    suffix_length = canonical_length - segment_end;
    total = prefix_length + suffix_length;

    if (output == NULL || output_capacity <= total) return 0U;

    for (i = 0U; i < prefix_length; ++i) output[i] = prefix[i];
    for (i = 0U; i < suffix_length; ++i) {
        char c = canonical[segment_end + i];
        output[prefix_length + i] = (c == '/') ? '\\' : c;
    }
    output[total] = '\0';
    return total;
}

#endif
