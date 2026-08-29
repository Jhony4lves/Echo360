#!/usr/bin/env bash
set -euo pipefail

SYSROOT="${OPENXECHAIN_SYSROOT:-/tmp/openxechain-sysroot}"
SOURCE="${1:-echocore/openxechain/echo_core.c}"
OUT_DIR="${2:-/tmp/echocore-build}"

CLANG="${SYSROOT}/bin/clang"
SYNTHXEX="${SYSROOT}/bin/synthxex"
PE="${OUT_DIR}/EchoCore.pe"
XEX="${OUT_DIR}/EchoCore.xex"

if [[ ! -x "${CLANG}" ]]; then
  echo "EchoCore build: missing OpenXeChain clang at ${CLANG}" >&2
  exit 2
fi
if [[ ! -x "${SYNTHXEX}" ]]; then
  echo "EchoCore build: missing SynthXEX at ${SYNTHXEX}" >&2
  exit 2
fi
if [[ ! -f "${SOURCE}" ]]; then
  echo "EchoCore build: source not found: ${SOURCE}" >&2
  exit 2
fi

mkdir -p "${OUT_DIR}"
rm -f "${PE}" "${XEX}"

# The pinned OpenXeChain clang configuration selects ppc32-xbox360 and links
# Newlib/xecorelib. Its Xbox linker supplies /SUBSYSTEM:xbox360, /FIXED,
# /BASE:0x82000000, /ALIGN:0x10000 and /ENTRY:_start.
"${CLANG}" \
  -std=c11 \
  -Os \
  -ffunction-sections \
  -fdata-sections \
  -Wall \
  -Wextra \
  -Werror \
  "${SOURCE}" \
  -o "${PE}"

test -s "${PE}"

"${SYNTHXEX}" \
  --input "${PE}" \
  --output "${XEX}" \
  --type title

test -s "${XEX}"

python3 - "${PE}" "${XEX}" <<'PY'
from pathlib import Path
import sys

pe = Path(sys.argv[1])
xex = Path(sys.argv[2])

pe_head = pe.read_bytes()[:2]
xex_head = xex.read_bytes()[:4]

if pe_head != b"MZ":
    raise SystemExit(f"Unexpected PE signature: {pe_head!r}")
if xex_head != b"XEX2":
    raise SystemExit(f"Unexpected XEX signature: {xex_head!r}")

print(f"EchoCore PE:  {pe} ({pe.stat().st_size} bytes)")
print(f"EchoCore XEX: {xex} ({xex.stat().st_size} bytes, magic XEX2)")
PY
