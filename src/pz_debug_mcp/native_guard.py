"""Recover only this native controller's extra suspensions and software breakpoint bytes."""

import ctypes
from ctypes import wintypes as w
import threading


class ThreadEntry(ctypes.Structure):
    _fields_ = [('size', w.DWORD), ('usage', w.DWORD), ('tid', w.DWORD), ('pid', w.DWORD),
                ('base', w.LONG), ('delta', w.LONG), ('flags', w.DWORD)]


class NativeGuard:
    def __init__(self, pid):
        self.pid = pid
        self.lock = threading.RLock()
        self.breakpoints = {}
        self.baseline = {}
        self.result = None
        self.watcher = None
        self.k = k = ctypes.WinDLL('kernel32', use_last_error=True)
        signatures = {
            'OpenProcess': ([w.DWORD, w.BOOL, w.DWORD], w.HANDLE),
            'OpenThread': ([w.DWORD, w.BOOL, w.DWORD], w.HANDLE),
            'CloseHandle': ([w.HANDLE], w.BOOL),
            'SuspendThread': ([w.HANDLE], w.DWORD),
            'ResumeThread': ([w.HANDLE], w.DWORD),
            'CheckRemoteDebuggerPresent': ([w.HANDLE, ctypes.POINTER(w.BOOL)], w.BOOL),
            'CreateToolhelp32Snapshot': ([w.DWORD, w.DWORD], w.HANDLE),
            'Thread32First': ([w.HANDLE, ctypes.POINTER(ThreadEntry)], w.BOOL),
            'Thread32Next': ([w.HANDLE, ctypes.POINTER(ThreadEntry)], w.BOOL),
            'GetThreadTimes': ([w.HANDLE] + [ctypes.POINTER(w.FILETIME)] * 4, w.BOOL),
            'GetExitCodeProcess': ([w.HANDLE, ctypes.POINTER(w.DWORD)], w.BOOL),
            'ReadProcessMemory': ([w.HANDLE, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)], w.BOOL),
            'WriteProcessMemory': ([w.HANDLE, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)], w.BOOL),
            'VirtualProtectEx': ([w.HANDLE, ctypes.c_void_p, ctypes.c_size_t, w.DWORD, ctypes.POINTER(w.DWORD)], w.BOOL),
            'FlushInstructionCache': ([w.HANDLE, ctypes.c_void_p, ctypes.c_size_t], w.BOOL),
        }
        for name, (args, result) in signatures.items():
            getattr(k, name).argtypes = args
            getattr(k, name).restype = result
        self.handle = k.OpenProcess(0x0438, False, pid)
        if not self.handle:
            raise OSError(ctypes.get_last_error(), 'Cannot prepare native cleanup guard')
        try:
            for tid in self._threads():
                thread = k.OpenThread(0x0802, False, tid)
                if not thread:
                    continue
                try:
                    stamp = self._stamp(thread)
                    count = k.SuspendThread(thread)
                    if count != 0xffffffff:
                        k.ResumeThread(thread)
                        self.baseline[tid] = (stamp, count)
                finally:
                    k.CloseHandle(thread)
        except BaseException:
            k.CloseHandle(self.handle)
            self.handle = None
            raise

    def _threads(self):
        snapshot = self.k.CreateToolhelp32Snapshot(4, 0)
        if snapshot == ctypes.c_void_p(-1).value:
            raise OSError(ctypes.get_last_error(), 'Thread snapshot failed')
        try:
            entry = ThreadEntry()
            entry.size = ctypes.sizeof(entry)
            valid = self.k.Thread32First(snapshot, ctypes.byref(entry))
            while valid:
                if entry.pid == self.pid:
                    yield entry.tid
                valid = self.k.Thread32Next(snapshot, ctypes.byref(entry))
        finally:
            self.k.CloseHandle(snapshot)

    def _stamp(self, thread):
        times = [w.FILETIME() for _ in range(4)]
        if not self.k.GetThreadTimes(thread, *(ctypes.byref(v) for v in times)):
            raise OSError(ctypes.get_last_error(), 'Thread identity unavailable')
        return (times[0].dwHighDateTime << 32) | times[0].dwLowDateTime

    def update(self, breakpoints):
        with self.lock:
            self.breakpoints = {int(v['address'], 16): bytes.fromhex(v['original_hex']) for v in breakpoints}

    def watch(self, process):
        def cleanup():
            process.wait()
            self.recover()
        self.watcher = threading.Thread(target=cleanup, daemon=True, name='Native-debug-cleanup')
        self.watcher.start()

    def recover(self):
        with self.lock:
            if self.result is not None:
                return self.result
            result = {'resumed_threads': 0, 'restored_breakpoints': 0, 'errors': []}
            try:
                exit_code = w.DWORD()
                if not self.k.GetExitCodeProcess(self.handle, ctypes.byref(exit_code)) or exit_code.value != 259:
                    return result
                attached = w.BOOL()
                if not self.k.CheckRemoteDebuggerPresent(self.handle, ctypes.byref(attached)) or attached.value:
                    result['errors'].append('Another debugger still owns the process; no recovery applied')
                    return result
                for address, original in self.breakpoints.items():
                    current = ctypes.create_string_buffer(len(original))
                    read = ctypes.c_size_t()
                    if not self.k.ReadProcessMemory(self.handle, address, current, len(original), ctypes.byref(read)):
                        result['errors'].append(f'Cannot inspect breakpoint {address:#x}')
                        continue
                    if current.raw == original:
                        continue
                    if current.raw[:1] != b'\xcc' or current.raw[1:] != original[1:]:
                        result['errors'].append(f'Breakpoint bytes changed at {address:#x}; preserved')
                        continue
                    protection = w.DWORD()
                    if not self.k.VirtualProtectEx(self.handle, address, 1, 0x40, ctypes.byref(protection)):
                        result['errors'].append(f'Cannot restore breakpoint {address:#x}')
                        continue
                    try:
                        written = ctypes.c_size_t()
                        byte = ctypes.create_string_buffer(original[:1])
                        if self.k.WriteProcessMemory(self.handle, address, byte, 1, ctypes.byref(written)):
                            self.k.FlushInstructionCache(self.handle, address, 1)
                            result['restored_breakpoints'] += 1
                        else:
                            result['errors'].append(f'Breakpoint restore failed at {address:#x}')
                    finally:
                        ignored = w.DWORD()
                        self.k.VirtualProtectEx(self.handle, address, 1, protection.value, ctypes.byref(ignored))
                for tid in self._threads():
                    expected = self.baseline.get(tid)
                    if expected is None:
                        continue
                    thread = self.k.OpenThread(0x0802, False, tid)
                    if not thread:
                        continue
                    try:
                        if self._stamp(thread) != expected[0]:
                            continue
                        count = self.k.SuspendThread(thread)
                        if count == 0xffffffff:
                            continue
                        self.k.ResumeThread(thread)
                        if count == expected[1] + 1:
                            self.k.ResumeThread(thread)
                            result['resumed_threads'] += 1
                        elif count > expected[1] + 1:
                            result['errors'].append(f'Thread {tid} has additional suspensions; preserved')
                    except OSError:
                        pass
                    finally:
                        self.k.CloseHandle(thread)
            except OSError as error:
                result['errors'].append(str(error))
            finally:
                self.k.CloseHandle(self.handle)
                self.handle = None
                self.result = result
            return result

    def join(self):
        if self.watcher:
            self.watcher.join(timeout=5)
        else:
            self.recover()
