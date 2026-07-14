#!/usr/bin/env bash
# Runs the Linux player perf harness (LinuxPlayerPerfHarness.kt) with frame
# timing output enabled — the measurement tool for the render-path work.
#
#   scripts/linux-player-perf.sh <file-or-url> [--fullscreen] [-- <extra jvm env>]
#
# Sets the same NixOS runtime libs as run-source.sh (Skiko needs libGL,
# the bridge dlopens libmpv). On non-Nix systems those libs must already
# be on the linker path; the nix-shell wrapper is skipped then.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

[ $# -ge 1 ] || { echo "usage: $0 <file-or-url> [--fullscreen]" >&2; exit 2; }

export NUVIO_LINUX_PERF=1

if command -v nix >/dev/null 2>&1; then
  LIBS=$(nix build --print-out-paths --no-link \
    nixpkgs#libglvnd nixpkgs#xorg.libX11 nixpkgs#fontconfig.lib nixpkgs#stdenv.cc.cc.lib nixpkgs#mpv-unwrapped \
    | sed 's|$|/lib|' | tr '\n' ':')
  export LD_LIBRARY_PATH="${LIBS}/run/opengl-driver/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  exec nix-shell -p jdk21 gcc --run "./gradlew :composeApp:runLinuxPlayerPerfHarness --console=plain -PharnessArgs=\"$*\""
else
  exec ./gradlew :composeApp:runLinuxPlayerPerfHarness --console=plain -PharnessArgs="$*"
fi
