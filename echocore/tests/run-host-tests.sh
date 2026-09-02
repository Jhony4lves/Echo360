#!/usr/bin/env bash
set -euo pipefail

CC="${CC:-clang}"
CFLAGS=(
  -std=c11
  -Wall
  -Wextra
  -Werror
  -pedantic
)

found=0
for source in echocore/tests/*_test.c; do
  if [[ ! -f "${source}" ]]; then
    continue
  fi
  found=1
  name="$(basename "${source}" .c)"
  binary="/tmp/${name}"
  echo "[EchoCore host test] compile ${source}"

  if [[ "${name}" == "echo_transfer_writer_behavior_test" ]]; then
    "${CC}" "${CFLAGS[@]}" \
      -fno-pie -no-pie \
      -Iechocore/tests/xbox_stubs \
      echocore/openxechain/echo_transfer_writer_xbox.c \
      "${source}" \
      -o "${binary}"
  else
    "${CC}" "${CFLAGS[@]}" "${source}" -o "${binary}"
  fi

  echo "[EchoCore host test] run ${name}"
  "${binary}"
done

if [[ "${found}" -ne 1 ]]; then
  echo "EchoCore host tests: no *_test.c files found" >&2
  exit 2
fi

echo "EchoCore host tests: all passed"
