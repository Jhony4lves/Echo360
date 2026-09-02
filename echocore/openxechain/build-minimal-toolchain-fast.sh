#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:-/tmp/openxechain}"
PREFIX="${OPENXECHAIN_SYSROOT:-/tmp/openxechain-sysroot}"
BUILD_TYPE="${BUILD_TYPE:-Release}"
PARALLEL="${PARALLEL:-$(nproc)}"
LLVM_PARALLEL="${LLVM_PARALLEL:-1}"
HOST_CC="${HOST_CC:-clang}"
HOST_CXX="${HOST_CXX:-clang++}"
XBOX_TARGET="${XBOX_TARGET:-ppc32-unknown-xbox360}"

LLVM_BUILD="${ROOT}/build-llvm-fast"
XECORE_BUILD="${ROOT}/build-xecorelib-fast"
SYNTH_BUILD="${ROOT}/build-synthxex-fast"

for required in "${ROOT}/llvm/llvm" "${ROOT}/xecorelib" "${ROOT}/synthxex"; do
  if [[ ! -d "${required}" ]]; then
    echo "EchoCore fast toolchain: missing source directory ${required}" >&2
    exit 2
  fi
done

# The OpenXeChain fork models xbox360 as an OS in llvm::Triple. A two-component
# spelling such as ppc32-xbox360 is normalized as arch+vendor and silently loses
# the Xbox OS, producing powerpc-unknown-none. Keep an explicit unknown vendor
# so Clang selects CrossXbox360ToolChain for every invocation.
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
  -DLLVM_DEFAULT_TARGET_TRIPLE="${XBOX_TARGET}" \
  -DLLVM_INSTALL_BINUTILS_SYMLINKS=true \
  -DLLVM_INSTALL_CCTOOLS_SYMLINKS=true \
  -DLLVM_INSTALL_TOOLCHAIN_ONLY=true \
  -DLLVM_DISTRIBUTION_COMPONENTS="${LLVM_COMPONENTS}" \
  -DLLVM_PARALLEL_LINK_JOBS=1 \
  -G Ninja

# GitHub's hosted runner killed cc1plus while building Clang Sema with -j2.
# Keep the memory-heavy LLVM/Clang phase strictly serial; xecorelib and
# SynthXEX remain independently parallel below because they are much smaller.
cmake --build "${LLVM_BUILD}" --target distribution --parallel "${LLVM_PARALLEL}"
cmake --build "${LLVM_BUILD}" --target install-distribution --parallel "${LLVM_PARALLEL}"

# llvm-dlltool and llvm-ar are the same multiplexer binary selected by argv[0].
# install-distribution installs llvm-ar but omits the alias when only the parent
# component is selected, so recreate the canonical relationship explicitly.
if [[ ! -e "${PREFIX}/bin/llvm-dlltool" ]]; then
  ln -s llvm-ar "${PREFIX}/bin/llvm-dlltool"
fi

for binary in clang lld lld-link llvm-ar llvm-dlltool; do
  if [[ ! -x "${PREFIX}/bin/${binary}" ]]; then
    echo "EchoCore fast toolchain: expected LLVM tool missing: ${binary}" >&2
    exit 3
  fi
done

cat > "${PREFIX}/bin/clang.cfg" <<EOF
--target=${XBOX_TARGET}
-Wno-main-return-type
--sysroot=<CFGDIR>/..
-fdeclspec
-mlongcall
EOF

cat > "${PREFIX}/bin/clang++.cfg" <<EOF
--target=${XBOX_TARGET}
-Wno-main-return-type
--sysroot=<CFGDIR>/..
-fdeclspec
-mlongcall
EOF

# Prove the installed driver is not silently targeting generic PowerPC before
# xecorelib gets a chance to compile hv.s. -### prints the effective cc1 triple.
EFFECTIVE_DRIVER="$(${PREFIX}/bin/clang -### -c -x c /dev/null 2>&1 || true)"
case "${EFFECTIVE_DRIVER}" in
  *powerpc-unknown-xbox360*|*ppc32-unknown-xbox360*) ;;
  *)
    echo "EchoCore fast toolchain: Clang did not select the Xbox 360 target" >&2
    printf '%s\n' "${EFFECTIVE_DRIVER}" >&2
    exit 4
    ;;
esac

# Prove the installed driver can assemble a PowerPC/Xbox .s file using the same
# config xecorelib/install.sh will inherit for hv.s.
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
