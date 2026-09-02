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
  "${CC}" "${CFLAGS[@]}" "${source}" -o "${binary}"
  echo "[EchoCore host test] run ${name}"
  "${binary}"
done

if [[ "${found}" -ne 1 ]]; then
  echo "EchoCore host tests: no *_test.c files found" >&2
  exit 2
fi

echo "EchoCore host tests: all passed"
