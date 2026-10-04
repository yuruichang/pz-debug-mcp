package com.yuruichang.pzdebug;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

public final class BridgeRuntime implements AutoCloseable {
    public static final String VERSION = "0.3.0";
    public static final class Failure extends RuntimeException {
        final String code;
        public Failure(String code, String message) { super(message); this.code = code; }
    }
    private final Path directory;
    final String endpoint, session = UUID.randomUUID().toString().replace("-", "");
    final Recorder recorder;
    final Inspector inspector = new Inspector(session);
    private final ArrayBlockingQueue<String> gameRequests = new ArrayBlockingQueue<>(1);
    private final Mailbox gameMailbox, runtimeMailbox;
    private final Thread worker;
    private volatile boolean running = true, debug;
    private volatile Map<String, Object> gameStatus = Json.object();
    private volatile Map<String, Object> errors = Json.object("events", List.of(), "cursor", 0);
    private volatile Object inspectorStatus = Json.object();
    private volatile long gameTick, lastHeartbeat;
    private volatile int budget = 2, jobs = 4, interval = 100, refreshSeconds = 10;
    private long nextDiagnostics;
    private int modCursor;
    private List<Class<?>> modTypes = List.of();
    private long nextMods;
    BridgeRuntime(Path cache, String endpoint) throws IOException {
        if (!endpoint.equals("client") && !endpoint.equals("server")) throw new IllegalArgumentException("Unknown endpoint");
        this.endpoint = endpoint;
        directory = cache.resolve("Lua/PZDebugMCP").resolve(endpoint);
        Files.createDirectories(directory);
        recorder = new Recorder(directory.resolve("records"), session);
        gameMailbox = new Mailbox(directory, endpoint, session);
        runtimeMailbox = new Mailbox(directory.resolve("runtime"), endpoint, session);
        worker = new Thread(this::loop, "PZDebugMCP-IO"); worker.setDaemon(true); worker.start();
    }
    public static void atomic(Path path, String text) throws IOException {
        Path temp = path.resolveSibling("." + path.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temp, text + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>(gameStatus);
        result.putAll(Json.object("protocol", 1, "version", VERSION, "backend", "zombiebuddy_java",
            "session", session, "endpoint", endpoint, "timestamp_ms", System.currentTimeMillis(),
            "debug_enabled", debug, "game_thread_age_ms", gameTick == 0 ? null : System.currentTimeMillis() - gameTick,
            "recorder", recorder.status(), "java_collector", inspectorStatus,
            "independent_communication", true, "runtime_mailbox", "runtime", "java_runtime", JavaDiagnostics.runtime(Json.object())));
        return result;
    }
    void publish(String text, boolean debugEnabled) {
        gameStatus = Json.map(Json.decode(text)); debug = debugEnabled; gameTick = System.currentTimeMillis();
        inspectorStatus = inspector.stats();
    }
    void publishErrors(String text) { errors = Json.map(Json.decode(text)); }
    String takeRequest() { return gameRequests.poll(); }
    void complete(String text) { gameMailbox.complete(text); }
    void configure(Map<String, Object> args) {
        inspector.configure(args);
        budget = Json.integer(args, "budget_ms", budget, 1, 20);
        jobs = Json.integer(args, "jobs_per_tick", jobs, 1, 32);
        interval = Json.integer(args, "interval_ms", interval, 50, 5000);
        refreshSeconds = Json.integer(args, "refresh_seconds", refreshSeconds, 1, 300);
        if (args.get("enabled") != null) {
            if (!(args.get("enabled") instanceof Boolean b)) throw new IllegalArgumentException("enabled must be boolean");
            recorder.enabled(b);
        }
    }
    void tick() {
        long now = System.currentTimeMillis();
        if (recorder.enabled() && debug) {
            if (now >= nextMods) { modTypes = JavaDiagnostics.modClasses(); nextMods = now + 10000; modCursor = 0; }
            if (modCursor < modTypes.size()) {
                Class<?> cls = modTypes.get(modCursor++);
                inspector.describe(cls, "mod:" + cls.getName(), 0);
            }
            inspector.tick(recorder, now, budget, jobs, interval, refreshSeconds);
        }
    }
    String javaQuery(String text) {
        Map<String, Object> args = Json.map(Json.decode(text));
        Object data = inspector.query(args);
        long seq = recorder.offer("query", Json.text(args, "target", ""), data);
        return Json.encode(Json.object("session", session, "record_sequence", seq, "data", data));
    }
    String javaList(String id, int offset, int limit) { return Json.encode(inspector.inspect(id, offset, limit, false)); }
    private void loop() {
        while (running) {
            try {
                runtimeMailbox.flush(); gameMailbox.flush();
                runtimeMailbox.poll(debug, (op, args, text) -> dispatch(op, args, text, false));
                gameMailbox.poll(debug, (op, args, text) -> dispatch(op, args, text, true));
                long now = System.currentTimeMillis();
                if (now - lastHeartbeat >= 500) { atomic(directory.resolve("heartbeat.json"), Json.encode(status())); lastHeartbeat = now; }
                if (recorder.enabled() && debug && now >= nextDiagnostics) {
                    nextDiagnostics = now + 5000;
                    recorder.offer("java_runtime", "jvm:metrics", JavaDiagnostics.runtime(Json.object("section", "metrics")));
                    recorder.offer("java_mods", "java:mods", JavaDiagnostics.runtime(Json.object("section", "mods", "limit", 100)));
                }
                JavaDiagnostics.drainObservations(recorder);
                MethodTrace.drain();
                recorder.drain();
                Thread.sleep(25);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch (Exception | LinkageError e) {
                if (e instanceof Exception error) recorder.error(error);
                try { Thread.sleep(100); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); break; }
            }
        }
        try {
            for (int i = 0; i < 8 && ((Number)recorder.status().get("pending_writes")).intValue() > 0; i++) recorder.drain();
        } catch (IOException ignored) {}
    }
    private Object dispatch(String operation, Map<String, Object> args, String text, boolean allowGame) {
        return switch (operation) {
            case "status" -> status();
            case "java_runtime" -> Json.text(args, "section", "").equals("roots") ? inspectorStatus : JavaDiagnostics.runtime(args);
            case "read_errors" -> readErrors(args);
            case "trace_java" -> MethodTrace.request(args, recorder);
            default -> {
                if (!allowGame) throw new Failure("UNKNOWN_OPERATION", "Game operations cannot use the diagnostic mailbox");
                if (!gameRequests.offer(text)) throw new Failure("BUSY", "Game request queue is occupied");
                yield Mailbox.DEFERRED;
            }
        };
    }
    private Object readErrors(Map<String, Object> args) {
        boolean reset = args.get("session") != null && !session.equals(args.get("session"));
        int after = reset ? 0 : Json.integer(args, "after", 0, 0, Integer.MAX_VALUE);
        int limit = Json.integer(args, "limit", 50, 1, 100);
        Map<String, Object> snapshot = errors;
        List<?> events = (List<?>)snapshot.get("events");
        List<Object> out = new ArrayList<>(); long cursor = after;
        long first = events.isEmpty() ? ((Number)snapshot.get("cursor")).longValue() + 1
            : ((Number)Json.map(events.getFirst()).get("sequence")).longValue();
        for (Object entry : events) {
            long seq = ((Number)Json.map(entry).get("sequence")).longValue();
            if (seq > after && out.size() < limit) { out.add(entry); cursor = seq; }
        }
        return Json.object("events", out, "cursor", cursor, "session", session, "reset", reset,
            "gap", after < first - 1, "has_more", cursor < ((Number)snapshot.get("cursor")).longValue(),
            "cached", true, "game_thread_age_ms", gameTick == 0 ? null : System.currentTimeMillis() - gameTick);
    }
    @Override public void close() {
        running = false; worker.interrupt();
        try { worker.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
