#include <stddef.h>
#include <stdint.h>

#include "echo_pairing_store_xbox.h"
#include "echo_pairing_token.h"
#include "echo_pairing_token_xbox.h"

#define ECHO_NOTIFY_CONSOLE_MESSAGE 34U
#define ECHO_XUSER_INDEX_ANY 0xFFU
#define ECHO_XNOTIFY_SYSTEM UINT64_C(1)
#define ECHO_KERNEL_MODE 0U
#define ECHO_NOT_ALERTABLE 0U
#define ECHO_PAIRING_DISPLAY_MS 30000U
#define ECHO_PAIRING_SHORT_DISPLAY_MS 5000U
#define ECHO_PAIRING_MESSAGE_CAPACITY 96U

extern void XNotifyQueueUI(
    uint32_t notification_type,
    uint32_t user_index,
    uint64_t areas,
    uint16_t *display_text,
    void *context_data
);
extern int KeDelayExecutionThread(
    uint32_t processor_mode,
    uint32_t alertable,
    int64_t *interval_ptr
);

static uint16_t g_echo_pairing_message[ECHO_PAIRING_MESSAGE_CAPACITY];

static void echo_pairing_xex_zero(void *memory, uint32_t length) {
    volatile uint8_t *bytes = (volatile uint8_t *)memory;
    uint32_t i;
    if (bytes == NULL) return;
    for (i = 0U; i < length; ++i) bytes[i] = 0U;
}

static void echo_pairing_xex_delay(uint32_t milliseconds) {
    int64_t interval = -(int64_t)milliseconds * 10000LL;
    (void)KeDelayExecutionThread(ECHO_KERNEL_MODE, ECHO_NOT_ALERTABLE, &interval);
}

static int echo_pairing_xex_set_message(const char *prefix, const char *suffix) {
    uint32_t out = 0U;
    uint32_t i;
    if (prefix == NULL) return -1;

    echo_pairing_xex_zero(g_echo_pairing_message, sizeof(g_echo_pairing_message));
    for (i = 0U; prefix[i] != '\0'; ++i) {
        if (out + 1U >= ECHO_PAIRING_MESSAGE_CAPACITY) return -1;
        g_echo_pairing_message[out++] = (uint16_t)(uint8_t)prefix[i];
    }
    if (suffix != NULL) {
        for (i = 0U; suffix[i] != '\0'; ++i) {
            if (out + 1U >= ECHO_PAIRING_MESSAGE_CAPACITY) return -1;
            g_echo_pairing_message[out++] = (uint16_t)(uint8_t)suffix[i];
        }
    }
    g_echo_pairing_message[out] = 0U;
    return 0;
}

static void echo_pairing_xex_notify(const char *prefix, const char *suffix, uint32_t hold_ms) {
    if (echo_pairing_xex_set_message(prefix, suffix) != 0) return;
    XNotifyQueueUI(
        ECHO_NOTIFY_CONSOLE_MESSAGE,
        ECHO_XUSER_INDEX_ANY,
        ECHO_XNOTIFY_SYSTEM,
        g_echo_pairing_message,
        NULL
    );
    echo_pairing_xex_delay(hold_ms);
    echo_pairing_xex_zero(g_echo_pairing_message, sizeof(g_echo_pairing_message));
}

/*
 * Physical pairing bootstrap. It never opens a network socket.
 *
 * First launch only:
 *  1. verify pairing.dat is genuinely absent;
 *  2. generate a non-zero 128-bit token with XeCryptRandom;
 *  3. derive the 256-bit secret with SHA-256(domain || token);
 *  4. persist pairing.dat transactionally;
 *  5. show the human Base32 token locally on the Xbox for 30 seconds.
 *
 * The resident plugin only loads pairing.dat. It never creates or repairs it.
 */
void _start(void) {
    uint8_t existing_secret[ECHO_AUTH_SECRET_BYTES];
    uint8_t token[ECHO_PAIRING_TOKEN_BYTES];
    uint8_t secret[ECHO_AUTH_SECRET_BYTES];
    char display[ECHO_PAIRING_TOKEN_DISPLAY_CAPACITY];
    int load_result;
    int store_result;

    echo_pairing_xex_zero(existing_secret, sizeof(existing_secret));
    echo_pairing_xex_zero(token, sizeof(token));
    echo_pairing_xex_zero(secret, sizeof(secret));
    echo_pairing_xex_zero(display, sizeof(display));

    load_result = echo_pairing_xbox_load_secret(existing_secret);
    if (load_result == ECHO_PAIRING_STORE_OK) {
        echo_pairing_xex_notify(
            "Echo360: pairing already exists. No changes made.",
            NULL,
            ECHO_PAIRING_SHORT_DISPLAY_MS
        );
        goto cleanup;
    }
    if (load_result != ECHO_PAIRING_STORE_NOT_FOUND) {
        echo_pairing_xex_notify(
            "Echo360: pairing.dat is invalid. Refusing to overwrite it.",
            NULL,
            ECHO_PAIRING_SHORT_DISPLAY_MS
        );
        goto cleanup;
    }

    if (echo_pairing_token_xbox_generate(token) != 0 ||
        echo_pairing_token_xbox_derive_secret(token, secret) != 0 ||
        echo_pairing_token_format_display(token, display) != 0) {
        echo_pairing_xex_notify(
            "Echo360: failed to generate pairing identity.",
            NULL,
            ECHO_PAIRING_SHORT_DISPLAY_MS
        );
        goto cleanup;
    }

    store_result = echo_pairing_xbox_store_secret(secret);
    if (store_result != ECHO_PAIRING_STORE_CREATED) {
        echo_pairing_xex_notify(
            "Echo360: failed to persist pairing identity.",
            NULL,
            ECHO_PAIRING_SHORT_DISPLAY_MS
        );
        goto cleanup;
    }

    echo_pairing_xex_notify(
        "Echo360 Pair: ",
        display,
        ECHO_PAIRING_DISPLAY_MS
    );

cleanup:
    echo_pairing_xex_zero(existing_secret, sizeof(existing_secret));
    echo_pairing_xex_zero(token, sizeof(token));
    echo_pairing_xex_zero(secret, sizeof(secret));
    echo_pairing_xex_zero(display, sizeof(display));
}
