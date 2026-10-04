package com.yuruichang.pzdebug;

import java.nio.file.*;
import java.util.*;

/** Isolated cache for running the actual Java adapter with the shipped Kahlua. */
public final class LuaFixture {
    private static Path cache;
    private static BridgeRuntime runtime;
    private static long sequence;
    private static String lastId;
    public static String open(String endpoint) throws Exception {
        if (runtime != null) runtime.close();
        cache = Files.createTempDirectory("pzdebug-kahlua");
        runtime = new BridgeRuntime(cache, endpoint);
        var field = PZDebugJava.class.getDeclaredField("runtime"); field.setAccessible(true); field.set(null, runtime);
        return runtime.session;
    }
    public static String send(String operation, String arguments) throws Exception {
        lastId = UUID.randomUUID().toString().replace("-", "");
        BridgeRuntime.atomic(cache.resolve("Lua/PZDebugMCP/client/request.json"), Json.encode(Json.object(
            "protocol", 1, "id", lastId, "session", runtime.session, "sequence", ++sequence,
            "endpoint", "client", "operation", operation, "expires_ms", System.currentTimeMillis() + 5000,
            "arguments", Json.decode(arguments))));
        return lastId;
    }
    public static String response() throws Exception {
        Path directory = cache.resolve("Lua/PZDebugMCP/client"), marker = directory.resolve("response.ready.txt");
        if (!Files.exists(marker) || !Files.readString(marker).strip().equals(lastId)) return null;
        return Files.readString(directory.resolve("response.json"));
    }
    public static void waitTick() throws InterruptedException { Thread.sleep(20); }
    public static void close() { PZDebugJava.close(); }
}
