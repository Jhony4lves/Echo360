#!/usr/bin/env bash
set -euo pipefail

SYSROOT="${OPENXECHAIN_SYSROOT:-/tmp/openxechain-sysroot}"
SOURCE="${1:-echocore/openxechain/echo_core.c}"
OUT_DIR="${2:-/tmp/echocore-build}"

CLANG="${SYSROOT}/bin/clang"
SYNTHXEX="${SYSROOT}/bin/synthxex"
PE="${OUT_DIR}/EchoCore.pe"
XEX="${OUT_DIR}/EchoCore.xex"
OBJ="${OUT_DIR}/EchoCore.o"
ASM="${OUT_DIR}/EchoCore.s"

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
rm -f "${PE}" "${XEX}" "${OBJ}" "${ASM}"

COMMON_FLAGS=(
  -std=c11
  -Os
  -ffreestanding
  -fno-builtin
  -nostdlib
  -ffunction-sections
  -fdata-sections
  -Wall
  -Wextra
  -Werror
)

# Temporary ABI probe: decode the COFF symbol table ourselves because GNU
# binutils does not recognize OpenXeChain's Xbox/PowerPC object machine type.
"${CLANG}" "${COMMON_FLAGS[@]}" -c "${SOURCE}" -o "${OBJ}"
"${CLANG}" "${COMMON_FLAGS[@]}" -S "${SOURCE}" -o "${ASM}"
python3 - "${OBJ}" <<'PY'
from pathlib import Path
import struct, sys

p = Path(sys.argv[1])
data = p.read_bytes()
want = {b'_start', b'echo_accept_bounded', b'echo_handle_ping'}
chosen = None
for endian in ('<', '>'):
    if len(data) < 20:
        continue
    machine, nsec, _ts, psym, nsym, opt, chars = struct.unpack_from(endian + 'HHIIIHH', data, 0)
    if 0 < nsec < 256 and 0 < psym < len(data) and 0 < nsym < 100000 and psym + nsym * 18 + 4 <= len(data):
        chosen = (endian, machine, nsec, psym, nsym)
        break
if chosen is None:
    raise SystemExit('EchoCore COFF probe: could not decode header')
endian, machine, nsec, psym, nsym = chosen
str_base = psym + nsym * 18
str_size = struct.unpack_from(endian + 'I', data, str_base)[0]
print(f'=== EchoCore COFF: endian={endian} machine=0x{machine:04x} sections={nsec} symbols={nsym} ===')

def sym_name(raw):
    first, second = struct.unpack(endian + 'II', raw)
    if first == 0 and second >= 4 and second < str_size:
        start = str_base + second
        end = data.find(b'\0', start, min(len(data), str_base + str_size))
        if end < 0:
            end = min(len(data), str_base + str_size)
        return data[start:end]
    return raw.split(b'\0', 1)[0]

i = 0
seen = set()
while i < nsym:
    off = psym + i * 18
    name = sym_name(data[off:off+8])
    value, sec, typ = struct.unpack_from(endian + 'IhH', data, off + 8)
    storage = data[off + 16]
    aux = data[off + 17]
    if name in want or any(x in name for x in want):
        print(f'COFF symbol {name!r}: section={sec} value=0x{value:x} type=0x{typ:x} storage={storage} aux={aux}')
        seen.add(name)
    i += 1 + aux
print('COFF selected symbols seen:', sorted(x.decode('ascii', 'replace') for x in seen))
PY

echo "=== EchoCore assembly context ==="
for symbol in _start echo_accept_bounded echo_handle_ping; do
  echo "--- ${symbol} ---"
  grep -n -A8 -B4 "${symbol}" "${ASM}" | head -n 80 || true
done

# The bootstrap is intentionally freestanding. It uses no Newlib/libc and no
# compiler runtime helpers; Xbox OS imports are resolved only through xecorelib.
"${CLANG}" \
  "${COMMON_FLAGS[@]}" \
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
