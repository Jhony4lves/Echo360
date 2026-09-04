#!/usr/bin/env bash
set -euo pipefail

SYSROOT="${OPENXECHAIN_SYSROOT:-/tmp/openxechain-sysroot}"
SOURCE="${1:-echocore/openxechain/echo_core_loader.c}"
OUT_DIR="${2:-/tmp/echocore-loader-build}"

CLANG="${SYSROOT}/bin/clang"
SYNTHXEX="${SYSROOT}/bin/synthxex"
PE="${OUT_DIR}/EchoCoreLoader.pe"
XEX="${OUT_DIR}/EchoCoreLoader.xex"
OBJ="${OUT_DIR}/EchoCoreLoader.o"

for binary in "${CLANG}" "${SYNTHXEX}"; do
  if [[ ! -x "${binary}" ]]; then
    echo "EchoCore loader build: missing ${binary}" >&2
    exit 2
  fi
done
if [[ ! -s "${SYSROOT}/lib/xecorelib.a" ]]; then
  echo "EchoCore loader build: missing ${SYSROOT}/lib/xecorelib.a" >&2
  exit 2
fi
if [[ ! -f "${SOURCE}" ]]; then
  echo "EchoCore loader build: source not found: ${SOURCE}" >&2
  exit 2
fi

mkdir -p "${OUT_DIR}"
rm -f "${PE}" "${XEX}" "${OBJ}"

COMMON_FLAGS=(
  -std=c11
  -Os
  -ffreestanding
  -fno-builtin
  -nostdlib
  -Wall
  -Wextra
  -Werror
)

"${CLANG}" "${COMMON_FLAGS[@]}" -c "${SOURCE}" -o "${OBJ}"

"${CLANG}" \
  -nostdlib \
  "${OBJ}" \
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
pe_data = pe.read_bytes()
xex_data = xex.read_bytes()
if pe_data[:2] != b"MZ":
    raise SystemExit("EchoCoreLoader PE is missing MZ signature")
if xex_data[:4] != b"XEX2":
    raise SystemExit("EchoCoreLoader XEX is missing XEX2 signature")
if b"EchoCoreResident.xex" not in pe_data:
    raise SystemExit("EchoCoreLoader PE lost the fixed resident filename")
print(f"EchoCore loader PE:  {pe} ({pe.stat().st_size} bytes)")
print(f"EchoCore loader XEX: {xex} ({xex.stat().st_size} bytes, title, XEX2)")
PY
