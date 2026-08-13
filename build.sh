#!/bin/sh

# Build script using sbt. Overwrites `./tmux-snapshot` if it exists.

set -eu

sbt -Dsbt.color=never nativeLink

# The output directory carries the Scala version, so it is resolved by glob
# rather than hardcoded: a Scala upgrade would otherwise leave a stale binary
# in place and fail silently. An unresolved or ambiguous glob is a hard error.
set -- target/out/native0.5/scala-*/tmux-snapshot/tmux-snapshot

if [ "$#" -gt 1 ]; then
  echo "build.sh: expected exactly one build output, found $#: $*" >&2
  exit 1
fi

if [ ! -f "$1" ]; then
  echo "build.sh: no build output at target/out/native0.5/scala-*/tmux-snapshot/tmux-snapshot" >&2
  exit 1
fi

cp "$1" ./tmux-snapshot
chmod +x ./tmux-snapshot
