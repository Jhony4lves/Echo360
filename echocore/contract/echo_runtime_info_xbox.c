#include <stdint.h>

#include "echo_readonly_contract.h"
#include "echo_xam_abi.h"

#define ECHOCORE_BUILD_V1 0x00010000U

uint32_t echo_xbox_current_title_id(void) {
    return XamGetCurrentTitleId();
}

void echo_xbox_make_current_title_payload(
    uint8_t out[ECHO_CURRENT_TITLE_BYTES]
) {
    echo_ro_write_be32(out, echo_xbox_current_title_id());
}

uint32_t echo_xbox_runtime_status_flags(int resident_plugin) {
    uint32_t flags = resident_plugin != 0 ? ECHO_CORE_STATUS_RESIDENT_PLUGIN : 0U;
    uint32_t link = NetDll_XNetGetEthernetLinkStatus(
        resident_plugin != 0 ? ECHO_XNCALLER_SYSAPP : ECHO_XNCALLER_TITLE
    );

    if ((link & ECHO_XNET_ETHERNET_LINK_ACTIVE) != 0U) {
        flags |= ECHO_CORE_STATUS_NETWORK_LINK_ACTIVE;
    }
    return flags;
}

void echo_xbox_make_core_info_payload(
    uint8_t out[ECHO_CORE_INFO_BYTES],
    int resident_plugin
) {
    const uint64_t capabilities =
        ECHO_CAP_PING |
        ECHO_CAP_CORE_INFO |
        ECHO_CAP_CURRENT_TITLE |
        ECHO_CAP_FILE_STAT;

    echo_ro_make_core_info(
        out,
        ECHOCORE_BUILD_V1,
        XamGetSystemVersion(),
        echo_xbox_current_title_id(),
        capabilities,
        echo_xbox_runtime_status_flags(resident_plugin)
    );
}
