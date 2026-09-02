#include <stdint.h>

/*
 * EchoCore resident-plugin smoke test.
 *
 * This file deliberately does almost nothing. Its purpose is to prove that the
 * open OpenXeChain stack can produce a DLL-style XEX whose entrypoint creates a
 * raw Xbox SYSTEM thread without any CRT/XAPI dependency.
 *
 * Safety boundary:
 * - no networking
 * - no filesystem access
 * - no title/memory patching
 * - no launch/reboot/NAND APIs
 * - worker sleeps briefly and exits
 */

#define ECHO_DLL_PROCESS_ATTACH 1U
#define ECHO_EX_CREATE_FLAG_SYSTEM 0x00000002U
#define ECHO_KERNEL_MODE 0U
#define ECHO_NOT_ALERTABLE 0U

extern int ExCreateThread(
    uint32_t *handle_ptr,
    uint32_t stack_size,
    uint32_t *thread_id_ptr,
    void *xapi_thread_startup,
    void *start_address,
    void *start_context,
    uint32_t creation_flags
);
extern int NtClose(uint32_t handle);
extern int KeDelayExecutionThread(
    uint32_t processor_mode,
    uint32_t alertable,
    int64_t *interval_ptr
);

static uint32_t echo_plugin_worker(void *context) {
    unsigned i;
    (void)context;

    /* Relative 100 ns units: -1,000,000 == 100 ms. */
    for (i = 0U; i < 10U; ++i) {
        int64_t interval = -1000000LL;
        (void)KeDelayExecutionThread(
            ECHO_KERNEL_MODE,
            ECHO_NOT_ALERTABLE,
            &interval
        );
    }

    return 0U;
}

/*
 * SynthXEX DLLs enter through the PE entrypoint. The pinned OpenXeChain fork
 * historically used /ENTRY:_start for Xbox DLLs too, so this smoke test owns
 * the entrypoint directly instead of depending on a CRT DllMain trampoline.
 */
int _start(void *module_handle, uint32_t reason, void *reserved) {
    uint32_t thread_handle = 0U;
    int status;

    (void)module_handle;
    (void)reserved;

    if (reason != ECHO_DLL_PROCESS_ATTACH) {
        return 1;
    }

    status = ExCreateThread(
        &thread_handle,
        0U,
        (uint32_t *)0,
        (void *)0,
        (void *)echo_plugin_worker,
        (void *)0,
        ECHO_EX_CREATE_FLAG_SYSTEM
    );
    if (status < 0) {
        return 0;
    }

    if (thread_handle != 0U) {
        (void)NtClose(thread_handle);
    }

    return 1;
}
