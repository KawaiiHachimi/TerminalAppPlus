#!/bin/sh
# Compatibility entry point: all Plus guest capabilities now use one service.
set -eu
exec sh "$(dirname "$0")/install-guest-tools.sh" "$@"
