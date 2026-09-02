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
if [[ ! -s "${SYSROOT}/lib/xecorelib.a" ]]; then
  echo "EchoCore build: missing xecorelib at ${SYSROOT}/lib/xecorelib.a" >&2
  exit 2
fi
if [[ ! -f "${SOURCE}" ]]; then
  echo "EchoCore build: source not found: ${SOURCE}" >&2
  exit 2
fi

mkdir -p "${OUT_DIR}"
rm -f "${PE}" "${XEX}"

# The bootstrap is intentionally freestanding. It uses no Newlib/libc and no
# compiler runtime helpers; Xbox OS imports are resolved only through xecorelib.
"${CLANG}" \
  -std=c11 \
  -Os \
  -ffreestanding \
  -fno-builtin \
  -nostdlib \
  -ffunction-sections \
  -fdata-sections \
  -Wall \
  -Wextra \
  -Werror \
  "${SOURCE}" \
  -Wl,/libpath:"${SYSROOT}/lib" \
  -Wl,/defaultlib:xecorelib.a \
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
