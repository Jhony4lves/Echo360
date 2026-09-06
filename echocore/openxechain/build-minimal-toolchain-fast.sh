#!/usr/bin/env bash
set -euo pipefail

ROOT="${1:-/tmp/openxechain}"
PHASE="${2:-all}"
PREFIX="${OPENXECHAIN_SYSROOT:-/tmp/openxechain-sysroot}"
BUILD_TYPE="${BUILD_TYPE:-Release}"
PARALLEL="${PARALLEL:-$(nproc)}"
LLVM_PARALLEL="${LLVM_PARALLEL:-1}"
HOST_CC="${HOST_CC:-clang}"
HOST_CXX="${HOST_CXX:-clang++}"
XBOX_TARGET="${XBOX_TARGET:-ppc32-unknown-xbox360}"
USE_CCACHE="${USE_CCACHE:-1}"

LLVM_BUILD="${ROOT}/build-llvm-fast"
XECORE_BUILD="${ROOT}/build-xecorelib-fast"
SYNTH_BUILD="${ROOT}/build-synthxex-fast"
SMOKE_BUILD="${ROOT}/build-driver-smoke"
# llvm-dlltool is a real OpenXeChain dependency: xecorelib uses
# `llvm-dlltool -m xbox360` to create the xboxkrnl and XAM import libraries.
# Do not fake it with an llvm-ar symlink.
LLVM_COMPONENTS="clang;clang-resource-headers;lld;llvm-ar;llvm-dlltool"

log_resources() {
  local label="${1:-resources}"
  echo "===== EchoCore toolchain resources: ${label} ====="
  date -u '+UTC %Y-%m-%dT%H:%M:%SZ' || true
  uname -a || true
  echo "-- memory --"
  free -h || true
  echo "-- filesystem --"
  df -h / /tmp "${GITHUB_WORKSPACE:-$PWD}" 2>/dev/null || df -h || true
  echo "-- build sizes --"
  du -sh "${LLVM_BUILD}" "${PREFIX}" "${CCACHE_DIR:-${HOME}/.cache/ccache}" 2>/dev/null || true
  if command -v ccache >/dev/null 2>&1; then
    ccache --show-stats || true
  fi
  echo "===================================================="
}

require_llvm_source() {
  if [[ ! -d "${ROOT}/llvm/llvm" ]]; then
    echo "EchoCore fast toolchain: missing LLVM source directory ${ROOT}/llvm/llvm" >&2
    exit 2
  fi
}

require_finalize_sources() {
  local required
  for required in "${ROOT}/xecorelib" "${ROOT}/synthxex"; do
    if [[ ! -d "${required}" ]]; then
      echo "EchoCore fast toolchain: missing source directory ${required}" >&2
      exit 2
    fi
  done
}

configure_llvm() {
  local launcher_args=()

  rm -rf "${LLVM_BUILD}"
  mkdir -p "${LLVM_BUILD}"

  if [[ "${USE_CCACHE}" == "1" ]] && command -v ccache >/dev/null 2>&1; then
    launcher_args+=(
      -DCMAKE_C_COMPILER_LAUNCHER=ccache
      -DCMAKE_CXX_COMPILER_LAUNCHER=ccache
    )
    ccache --set-config=max_size="${CCACHE_MAXSIZE:-2G}" || true
    ccache --set-config=compression=true || true
    ccache --zero-stats || true
  fi

  log_resources "before LLVM configure"

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
    -DLLVM_INCLUDE_TESTS=OFF \
    -DLLVM_BUILD_TESTS=OFF \
    -DLLVM_INCLUDE_BENCHMARKS=OFF \
    -DLLVM_INCLUDE_EXAMPLES=OFF \
    -DLLVM_INCLUDE_DOCS=OFF \
    -DCLANG_INCLUDE_TESTS=OFF \
    -DCLANG_ENABLE_STATIC_ANALYZER=OFF \
    -DCLANG_ENABLE_ARCMT=OFF \
    -DLLVM_ENABLE_TERMINFO=OFF \
    -DLLVM_ENABLE_LIBXML2=OFF \
    -DLLVM_ENABLE_Z3_SOLVER=OFF \
    "${launcher_args[@]}" \
    -G Ninja
}

