#ifndef ECHO_DASHLAUNCH_SNAPSHOT_H
#define ECHO_DASHLAUNCH_SNAPSHOT_H

#include <stdint.h>

#include "echo_readonly_contract.h"

#define ECHO_DASHLAUNCH_PATH_BYTES 260U
#define ECHO_DASHLAUNCH_PATH_COUNT 11U

#define ECHO_DL_BOOL_PINGPATCH   (UINT32_C(1) << 0)
#define ECHO_DL_BOOL_CONTPATCH   (UINT32_C(1) << 1)
#define ECHO_DL_BOOL_XBLAPATCH   (UINT32_C(1) << 2)
#define ECHO_DL_BOOL_LICPATCH    (UINT32_C(1) << 3)
#define ECHO_DL_BOOL_LIVEBLOCK   (UINT32_C(1) << 4)
#define ECHO_DL_BOOL_LIVESTRONG  (UINT32_C(1) << 5)
#define ECHO_DL_BOOL_SOCKPATCH   (UINT32_C(1) << 6)
#define ECHO_DL_BOOL_FAKELIVE    (UINT32_C(1) << 7)
#define ECHO_DL_BOOL_FTPSERV     (UINT32_C(1) << 8)
#define ECHO_DL_BOOL_EXCHANDLER  (UINT32_C(1) << 9)
#define ECHO_DL_BOOL_NOUPDATER   (UINT32_C(1) << 10)
#define ECHO_DL_BOOL_KNOWN_MASK  ((UINT32_C(1) << 11) - UINT32_C(1))

typedef enum echo_dashlaunch_path_slot {
    ECHO_DL_PATH_PLUGIN1 = 0,
    ECHO_DL_PATH_PLUGIN2,
    ECHO_DL_PATH_PLUGIN3,
    ECHO_DL_PATH_PLUGIN4,
    ECHO_DL_PATH_PLUGIN5,
    ECHO_DL_PATH_DEFAULT,
    ECHO_DL_PATH_GUIDE,
    ECHO_DL_PATH_POWER,
    ECHO_DL_PATH_FAKEANIM,
    ECHO_DL_PATH_CONFIGAPP,
    ECHO_DL_PATH_DUMPFILE
} echo_dashlaunch_path_slot;

typedef struct echo_dashlaunch_path_value {
    char path[ECHO_DASHLAUNCH_PATH_BYTES];
    uint32_t flags;
    uint8_t available;
    uint8_t reserved[3];
} echo_dashlaunch_path_value;

typedef struct echo_dashlaunch_snapshot {
    uint8_t status;
    uint8_t present;
    uint16_t ftp_port;
    uint32_t boolean_known_mask;
    uint32_t boolean_value_mask;
    echo_dashlaunch_path_value paths[ECHO_DASHLAUNCH_PATH_COUNT];
} echo_dashlaunch_snapshot;

/*
 * Read only the configuration already active inside launch.xex.
 * No launch.ini access and no DashLaunch setter ordinal is resolved.
 */
void echo_xbox_read_dashlaunch_snapshot(echo_dashlaunch_snapshot *snapshot);

#endif
