#include <stddef.h>
#include <stdint.h>

#include <xecore/xboxkrnl_mem.h>
#include <xecore/xboxkrnl_modules.h>
#include <xecore/xboxkrnl_types.h>

#include "echo_dashlaunch_snapshot.h"

#define ECHO_DASHLAUNCH_MODULE_NAME "launch.xex"
#define ECHO_DASHLAUNCH_GET_BY_NAME_ORDINAL 9U
#define ECHO_DASHLAUNCH_FLAG_INVALID_ITEM 0x000000FFU

typedef int (*echo_dashlaunch_get_by_name)(char *option_name, uint32_t *value);

typedef struct echo_dashlaunch_keydata {
    char launchpath[ECHO_DASHLAUNCH_PATH_BYTES];
    uint32_t flags;
    uint32_t dev;
    uint32_t root_dev;
} echo_dashlaunch_keydata;

_Static_assert(
    sizeof(echo_dashlaunch_keydata) == 272U,
    "DashLaunch keydata layout must remain 272 bytes"
);

static void echo_dl_zero(void *memory, size_t length) {
    volatile uint8_t *bytes = (volatile uint8_t *)memory;
    size_t i;
    if (bytes == NULL) return;
    for (i = 0U; i < length; ++i) bytes[i] = 0U;
}

static echo_dashlaunch_get_by_name echo_dl_resolve_getter(void) {
    HMODULE module = (HMODULE)0;
    void *procedure = (void *)0;
    union {
        void *object;
        echo_dashlaunch_get_by_name function;
    } conversion;

    conversion.object = (void *)0;
    if (XexGetModuleHandle(ECHO_DASHLAUNCH_MODULE_NAME, &module) < 0 || module == (HMODULE)0) {
        return (echo_dashlaunch_get_by_name)0;
    }
    if (XexGetProcedureAddress(
            module,
            ECHO_DASHLAUNCH_GET_BY_NAME_ORDINAL,
            &procedure
        ) < 0 || procedure == (void *)0) {
        return (echo_dashlaunch_get_by_name)0;
    }

    conversion.object = procedure;
    return conversion.function;
}

static void echo_dl_read_bool(
    echo_dashlaunch_get_by_name getter,
    char *name,
    uint32_t bit,
    echo_dashlaunch_snapshot *snapshot
) {
    uint32_t value = 0U;
    if (getter(name, &value) == 0) return;
    snapshot->boolean_known_mask |= bit;
    if ((value & 1U) != 0U) snapshot->boolean_value_mask |= bit;
}

static void echo_dl_read_port(
    echo_dashlaunch_get_by_name getter,
    char *name,
    echo_dashlaunch_snapshot *snapshot
) {
    uint32_t value = 0U;
    if (getter(name, &value) == 0) return;
    if (value == 0U || value > 0xFFFFU) return;
    snapshot->ftp_port = (uint16_t)value;
}

static void echo_dl_read_path(
    echo_dashlaunch_get_by_name getter,
    char *name,
    echo_dashlaunch_path_value *destination
) {
    uint32_t raw_pointer = 0U;
    echo_dashlaunch_keydata *key;
    size_t i;

    if (getter(name, &raw_pointer) == 0 || raw_pointer == 0U) return;
    key = (echo_dashlaunch_keydata *)(uintptr_t)raw_pointer;

    /* The struct is smaller than a page. Validate both ends because it may
       straddle a page boundary. Do not dereference a stale DashLaunch pointer. */
    if (!MmIsAddressValid((void *)key) ||
        !MmIsAddressValid((void *)((uint8_t *)key + sizeof(*key) - 1U))) {
        return;
    }

    destination->flags = key->flags;
    if (key->flags == ECHO_DASHLAUNCH_FLAG_INVALID_ITEM) {
        return;
    }

    for (i = 0U; i < ECHO_DASHLAUNCH_PATH_BYTES; ++i) {
        char ch = key->launchpath[i];
        destination->path[i] = ch;
        if (ch == '\0') {
            destination->available = 1U;
            return;
        }
    }

    /* Unterminated path is treated as unavailable instead of truncating it. */
    echo_dl_zero(destination->path, sizeof(destination->path));
    destination->available = 0U;
}

void echo_xbox_read_dashlaunch_snapshot(echo_dashlaunch_snapshot *snapshot) {
    echo_dashlaunch_get_by_name getter;

    if (snapshot == NULL) return;
    echo_dl_zero(snapshot, sizeof(*snapshot));
    snapshot->status = ECHO_STATUS_UNSUPPORTED;

    getter = echo_dl_resolve_getter();
    if (getter == (echo_dashlaunch_get_by_name)0) return;

    snapshot->present = 1U;
    snapshot->status = ECHO_STATUS_OK;

    echo_dl_read_bool(getter, "pingpatch", ECHO_DL_BOOL_PINGPATCH, snapshot);
    echo_dl_read_bool(getter, "contpatch", ECHO_DL_BOOL_CONTPATCH, snapshot);
    echo_dl_read_bool(getter, "xblapatch", ECHO_DL_BOOL_XBLAPATCH, snapshot);
    echo_dl_read_bool(getter, "licpatch", ECHO_DL_BOOL_LICPATCH, snapshot);
    echo_dl_read_bool(getter, "liveblock", ECHO_DL_BOOL_LIVEBLOCK, snapshot);
    echo_dl_read_bool(getter, "livestrong", ECHO_DL_BOOL_LIVESTRONG, snapshot);
    echo_dl_read_bool(getter, "sockpatch", ECHO_DL_BOOL_SOCKPATCH, snapshot);
    echo_dl_read_bool(getter, "fakelive", ECHO_DL_BOOL_FAKELIVE, snapshot);
    echo_dl_read_bool(getter, "ftpserv", ECHO_DL_BOOL_FTPSERV, snapshot);
    echo_dl_read_bool(getter, "exchandler", ECHO_DL_BOOL_EXCHANDLER, snapshot);
    echo_dl_read_bool(getter, "noupdater", ECHO_DL_BOOL_NOUPDATER, snapshot);
    echo_dl_read_port(getter, "ftpport", snapshot);

    echo_dl_read_path(getter, "plugin1", &snapshot->paths[ECHO_DL_PATH_PLUGIN1]);
    echo_dl_read_path(getter, "plugin2", &snapshot->paths[ECHO_DL_PATH_PLUGIN2]);
    echo_dl_read_path(getter, "plugin3", &snapshot->paths[ECHO_DL_PATH_PLUGIN3]);
    echo_dl_read_path(getter, "plugin4", &snapshot->paths[ECHO_DL_PATH_PLUGIN4]);
    echo_dl_read_path(getter, "plugin5", &snapshot->paths[ECHO_DL_PATH_PLUGIN5]);
    echo_dl_read_path(getter, "Default", &snapshot->paths[ECHO_DL_PATH_DEFAULT]);
    echo_dl_read_path(getter, "Guide", &snapshot->paths[ECHO_DL_PATH_GUIDE]);
    echo_dl_read_path(getter, "Power", &snapshot->paths[ECHO_DL_PATH_POWER]);
    echo_dl_read_path(getter, "Fakeanim", &snapshot->paths[ECHO_DL_PATH_FAKEANIM]);
    echo_dl_read_path(getter, "configapp", &snapshot->paths[ECHO_DL_PATH_CONFIGAPP]);
    echo_dl_read_path(getter, "dumpfile", &snapshot->paths[ECHO_DL_PATH_DUMPFILE]);
}
