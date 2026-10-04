package com.yuruichang.pzdebug;

import zombie.ZomboidFileSystem;
import java.nio.file.Path;

/** Small Lua-facing adapter. Only serialized protocol values cross to the I/O thread. */
public final class PZDebugJava {
    private PZDebugJava() {}
    private static BridgeRuntime runtime;
    public static synchronized String open(String endpoint) throws java.io.IOException {
        MethodTrace.resetSession();
        if (runtime != null) runtime.close();
        runtime = new BridgeRuntime(Path.of(ZomboidFileSystem.instance.getCacheDir()), endpoint);
        return runtime.session;
    }
    public static String takeRequest() { return runtime.takeRequest(); }
    public static void complete(String json) { runtime.complete(json); }
    public static void publish(String json, boolean debug) { runtime.publish(json, debug); }
    public static void publishErrors(String json) { runtime.publishErrors(json); }
    public static String recorderStatus() { return Json.encode(runtime.recorder.status()); }
    public static long record(String kind, String target, String json) {
        try { return runtime.recorder.offer(kind, target, Json.decode(json)); }
        catch (RuntimeException e) { runtime.recorder.error(e); return 0; }
    }
    private static String result(java.util.function.Supplier<Object> action) {
        try { return Json.encode(Json.object("ok", true, "result", action.get())); }
        catch (RuntimeException | LinkageError e) {
            return Json.encode(Json.object("ok", false, "error", Json.object(
                "code", e instanceof BridgeRuntime.Failure f ? f.code : "JAVA_ERROR",
                "message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
        }
    }
    public static String describe(Object value, String origin, int depth) {
        return result(() -> runtime.inspector.describe(value, origin, depth));
    }
    public static Object resolve(String handle) {
        try { return runtime.inspector.value(handle); } catch (RuntimeException e) { return null; }
    }
    public static String query(String json) { return result(() -> Json.decode(runtime.javaQuery(json))); }
    public static String list(String handle, int offset, int limit) { return result(() -> Json.decode(runtime.javaList(handle, offset, limit))); }
    public static String configure(String json) {
        return result(() -> { runtime.configure(Json.map(Json.decode(json))); return true; });
    }
    public static void tick() { runtime.tick(); }
    public static synchronized void close() {
        if (runtime != null) { MethodTrace.resetSession(); runtime.close(); runtime = null; }
    }
}