build_llvm() {
  if [[ ! -f "${LLVM_BUILD}/CMakeCache.txt" ]]; then
    echo "EchoCore fast toolchain: LLVM is not configured" >&2
    exit 3
  fi

  log_resources "before LLVM build"
  cmake --build "${LLVM_BUILD}" --target distribution --parallel "${LLVM_PARALLEL}"
  log_resources "after LLVM build"
  printf '%s\n' "${LLVM_COMPONENTS}" > "${LLVM_BUILD}/.echocore-distribution-complete"
}

install_llvm() {
  local component binary

  if [[ ! -f "${LLVM_BUILD}/.echocore-distribution-complete" ]]; then
    echo "EchoCore fast toolchain: completed LLVM build stamp is missing" >&2
    exit 4
  fi

  rm -rf "${PREFIX}"
  mkdir -p "${PREFIX}"
  log_resources "before LLVM install"

  # Install only what EchoCore needs. Keep this phase free of contract tests:
  # GitHub Actions checkpoints the installed sysroot immediately after this
  # returns so a cheap verifier can never discard a multi-hour LLVM build.
  for component in clang-resource-headers clang lld llvm-ar llvm-dlltool; do
    echo "EchoCore fast toolchain: installing LLVM component ${component}"
    cmake --install "${LLVM_BUILD}" --component "${component}"
  done

  for binary in clang lld lld-link llvm-ar llvm-dlltool; do
    if [[ ! -x "${PREFIX}/bin/${binary}" ]]; then
      echo "EchoCore fast toolchain: expected LLVM tool missing after install: ${binary}" >&2
      exit 5
    fi
  done

  cat > "${PREFIX}/bin/clang.cfg" <<EOF_CFG
--target=${XBOX_TARGET}
-Wno-main-return-type
--sysroot=<CFGDIR>/..
-fdeclspec
-mlongcall
EOF_CFG

  cat > "${PREFIX}/bin/clang++.cfg" <<EOF_CFG
--target=${XBOX_TARGET}
-Wno-main-return-type
--sysroot=<CFGDIR>/..
-fdeclspec
-mlongcall
EOF_CFG

  printf '%s\n' "${LLVM_COMPONENTS}" > "${PREFIX}/.echocore-llvm-installed"
  log_resources "after LLVM install"
}

verify_llvm_driver() {
  local binary effective_driver

  echo "===== EchoCore LLVM contract verification ====="
  if [[ ! -f "${PREFIX}/.echocore-llvm-installed" ]]; then
    echo "EchoCore LLVM verifier: install checkpoint stamp is missing: ${PREFIX}/.echocore-llvm-installed" >&2
    exit 6
  fi

  echo "-- installed binaries --"
  ls -la "${PREFIX}/bin" || true

  for binary in clang lld lld-link llvm-ar llvm-dlltool; do
    echo "==> checking ${binary}"
    if [[ ! -x "${PREFIX}/bin/${binary}" ]]; then
      echo "EchoCore LLVM verifier: missing executable ${PREFIX}/bin/${binary}" >&2
      exit 7
    fi
    ls -l "${PREFIX}/bin/${binary}"
  done

  echo "==> clang version"
  "${PREFIX}/bin/clang" --version
  echo "==> llvm-ar version"
  "${PREFIX}/bin/llvm-ar" --version
  echo "==> REAL llvm-dlltool version"
  "${PREFIX}/bin/llvm-dlltool" --version

  # The OpenXeChain fork models xbox360 as an OS in llvm::Triple. A two-part
  # spelling such as ppc32-xbox360 is normalized as arch+vendor and loses the
  # Xbox OS. Keep the explicit unknown vendor in clang.cfg and prove it here.
  echo "==> checking effective Clang target"
  effective_driver="$(${PREFIX}/bin/clang -### -c -x c /dev/null 2>&1 || true)"
  printf '%s\n' "${effective_driver}"
  case "${effective_driver}" in
    *powerpc-unknown-xbox360*|*ppc32-unknown-xbox360*) ;;
    *)
      echo "EchoCore LLVM verifier: Clang did not select the Xbox 360 target" >&2
      exit 8
      ;;
  esac

  rm -rf "${SMOKE_BUILD}"
  mkdir -p "${SMOKE_BUILD}"

  echo "==> PowerPC/Xbox assembly smoke"
  cat > "${SMOKE_BUILD}/echocore-asm-smoke.s" <<'EOF_ASM'
    .text
    .globl echo_asm_smoke
