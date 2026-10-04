package com.yuruichang.pzdebug;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** One durable claim per mailbox; the diagnostic mailbox never waits for a Lua callback. */
final class Mailbox {
    interface Handler { Object execute(String operation, Map<String, Object> args, String text) throws Exception; }
    static final Object DEFERRED = new Object();
    private final Path directory;
    private final String endpoint, session;
    private final AtomicReference<Map<String, Object>> completed = new AtomicReference<>();
    private String lastText, lastId;
    private long lastSequence;
    private boolean pending;
    Mailbox(Path directory, String endpoint, String session) throws IOException {
        this.directory = directory; this.endpoint = endpoint; this.session = session;
        Files.createDirectories(directory);
    }
    void complete(String text) {
        var response = Json.map(Json.decode(text));
        if (!session.equals(response.get("session")) || !Objects.equals(lastId, response.get("id")) ||
            !(response.get("protocol") instanceof Number protocol) || protocol.doubleValue() != 1 ||
            !(response.get("ok") instanceof Boolean))
            throw new IllegalArgumentException("Response does not match the claimed request");
        completed.set(response);
    }
    void flush() throws IOException {
        var response = completed.get();
        if (response == null) return;
        String text = Json.encode(response);
        if (text.getBytes(StandardCharsets.UTF_8).length > 524288)
            text = Json.encode(Json.object("protocol", 1, "id", response.get("id"), "session", session,
                "ok", false, "error", Json.object("code", "TOO_LARGE", "message", "Response exceeds 512 KiB")));
        BridgeRuntime.atomic(directory.resolve("response.json"), text);
        BridgeRuntime.atomic(directory.resolve("response.ready.txt"), (String)response.get("id"));
        completed.compareAndSet(response, null); pending = false;
    }
    void poll(boolean debug, Handler handler) throws IOException {
        if (pending || completed.get() != null) return;
        Path file = directory.resolve("request.json");
        if (!Files.exists(file) || Files.size(file) > 524288) return;
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.equals(lastText)) return;
        lastText = text;
        Map<String, Object> request;
        try { request = Json.map(Json.decode(text)); } catch (IllegalArgumentException e) { return; }
        Object id = request.get("id");
        if (!(id instanceof String key) || !key.matches("[a-f0-9]{32}") || Objects.equals(lastId, key) ||
            !session.equals(request.get("session")) || !endpoint.equals(request.get("endpoint")) ||
            !(request.get("protocol") instanceof Number p) || p.doubleValue() != 1) return;
        int sequence;
        try { sequence = Json.integer(request, "sequence", 0, 1, Integer.MAX_VALUE); }
        catch (IllegalArgumentException e) { return; }
        if (sequence <= lastSequence) return;
        Path claimFile = directory.resolve("claim.json");
        if (Files.exists(claimFile) && Files.size(claimFile) <= 524288) {
            try {
                var claim = Json.map(Json.decode(Files.readString(claimFile, StandardCharsets.UTF_8)));
                if (session.equals(claim.get("session")) && key.equals(claim.get("id"))) return;
            } catch (IllegalArgumentException e) { /* Invalid input is never executed as code. */ }
        }
        String operation; Map<String, Object> args;
        try {
            operation = Json.text(request, "operation", "");
            args = Json.map(request.get("arguments"));
        } catch (IllegalArgumentException e) {
            lastId = key; lastSequence = sequence; finish(false, error("ARGUMENT", e.getMessage())); return;
        }
        if (!(request.get("expires_ms") instanceof Number expiry) || expiry.doubleValue() < System.currentTimeMillis()) {
            lastId = key; lastSequence = sequence; finish(false, error("EXPIRED", "Request expired before execution")); return;
        }
        if (!operation.equals("status") && !debug) {
            lastId = key; lastSequence = sequence; finish(false, error("DEBUG_DISABLED", "Start the game with -debug")); return;
        }
        try { BridgeRuntime.atomic(claimFile, Json.encode(Json.object("id", key, "session", session, "sequence", sequence))); }
        catch (IOException e) { lastText = null; throw e; }
        lastId = key; lastSequence = sequence; pending = true;
        try {
            Object result = handler.execute(operation, args, text);
            if (result != DEFERRED) finish(true, result);
        } catch (Exception | LinkageError e) {
            finish(false, error(e instanceof BridgeRuntime.Failure f ? f.code : "JAVA_ERROR",
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }
    private static Object error(String code, String message) { return Json.object("code", code, "message", message); }
    private void finish(boolean ok, Object value) {
        pending = true;
        completed.set(Json.object("protocol", 1, "id", lastId, "session", session, "ok", ok,
            "timestamp_ms", System.currentTimeMillis(), ok ? "result" : "error", value));
    }
}
