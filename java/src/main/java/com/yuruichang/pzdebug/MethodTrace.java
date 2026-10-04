package com.yuruichang.pzdebug;

import java.lang.reflect.*;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Exact loaded methods only. Hooks preserve arguments, return values and exceptions. */
public final class MethodTrace {
    private MethodTrace() {}
    private static final Map<String, Session> sessions = new LinkedHashMap<>();
    private record HookKey(Class<?> type, String descriptor) {}
    private static final Map<HookKey, java.lang.instrument.ClassFileTransformer> hooks = new HashMap<>();
    private static volatile Session active;
    private static final class Session {
        final String id = UUID.randomUUID().toString().replace("-", ""), origin;
        final Class<?> type;
        final Recorder recorder;
        final long deadline;
        final AtomicLong sequence = new AtomicLong(), dropped = new AtomicLong();
        final ArrayBlockingQueue<Map<String, Object>> pending = new ArrayBlockingQueue<>(600);
        final ArrayDeque<Map<String, Object>> samples = new ArrayDeque<>();
        volatile boolean stopped;
        Session(Class<?> type, String origin, int seconds, Recorder recorder) {
            this.type = type; this.origin = origin; this.recorder = recorder; deadline = System.nanoTime() + seconds * 1_000_000_000L;
        }
    }
    private static final class Token {
        final Session session; final long started = System.nanoTime();
        Object arguments;
        Token(Session session) { this.session = session; }
    }
    public static Object begin(Class<?> type, String origin) {
        Session session = active;
        if (session == null || session.stopped || System.nanoTime() >= session.deadline ||
            session.type != type || !origin.equals(session.origin)) return null;
        return new Token(session);
    }
    private static Object scalar(Object value) {
        if (value == null) return null;
        Class<?> cls = value.getClass();
        if (cls == String.class) { String text = (String)value; return text.substring(0, Math.min(text.length(), 512)); }
        if (cls == Boolean.class || cls == Integer.class || cls == Long.class || cls == Short.class || cls == Byte.class) return value;
        if (cls == Double.class || cls == Float.class) return Double.isFinite(((Number)value).doubleValue()) ? value : null;
        if (cls == Character.class) return String.valueOf((Character)value);
        return Json.object("class", cls.getName(), "identity", System.identityHashCode(value));
    }
    public static void arguments(Object object, Object[] args) {
        Token token = (Token)object; List<Object> values = new ArrayList<>();
        for (int i = 0; i < Math.min(16, args.length); i++) values.add(scalar(args[i]));
        token.arguments = values;
    }
    public static void end(Object object, Object result, Throwable thrown) {
        Token token = (Token)object; Session session = token.session;
        if (session.stopped) return;
        long sequence = session.sequence.incrementAndGet();
        var sample = Json.object("sequence", sequence, "timestamp_ms", System.currentTimeMillis(),
            "duration_ns", System.nanoTime() - token.started, "thread_id", Thread.currentThread().threadId(),
            "arguments", token.arguments, "result", scalar(result),
            "exception_class", thrown == null ? null : thrown.getClass().getName());
        if (!session.pending.offer(sample)) session.dropped.incrementAndGet();
    }
    public static final class TraceAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static Object enter(@Advice.Origin Class<?> type, @Advice.Origin("#t.#m#d") String origin, @Advice.AllArguments Object[] arguments) {
            Object token = MethodTrace.begin(type, origin);
            if (token != null) MethodTrace.arguments(token, arguments);
            return token;
        }
        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void exit(@Advice.Enter Object token,
                @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result, @Advice.Thrown Throwable thrown) {
            if (token != null) MethodTrace.end(token, result, thrown);
        }
    }
    static synchronized Object request(Map<String, Object> args, Recorder recorder) {
        String action = Json.text(args, "action", "read");
        if (action.equals("start")) {
            if (active != null && !active.stopped && System.nanoTime() < active.deadline)
                throw new BridgeRuntime.Failure("TRACE_BUSY", "One Java method trace may run at a time");
            String className = Json.text(args, "class_name", ""), methodName = Json.text(args, "method", "");
            if (!className.matches("[A-Za-z_$][A-Za-z0-9_.$]*") || !methodName.matches("[A-Za-z_$][A-Za-z0-9_$]*") ||
                className.startsWith("java.") || className.startsWith("jdk.") || className.startsWith("sun.") ||
                className.startsWith("net.bytebuddy.") || className.startsWith("me.zed_0xff.zombie_buddy.") ||
                className.startsWith("local.zbselective.") || className.startsWith("com.yuruichang.pzdebug."))
                throw new BridgeRuntime.Failure("TRACE_TARGET", "Select an exact game or mod method");
            Class<?> cls = JavaDiagnostics.loadedClass(className);
            if (cls.getClassLoader() == null || !JavaDiagnostics.initialized(cls))
                throw new BridgeRuntime.Failure("CLASS_NOT_INITIALIZED", "Trace requires a confirmed initialized application class");
            Object parameters = args.getOrDefault("parameters", List.of());
            if (!(parameters instanceof List<?> types) || types.size() > 16) throw new IllegalArgumentException("Expected parameter type names");
            Method selected = null;
            for (Method method : cls.getDeclaredMethods()) {
                if (!method.getName().equals(methodName) || method.getParameterCount() != types.size()) continue;
                boolean match = true; Class<?>[] actual = method.getParameterTypes();
                for (int i = 0; i < actual.length; i++) if (!actual[i].getTypeName().equals(types.get(i))) match = false;
                if (match) {
                    if (selected != null) throw new BridgeRuntime.Failure("AMBIGUOUS_METHOD", "Matching bridge methods require a more specific target");
                    selected = method;
                }
            }
            if (selected == null || Modifier.isNative(selected.getModifiers()) || Modifier.isAbstract(selected.getModifiers()))
                throw new BridgeRuntime.Failure("TRACE_TARGET", "Concrete Java method with exact parameters required");
            int seconds = Json.integer(args, "duration_seconds", 10, 1, 30);
            String descriptor = MethodType.methodType(selected.getReturnType(), selected.getParameterTypes()).descriptorString();
            String origin = className + "." + methodName + descriptor;
            HookKey hookKey = new HookKey(cls, origin);
            if (!hooks.containsKey(hookKey)) install(cls, methodName, descriptor, hookKey);
            Session session = new Session(cls, origin, seconds, recorder);
            sessions.put(session.id, session); active = session;
            while (sessions.size() > 4) sessions.remove(sessions.keySet().iterator().next());
            return Json.object("trace_id", session.id, "class_name", className, "method", methodName,
                "descriptor", descriptor, "duration_seconds", seconds, "capacity", 600,
                "installed_hooks_persist_until_restart", true);
        }
        if (!action.equals("read") && !action.equals("stop")) throw new IllegalArgumentException("Trace action start/read/stop");
        Session session = sessions.get(Json.text(args, "trace_id", ""));
        if (session == null) throw new BridgeRuntime.Failure("TRACE_NOT_FOUND", "Unknown Java trace");
        if (action.equals("stop")) session.stopped = true;
        drain(session);
        long after = Json.integer(args, "after", 0, 0, Integer.MAX_VALUE);
        int limit = Json.integer(args, "limit", 100, 1, 100); List<Object> results = new ArrayList<>(); long cursor = after;
        for (var sample : session.samples) if (((Number)sample.get("sequence")).longValue() > after && results.size() < limit) {
            results.add(sample); cursor = ((Number)sample.get("sequence")).longValue();
        }
        long first = session.samples.isEmpty() ? session.sequence.get() + 1 : ((Number)session.samples.peek().get("sequence")).longValue();
        if (results.size() < limit) cursor = Math.max(cursor, session.sequence.get());
        return Json.object("trace_id", session.id, "samples", results, "cursor", cursor,
            "done", session.stopped || System.nanoTime() >= session.deadline, "dropped", session.dropped.get(),
            "gap", after < first - 1 || session.dropped.get() > 0, "has_more", cursor < session.sequence.get());
    }
    private static void install(Class<?> cls, String method, String descriptor, HookKey key) {
        var inst = JavaDiagnostics.instrumentation;
        if (inst == null || !inst.isRetransformClassesSupported() || !inst.isModifiableClass(cls))
            throw new BridgeRuntime.Failure("TRACE_UNSUPPORTED", "Loaded-class retransformation is unavailable");
        if (hooks.size() >= 8) throw new BridgeRuntime.Failure("TRACE_LIMIT", "At most eight distinct installed method hooks per JVM");
        boolean[] transformed = {false};
        var transformer = new AgentBuilder.Default().disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(new AgentBuilder.Listener.Adapter() {
                @Override public void onTransformation(net.bytebuddy.description.type.TypeDescription td,
                        ClassLoader loader, net.bytebuddy.utility.JavaModule module, boolean loaded,
                        net.bytebuddy.dynamic.DynamicType type) { transformed[0] = true; }
            })
            .type(named(cls.getName()), loader -> loader == cls.getClassLoader())
            .transform((builder, type, loader, module, domain) ->
                builder.visit(Advice.to(TraceAdvice.class).on(named(method).and(hasDescriptor(descriptor)))))
            .installOn(inst);
        if (!transformed[0]) {
            inst.removeTransformer(transformer);
            throw new BridgeRuntime.Failure("TRACE_INSTALL_FAILED", "Target transformation was not confirmed");
        }
        hooks.put(key, transformer);
    }
    private static void drain(Session session) {
        for (int i = 0; i < 600; i++) {
            var sample = session.pending.poll(); if (sample == null) break;
            session.samples.add(sample); if (session.samples.size() > 600) session.samples.remove();
            session.recorder.offer("java_trace", session.origin, sample);
        }
    }
    static synchronized void drain() { for (Session session : sessions.values()) drain(session); }
    static synchronized void resetSession() {
        if (active != null) active.stopped = true;
        drain(); active = null; sessions.clear();
    }
    static synchronized void close() {
        resetSession();
        if (JavaDiagnostics.instrumentation != null)
            for (var transformer : hooks.values()) JavaDiagnostics.instrumentation.removeTransformer(transformer);
        hooks.clear();
    }
}
