#!/usr/bin/env bash
set -euo pipefail

SYSROOT="${OPENXECHAIN_SYSROOT:-/tmp/openxechain-sysroot-fast}"
OUT_DIR="${1:-/tmp/echocore-resident-build}"
IMAGE_BASE="${ECHO_PLUGIN_IMAGE_BASE:-0x90B00000}"

CLANG="${SYSROOT}/bin/clang"
LLD_LINK="${SYSROOT}/bin/lld-link"
SYNTHXEX="${SYSROOT}/bin/synthxex"
XECORELIB="${SYSROOT}/lib/xecorelib.a"
PE="${OUT_DIR}/EchoCoreResident.pe"
XEX="${OUT_DIR}/EchoCoreResident.xex"

SOURCES=(
  echocore/openxechain/echo_core_resident.c
  echocore/openxechain/echo_net_server_xbox.c
  echocore/openxechain/echo_session_engine_xbox.c
  echocore/openxechain/echo_request_pipeline_xbox.c
  echocore/openxechain/echo_readonly_dispatch_xbox.c
  echocore/openxechain/echo_command_auth_xbox.c
  echocore/openxechain/echo_auth_crypto_xbox.c
  echocore/openxechain/echo_pairing_store_xbox.c
  echocore/openxechain/echo_transfer_writer_xbox.c
  echocore/contract/echo_dir_list_xbox.c
  echocore/contract/echo_doctor_telemetry_xbox.c
  echocore/contract/echo_file_stat_xbox.c
  echocore/contract/echo_runtime_info_xbox.c
)

for binary in "${CLANG}" "${LLD_LINK}" "${SYNTHXEX}"; do
  if [[ ! -x "${binary}" ]]; then
    echo "EchoCore resident build: missing tool ${binary}" >&2
    exit 2
  fi
done
if [[ ! -s "${XECORELIB}" ]]; then
  echo "EchoCore resident build: missing ${XECORELIB}" >&2
  exit 2
fi
for source in "${SOURCES[@]}"; do
  if [[ ! -f "${source}" ]]; then
    echo "EchoCore resident build: source not found: ${source}" >&2
    exit 2
  fi
done

mkdir -p "${OUT_DIR}/obj"
rm -f "${OUT_DIR}/obj/"*.obj "${PE}" "${XEX}"

OBJECTS=()
for source in "${SOURCES[@]}"; do
  rel="${source#echocore/}"
  stem="${rel//\//_}"
  stem="${stem%.c}"
  obj="${OUT_DIR}/obj/${stem}.obj"
  echo "[EchoCore resident] compile ${source}"
  # The Xbox driver does not add the installed sysroot headers implicitly.
  # Also keep function/data sections disabled: the pinned PowerPC/COFF backend
  # emits broken one_only COMDAT definition symbols for those options.
  "${CLANG}" \
    -std=c11 \
    -Os \
    -ffreestanding \
    -fno-builtin \
    -I"${SYSROOT}/include" \
    -Wall \
    -Wextra \
    -Werror \
    -c "${source}" \
    -o "${obj}"
  test -s "${obj}"
  OBJECTS+=("${obj}")
done

"${LLD_LINK}" \
  /SUBSYSTEM:xbox360 \
  /FIXED \
  /BASE:"${IMAGE_BASE}" \
  /ALIGN:0x10000 \
  /ENTRY:_start \
  /DLL \
  /OPT:REF \
  /OUT:"${PE}" \
  "${OBJECTS[@]}" \
  "${XECORELIB}"

test -s "${PE}"

"${SYNTHXEX}" \
  --input "${PE}" \
  --output "${XEX}" \
  --type sysdll

test -s "${XEX}"

python3 - "${PE}" "${XEX}" "${IMAGE_BASE}" <<'PY'
from pathlib import Path
import struct
import sys

pe = Path(sys.argv[1])
xex = Path(sys.argv[2])
expected_base = int(sys.argv[3], 0)
data = pe.read_bytes()
xex_data = xex.read_bytes()
if data[:2] != b"MZ":
    raise SystemExit("Resident PE is missing MZ signature")
if xex_data[:4] != b"XEX2":
    raise SystemExit("Resident XEX is missing XEX2 signature")
module_flags = struct.unpack_from(">I", xex_data, 4)[0]
expected_module_flags = 0x0000000A
if module_flags != expected_module_flags:
    raise SystemExit(f"Unexpected resident XEX module flags: 0x{module_flags:08X} (expected sysdll 0x{expected_module_flags:08X})")
pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
if data[pe_offset:pe_offset + 4] != b"PE\0\0":
    raise SystemExit("Resident image is missing PE signature")
characteristics = struct.unpack_from("<H", data, pe_offset + 4 + 18)[0]
if not (characteristics & 0x2000):
    raise SystemExit(f"Resident PE does not have IMAGE_FILE_DLL set: 0x{characteristics:04X}")
optional = pe_offset + 4 + 20
magic = struct.unpack_from("<H", data, optional)[0]
if magic != 0x10B:
    raise SystemExit(f"Unexpected resident optional-header magic: 0x{magic:04X}")
image_base = struct.unpack_from("<I", data, optional + 28)[0]
if image_base != expected_base:
    raise SystemExit(f"Unexpected resident image base: 0x{image_base:08X} (expected 0x{expected_base:08X})")
print(f"EchoCore resident PE:  {pe} ({pe.stat().st_size} bytes, DLL, base 0x{image_base:08X})")
print(f"EchoCore resident XEX: {xex} ({xex.stat().st_size} bytes, XEX2, sysdll flags 0x{module_flags:08X})")
PY
