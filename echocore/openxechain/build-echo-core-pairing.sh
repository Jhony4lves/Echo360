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

# SynthXEX v0.0.5 reports only a generic "Invalid RVA or offset" when its
# import walker cannot map a PE RVA. Dump the exact import layout before
# conversion so the next failure identifies the offending descriptor/IAT.
python3 - "${PE}" <<'PY'
from pathlib import Path
import struct
import sys

path = Path(sys.argv[1])
data = path.read_bytes()
if data[:2] != b"MZ":
    raise SystemExit("pairing PE doctor: missing MZ")
pe = struct.unpack_from("<I", data, 0x3C)[0]
if data[pe:pe + 4] != b"PE\0\0":
    raise SystemExit("pairing PE doctor: missing PE signature")
coff = pe + 4
section_count = struct.unpack_from("<H", data, coff + 2)[0]
optional_size = struct.unpack_from("<H", data, coff + 16)[0]
optional = coff + 20
magic = struct.unpack_from("<H", data, optional)[0]
if magic != 0x10B:
    raise SystemExit(f"pairing PE doctor: unsupported PE magic 0x{magic:04X}")
import_rva, import_size = struct.unpack_from("<II", data, optional + 104)
section_table = optional + optional_size
sections = []
for i in range(section_count):
    off = section_table + i * 40
    raw_name = data[off:off + 8].split(b"\0", 1)[0]
    name = raw_name.decode("ascii", "replace")
    virtual_size, rva, raw_size, raw_offset = struct.unpack_from("<IIII", data, off + 8)
    sections.append((name, rva, virtual_size, raw_offset, raw_size))
print(f"PAIRING_PE size={len(data)} sections={section_count} import_rva=0x{import_rva:08X} import_size=0x{import_size:X}")
for name, rva, vsize, roff, rsize in sections:
    print(f"PAIRING_SECTION {name} rva=0x{rva:08X} vsize=0x{vsize:X} raw=0x{roff:X}+0x{rsize:X}")

def rva_map(rva):
    for name, base, vsize, roff, rsize in reversed(sections):
        span = max(vsize, rsize)
        if base <= rva < base + span:
            file_off = roff + (rva - base)
            return name, file_off, rva < base + vsize, rva < base + rsize
    return None

def read_c_string(rva):
    mapped = rva_map(rva)
    if not mapped:
        return "<UNMAPPED>"
    _, off, _, _ = mapped
    end = data.find(b"\0", off)
    if end < 0:
        return "<UNTERMINATED>"
    return data[off:end].decode("ascii", "replace")

if import_rva:
    mapped = rva_map(import_rva)
    print(f"PAIRING_IMPORT_DIRECTORY map={mapped}")
    if mapped:
        cursor = mapped[1]
        for index in range(64):
            if cursor + 20 > len(data):
                print(f"PAIRING_IMPORT_DESCRIPTOR[{index}] beyond_file off=0x{cursor:X}")
                break
            fields = struct.unpack_from("<IIIII", data, cursor)
            if fields == (0, 0, 0, 0, 0):
                print(f"PAIRING_IMPORT_DESCRIPTOR[{index}] terminator")
                break
            oft, stamp, chain, name_rva, first_thunk = fields
            print(
                f"PAIRING_IMPORT_DESCRIPTOR[{index}] "
                f"name_rva=0x{name_rva:08X} name_map={rva_map(name_rva)} "
                f"name={read_c_string(name_rva)!r} "
                f"oft=0x{oft:08X} oft_map={rva_map(oft) if oft else None} "
                f"iat=0x{first_thunk:08X} iat_map={rva_map(first_thunk)}"
            )
            if first_thunk:
                thunk_map = rva_map(first_thunk)
                if thunk_map:
                    toff = thunk_map[1]
                    count = 0
                    while toff + 4 <= len(data) and count < 1024:
                        value = struct.unpack_from("<I", data, toff)[0]
                        if value == 0:
                            break
                        count += 1
                        toff += 4
                    print(f"PAIRING_IAT[{index}] entries={count} terminator={'yes' if toff + 4 <= len(data) and struct.unpack_from('<I', data, toff)[0] == 0 else 'no'}")
            cursor += 20
PY

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
