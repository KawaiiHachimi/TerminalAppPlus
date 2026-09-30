#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Shared package selection; sourcing this file has no side effects.
guest_package_family() {
    if command -v apt-get >/dev/null 2>&1 && command -v dpkg-query >/dev/null 2>&1; then
        printf 'deb\n'
    elif command -v rpm >/dev/null 2>&1 &&
         { command -v dnf >/dev/null 2>&1 || command -v yum >/dev/null 2>&1; }; then
        printf 'rpm\n'
    else
        return 1
    fi
}
guest_packages() {
    case "$1" in
        deb) printf 'python3 liblz4-1' ;;
        rpm) printf 'python3 lz4-libs' ;;
        *) return 1 ;;
    esac
    [ "$2" = 0 ] || printf ' socat'
    printf '\n'
}
guest_package_installed() {
    case "$1" in
        deb) [ "$(dpkg-query -W -f='${Status}' "$2" 2>/dev/null || true)" = 'install ok installed' ] ;;
        rpm) rpm -q "$2" >/dev/null 2>&1 ;;
        *) return 1 ;;
    esac
}
guest_install_packages() {
    family=$1; shift
    case "$family" in
        deb) apt-get update && apt-get install -y "$@" ;;
        rpm)
            if command -v dnf >/dev/null; then dnf install -y "$@"
            elif command -v yum >/dev/null; then yum install -y "$@"
            else printf 'dnf/yum is required.\n' >&2; return 1
            fi ;;
    esac
}
