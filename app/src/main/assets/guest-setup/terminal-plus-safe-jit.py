#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Opt-in LLVM CPU-name workaround. Inspired by U-AVF (Apache-2.0).
Usage: terminal-plus-safe-jit.py [--probe] PROGRAM [ARGS...]
Per-process wrapper plus explicit, reversible glibc global preload installation.
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


def _affected(machine=None, hwcap=None, cpu_name=None):
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
                llvm = ctypes.CDLL(library, mode=ctypes.RTLD_GLOBAL)
                llvm.LLVMGetHostCPUName.restype = ctypes.c_void_p
                llvm.LLVMDisposeMessage.argtypes = [ctypes.c_void_p]
                # MIDR-based names vary across big/little cores. Inspect all permitted CPUs.
                affinity = os.sched_getaffinity(0) if hasattr(os, 'sched_getaffinity') else None
                try:
                    for cpu in sorted(affinity) if affinity else [None]:
                        if cpu is not None:
                            os.sched_setaffinity(0, {cpu})
                        pointer = llvm.LLVMGetHostCPUName()
                        if not pointer:
                            continue
                        try:
                            name = ctypes.string_at(pointer).decode('ascii')
                        finally:
                            llvm.LLVMDisposeMessage(pointer)
                        if name == 'cortex-x925':
                            return True
                finally:
                    if affinity is not None:
                        os.sched_setaffinity(0, affinity)
            except (OSError, AttributeError, UnicodeError):
                continue
        return False
    return cpu_name == 'cortex-x925'


def affected(machine=None, hwcap=None, cpu_name=None):
    old = os.environ.get('TERMINAL_PLUS_SAFE_JIT_BYPASS')
    os.environ['TERMINAL_PLUS_SAFE_JIT_BYPASS'] = '1'
    try:
        return _affected(machine, hwcap, cpu_name)
    finally:
        if old is None:
            os.environ.pop('TERMINAL_PLUS_SAFE_JIT_BYPASS', None)
        else:
            os.environ['TERMINAL_PLUS_SAFE_JIT_BYPASS'] = old


