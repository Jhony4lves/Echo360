#ifndef ECHO_XAM_ABI_H
#define ECHO_XAM_ABI_H

#include <stdint.h>

/*
 * Small audited ABI surface used by the read-only EchoCore contract.
 *
 * The pinned xecorelib revision provides import stubs in xecorelib.a, but it
 * does not currently provide typed declarations for these XAM exports.
 * Keep the declarations here intentionally narrow and cross-check ordinals
 * against public 17559/xkelib tables before adding more.
 *
 * XamGetCurrentTitleId   XAM ordinal 463
 * XamGetSystemVersion    XAM ordinal 642
 * NetDll_XNetGetEthernetLinkStatus XAM ordinal 75
 */
extern uint32_t XamGetCurrentTitleId(void);
extern uint32_t XamGetSystemVersion(void);
extern uint32_t NetDll_XNetGetEthernetLinkStatus(uint32_t caller);

#define ECHO_XNCALLER_TITLE  1U
#define ECHO_XNCALLER_SYSAPP 2U
#define ECHO_XNET_ETHERNET_LINK_ACTIVE 0x01U
#define ECHO_XNET_ETHERNET_LINK_100MBPS 0x02U
#define ECHO_XNET_ETHERNET_LINK_10MBPS  0x04U
#define ECHO_XNET_ETHERNET_LINK_FULL_DUPLEX 0x08U
#define ECHO_XNET_ETHERNET_LINK_HALF_DUPLEX 0x10U

#endif
