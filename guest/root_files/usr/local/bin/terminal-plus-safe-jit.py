#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Opt-in LLVM CPU-name workaround. Inspired by U-AVF (Apache-2.0).
Usage: terminal-plus-safe-jit.py [--probe] PROGRAM [ARGS...]
Only changes the launched process environment; never edits /proc or desktop files.
"""
import ctypes
import ctypes.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys


def affected(machine=None, hwcap=None, cpu_name=None):
    if (machine or platform.machine()) != 'aarch64':
        return False
    if hwcap is None:
        libc = ctypes.CDLL(None)
        libc.getauxval.argtypes = [ctypes.c_ulong]
        libc.getauxval.restype = ctypes.c_ulong
        hwcap = libc.getauxval(16)  # AT_HWCAP, HWCAP_SVE
    if hwcap & (1 << 22):
        return False
    if cpu_name is None:
        for major in range(24, 18, -1):
            library = ctypes.util.find_library('LLVM-' + str(major))
            library = library or f'libLLVM.so.{major}.1'
            try:
                llvm = ctypes.CDLL(library)
                llvm.LLVMGetHostCPUName.restype = ctypes.c_void_p
                llvm.LLVMDisposeMessage.argtypes = [ctypes.c_void_p]
                pointer = llvm.LLVMGetHostCPUName()
                if not pointer:
                    continue
                try:
                    name = ctypes.string_at(pointer).decode('ascii')
                finally:
                    llvm.LLVMDisposeMessage(pointer)
                if name == 'cortex-x925':
                    return True
            except (OSError, AttributeError, UnicodeError):
                continue
        return False
    return cpu_name == 'cortex-x925'


PRELOAD_SOURCE = r"""
#define _GNU_SOURCE
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
/* Linux LLVM StringRef return ABI. Only the launched ARM64 process is affected. */
struct llvm_string_ref { const char *data; size_t size; };
struct llvm_string_ref plus_cpu_name(void) __asm__("_ZN4llvm3sys14getHostCPUNameEv");
struct llvm_string_ref plus_cpu_name(void) {
    return (struct llvm_string_ref){ "generic", 7 };
}
char *LLVMGetHostCPUName(void) { return strdup("generic"); }
"""


def preload(state):
    library = state / 'llvm-generic.so'
    source = state / 'llvm-generic.c'
    if library.is_file() and source.is_file() and source.read_text() == PRELOAD_SOURCE:
        return library
    compiler = shutil.which('cc') or shutil.which('gcc')
    if not compiler:
        raise RuntimeError('safe-JIT needs a Guest C compiler once: install gcc and libc development headers')
    import tempfile
    with tempfile.TemporaryDirectory(dir=state) as directory:
        output = Path(directory) / library.name
        subprocess.run([compiler, '-shared', '-fPIC', '-O2', '-x', 'c', '-', '-o', str(output)],
                       input=PRELOAD_SOURCE, text=True, check=True, timeout=30, capture_output=True)
        os.replace(output, library)
    source.write_text(PRELOAD_SOURCE)
    return library


def environment(env=None, cpuinfo=None, home=None):
    env = dict(os.environ if env is None else env)
    info = Path('/proc/cpuinfo').read_text() if cpuinfo is None else cpuinfo
    # Retain kernel-advertised features, omit MIDR fields that select X925/SVE.
    features = re.search(r'^Features\s*:\s*(.*)$', info, re.M)
    if not features:
        raise RuntimeError('No kernel CPU features; refusing to guess')
    state = Path(home or Path.home()) / '.local/state/terminal-plus/jit-nosve-v2'
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = state / 'cpuinfo'
    content = 'processor : 0\nCPU architecture : 8\nFeatures : ' + features[1] + '\n'
    # Unique temporary file; no shared fixed name or user-cache deletion.
    import tempfile
    with tempfile.NamedTemporaryFile(mode='w', dir=state, delete=False) as f:
        f.write(content)
        temp = f.name
    os.replace(temp, path)
    env['LLVM_CPUINFO'] = str(path)
    library = preload(state)
    env['LD_PRELOAD'] = str(library) + (':' + env['LD_PRELOAD'] if env.get('LD_PRELOAD') else '')
    env['MESA_SHADER_CACHE_DIR'] = str(state / 'shader-cache')
    return env


def main():
    args = sys.argv[1:]
    probe = bool(args and args[0] == '--probe')
    if probe:
        args.pop(0)
    needed = affected()
    if probe:
        print(json.dumps({'required': needed, 'environment':
              {'strategy': 'LLVM CPU-name preload', 'compiler_available': bool(shutil.which('cc') or shutil.which('gcc'))} if needed else {}}))
        return
    if args and args[0] == '--':
        args.pop(0)
    if not args:
        raise SystemExit('Usage: terminal-plus-safe-jit.py [--probe] PROGRAM [ARGS...]')
    os.execvpe(args[0], args, environment() if needed else dict(os.environ))


if __name__ == '__main__':
    main()
