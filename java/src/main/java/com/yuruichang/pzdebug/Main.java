package com.yuruichang.pzdebug;

import me.zed_0xff.zombie_buddy.Exposer;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        JavaDiagnostics.install();
        Exposer.exposeClass(PZDebugJava.class, "PZDebugJava");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            PZDebugJava.close(); MethodTrace.close(); JavaDiagnostics.close();
        }, "PZDebugMCP-Shutdown"));
        System.out.println("[PZDebugMCP] Java " + BridgeRuntime.VERSION + " loaded");
    }
}
