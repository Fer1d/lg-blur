#!/bin/bash
# Host-only reproduction into a NEW caller-selected output directory.
set -euo pipefail
if [ "$#" -ne 1 ]; then printf 'Usage: bash reproduce.sh NEW_OUTPUT_DIRECTORY\n' >&2; exit 2; fi
scripts="$(dirname "$(realpath "$0")")"
base=${LG_BLUR_EXTERNAL_ROOT:?set LG_BLUR_EXTERNAL_ROOT}
mkdir -- "$1"
out="$(realpath "$1")"
python3 -I "$scripts/patch.py" "$base/device-inputs/libsurfaceflinger.so" \
  "$out/libsurfaceflinger.ceil-half.REVIEW-ONLY.so" "$out/manifest.json"
python3 -I "$scripts/test.py" "$base/device-inputs/libsurfaceflinger.so" \
  "$out/libsurfaceflinger.ceil-half.REVIEW-ONLY.so" "$out/tests"
python3 -I "$scripts/scope_audit.py" "$base/device-inputs/libsurfaceflinger.so" \
  "$base/bridge-GUC6ID60/input/libgui.so" \
  "$base/framework-dex-J10KU40m/blur-and-surfacecontrol.dexdump.txt" "$out/scope"
sha256sum "$out/libsurfaceflinger.ceil-half.REVIEW-ONLY.so" "$out/manifest.json"
