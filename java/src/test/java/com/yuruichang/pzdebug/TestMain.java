package com.yuruichang.pzdebug;

import fixture.TestAgent;
import fixture.TraceSubject;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class TestMain {
    private static int checks, initialized;
    public static final class Dormant {
        public static int value;
        static { initialized++; value = 42; }
    }
    public static final class Equal {
        private int secret = 17;
        public int callbacks;
        @Override public boolean equals(Object other) { callbacks++; return true; }
        @Override public int hashCode() { callbacks++; return 1; }
        public int getUnknown() { callbacks++; return 0; }
    }
    static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    static void fails(Runnable action, String message) {
        checks++;
        try { action.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        TraceSubject.compute(0);
        JavaDiagnostics.attach(TestAgent.instrumentation);
        if (args.length == 2 && args[0].equals("--serve")) { serve(Path.of(args[1])); return; }
        json(); fields(); records(); mailbox(); tracing();
        MethodTrace.close(); JavaDiagnostics.close();
        System.out.println("Java checks passed: " + checks);
    }
    private static void json() {
        Object value = Json.object("text", "中文🚗\n\"\\", "items", Arrays.asList(null, true, 1.5), "empty", List.of());
        check(Json.encode(Json.decode(Json.encode(value))).equals(Json.encode(value)), "JSON UTF-8 roundtrip");
        for (String text : List.of("[1,]", "01", "1e999", "{\"a\":1,\"a\":2}", "\"\\ud800\"", "\"\\u+001\"", "true x", "[", "{\"x\":}"))
            fails(() -> Json.decode(text), "Reject malformed JSON: " + text);
        fails(() -> Json.decode("[".repeat(26) + "0" + "]".repeat(26)), "JSON depth bounded");
        fails(() -> Json.encode(Double.NaN), "Reject non-finite output");
    }
    private static void fields() throws Exception {
        Inspector inspector = new Inspector("session");
        Equal a = new Equal(), b = new Equal();
        String ha = (String)Json.map(inspector.describe(a, "a", 0)).get("handle");
        String hb = (String)Json.map(inspector.describe(b, "b", 0)).get("handle");
        check(!ha.equals(hb), "Equal objects have distinct handles");
        check(ha.equals(Json.map(inspector.describe(a, "a", 0)).get("handle")), "Stable identity handle");
        check(a.callbacks == 0 && b.callbacks == 0, "No object hashCode/equals callback");
        check(((Number)inspector.query(Json.object("target", ha, "action", "field", "member", "secret")).get("value")).intValue() == 17, "Private Java field readable");
        fails(() -> inspector.query(Json.object("target", ha, "action", "call", "member", "getUnknown")), "Unknown getter rejected");
        check(a.callbacks == 0, "Rejected getter not executed");
        String dormant = (String)Json.map(inspector.describe(Dormant.class, "dormant", 0)).get("handle");
        check(!JavaDiagnostics.initialized(Dormant.class), "Dormant class remains uninitialized");
        check(Boolean.FALSE.equals(inspector.query(Json.object("target", dormant, "action", "field", "member", "value")).get("accessible")), "Static read blocked before init");
        check(initialized == 0, "Static collection did not initialize class");
        int _ = Dormant.value;
        check(JavaDiagnostics.initialized(Dormant.class), "Initialized class recognized");
        check(((Number)inspector.query(Json.object("target", dormant, "action", "field", "member", "value")).get("value")).intValue() == 42, "Initialized static read");
        int[] array = {2, 4, 6};
        String arr = (String)Json.map(inspector.describe(array, "array", 0)).get("handle");
        check(((Number)inspector.query(Json.object("target", arr, "action", "field", "field_index", 1)).get("value")).intValue() == 4, "Array index read");
        fails(() -> inspector.query(Json.object("target", arr, "action", "field", "field_index", 9)), "Array bounds validated");
        check(((List<?>)inspector.query(Json.object("target", ha, "action", "methods")).get("items")).size() > 0, "Methods metadata present");
        inspector.configure(Json.object("max_handles", 128));
        fails(() -> inspector.value(ha), "Handle invalidated on resize");
        for (int i = 0; i < 150; i++) inspector.describe(new Equal(), "root" + i, 0);
        check(((Number)inspector.stats().get("handles")).intValue() == 128, "Handle storage bounded");
        check(((List<?>)inspector.stats().get("roots")).size() == 128, "Roots evicted with objects");
        check(Json.map(inspector.describe(Thread.currentThread(), "thread", 0)).get("kind").equals("restricted"), "Control object blocked");
    }
    private static void records() throws Exception {
        Path directory = Files.createTempDirectory("pzdebug-record-test");
        Recorder recorder = new Recorder(directory, "session");
        for (int batch = 0; batch < 23; batch++) {
            for (int i = 0; i < 100; i++) check(recorder.offer("object", "fixture", Json.object("text", "中文🚗".repeat(30))) > 0, "Record accepted");
            recorder.drain();
        }
        var index = Json.map(Json.decode(Files.readString(directory.resolve("index.json"))));
        check(((Number)index.get("last")).longValue() == 2300, "Index advertises committed records only");
        check(((Number)index.get("first")).longValue() > 1, "Ring rolls old segments");
        check(((List<?>)index.get("segments")).size() == 16, "16 slots bounded");
        for (var item : (List<?>)index.get("segments")) {
            var segment = Json.map(item);
            Path file = directory.resolve(String.format("segment-%02d.log", ((Number)segment.get("slot")).intValue()));
            check(Files.size(file) <= 262144, "UTF-8 segment size bounded");
            for (String line : Files.readAllLines(file)) check(Json.map(Json.decode(line)).get("session").equals("session"), "Complete UTF-8 record");
        }
        for (int i = 0; i < 1200; i++) recorder.offer("object", "fixture", Json.object());
        check(((Number)recorder.status().get("queue_dropped")).longValue() > 0, "Recorder backpressure visible");
    }
    private static Map<String, Object> request(BridgeRuntime runtime, Path cache, String operation, long seq, long expiry, Object arguments) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        var request = Json.object("protocol", 1, "id", id, "session", runtime.session, "sequence", seq,
            "endpoint", "client", "expires_ms", expiry, "operation", operation, "arguments", arguments);
        BridgeRuntime.atomic(cache.resolve("Lua/PZDebugMCP/client/request.json"), Json.encode(request));
        return request;
    }
    private static Map<String, Object> response(Path cache, String id) throws Exception {
        Path directory = cache.resolve("Lua/PZDebugMCP/client");
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Path marker = directory.resolve("response.ready.txt");
            if (Files.exists(marker) && Files.readString(marker).strip().equals(id))
                return Json.map(Json.decode(Files.readString(directory.resolve("response.json"))));
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for Java response");
    }
    private static void mailbox() throws Exception {
        Path cache = Files.createTempDirectory("pzdebug-mail-test");
        try (BridgeRuntime runtime = new BridgeRuntime(cache, "client")) {
            runtime.publish(Json.encode(Json.object("game_version", "fixture")), true);
            runtime.publishErrors(Json.encode(Json.object("events", List.of(Json.object("sequence", 1, "kind", "lua_error", "message", "fixture")), "cursor", 1)));
            var status = request(runtime, cache, "status", 1, System.currentTimeMillis() + 5000, Json.object());
            var out = response(cache, (String)status.get("id"));
            check(Boolean.TRUE.equals(out.get("ok")), "Background status available without game tick");
            check(Json.map(out.get("result")).get("backend").equals("zombiebuddy_java"), "Java backend reported");
            var metrics = request(runtime, cache, "java_runtime", 2, System.currentTimeMillis() + 5000, Json.object("section", "metrics"));
            check(Boolean.TRUE.equals(response(cache, (String)metrics.get("id")).get("ok")), "JVM query independent of Lua");
            var errors = request(runtime, cache, "read_errors", 3, System.currentTimeMillis() + 5000, Json.object());
            check(((List<?>)Json.map(response(cache, (String)errors.get("id")).get("result")).get("events")).size() == 1, "Cached errors independent of Lua");
            var expired = request(runtime, cache, "run_test", 4, System.currentTimeMillis() - 1000, Json.object());
            check(Json.map(response(cache, (String)expired.get("id")).get("error")).get("code").equals("EXPIRED"), "Expired requests never dispatched");
            var game = request(runtime, cache, "run_test", 5, System.currentTimeMillis() + 5000, Json.object());
            String dispatch = null; long deadline = System.currentTimeMillis() + 3000;
            while (dispatch == null && System.currentTimeMillis() < deadline) { dispatch = runtime.takeRequest(); Thread.sleep(10); }
            check(dispatch != null, "Game request queued");
            Path claim = cache.resolve("Lua/PZDebugMCP/client/claim.json");
            check(Json.map(Json.decode(Files.readString(claim))).get("id").equals(game.get("id")), "Claim persisted before game dispatch");
            var injected = request(runtime, cache, "status", 6, System.currentTimeMillis() + 5000, Json.object());
            runtime.complete(Json.encode(Json.object("protocol", 1, "id", game.get("id"), "session", runtime.session, "ok", true, "result", Json.object("passed", true))));
            check(Boolean.TRUE.equals(response(cache, (String)game.get("id")).get("ok")), "Pending game request not overwritten");
            check(Boolean.TRUE.equals(response(cache, (String)injected.get("id")).get("ok")), "Next request waits for previous completion");
            runtime.publish("{}", false);
            var disabled = request(runtime, cache, "java_runtime", 7, System.currentTimeMillis() + 5000, Json.object());
            check(Json.map(response(cache, (String)disabled.get("id")).get("error")).get("code").equals("DEBUG_DISABLED"), "Debug gating applies to Java");
        }
    }
    private static void tracing() throws Exception {
        JavaDiagnostics.refresh();
        Recorder recorder = new Recorder(Files.createTempDirectory("pzdebug-trace-test"), "session");
        var start = Json.map(MethodTrace.request(Json.object("action", "start", "class_name", "fixture.TraceSubject",
            "method", "compute", "parameters", List.of("int"), "duration_seconds", 10), recorder));
        check(TraceSubject.compute(5) == 12, "Tracing preserves return value");
        boolean thrown = false;
        try { TraceSubject.compute(-1); } catch (IllegalArgumentException expected) { thrown = true; }
        check(thrown, "Tracing preserves original exception");
        var read = Json.map(MethodTrace.request(Json.object("action", "read", "trace_id", start.get("trace_id")), recorder));
        var samples = (List<?>)read.get("samples");
        check(samples.size() == 2, "Both trace paths recorded");
        check(((Number)Json.map(samples.getFirst()).get("result")).intValue() == 12, "Return captured");
        check(Json.map(samples.getLast()).get("exception_class").equals("java.lang.IllegalArgumentException"), "Exception type captured");
        check(((Number)((List<?>)Json.map(samples.getFirst()).get("arguments")).getFirst()).intValue() == 5, "Argument captured");
        MethodTrace.request(Json.object("action", "stop", "trace_id", start.get("trace_id")), recorder);
        TraceSubject.compute(2);
        check(((List<?>)Json.map(MethodTrace.request(Json.object("action", "read", "trace_id", start.get("trace_id")), recorder)).get("samples")).size() == 2, "Stop prevents new samples");
        var empty = Json.map(MethodTrace.request(Json.object("action", "start", "class_name", "fixture.TraceSubject",
            "method", "nothing", "parameters", List.of()), recorder));
        TraceSubject.nothing();
        check(((List<?>)Json.map(MethodTrace.request(Json.object("action", "stop", "trace_id", empty.get("trace_id")), recorder)).get("samples")).size() == 1, "Void method trace works");
    }
    private static void serve(Path cache) throws Exception {
        try (BridgeRuntime runtime = new BridgeRuntime(cache, "client")) {
            Equal probe = new Equal();
            String handle = (String)Json.map(runtime.inspector.describe(probe, "fixture", 0)).get("handle");
            runtime.publish(Json.encode(Json.object("game_version", "fixture", "fixture_handle", handle)), true);
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::close));
            System.out.println("READY"); System.out.flush();
            while (true) {
                boolean paused = Files.exists(cache.resolve("pause-game.txt"));
                String text = paused ? null : runtime.takeRequest();
                if (text != null) {
                    Map<String, Object> req = Json.map(Json.decode(text));
                    boolean ok = true; Object value;
                    try {
                        if (((Number)req.get("expires_ms")).doubleValue() < System.currentTimeMillis()) throw new BridgeRuntime.Failure("EXPIRED", "Expired in game queue");
                        if (!req.get("operation").equals("inspect_java")) throw new BridgeRuntime.Failure("UNKNOWN_OPERATION", "Fixture only supports inspect_java");
                        value = Json.decode(runtime.javaQuery(Json.encode(req.get("arguments"))));
                    } catch (Exception error) { ok = false; value = Json.object("code", error instanceof BridgeRuntime.Failure f ? f.code : "JAVA_ERROR", "message", error.getMessage()); }
                    runtime.complete(Json.encode(Json.object("protocol", 1, "id", req.get("id"), "session", runtime.session,
                        "ok", ok, ok ? "result" : "error", value)));
                }
                TraceSubject.compute(3);
                if (!paused) {
                    runtime.tick();
                    runtime.publish(Json.encode(Json.object("game_version", "fixture", "fixture_handle", handle)), true);
                }
                Thread.sleep(20);
            }
        }
    }
}
