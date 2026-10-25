#!/bin/sh
# Only needed for the complete source archive; recursive Git clones already have this metadata.
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
uxplay_dir="$project_root/app/src/main/cpp/third_party/UxPlay"
if [ ! -f "$uxplay_dir/lib/raop.c" ]; then
    echo "UxPlay source is missing. Use the complete source archive or clone --recurse-submodules." >&2
    exit 1
fi
if [ ! -e "$uxplay_dir/.git" ]; then
    git -C "$uxplay_dir" init --quiet
    # The Gradle patch task restores files from this index; no commit or identity is needed.
    git -C "$uxplay_dir" add --all
fi
echo "UxPlay patch baseline is ready. Configure local.properties, then run Gradle."
