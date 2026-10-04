import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import unittest
from pz_debug_mcp.debug_process import DebugProcess
from pz_debug_mcp.bridge import BridgeError
from pz_debug_mcp.native_guard import NativeGuard

ROOT=Path(__file__).resolve().parents[1]
BIN=ROOT/"bin"
JAVA=os.environ.get("PZDEBUG_JDI_JAVA") or shutil.which("java")


class HelperLifecycleTests(unittest.TestCase):
    def test_structured_errors_and_eof(self):
        helper=DebugProcess([JAVA,"--add-modules","jdk.jdi","-jar",str(BIN/"java-debugger.jar")],5)
        try:
            with self.assertRaises(BridgeError) as err:helper.request("threads")
            self.assertEqual(err.exception.code,"DEBUGGER_DISCONNECTED")
            self.assertFalse(helper.request("status")["connected"])
        finally:helper.close()
        self.assertEqual(helper.status()["helper_state"],"closed")
        self.assertIsNone(helper.status()["pending"])

    def test_helper_exit_is_reported(self):
        helper=DebugProcess([sys.executable,"-c","import sys; sys.stdin.readline(); sys.exit(7)"],3)
        try:
            with self.assertRaises(BridgeError) as err:helper.request("status")
            self.assertEqual(err.exception.code,"DEBUGGER_EXITED")
        finally:helper.close()

    def test_timeout_requires_cleanup_before_reconnect(self):
        helper=DebugProcess([sys.executable,"-c","import sys,time; sys.stdin.readline(); time.sleep(10)"],.1)
        try:
            with self.assertRaises(BridgeError) as err:helper.request("status")
            self.assertEqual(err.exception.code,"TIMEOUT")
            with self.assertRaises(BridgeError) as err:helper.request("status")
            self.assertEqual(err.exception.code,"INDETERMINATE")
        finally:helper.close()
        self.assertIsNone(helper.status()["pending"])


@unittest.skipUnless((BIN/"java-debugger.jar").exists(),"Build debugger helpers")
class JavaDebugTests(unittest.TestCase):
    def setUp(self):
        with socket.socket() as sock:
            sock.bind(("127.0.0.1",0));port=sock.getsockname()[1]
        self.target=subprocess.Popen([JAVA,f"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:{port}",
            "-cp",str(ROOT/"build/debugger/fixtures"),"fixture.DebugSubject"],stdout=subprocess.PIPE,text=True)
        self.addCleanup(self.finish)
        self.assertIn("Listening",self.target.stdout.readline())
        self.assertEqual(self.target.stdout.readline().strip(),"READY")
        self.helper=DebugProcess([JAVA,"--add-modules","jdk.jdi","-jar",str(BIN/"java-debugger.jar")])
        self.helper.request("connect",{"port":port})

    def finish(self):
        if hasattr(self,"helper"):self.helper.close()
        if self.target.poll() is None:self.target.terminate()
        self.target.communicate(timeout=5)

    def wait_stop(self):
        for _ in range(150):
            state=self.helper.request("status")
            if state["stopped_threads"]:return state["stopped_threads"][0]
            time.sleep(.01)
        self.fail("Java breakpoint/step did not stop")

    def test_breakpoint_locals_step_and_current_bytecode(self):
        bp=self.helper.request("breakpoint_add",{"class_name":"fixture.DebugSubject","line":7})
        tid=self.wait_stop()
        frames=self.helper.request("frames",{"thread_id":tid})["frames"]
        values={v["name"]:v["value"] for v in frames[0]["locals"]}
        self.assertEqual(values["answer"],values["input"]*2+7)
        body=self.helper.request("bytecode",{"class_name":"fixture.DebugSubject","method":"calculate"})
        self.assertEqual(body["methods"][0]["source"],"current_vm_method_bytecodes")
        self.helper.request("breakpoint_remove",{"breakpoint_id":bp["breakpoint_id"]})
        self.helper.request("step",{"thread_id":tid,"depth":"out"})
        tid=self.wait_stop()
        self.assertEqual(self.helper.request("frames",{"thread_id":tid})["frames"][0]["method"],"main")
        self.helper.request("disconnect")
        self.assertIsNone(self.target.poll())

    def test_disconnect_resumes_stopped_vm(self):
        self.helper.request("breakpoint_add",{"class_name":"fixture.DebugSubject","method":"calculate"})
        self.wait_stop()
        self.helper.close()
        self.assertIsNone(self.target.poll())

    def test_step_into_and_over(self):
        bp=self.helper.request("breakpoint_add",{"class_name":"fixture.DebugSubject","line":5})
        tid=self.wait_stop()
        self.helper.request("breakpoint_remove",{"breakpoint_id":bp["breakpoint_id"]})
        self.helper.request("step",{"thread_id":tid,"depth":"into"})
        tid=self.wait_stop()
        self.assertEqual(self.helper.request("frames",{"thread_id":tid})["frames"][0]["line"],6)
        self.helper.request("step",{"thread_id":tid,"depth":"over"})
        tid=self.wait_stop()
        self.assertEqual(self.helper.request("frames",{"thread_id":tid})["frames"][0]["line"],7)
        self.helper.request("resume",{"thread_id":tid})


