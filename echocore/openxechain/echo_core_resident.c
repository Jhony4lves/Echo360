#include <stddef.h>
#include <stdint.h>

#include "echo_auth_state.h"
#include "echo_net_server_xbox.h"
#include "echo_pairing_store_xbox.h"

/*
 * EchoCore resident candidate lifecycle.
 *
 * Security and unload invariants:
 * - resident plugin NEVER creates pairing identity; it only loads an existing
 *   pairing.dat created by an explicit manual pairing flow;
 * - no pairing secret => no listener;
 * - worker is a raw Xbox SYSTEM thread (no CRT/XapiThreadStartup dependency);
 * - detach requests cooperative server shutdown and joins the worker before
 *   returning, preventing code execution after the DLL is unloaded;
 * - the in-memory pairing secret is wiped after the server exits and again on
 *   detach as defense in depth.
 */

#define ECHO_DLL_PROCESS_DETACH 0U
#define ECHO_DLL_PROCESS_ATTACH 1U
#define ECHO_DLL_THREAD_ATTACH  2U
#define ECHO_DLL_THREAD_DETACH  3U

#define ECHO_EX_CREATE_FLAG_SYSTEM 0x00000002U
#define ECHO_WAIT_MODE_KERNEL 0U
#define ECHO_NOT_ALERTABLE 0U
#define ECHO_RESIDENT_THREAD_HANDLE_NONE 0U

extern int ExCreateThread(
    uint32_t *handle_ptr,
    uint32_t stack_size,
    uint32_t *thread_id_ptr,
    void *xapi_thread_startup,
    void *start_address,
    void *start_context,
    uint32_t creation_flags
);
extern int NtWaitForSingleObjectEx(
    uint32_t object_handle,
    uint32_t wait_mode,
    uint32_t alertable,
    int64_t *timeout_ptr
);
extern int NtClose(uint32_t handle);

static volatile uint32_t g_echo_resident_stop_requested = 1U;
static volatile uint32_t g_echo_resident_worker_running = 0U;
static uint32_t g_echo_resident_thread_handle = ECHO_RESIDENT_THREAD_HANDLE_NONE;
static uint8_t g_echo_resident_secret[ECHO_AUTH_SECRET_BYTES];
static int g_echo_resident_last_pairing_status = ECHO_PAIRING_STORE_NOT_FOUND;
static int g_echo_resident_last_server_status = ECHO_NET_STOPPED;

/* SynthXEX v0.0.5 needs raw data in the final PE section for RVA mapping. */
static volatile uint32_t g_echo_synthxex_data_anchor = UINT32_C(0x4543484F);

static void echo_resident_secure_zero(void *buffer, size_t length) {
    volatile uint8_t *bytes = (volatile uint8_t *)buffer;
    size_t i;
    if (buffer == NULL) return;
    for (i = 0U; i < length; ++i) bytes[i] = 0U;
}

static uint32_t echo_resident_worker(void *context) {
    int pairing_status;
    (void)context;

    g_echo_resident_worker_running = 1U;
    g_echo_resident_last_server_status = ECHO_NET_STOPPED;
    echo_resident_secure_zero(g_echo_resident_secret, sizeof(g_echo_resident_secret));

    if (g_echo_resident_stop_requested != 0U) goto done;

    pairing_status = echo_pairing_xbox_load_secret(g_echo_resident_secret);
    g_echo_resident_last_pairing_status = pairing_status;
    if (pairing_status != ECHO_PAIRING_STORE_OK) goto done;

    if (g_echo_resident_stop_requested == 0U) {
        g_echo_resident_last_server_status = echo_xbox_run_paired_readonly_server(
            g_echo_resident_secret,
            &g_echo_resident_stop_requested
        );
    }

done:
    echo_resident_secure_zero(g_echo_resident_secret, sizeof(g_echo_resident_secret));
    g_echo_resident_worker_running = 0U;
    return 0U;
}

static int echo_resident_attach(void) {
    uint32_t handle = ECHO_RESIDENT_THREAD_HANDLE_NONE;
    uint32_t synthxex_anchor;
    int status;

    /* Keep initialized .data live for the pinned SynthXEX import walker. */
    synthxex_anchor = g_echo_synthxex_data_anchor;
    (void)synthxex_anchor;

    /* Duplicate process-attach must never create another listener/thread. */
    if (g_echo_resident_thread_handle != ECHO_RESIDENT_THREAD_HANDLE_NONE) {
        return 1;
    }

    g_echo_resident_stop_requested = 0U;
    g_echo_resident_worker_running = 0U;
    g_echo_resident_last_pairing_status = ECHO_PAIRING_STORE_NOT_FOUND;
    g_echo_resident_last_server_status = ECHO_NET_STOPPED;
    echo_resident_secure_zero(g_echo_resident_secret, sizeof(g_echo_resident_secret));

    status = ExCreateThread(
        &handle,
        0U,
        (uint32_t *)0,
        (void *)0,
        (void *)echo_resident_worker,
        (void *)0,
        ECHO_EX_CREATE_FLAG_SYSTEM
    );
    if (status < 0 || handle == ECHO_RESIDENT_THREAD_HANDLE_NONE) {
        g_echo_resident_stop_requested = 1U;
        echo_resident_secure_zero(g_echo_resident_secret, sizeof(g_echo_resident_secret));
        return 0;
    }

    g_echo_resident_thread_handle = handle;
    return 1;
}

static int echo_resident_detach(void) {
    uint32_t handle = g_echo_resident_thread_handle;

    g_echo_resident_stop_requested = 1U;

    if (handle != ECHO_RESIDENT_THREAD_HANDLE_NONE) {
        /*
         * Infinite join is intentional. All EchoCore network blocking I/O is
         * already bounded internally; returning while the worker still owns
         * instructions from this module would be a use-after-unload hazard.
         */
        (void)NtWaitForSingleObjectEx(
            handle,
            ECHO_WAIT_MODE_KERNEL,
            ECHO_NOT_ALERTABLE,
            (int64_t *)0
        );
        (void)NtClose(handle);
        g_echo_resident_thread_handle = ECHO_RESIDENT_THREAD_HANDLE_NONE;
    }

    echo_resident_secure_zero(g_echo_resident_secret, sizeof(g_echo_resident_secret));
    g_echo_resident_worker_running = 0U;
    return 1;
}

int _start(void *module_handle, uint32_t reason, void *reserved) {
    (void)module_handle;
    (void)reserved;

    switch (reason) {
        case ECHO_DLL_PROCESS_ATTACH:
            return echo_resident_attach();
        case ECHO_DLL_PROCESS_DETACH:
            return echo_resident_detach();
        case ECHO_DLL_THREAD_ATTACH:
        case ECHO_DLL_THREAD_DETACH:
        default:
            return 1;
    }
}
