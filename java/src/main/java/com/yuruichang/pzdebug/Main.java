package com.yuruichang.pzdebug;

import me.zed_0xff.zombie_buddy.Exposer;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        JavaDiagnostics.install();
        registerLua();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            PZDebugJava.close(); LuaDebugger.close(); MethodTrace.close(); JavaDiagnostics.close();
        }, "PZDebugMCP-Shutdown"));
        System.out.println("[PZDebugMCP] Java " + BridgeRuntime.VERSION + " loaded");
    }
    public static void registerLua() { Exposer.exposeClass(PZDebugJava.class); }
}
