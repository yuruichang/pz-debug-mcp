package com.yuruichang.pzdebug;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

public final class Recorder {
    public static final String POLICY = "java_fields_reviewed_lua_v1";
    private final Path directory;
    private final String session;
    private final ArrayBlockingQueue<Map<String, Object>> queue = new ArrayBlockingQueue<>(1024);
    private final Map<Integer, Map<String, Object>> segments = new LinkedHashMap<>();
    private long accepted, committed, dropped;
    private int slot = 1, count, bytes;
    private long publishedThrough = -1, publishedAt;
    private volatile String lastError;
    private volatile boolean enabled = true;
    Recorder(Path directory, String session) throws IOException {
        this.directory = directory; this.session = session; Files.createDirectories(directory);
        if (committed != publishedThrough || System.currentTimeMillis() - publishedAt >= 1000) publish();
    }
    public synchronized long offer(String kind, String target, Object data) {
        Map<String, Object> entry = Json.object("session", session, "sequence", accepted + 1,
            "timestamp_ms", System.currentTimeMillis(), "kind", kind, "target", target, "data", data);
        if (Json.encode(entry).getBytes(StandardCharsets.UTF_8).length > 196608 || !queue.offer(entry)) {
            dropped++; return 0;
        }
        return ++accepted;
    }
    public void enabled(boolean value) { enabled = value; }
    public boolean enabled() { return enabled; }
    public synchronized Map<String, Object> status() {
        return Json.object("enabled", enabled, "sequence", committed, "accepted_through", accepted,
            "pending_writes", queue.size(), "queue_dropped", dropped, "last_error", lastError,
            "read_policy", POLICY, "coverage", "java_fields_and_reviewed_lua",
            "automatic_object_graph", true, "writer", "java_background_thread");
    }
    void drain() throws IOException {
        for (int i = 0; i < 128; i++) {
            Map<String, Object> entry = queue.peek();
            if (entry == null) break;
            byte[] payload = (Json.encode(entry) + "\n").getBytes(StandardCharsets.UTF_8);
            if (count >= 128 || bytes + payload.length > 262144) { slot = slot % 16 + 1; count = 0; bytes = 0; }
            Path file = directory.resolve(String.format("segment-%02d.log", slot));
            if (count == 0) {
                Files.write(file, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                segments.put(slot, Json.object("slot", slot, "first", entry.get("sequence"), "last", entry.get("sequence"), "count", 0, "bytes", 0));
            }
            Files.write(file, payload, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            count++; bytes += payload.length;
            var segment = segments.get(slot);
            segment.put("last", entry.get("sequence")); segment.put("count", count); segment.put("bytes", bytes);
            synchronized (this) { committed = ((Number) entry.get("sequence")).longValue(); }
            queue.poll();
        }
        lastError = null;
        if (committed != publishedThrough || System.currentTimeMillis() - publishedAt >= 1000) publish();
    }
    void error(Exception error) { lastError = error.getClass().getSimpleName(); }
    private void publish() throws IOException {
        long first = committed + 1;
        for (var entry : segments.values()) first = Math.min(first, ((Number) entry.get("first")).longValue());
        BridgeRuntime.atomic(directory.resolve("index.json"), Json.encode(Json.object("schema", 1,
            "session", session, "first", first, "last", committed, "segments", new ArrayList<>(segments.values()),
            "timestamp_ms", System.currentTimeMillis(), "recorder", status())));
        publishedThrough = committed; publishedAt = System.currentTimeMillis();
    }
}
