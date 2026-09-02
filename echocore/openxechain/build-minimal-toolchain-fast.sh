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

# Only parent components with real install targets belong in an LLVM
# distribution. lld-link is installed as a symlink of lld. llvm-dlltool is
# implemented by llvm-ar and normally installed as an alias, but that alias is
# not materialized by install-distribution when only the parent llvm-ar
# component is selected. Keep llvm-ar as the distribution component and create
# the canonical argv[0]-dispatch alias explicitly after installation.
LLVM_COMPONENTS="clang;clang-resource-headers;lld;llvm-ar"

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

# llvm-dlltool and llvm-ar are the same multiplexer binary selected by argv[0].
# The normal full LLVM install creates this symlink for us. The restricted
# distribution install above installs llvm-ar but omits the alias, so recreate
# exactly that upstream relationship instead of compiling a second tool.
if [[ ! -e "${PREFIX}/bin/llvm-dlltool" ]]; then
  ln -s llvm-ar "${PREFIX}/bin/llvm-dlltool"
fi

# Parent installs must have materialized every executable EchoCore/xecorelib
# needs. Fail here, before xecorelib, if LLVM's install semantics change again.
for binary in clang lld lld-link llvm-ar llvm-dlltool; do
  if [[ ! -x "${PREFIX}/bin/${binary}" ]]; then
    echo "EchoCore fast toolchain: expected LLVM tool missing: ${binary}" >&2
    exit 3
  fi
done

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

# Prove the alias dispatches to llvm-dlltool before asking xecorelib to use it.
"${PREFIX}/bin/llvm-dlltool" --version >/dev/null

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
