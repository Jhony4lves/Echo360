#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:-/tmp/openxechain}"
PREFIX="${OPENXECHAIN_SYSROOT:-/tmp/openxechain-sysroot}"
BUILD_TYPE="${BUILD_TYPE:-Release}"
PARALLEL="${PARALLEL:-$(nproc)}"
HOST_CC="${HOST_CC:-clang}"
HOST_CXX="${HOST_CXX:-clang++}"

LLVM_BUILD="${ROOT}/build-llvm-fast"
XECORE_BUILD="${ROOT}/build-xecorelib-fast"
SYNTH_BUILD="${ROOT}/build-synthxex-fast"

for required in "${ROOT}/llvm/llvm" "${ROOT}/xecorelib" "${ROOT}/synthxex"; do
  if [[ ! -d "${required}" ]]; then
    echo "EchoCore fast toolchain: missing source directory ${required}" >&2
    exit 2
  fi
done

# Only these installed LLVM-facing components are needed by EchoCore today:
# - clang (+ resource headers for stdint/stddef)
# - lld + lld-link symlink for Xbox PE/COFF linkage
# - llvm-ar + llvm-dlltool symlink for xecorelib import libraries
#
# The Xbox toolchain uses Clang's integrated assembler by default, so the
# external CrossXbox360::Assembler path is not part of this normal build.
LLVM_COMPONENTS="clang;clang-resource-headers;lld;lld-link;llvm-ar;llvm-dlltool"

rm -rf "${LLVM_BUILD}" "${XECORE_BUILD}" "${SYNTH_BUILD}"
mkdir -p "${PREFIX}" "${LLVM_BUILD}" "${XECORE_BUILD}" "${SYNTH_BUILD}"

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
  -DLLVM_DISTRIBUTION_COMPONENTS="${LLVM_COMPONENTS}" \
  -G Ninja

# Building the distribution target avoids building/installing every LLVM tool
# pulled into the default ALL target. Dependencies of the selected tools are
# still built normally, so this is an optimization, not a binary hack/copy.
cmake --build "${LLVM_BUILD}" --target distribution --parallel "${PARALLEL}"
cmake --build "${LLVM_BUILD}" --target install-distribution --parallel "${PARALLEL}"

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

# Prove the installed driver can assemble a PowerPC/Xbox .s file using its
# integrated assembler before invoking xecorelib's install script.
cat > "${LLVM_BUILD}/echocore-asm-smoke.s" <<'EOF'
    .text
    .globl echo_asm_smoke
echo_asm_smoke:
    blr
EOF
"${PREFIX}/bin/clang" \
  -c \
  "${LLVM_BUILD}/echocore-asm-smoke.s" \
  -o "${LLVM_BUILD}/echocore-asm-smoke.o"
test -s "${LLVM_BUILD}/echocore-asm-smoke.o"

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

for binary in clang lld lld-link llvm-ar llvm-dlltool synthxex; do
  test -x "${PREFIX}/bin/${binary}"
done
test -s "${PREFIX}/lib/xecorelib.a"

printf 'EchoCore component-only toolchain ready: %s\n' "${PREFIX}"
"${PREFIX}/bin/clang" --version | head -1
