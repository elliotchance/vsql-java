#!/bin/bash

# update-amalgamation.sh replaces the libvsql C in internal/amalgamation with
# the C from a vsql amalgamation (vsql-VERSION-amalgamation.zip from a vsql
# release, or the folder it unzips to). With no arguments, or a version, the zip
# is downloaded from the latest (or that) vsql release:
#
#   scripts/update-amalgamation.sh
#   scripts/update-amalgamation.sh v1.2.3
#   scripts/update-amalgamation.sh vsql-v1.2.3-amalgamation.zip
#
# Only what's needed to build libvsql (the shared library) with its Makefile is
# copied, see scripts/build-libvsql.sh.

set -euo pipefail

source="${1:-latest}"
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/internal/amalgamation"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

if [ ! -e "$source" ]; then
  version="$source"
  if [ "$version" = latest ]; then
    version="$(curl -fsSL https://api.github.com/repos/elliotchance/vsql/releases/latest |
      sed -n 's/^ *"tag_name": *"\(.*\)",$/\1/p')"
  fi
  zip="vsql-$version-amalgamation.zip"
  echo "Downloading $zip"
  curl -fsSL -o "$tmp/$zip" \
    "https://github.com/elliotchance/vsql/releases/download/$version/$zip"
  source="$tmp/$zip"
fi

if [ -f "$source" ]; then
  unzip -q "$source" -d "$tmp/unzipped"
  source="$(dirname "$(find "$tmp/unzipped" -name libvsql.h | head -1)")"
fi

if [ ! -f "$source/libvsql.h" ] || [ ! -f "$source/libvsql.c" ]; then
  echo "$1 isn't a vsql amalgamation (there's no libvsql.h or libvsql.c)" >&2
  exit 1
fi

rm -rf "$out"
mkdir -p "$out"
for file in "$source"/*; do
  name="$(basename "$file")"
  case "$name" in
    # vsql, the command line program, and the example.
    vsql.c | vsql_*_amd64.c | vsql_*_arm64.c | example.c) ;;
    *) cp -R "$file" "$out/" ;;
  esac
done

# The release, for DatabaseMetaData.getDatabaseProductVersion.
if [ -n "${version:-}" ]; then
  echo "$version" > "$root/src/main/resources/io/github/elliotchance/vsql/libvsql-version"
fi

echo "Updated $out from ${1:-the latest release}"
