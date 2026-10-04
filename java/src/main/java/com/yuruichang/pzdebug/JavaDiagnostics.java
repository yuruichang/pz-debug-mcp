package com.yuruichang.pzdebug;

import java.lang.instrument.*;
import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

public final class JavaDiagnostics {
    private JavaDiagnostics() {}
    static volatile Instrumentation instrumentation;
    private static volatile Class<?>[] loaded = new Class<?>[0];
    private static volatile List<Class<?>> mods = List.of();
    private static volatile long refreshed;
    private static String unavailable = "not_started";
    private record Bytes(String name, int loader, byte[] bytes, boolean redefinition) {}
    private static final ArrayBlockingQueue<Bytes> observed = new ArrayBlockingQueue<>(16);
    private static final ArrayDeque<Map<String, Object>> hashes = new ArrayDeque<>();
    private static long observedSequence, observedDropped;
    private static Object unsafe;
    private static Method shouldInitialize;
    private static ClassFileTransformer observer;
    private static final ThreadLocal<Boolean> observing = ThreadLocal.withInitial(() -> false);
    static void install() {
        try {
            Class<?> loader = Class.forName("me.zed_0xff.zombie_buddy.Loader", false, JavaDiagnostics.class.getClassLoader());
            Field field = loader.getDeclaredField("g_instrumentation");
            if (!field.trySetAccessible()) throw new IllegalAccessException("Instrumentation access unavailable");
            instrumentation = (Instrumentation) field.get(null);
            if (instrumentation == null) throw new IllegalStateException("Instrumentation not provided");
            unavailable = null;
        } catch (ReflectiveOperationException | RuntimeException e) { unavailable = e.getClass().getSimpleName(); }
        attach(instrumentation);
    }
    static void attach(Instrumentation value) {
        instrumentation = value;
        refreshed = 0;
        try {
            Class<?> type = Class.forName("jdk.internal.misc.Unsafe", false, null);
            unsafe = type.getMethod("getUnsafe").invoke(null);
            shouldInitialize = type.getMethod("shouldBeInitialized", Class.class);
        } catch (ReflectiveOperationException | RuntimeException e) { unsafe = null; shouldInitialize = null; }
        refresh();
        if (instrumentation != null) {
            try {
                MessageDigest.getInstance("SHA-256");
                observing.set(false);
                observer = new ClassFileTransformer() {
                    @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> old,
                            ProtectionDomain domain, byte[] bytes) {
                        if (Boolean.TRUE.equals(observing.get()) || loader == null || name == null ||
                            name.startsWith("com/yuruichang/pzdebug/") || name.startsWith("net/bytebuddy/") ||
                            name.startsWith("java/") || name.startsWith("jdk/") || bytes.length > 524288) return null;
                        observing.set(true);
                        try {
                            if (!observed.offer(new Bytes(name.replace('/', '.'), System.identityHashCode(loader),
                                    bytes.clone(), old != null))) observedDropped++;
                        } catch (RuntimeException ignored) { observedDropped++; }
                        finally { observing.set(false); }
                        return null;
                    }
                };
                instrumentation.addTransformer(observer, instrumentation.isRetransformClassesSupported());
            } catch (Exception | LinkageError e) { observer = null; unavailable = "observer:" + e.getClass().getSimpleName(); }
        }
    }
    static boolean initialized(Class<?> cls) {
        if (unsafe == null || shouldInitialize == null) return false;
        try { return Boolean.FALSE.equals(shouldInitialize.invoke(unsafe, cls)); }
        catch (ReflectiveOperationException | RuntimeException e) { return false; }
    }
    static void refresh() {
        if (instrumentation == null || System.currentTimeMillis() - refreshed < 5000) return;
        try {
            Class<?>[] classes = instrumentation.getAllLoadedClasses();
            Arrays.sort(classes, Comparator.comparing(Class::getName));
            loaded = classes; mods = scanModClasses(); refreshed = System.currentTimeMillis();
        } catch (RuntimeException | LinkageError e) { unavailable = "class_inventory:" + e.getClass().getSimpleName(); }
    }
    static Class<?> loadedClass(String name) {
        Class<?> found = null;
        for (Class<?> cls : loaded) if (cls.getName().equals(name)) {
            if (found != null && found != cls) throw new BridgeRuntime.Failure("AMBIGUOUS_CLASS", "Same class name in multiple class loaders");
            found = cls;
        }
        if (found == null) throw new BridgeRuntime.Failure("CLASS_NOT_LOADED", "Class is not present in the loaded-class inventory");
        return found;
    }
    static List<Class<?>> modClasses() { return mods; }
    private static List<Class<?>> scanModClasses() {
        List<Class<?>> result = new ArrayList<>();
        for (Class<?> cls : loaded) {
            if (cls.isArray() || cls.getClassLoader() == null) continue;
            String source = source(cls);
            if (!cls.isArray() && cls.getClassLoader() != null && source != null && source.endsWith(".jar") &&
                !source.endsWith("/projectzomboid.jar") && !source.endsWith("/ZombieBuddy.jar") &&
                !cls.getName().startsWith("com.yuruichang.pzdebug.") && initialized(cls)) {
                result.add(cls); if (result.size() >= 256) break;
            }
        }
        return result;
    }
    private static String source(Class<?> cls) {
        try {
            var domain = cls.getProtectionDomain();
            return domain != null && domain.getCodeSource() != null && domain.getCodeSource().getLocation() != null
                ? domain.getCodeSource().getLocation().toExternalForm() : null;
        } catch (RuntimeException e) { return null; }
    }
    static Object runtime(Map<String, Object> args) {
        String section = Json.text(args, "section", "summary"), filter = Json.text(args, "filter", "");
        int offset = Json.integer(args, "offset", 0, 0, 1000000), limit = Json.integer(args, "limit", 50, 1, 100);
        refresh();
        return switch (section) {
            case "summary" -> Json.object("java_version", System.getProperty("java.version"),
                "vm_name", System.getProperty("java.vm.name"), "uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime(),
                "instrumentation", instrumentation != null, "loaded_classes", loaded.length,
                "static_initialization_check", shouldInitialize != null, "bytecode_observer", observer != null,
                "unavailable_reason", unavailable, "metrics", metrics(),
                "breakpoint_control", false, "native_engine_internals", false);
            case "metrics" -> metrics();
            case "threads" -> threads(offset, limit, filter);
            case "classes" -> classes(offset, limit, filter);
            case "mods" -> mods(offset, limit, filter);
            case "patches" -> patches(offset, limit, filter);
            default -> throw new BridgeRuntime.Failure("ARGUMENT", "Unknown Java runtime section");
        };
    }
    private static Object metrics() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        List<Object> collectors = new ArrayList<>();
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans())
            collectors.add(Json.object("name", gc.getName(), "collections", gc.getCollectionCount(), "time_ms", gc.getCollectionTime()));
        return Json.object("timestamp_ms", System.currentTimeMillis(),
            "heap", Json.object("used", heap.getUsed(), "committed", heap.getCommitted(), "max", heap.getMax()),
            "non_heap", Json.object("used", nonHeap.getUsed(), "committed", nonHeap.getCommitted(), "max", nonHeap.getMax()),
            "threads", threads.getThreadCount(), "peak_threads", threads.getPeakThreadCount(), "gc", collectors);
    }
    private static Object threads(int offset, int limit, String filter) {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long[] ids = bean.getAllThreadIds();
        List<Object> results = new ArrayList<>();
        for (ThreadInfo info : bean.getThreadInfo(ids, 24)) {
            if (info == null || !info.getThreadName().contains(filter)) continue;
            List<Object> stack = new ArrayList<>();
            for (StackTraceElement frame : info.getStackTrace()) stack.add(Json.object(
                "class", frame.getClassName(), "method", frame.getMethodName(), "file", frame.getFileName(),
                "line", frame.getLineNumber(), "native", frame.isNativeMethod()));
            results.add(Json.object("id", info.getThreadId(), "name", info.getThreadName(), "state", info.getThreadState().name(),
                "lock_name", info.getLockName(), "lock_owner_id", info.getLockOwnerId(), "stack", stack));
        }
        long[] deadlocks = bean.isSynchronizerUsageSupported() ? bean.findDeadlockedThreads() : bean.findMonitorDeadlockedThreads();
        List<Object> dead = new ArrayList<>(); if (deadlocks != null) for (long id : deadlocks) dead.add(id);
        var page = page(results, offset, limit); page.put("deadlocked_thread_ids", dead);
        page.put("virtual_threads_included", false); return page;
    }
    private static Object classes(int offset, int limit, String filter) {
        List<Object> result = new ArrayList<>();
        for (Class<?> cls : loaded) if (cls.getName().contains(filter)) {
            result.add(Json.object("name", cls.getName(), "target", "class:" + cls.getName(),
                "loader_id", System.identityHashCode(cls.getClassLoader()), "source", source(cls),
                "initialized", shouldInitialize == null ? null : initialized(cls),
                "modifiable", instrumentation != null && instrumentation.isModifiableClass(cls)));
        }
        var out = page(result, offset, limit); out.put("inventory_available", instrumentation != null); return out;
    }
    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Class<?> cls = object instanceof Class<?> type ? type : object.getClass();
        Field field = cls.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException(name);
        return field.get(object instanceof Class<?> ? null : object);
    }
    private static Object mods(int offset, int limit, String filter) {
        List<Object> result = new ArrayList<>(); String reason = null;
        try {
            Class<?> cls = Class.forName("me.zed_0xff.zombie_buddy.Loader", false, JavaDiagnostics.class.getClassLoader());
            if (!initialized(cls)) throw new IllegalAccessException("Loader initialization not confirmed");
            Map<?, ?> registry = (Map<?, ?>) field(cls, "g_jarLoadStatus");
            for (Object state : registry.values()) {
                Object id = field(state, "id");
                if (!(id instanceof String name) || !name.contains(filter)) continue;
                Object path = field(state, "jarPath");
                result.add(Json.object("id", name, "jar_path", path instanceof Path p ? p.toString() : null,
                    "sha256", field(state, "sha256"), "reason", field(state, "reason"), "decision", field(state, "decision")));
            }
        } catch (ReflectiveOperationException | RuntimeException e) { reason = e.getClass().getSimpleName(); }
        var out = page(result, offset, limit); out.put("source", "zombiebuddy_load_registry");
        out.put("available", reason == null); out.put("unavailable_reason", reason); return out;
    }
    private static Object patches(int offset, int limit, String filter) {
        List<Object> result = new ArrayList<>(); String reason = null;
        try {
            Class<?> cls = Class.forName("local.zbselective.TargetInstrumentation", false, JavaDiagnostics.class.getClassLoader());
            if (!initialized(cls)) throw new IllegalAccessException("Registry initialization not confirmed");
            Map<?, ?> registry = (Map<?, ?>) field(cls, "targets");
            for (var entry : registry.entrySet()) {
                String pkg = (String) entry.getKey();
                Object exact = field(entry.getValue(), "exact");
                for (Object name : (Set<?>) exact) if (((String) name).contains(filter) || pkg.contains(filter))
                    result.add(Json.object("package", pkg, "class", ((String) name).replace('/', '.'), "kind", "registered_exact_target"));
                // Wildcard rules remain opaque; matching classes cannot imply patch success.
                result.add(Json.object("package", pkg, "kind", "wildcard_rules", "count", ((List<?>)field(entry.getValue(), "patterns")).size()));
            }
        } catch (ReflectiveOperationException | RuntimeException e) { reason = e.getClass().getSimpleName(); }
        for (var hash : hashes) if (((String)hash.get("class")).contains(filter)) result.add(hash);
        var out = page(result, offset, limit);
        out.put("target_registry_available", reason == null); out.put("unavailable_reason", reason);
        out.put("observation_stage", "observer_input_after_installation");
        out.put("final_runtime_bytecode_proven", false); out.put("observer_dropped", observedDropped);
        return out;
    }
    static void drainObservations(Recorder recorder) {
        for (int i = 0; i < 16; i++) {
            Bytes item = observed.poll(); if (item == null) break;
            try {
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(item.bytes()));
                var record = Json.object("kind", "observed_transform_input", "sequence", ++observedSequence,
                    "class", item.name(), "loader_id", item.loader(), "sha256", digest,
                    "bytes", item.bytes().length, "redefinition", item.redefinition());
                hashes.add(record); if (hashes.size() > 256) hashes.remove();
                if (recorder.enabled()) recorder.offer("java_transform", item.name(), record);
            } catch (Exception e) { observedDropped++; }
        }
    }
    static Map<String, Object> page(List<Object> values, int offset, int limit) {
        int start = Math.min(offset, values.size()), end = Math.min(values.size(), start + limit);
        return Json.object("total", values.size(), "items", new ArrayList<>(values.subList(start, end)),
            "offset", offset, "next_offset", end, "has_more", end < values.size());
    }
    static void close() {
        if (observer != null && instrumentation != null) instrumentation.removeTransformer(observer);
        observer = null; observed.clear();
    }
}
