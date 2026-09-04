#include <stddef.h>
#include <stdint.h>

/*
 * EchoCore remote loader v1.
 *
 * This is a tiny title XEX intended to be launched by Aurora/NOVA from the
 * phone. It does exactly one privileged action: load the sibling
 * GAME:\\EchoCoreResident.xex as a system image, using the same XexLoadImage
 * flag used by established Xbox 360 homebrew plugin loaders.
 *
 * Physical-hardware invariant:
 * - a raw title _start must never return. Real Corona/RGH testing showed that
 *   falling out of _start can reboot the whole console after otherwise-correct
 *   work. Every loader outcome therefore requests normal XAM title termination
 *   and parks forever as a fail-safe if that request unexpectedly returns.
 *
 * Safety boundaries:
 * - no NAND access;
 * - no arbitrary path supplied over the network;
 * - no file writes;
 * - no reboot or title patching;
 * - duplicate invocations are idempotent when the resident is already loaded.
 */

#define ECHO_LOADER_SYSTEM_IMAGE_FLAGS 8U
#define ECHO_LOADER_SYSTEM_THREAD_FLAG 0x00000002U
#define ECHO_LOADER_THREAD_NONE 0U
#define ECHO_LOADER_KERNEL_MODE 0U
#define ECHO_LOADER_NOT_ALERTABLE 0U
#define ECHO_NOTIFY_CONSOLE_MESSAGE 34U
#define ECHO_XUSER_INDEX_ANY 0xFFU
#define ECHO_NOTIFY_PRIORITY_DEFAULT 1U
#define ECHO_LOADER_NOTICE_CAPACITY 64U
#define ECHO_LOADER_DATA_ANCHOR UINT32_C(0x45434C31) /* "ECL1" */
#define ECHO_LOADER_STATUS_PENDING UINT32_C(0xFFFFFFFF)

#define ECHO_RESIDENT_MODULE_NAME "EchoCoreResident.xex"
#define ECHO_RESIDENT_GAME_PATH "GAME:\\EchoCoreResident.xex"

typedef void *echo_hmodule;

extern uint32_t XexGetModuleHandle(const char *name, echo_hmodule *out_hmodule);
extern uint32_t XexLoadImage(
    const char *module_name,
    uint32_t module_flags,
    uint32_t min_version,
    echo_hmodule *out_hmodule
);
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
extern int KeDelayExecutionThread(uint32_t processor_mode, uint32_t alertable, int64_t *interval_ptr);
extern void XamLoaderTerminateTitle(void);
extern void XNotifyQueueUI(
    uint32_t notification_type,
    uint32_t user_index,
    uint32_t priority,
    const uint16_t *display_text,
    uint64_t parameter
);

static volatile uint32_t g_echo_loader_data_anchor = ECHO_LOADER_DATA_ANCHOR;
static volatile uint32_t g_echo_loader_status = ECHO_LOADER_STATUS_PENDING;
static echo_hmodule g_echo_loader_module = (echo_hmodule)0;
static uint16_t g_echo_loader_notice[ECHO_LOADER_NOTICE_CAPACITY];

static int echo_loader_nt_success(uint32_t status) {
    return (status & UINT32_C(0x80000000)) == 0U ? 1 : 0;
}

static void echo_loader_zero(void *buffer, size_t length) {
    volatile uint8_t *bytes = (volatile uint8_t *)buffer;
    size_t i;
    if (buffer == NULL) return;
    for (i = 0U; i < length; ++i) bytes[i] = 0U;
}

static void echo_loader_delay_ms(uint32_t milliseconds) {
    int64_t interval = -(int64_t)milliseconds * INT64_C(10000);
    (void)KeDelayExecutionThread(ECHO_LOADER_KERNEL_MODE, ECHO_LOADER_NOT_ALERTABLE, &interval);
}

static void echo_loader_notify(const char *text) {
    uint32_t i = 0U;
    if (text == NULL) return;
    echo_loader_zero(g_echo_loader_notice, sizeof(g_echo_loader_notice));
    while (text[i] != '\0' && i + 1U < ECHO_LOADER_NOTICE_CAPACITY) {
        g_echo_loader_notice[i] = (uint16_t)(uint8_t)text[i];
        ++i;
    }
    g_echo_loader_notice[i] = 0U;
    XNotifyQueueUI(
        ECHO_NOTIFY_CONSOLE_MESSAGE,
        ECHO_XUSER_INDEX_ANY,
        ECHO_NOTIFY_PRIORITY_DEFAULT,
        g_echo_loader_notice,
        UINT64_C(0)
    );
}

static _Noreturn void echo_loader_exit_title(void) {
    echo_loader_zero(g_echo_loader_notice, sizeof(g_echo_loader_notice));

    /* XAM owns normal title teardown. Never substitute a raw return from _start:
     * physical Corona/RGH testing proved that path can reboot the console. */
    XamLoaderTerminateTitle();

    /* Termination should remove this title. If XAM only queues the request or
     * unexpectedly returns, keep this entry thread alive until teardown lands. */
    for (;;) {
        echo_loader_delay_ms(1000U);
    }
}

static uint32_t echo_loader_worker(void *context) {
    (void)context;
    g_echo_loader_module = (echo_hmodule)0;
    g_echo_loader_status = XexLoadImage(
        ECHO_RESIDENT_GAME_PATH,
        ECHO_LOADER_SYSTEM_IMAGE_FLAGS,
        0U,
        &g_echo_loader_module
    );
    return 0U;
}

void _start(void) {
    echo_hmodule existing = (echo_hmodule)0;
    uint32_t thread_handle = ECHO_LOADER_THREAD_NONE;
    uint32_t thread_id = 0U;
    uint32_t query_status;
    int thread_status;

    if (g_echo_loader_data_anchor != ECHO_LOADER_DATA_ANCHOR) {
        echo_loader_notify("EchoCore Loader: data FAIL");
        echo_loader_delay_ms(1800U);
        echo_loader_exit_title();
    }

    query_status = XexGetModuleHandle(ECHO_RESIDENT_MODULE_NAME, &existing);
    if (echo_loader_nt_success(query_status) && existing != (echo_hmodule)0) {
        echo_loader_notify("EchoCore: Resident ja ativo");
        echo_loader_delay_ms(1200U);
        echo_loader_exit_title();
    }

    g_echo_loader_status = ECHO_LOADER_STATUS_PENDING;
    g_echo_loader_module = (echo_hmodule)0;

    thread_status = ExCreateThread(
        &thread_handle,
        0U,
        &thread_id,
        (void *)0,
        (void *)echo_loader_worker,
        (void *)0,
        ECHO_LOADER_SYSTEM_THREAD_FLAG
    );
    if (thread_status < 0 || thread_handle == ECHO_LOADER_THREAD_NONE) {
        echo_loader_notify("EchoCore Loader: thread FAIL");
        echo_loader_delay_ms(1800U);
        echo_loader_exit_title();
    }

    (void)NtWaitForSingleObjectEx(
        thread_handle,
        ECHO_LOADER_KERNEL_MODE,
        ECHO_LOADER_NOT_ALERTABLE,
        (int64_t *)0
    );
    (void)NtClose(thread_handle);

    if (g_echo_loader_status != ECHO_LOADER_STATUS_PENDING &&
        echo_loader_nt_success(g_echo_loader_status) &&
        g_echo_loader_module != (echo_hmodule)0) {
        echo_loader_notify("EchoCore: Resident carregado");
    } else {
        echo_loader_notify("EchoCore Loader: load FAIL");
    }

    echo_loader_delay_ms(1800U);
    echo_loader_exit_title();
}
