#!/bin/bash

# build-libvsql.sh builds libvsql (the shared library) for this machine from
# internal/amalgamation, with the amalgamation's Makefile, into
# build/native/OS-ARCH (such as build/native/linux-x86-64/libvsql.so). The jar
# includes every library in build/native (see pom.xml).
#
#   scripts/build-libvsql.sh
#
# It needs make and a C compiler: cc on Linux and macOS, and MinGW-w64 gcc on
# Windows (in an MSYS2 shell). The release builds the libraries so that they
# work on older systems than the one they're built on: Linux in a
# manylinux_2_28 container (glibc 2.28), and macOS for macOS 11.

set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"

case "$(uname -s)" in
  Linux) os=linux ext=so ;;
  Darwin) os=macos ext=dylib ;;
  MINGW* | MSYS* | CYGWIN*) os=windows ext=dll ;;
  *) echo "vsql isn't supported on $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) arch=x86-64 ;;
  aarch64 | arm64) arch=arm64 ;;
  *) echo "vsql isn't supported on $(uname -m)" >&2; exit 1 ;;
esac

# V generates C that relies on wrapping signed overflow (the Makefile adds
# -fwrapv). The C that V generates for Linux passes a pthread_t as a void*,
# which is an error from GCC 14 unless -Wno-int-conversion is given.
cflags="${CFLAGS:--O2} -Wno-int-conversion"
export MACOSX_DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET:-11.0}"

# The Makefile builds into bin/ next to it, so it's built in a copy.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
cp -R "$root/internal/amalgamation/." "$tmp/"
make -C "$tmp" libvsql CFLAGS="$cflags"

out="$root/build/native/$os-$arch"
mkdir -p "$out"
cp "$tmp/bin/libvsql.$ext" "$out/"
echo "Built $out/libvsql.$ext"
