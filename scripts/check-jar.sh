#!/bin/bash

# check-jar.sh checks that a jar has libvsql for every OS and architecture,
# each one for the right architecture.
#
#   scripts/check-jar.sh target/vsql-1.0.0.jar

set -euo pipefail

jar="$1"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
unzip -q "$jar" 'io/github/elliotchance/vsql/native/*' -d "$tmp"
dir="$tmp/io/github/elliotchance/vsql/native"

status=0
check() {
  local path="$dir/$1" want="$2"
  if [ ! -f "$path" ]; then
    echo "missing: $1"
    status=1
    return
  fi
  local got
  got="$(file -b "$path")"
  if [[ "$got" != *"$want"* ]]; then
    echo "wrong architecture: $1 is $got"
    status=1
    return
  fi
  echo "ok: $1 ($got)"
}

check linux-x86-64/libvsql.so "x86-64"
check linux-arm64/libvsql.so "ARM aarch64"
check macos-x86-64/libvsql.dylib "x86_64"
check macos-arm64/libvsql.dylib "arm64"
check windows-x86-64/libvsql.dll "x86-64"
exit $status
