#!/usr/bin/env bash
set -euo pipefail

image="${1:?usage: export-rootfs.sh <image> <output.tar>}"
out="${2:?usage: export-rootfs.sh <image> <output.tar>}"

tmp="$(mktemp -d)"
cid="$(docker create --platform linux/arm64 "$image")"
trap 'docker rm -f "$cid" >/dev/null; rm -rf "$tmp"' EXIT

docker export "$cid" | tar -x -C "$tmp"
tar --hard-dereference -cf "$out" -C "$tmp" .
