#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:-/tmp/openxechain}"
PREFIX="${OPENXECHAIN_SYSROOT:-/tmp/openxechain-sysroot}"
BUILD_TYPE="${BUILD_TYPE:-Release}"
PARALLEL="${PARALLEL:-$(nproc)}"
HOST_CC="${HOST_CC:-clang}"
HOST_CXX="${HOST_CXX:-clang++}"

LLVM_BUILD="${ROOT}/build-llvm"
XECORE_BUILD="${ROOT}/build-xecorelib"
SYNTH_BUILD="${ROOT}/build-synthxex"

for required in "${ROOT}/llvm/llvm" "${ROOT}/xecorelib" "${ROOT}/synthxex"; do
  if [[ ! -d "${required}" ]]; then
    echo "EchoCore toolchain: missing source directory ${required}" >&2
    exit 2
  fi
done

mkdir -p "${PREFIX}" "${LLVM_BUILD}" "${XECORE_BUILD}" "${SYNTH_BUILD}"

# EchoCore bootstrap is freestanding and only imports Xbox OS functions from
# xecorelib. Newlib and compiler-rt are deliberately omitted here: neither is
# needed by the current no-heap/no-libc bootstrap, and Newlib configuration is
# the stage that failed in the original full OpenXeChain CI probe.
cmake \
  -S "${ROOT}/llvm/llvm" \
  -B "${LLVM_BUILD}" \
  -DCMAKE_C_COMPILER="${HOST_CC}" \
  -DCMAKE_CXX_COMPILER="${HOST_CXX}" \
  -DCMAKE_BUILD_TYPE="${BUILD_TYPE}" \
  -DCMAKE_INSTALL_PREFIX="${PREFIX}" \
  -DLLVM_ENABLE_PROJECTS="lld;clang" \
  -DLLVM_TARGETS_TO_BUILD=PowerPC \
  -DLLVM_DEFAULT_TARGET_TRIPLE=ppc32-xbox360 \
  -DLLVM_INSTALL_BINUTILS_SYMLINKS=true \
  -DLLVM_INSTALL_CCTOOLS_SYMLINKS=true \
  -DLLVM_INSTALL_TOOLCHAIN_ONLY=true \
  -G Ninja

cmake --build "${LLVM_BUILD}" --parallel "${PARALLEL}"
cmake --install "${LLVM_BUILD}"

cat > "${PREFIX}/bin/clang.cfg" <<'EOF'
-Wno-main-return-type
--sysroot=<CFGDIR>/..
-fdeclspec
-mlongcall
EOF

cat > "${PREFIX}/bin/clang++.cfg" <<'EOF'
-Wno-main-return-type
--sysroot=<CFGDIR>/..
-fdeclspec
-mlongcall
EOF

(
  cd "${XECORE_BUILD}"
  PREFIX="${PREFIX}" \
  BINDIR="${PREFIX}/bin" \
  bash "${ROOT}/xecorelib/install.sh"
)

cmake \
  -S "${ROOT}/synthxex" \
  -B "${SYNTH_BUILD}" \
  -DCMAKE_C_COMPILER="${HOST_CC}" \
  -DCMAKE_CXX_COMPILER="${HOST_CXX}" \
  -DCMAKE_BUILD_TYPE="${BUILD_TYPE}" \
  -DCMAKE_INSTALL_PREFIX="${PREFIX}" \
  -G Ninja

cmake --build "${SYNTH_BUILD}" --parallel "${PARALLEL}"
cmake --install "${SYNTH_BUILD}"

for binary in clang lld-link llvm-ar llvm-dlltool synthxex; do
  test -x "${PREFIX}/bin/${binary}"
done
test -s "${PREFIX}/lib/xecorelib.a"

printf 'EchoCore minimal toolchain ready: %s\n' "${PREFIX}"
"${PREFIX}/bin/clang" --version | head -1
"${PREFIX}/bin/synthxex" --help >/dev/null 2>&1 || true