echo_asm_smoke:
    blr
EOF_ASM
  "${PREFIX}/bin/clang" \
    -c \
    "${SMOKE_BUILD}/echocore-asm-smoke.s" \
    -o "${SMOKE_BUILD}/echocore-asm-smoke.o"
  test -s "${SMOKE_BUILD}/echocore-asm-smoke.o"
  file "${SMOKE_BUILD}/echocore-asm-smoke.o" || true

  echo "==> Xbox 360 llvm-dlltool import-library smoke"
  cat > "${SMOKE_BUILD}/echocore-dlltool-smoke.def" <<'EOF_DEF'
LIBRARY echocore_smoke
EXPORTS
    EchoCoreSmoke @1
EOF_DEF
  "${PREFIX}/bin/llvm-dlltool" \
    -m xbox360 \
    -d "${SMOKE_BUILD}/echocore-dlltool-smoke.def" \
    -l "${SMOKE_BUILD}/echocore-dlltool-smoke.a"
  test -s "${SMOKE_BUILD}/echocore-dlltool-smoke.a"
  "${PREFIX}/bin/llvm-ar" t "${SMOKE_BUILD}/echocore-dlltool-smoke.a"

  echo "EchoCore LLVM contract verification: PASS"
  echo "=============================================="
}

build_xecorelib() {
  rm -rf "${XECORE_BUILD}"
  mkdir -p "${XECORE_BUILD}"
  (
    cd "${XECORE_BUILD}"
    PREFIX="${PREFIX}" \
    BINDIR="${PREFIX}/bin" \
    bash "${ROOT}/xecorelib/install.sh"
  )
  test -s "${PREFIX}/lib/xecorelib.a"
}

build_synthxex() {
  rm -rf "${SYNTH_BUILD}"
  mkdir -p "${SYNTH_BUILD}"
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
  test -x "${PREFIX}/bin/synthxex"
}

verify_complete_toolchain() {
  local binary
  for binary in clang lld lld-link llvm-ar llvm-dlltool synthxex; do
    test -x "${PREFIX}/bin/${binary}"
  done
  test -s "${PREFIX}/lib/xecorelib.a"
  printf 'EchoCore component-only toolchain ready: %s\n' "${PREFIX}"
  "${PREFIX}/bin/clang" --version | head -1
  log_resources "complete toolchain"
}

case "${PHASE}" in
  llvm-stage)
    require_llvm_source
    configure_llvm
    build_llvm
    install_llvm
    ;;
  llvm-verify)
    verify_llvm_driver
    ;;
  finalize)
    require_finalize_sources
    verify_llvm_driver
    build_xecorelib
    build_synthxex
    verify_complete_toolchain
    ;;
  all)
    require_llvm_source
    require_finalize_sources
    rm -rf "${LLVM_BUILD}" "${XECORE_BUILD}" "${SYNTH_BUILD}" "${SMOKE_BUILD}" "${PREFIX}"
    configure_llvm
    build_llvm
    install_llvm
    verify_llvm_driver
    build_xecorelib
    build_synthxex
    verify_complete_toolchain
    ;;
  *)
    echo "Usage: $0 [openxechain-root] [llvm-stage|llvm-verify|finalize|all]" >&2
    exit 64
    ;;
esac
