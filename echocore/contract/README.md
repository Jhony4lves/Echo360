# EchoCore read-only contract spike

This directory is the console-side draft that backs issue #36. It does not modify the Android app and it is not yet part of the stable EchoLink v1 contract.

The draft exists so the Xbox-side implementation and the Android-side integration can evolve against one explicit binary layout instead of duplicating assumptions.

## Compatibility rule

The existing EchoLink bootstrap remains unchanged:

- 16-byte big-endian header;
- `0x01 PING`;
- `0x02 PONG`;
- `0x7F ERROR`;
- TCP port 36000.

Read-only types are additive candidates after physical-console PING/PONG proof:

| Type | Meaning |
| --- | --- |
| `0x10` | CORE_INFO request |
| `0x11` | CORE_INFO response |
| `0x12` | CURRENT_TITLE request |
| `0x13` | CURRENT_TITLE response |
| `0x14` | FILE_STAT request |
| `0x15` | FILE_STAT response |
| `0x16` | DIR_LIST request |
| `0x17` | DIR_LIST response |

Unknown types remain fail-closed.

## CORE_INFO

The response is fixed at 32 bytes:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 2 | read-only contract version |
| 2 | 2 | reserved = 0 |
| 4 | 4 | EchoCore build |
| 8 | 4 | raw `XamGetSystemVersion()` |
| 12 | 4 | current `XamGetCurrentTitleId()` |
| 16 | 8 | capability bits |
| 24 | 4 | status flags |
| 28 | 4 | reserved = 0 |

No serial number, CPU key, KV, MAC address, account identity or other console secret belongs in this payload.

`CURRENT_TITLE` exists separately because frequent playtime observation should not require requesting the larger CORE_INFO payload.

## FILE_STAT

Request payload: raw path bytes, no NUL terminator, maximum 512 bytes.

The console must validate the path before opening it. The v1 spike allows only an explicit `Hdd1:/...` or `Hdd1:\\...` root, case-insensitively, and rejects:

- empty paths;
- embedded NUL/control bytes;
- `.` or `..` path segments;
- duplicate separators;
- extra `:` characters;
- unapproved roots such as `Usb0:`;
- the FTPdll-specific `fHdd:` alias until a native kernel alias is proven.

Response payload is fixed at 16 bytes:

- status;
- object type (`none`, `file`, `directory`);
- reserved bytes = 0;
- unsigned 64-bit size.

This maps directly to the evidence needed by EchoIntegrity without treating transport failures as corruption.

## DIR_LIST

The first implementation must be bounded:

- one directory per request;
- no automatic recursion;
- at most 256 entries per response set;
- at most 255 bytes per entry name;
- type + size for every entry;
- explicit `LIMIT_REACHED` state instead of silently truncating.

The exact entry framing is intentionally not frozen until FILE_STAT works on physical hardware.

## Xbox ABI targets

The console-side implementation intends to use verified public exports:

- `XamGetSystemVersion()` (XAM ordinal 642);
- `XamGetCurrentTitleId()` (XAM ordinal 463);
- `NtQueryFullAttributesFile` / `NtQueryInformationFile` for read-only stat;
- `NtQueryDirectoryFile` for bounded listing.

The resident service will use `XNCALLER_SYSAPP`; the manual bootstrap remains `XNCALLER_TITLE`.

## Promotion rule

Nothing in this directory should be promoted into the stable EchoLink docs or Android code until:

1. the manual EchoCore XEX builds reproducibly;
2. `PING -> PONG` succeeds on the physical RGH Xbox;
3. the plugin smoke XEX loads without destabilizing Aurora/dashboard;
4. the specific Xbox ABI call is proven on hardware;
5. host tests keep reserved fields, bounds and path validation fail-closed.
