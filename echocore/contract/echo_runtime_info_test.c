#include <assert.h>
#include <stdint.h>

#include "echo_readonly_contract.h"
#include "echo_runtime_info_xbox.h"
#include "echo_xam_abi.h"

static uint32_t stub_title_id = 0x465307E4U;
static uint32_t stub_system_version = 0x20449700U;
static uint32_t stub_link_status = ECHO_XNET_ETHERNET_LINK_ACTIVE | ECHO_XNET_ETHERNET_LINK_100MBPS;
static uint32_t observed_link_caller = 0U;

uint32_t XamGetCurrentTitleId(void) {
    return stub_title_id;
}

uint32_t XamGetSystemVersion(void) {
    return stub_system_version;
}

uint32_t NetDll_XNetGetEthernetLinkStatus(uint32_t caller) {
    observed_link_caller = caller;
    return stub_link_status;
}

int main(void) {
    uint8_t current[ECHO_CURRENT_TITLE_BYTES];
    uint8_t info[ECHO_CORE_INFO_BYTES];
    uint64_t caps;

    echo_xbox_make_current_title_payload(current);
    assert(echo_ro_read_be32(current) == stub_title_id);

    echo_xbox_make_core_info_payload(info, 0);
    assert(echo_ro_read_be16(info + 0U) == ECHO_RO_CONTRACT_VERSION);
    assert(echo_ro_read_be32(info + 8U) == stub_system_version);
    assert(echo_ro_read_be32(info + 12U) == stub_title_id);
    caps = echo_ro_read_be64(info + 16U);
    assert((caps & ECHO_CAP_PING) != 0U);
    assert((caps & ECHO_CAP_CORE_INFO) != 0U);
    assert((caps & ECHO_CAP_CURRENT_TITLE) != 0U);
    assert((caps & ECHO_CAP_FILE_STAT) != 0U);
    assert((caps & ECHO_CAP_DIR_LIST) == 0U);
    assert((echo_ro_read_be32(info + 24U) & ECHO_CORE_STATUS_NETWORK_LINK_ACTIVE) != 0U);
    assert((echo_ro_read_be32(info + 24U) & ECHO_CORE_STATUS_RESIDENT_PLUGIN) == 0U);
    assert(observed_link_caller == ECHO_XNCALLER_TITLE);

    echo_xbox_make_core_info_payload(info, 1);
    assert((echo_ro_read_be32(info + 24U) & ECHO_CORE_STATUS_RESIDENT_PLUGIN) != 0U);
    assert(observed_link_caller == ECHO_XNCALLER_SYSAPP);

    stub_link_status = 0U;
    echo_xbox_make_core_info_payload(info, 1);
    assert((echo_ro_read_be32(info + 24U) & ECHO_CORE_STATUS_NETWORK_LINK_ACTIVE) == 0U);

    stub_title_id = 0U;
    echo_xbox_make_current_title_payload(current);
    assert(echo_ro_read_be32(current) == 0U);

    return 0;
}
