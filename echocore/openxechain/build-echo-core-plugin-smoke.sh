#!/usr/bin/env bash
set -euo pipefail

SYSROOT="${OPENXECHAIN_SYSROOT:-/tmp/openxechain-sysroot}"
SOURCE="${1:-echocore/openxechain/echo_core_plugin_smoke.c}"
OUT_DIR="${2:-/tmp/echocore-plugin-build}"

CLANG="${SYSROOT}/bin/clang"
LLD_LINK="${SYSROOT}/bin/lld-link"
SYNTHXEX="${SYSROOT}/bin/synthxex"
OBJ="${OUT_DIR}/EchoCorePluginSmoke.obj"
PE="${OUT_DIR}/EchoCorePluginSmoke.pe"
XEX="${OUT_DIR}/EchoCorePluginSmoke.xex"
XECORELIB="${SYSROOT}/lib/xecorelib.a"

for binary in "${CLANG}" "${LLD_LINK}" "${SYNTHXEX}"; do
  if [[ ! -x "${binary}" ]]; then
    echo "EchoCore plugin smoke build: missing tool ${binary}" >&2
    exit 2
  fi
done
if [[ ! -s "${XECORELIB}" ]]; then
  echo "EchoCore plugin smoke build: missing ${XECORELIB}" >&2
  exit 2
fi
if [[ ! -f "${SOURCE}" ]]; then
  echo "EchoCore plugin smoke build: source not found: ${SOURCE}" >&2
  exit 2
fi

mkdir -p "${OUT_DIR}"
rm -f "${OBJ}" "${PE}" "${XEX}"

# Compile only. Do not enable function/data sections: the pinned OpenXeChain
# PowerPC/COFF backend emits broken one_only COMDAT definition symbols.
"${CLANG}" \
  -std=c11 \
  -Os \
  -ffreestanding \
  -fno-builtin \
  -Wall \
  -Wextra \
  -Werror \
  -c "${SOURCE}" \
  -o "${OBJ}"

# The pinned OpenXeChain clang driver is title-oriented, so DLL linkage is
# intentionally performed explicitly with lld-link.
"${LLD_LINK}" \
  /SUBSYSTEM:xbox360 \
  /FIXED \
  /BASE:0x90B00000 \
  /ALIGN:0x10000 \
  /ENTRY:_start \
  /DLL \
  /OUT:"${PE}" \
  "${OBJ}" \
  "${XECORELIB}"

test -s "${PE}"

"${SYNTHXEX}" \
  --input "${PE}" \
  --output "${XEX}" \
  --type sysdll

test -s "${XEX}"

python3 - "${PE}" "${XEX}" <<'PY'
from pathlib import Path
import struct
import sys

pe = Path(sys.argv[1])
xex = Path(sys.argv[2])
data = pe.read_bytes()
xex_data = xex.read_bytes()
if data[:2] != b"MZ":
    raise SystemExit("Plugin PE is missing MZ signature")
if xex_data[:4] != b"XEX2":
    raise SystemExit("Plugin XEX is missing XEX2 signature")
module_flags = struct.unpack_from(">I", xex_data, 4)[0]
expected_module_flags = 0x0000000A
if module_flags != expected_module_flags:
    raise SystemExit(f"Unexpected plugin XEX module flags: 0x{module_flags:08X} (expected sysdll 0x{expected_module_flags:08X})")
pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
if data[pe_offset:pe_offset + 4] != b"PE\0\0":
    raise SystemExit("Plugin image is missing PE signature")
characteristics = struct.unpack_from("<H", data, pe_offset + 4 + 18)[0]
if not (characteristics & 0x2000):
    raise SystemExit(f"Plugin PE does not have IMAGE_FILE_DLL set: 0x{characteristics:04X}")
optional = pe_offset + 4 + 20
magic = struct.unpack_from("<H", data, optional)[0]
if magic != 0x10B:
    raise SystemExit(f"Unexpected optional-header magic: 0x{magic:04X}")
image_base = struct.unpack_from("<I", data, optional + 28)[0]
if image_base != 0x90B00000:
    raise SystemExit(f"Unexpected plugin image base: 0x{image_base:08X}")
print(f"EchoCore plugin PE:  {pe} ({pe.stat().st_size} bytes, DLL, base 0x{image_base:08X})")
print(f"EchoCore plugin XEX: {xex} ({xex.stat().st_size} bytes, magic XEX2, sysdll flags 0x{module_flags:08X})")
PY
