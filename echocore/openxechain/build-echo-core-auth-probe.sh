#!/usr/bin/env bash
set -euo pipefail

SYSROOT="${OPENXECHAIN_SYSROOT:-/tmp/openxechain-sysroot}"
OUT_DIR="${1:-/tmp/echocore-auth-probe-build}"
CLANG="${SYSROOT}/bin/clang"
SYNTHXEX="${SYSROOT}/bin/synthxex"
PE="${OUT_DIR}/EchoCoreAuthProbe.pe"
XEX="${OUT_DIR}/EchoCoreAuthProbe.xex"

for binary in "${CLANG}" "${SYNTHXEX}"; do
  if [[ ! -x "${binary}" ]]; then
    echo "EchoCore auth probe build: missing tool ${binary}" >&2
    exit 2
  fi
done
if [[ ! -s "${SYSROOT}/lib/xecorelib.a" ]]; then
  echo "EchoCore auth probe build: missing ${SYSROOT}/lib/xecorelib.a" >&2
  exit 2
fi

SOURCES=(
  echocore/openxechain/echo_core_auth_probe.c
  echocore/openxechain/echo_pairing_store_xbox.c
  echocore/openxechain/echo_transfer_writer_xbox.c
  echocore/openxechain/echo_auth_crypto_xbox.c
)
for source in "${SOURCES[@]}"; do
  [[ -f "${source}" ]] || { echo "EchoCore auth probe build: missing ${source}" >&2; exit 2; }
done

mkdir -p "${OUT_DIR}"
rm -f "${PE}" "${XEX}"

"${CLANG}" \
  -std=c11 \
  -Os \
  -ffreestanding \
  -fno-builtin \
  -nostdlib \
  -I"${SYSROOT}/include" \
  -Wall \
  -Wextra \
  -Werror \
  "${SOURCES[@]}" \
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
import struct
import sys

pe = Path(sys.argv[1])
xex = Path(sys.argv[2])
pe_data = pe.read_bytes()
xex_data = xex.read_bytes()
if pe_data[:2] != b'MZ':
    raise SystemExit('EchoCoreAuthProbe PE is missing MZ signature')
if xex_data[:4] != b'XEX2':
    raise SystemExit('EchoCoreAuthProbe XEX is missing XEX2 signature')
flags = struct.unpack_from('>I', xex_data, 4)[0]
if flags != 0x00000001:
    raise SystemExit(f'Unexpected auth probe XEX flags: 0x{flags:08X}')
print(f'EchoCore auth probe PE:  {pe} ({pe.stat().st_size} bytes)')
print(f'EchoCore auth probe XEX: {xex} ({xex.stat().st_size} bytes, XEX2 title flags 0x{flags:08X})')
PY
