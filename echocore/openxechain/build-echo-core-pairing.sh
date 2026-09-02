#!/usr/bin/env bash
set -euo pipefail

SYSROOT="${OPENXECHAIN_SYSROOT:-/tmp/openxechain-sysroot}"
OUT_DIR="${1:-/tmp/echocore-pairing-build}"
CLANG="${SYSROOT}/bin/clang"
SYNTHXEX="${SYSROOT}/bin/synthxex"
PE="${OUT_DIR}/EchoCorePairing.pe"
XEX="${OUT_DIR}/EchoCorePairing.xex"

for binary in "${CLANG}" "${SYNTHXEX}"; do
  if [[ ! -x "${binary}" ]]; then
    echo "EchoCore pairing build: missing tool ${binary}" >&2
    exit 2
  fi
done
if [[ ! -s "${SYSROOT}/lib/xecorelib.a" ]]; then
  echo "EchoCore pairing build: missing ${SYSROOT}/lib/xecorelib.a" >&2
  exit 2
fi

SOURCES=(
  echocore/openxechain/echo_pairing_xex.c
  echocore/openxechain/echo_pairing_token_xbox.c
  echocore/openxechain/echo_pairing_store_xbox.c
  echocore/openxechain/echo_transfer_writer_xbox.c
  echocore/openxechain/echo_auth_crypto_xbox.c
)
for source in "${SOURCES[@]}"; do
  [[ -f "${source}" ]] || { echo "EchoCore pairing build: missing ${source}" >&2; exit 2; }
done

mkdir -p "${OUT_DIR}"
rm -f "${PE}" "${XEX}"

# The Xbox driver intentionally has no implicit system-include hook, so the
# installed xecore headers must be supplied explicitly.
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
import sys
pe = Path(sys.argv[1])
xex = Path(sys.argv[2])
if pe.read_bytes()[:2] != b"MZ": raise SystemExit("EchoCorePairing PE is missing MZ signature")
if xex.read_bytes()[:4] != b"XEX2": raise SystemExit("EchoCorePairing XEX is missing XEX2 signature")
print(f"EchoCore pairing PE:  {pe} ({pe.stat().st_size} bytes)")
print(f"EchoCore pairing XEX: {xex} ({xex.stat().st_size} bytes, magic XEX2)")
PY