@unittest.skipUnless(os.name=="nt" and (BIN/"native-debugger.exe").exists(),"Windows native helpers required")
class NativeDebugTests(unittest.TestCase):
    def setUp(self):
        self.target=subprocess.Popen([str(ROOT/"build/debugger/native-fixture.exe")],stdout=subprocess.PIPE,text=True)
        self.addCleanup(self.finish)
        pid=int(self.target.stdout.readline())
        self.guard=NativeGuard(pid)
        self.helper=DebugProcess([str(BIN/"native-debugger.exe")])
        self.helper.request("attach",{"pid":pid})
        self.guard.watch(self.helper.process)

    def request(self,action,arguments=None):
        result=self.helper.request(action,arguments)
        if 'breakpoints' in result:self.guard.update(result['breakpoints'])
        return result

    def finish(self):
        if hasattr(self,"helper"):self.helper.close()
        if hasattr(self,"guard"):self.guard.join()
        if self.target.poll() is None:self.target.terminate()
        self.target.communicate(timeout=5)

    def wait_stop(self):
        for _ in range(100):
            state=self.helper.request("status")
            if state["paused"]:return state
            time.sleep(.01)
        self.fail("Native target did not stop")

    def test_native_breakpoint_stack_locals_memory_and_step(self):
        bp=self.helper.request("breakpoint_add",{"symbol":"native_fixture!native_calculate"})
        self.helper.request("resume");state=self.wait_stop()
        self.assertEqual(state["last_event"]["type"],1)
        frames=self.helper.request("stack",{"limit":4})["frames"]
        self.assertIn("native_calculate",frames[0]["symbol"])
        locals=self.helper.request("locals",{"frame":0})["locals"]
        self.assertIn("input",[v["name"] for v in locals])
        memory=self.helper.request("memory",{"address":bp["address"],"bytes":8})
        self.assertEqual(len(memory["hex"]),16)
        before=frames[0]["instruction"]
        self.helper.request("step",{"depth":"into"});self.wait_stop()
        after=self.helper.request("stack",{"limit":1})["frames"][0]["instruction"]
        self.assertNotEqual(before,after)
        self.helper.request("breakpoint_remove",{"breakpoint_id":bp["breakpoint_id"]})
        self.helper.request("step",{"depth":"out"});self.wait_stop()
        self.assertIn("main",self.helper.request("stack",{"limit":1})["frames"][0]["symbol"])
        self.helper.request("detach");self.assertIsNone(self.target.poll())

    def test_native_helper_close_resumes_target(self):
        self.helper.request("breakpoint_add",{"symbol":"native_fixture!native_calculate"})
        self.helper.request("resume");self.wait_stop()
        self.helper.close();self.assertIsNone(self.target.poll())

    def test_native_controller_crash_while_running_keeps_progress(self):
        symbol=self.helper.request("symbols",{"pattern":"native_fixture!progress_counter"})['symbols'][0]['address']
        self.request("breakpoint_add",{"symbol":"native_fixture!native_calculate"})
        self.helper.request("resume");self.wait_stop()
        self.request("breakpoint_remove",{"breakpoint_id":0})
        self.request("resume")
        time.sleep(.1)
        import ctypes
        from ctypes import wintypes as w
        def counter():
            k=self.guard.k
            process=k.OpenProcess(0x410,False,self.target.pid)
            try:
                value=w.DWORD();size=ctypes.c_size_t()
                self.assertTrue(k.ReadProcessMemory(process,int(symbol,16),ctypes.byref(value),4,ctypes.byref(size)))
                return value.value
            finally:k.CloseHandle(process)
        before=counter()
        self.helper.process.kill()
        self.helper.process.wait(timeout=3)
        self.guard.join()
        time.sleep(.5)
        self.assertIsNone(self.target.poll())
        self.assertGreater(counter(),before,'Controller crash left target suspended')
        self.assertFalse(self.guard.result['errors'])
        with self.assertRaises(BridgeError):self.helper.request("status")