PRELOAD_SOURCE = r"""
#define _GNU_SOURCE
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <sys/auxv.h>
struct llvm_string_ref { const char *data; size_t size; };
static void *lookup(const char *symbol, void *caller) {
    void *result = dlsym(RTLD_NEXT, symbol);
    /* Mesa may load LLVM in a local dlopen scope. Search the caller's dependencies too. */
    if (!result) {
        Dl_info info;
        if (dladdr(caller, &info) && info.dli_fname) {
            void *handle = dlopen(info.dli_fname, RTLD_LAZY | RTLD_NOLOAD);
            if (handle) { result = dlsym(handle, symbol); dlclose(handle); }
        }
    }
    return result;
}
static int compatible(void) {
#if defined(__aarch64__)
    const char *bypass = getenv("TERMINAL_PLUS_SAFE_JIT_BYPASS");
    return !(getauxval(AT_HWCAP) & (1UL << 22)) && !(bypass && !strcmp(bypass, "1"));
#else
    return 0;
#endif
}
struct llvm_string_ref plus_cpu_name(void) __asm__("_ZN4llvm3sys14getHostCPUNameEv");
struct llvm_string_ref plus_cpu_name(void) {
    /* One affected Guest can expose X925, X4 and A720 on different vCPUs.
       Keep every JIT thread on the same conservative target, irrespective of migration. */
    if (compatible()) return (struct llvm_string_ref){ "generic", 7 };
    typedef struct llvm_string_ref (*query)(void);
    query original = (query)lookup("_ZN4llvm3sys14getHostCPUNameEv", __builtin_extract_return_addr(__builtin_return_address(0)));
    struct llvm_string_ref name = original ? original() : (struct llvm_string_ref){ "generic", 7 };
    return name;
}
char *LLVMGetHostCPUName(void) {
    if (compatible()) return strdup("generic");
    typedef char *(*query)(void);
    query original = (query)lookup("LLVMGetHostCPUName", __builtin_extract_return_addr(__builtin_return_address(0)));
    char *name = original ? original() : strdup("generic");
    return name;
}
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
        subprocess.run([compiler, '-shared', '-fPIC', '-O2', '-x', 'c', '-', '-o', str(output), '-ldl'],
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
    state = Path(home or Path.home()) / '.local/state/terminal-plus/jit-nosve-v3'
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


GLOBAL_LIBRARY = Path('/usr/local/lib/terminal-plus/jit-global/llvm-generic.so')
GLOBAL_PRELOAD = Path('/etc/ld.so.preload')


def preload_text(path):
    if path.is_symlink():
        raise RuntimeError('Refusing to replace a symlink: ' + str(path))
    return path.read_text() if path.exists() else ''


def global_status(path=GLOBAL_PRELOAD, library=GLOBAL_LIBRARY):
    text = preload_text(path)
    entry = str(library)
    enabled = any(line.strip() == entry for line in text.splitlines())
    return {'enabled': enabled, 'library': entry, 'library_exists': library.is_file(),
            'loaded': entry in Path('/proc/self/maps').read_text() if Path('/proc/self/maps').exists() else False}


def write_preload(path, text):
    import tempfile
    # Keep other preload entries and their comments, ownership and permissions.
    old = path.stat() if path.exists() else None
    with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, delete=False) as stream:
        temp = Path(stream.name)
        try:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
            os.chmod(temp, (old.st_mode & 0o777) if old else 0o644)
            if old:
                os.chown(temp, old.st_uid, old.st_gid)
            os.replace(temp, path)
        finally:
            temp.unlink(missing_ok=True)


def validate_global_library(library):
    # Test the loader before activating anything system-wide.
    env = dict(os.environ, LD_PRELOAD=str(library) +
               (':' + os.environ['LD_PRELOAD'] if os.environ.get('LD_PRELOAD') else ''))
    subprocess.run(['/bin/sh', '-c', 'true'], env=env, check=True, timeout=10, capture_output=True)
    subprocess.run([sys.executable, '-c',
        'import ctypes,sys; lib=ctypes.CDLL(sys.argv[1]); '
        'getattr(lib,"_ZN4llvm3sys14getHostCPUNameEv"); getattr(lib,"LLVMGetHostCPUName")', str(library)],
        env=env, check=True, timeout=10, capture_output=True)


def configure_global(enable, path=GLOBAL_PRELOAD, library=GLOBAL_LIBRARY):
    if os.geteuid() != 0:
        raise RuntimeError('Global configuration requires root (use sudo).')
    text = preload_text(path)
    entry = str(library)
    # Own one complete line only; never rewrite entries maintained by somebody else.
    tokens = [token for line in text.splitlines() for token in line.split('#', 1)[0].replace(':', ' ').split()]
    if entry in tokens and not any(line.strip() == entry for line in text.splitlines()):
        raise RuntimeError('Library appears in a non-managed preload line; adjust that entry manually.')
    if not enable:
        updated = ''.join(line for line in text.splitlines(keepends=True) if line.strip() != entry)
        if updated != text:
            if preload_text(path) != text:
                raise RuntimeError('Preload configuration changed; rerun the command.')
            write_preload(path, updated)
        return global_status(path, library)
    if platform.system() != 'Linux' or platform.libc_ver()[0] != 'glibc':
        raise RuntimeError('Global preload currently supports Linux/glibc guests only.')
    if not affected():
        raise RuntimeError('No affected ARM64 / no-SVE / X925 LLVM combination detected; configuration unchanged.')
    import tempfile
    if library.is_symlink() or library.with_suffix('.c').is_symlink():
        raise RuntimeError('Refusing a symlink in the global library path.')
    library.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
    for directory in (library.parent, *library.parent.parents):
        info = directory.lstat()
        if directory.is_symlink() or info.st_uid != 0 or info.st_mode & 0o022:
            raise RuntimeError('Global library directory must be root-owned and not writable by others: ' + str(directory))
    source = library.with_suffix('.c')
    if not (library.is_file() and source.is_file() and source.read_text() == PRELOAD_SOURCE):
        with tempfile.TemporaryDirectory(dir=library.parent) as temporary:
            candidate = preload(Path(temporary))
            validate_global_library(candidate)
            os.chmod(candidate, 0o755)
            os.replace(candidate, library)
            os.replace(candidate.with_suffix('.c'), source)
    else:
        validate_global_library(library)
    if shutil.which('restorecon'):
        subprocess.run(['restorecon', '-R', str(library.parent)], check=True)
    if not any(line.strip() == entry for line in text.splitlines()):
        updated = text + ('' if not text or text.endswith('\n') else '\n') + entry + '\n'
        if preload_text(path) != text:
            raise RuntimeError('Preload configuration changed; rerun the command.')
        write_preload(path, updated)
    return global_status(path, library)


def main():
    args = sys.argv[1:]
    if args in (['--install-global'], ['--remove-global'], ['--status']):
        status = global_status() if args == ['--status'] else configure_global(args == ['--install-global'])
        print(json.dumps(status))
        if args != ['--status']:
            print('Applies to new processes. Log out and back in or reboot the Guest to refresh the desktop.')
        return
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
        raise SystemExit('Usage: terminal-plus-safe-jit.py [--probe] PROGRAM [ARGS...] | --install-global | --remove-global | --status')
    os.execvpe(args[0], args, environment() if needed else dict(os.environ))


if __name__ == '__main__':
    main()
