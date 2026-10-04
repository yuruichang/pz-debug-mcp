package com.yuruichang.pzdebug;

import java.lang.reflect.*;
import java.util.*;

/** Access only from the registered game thread. Never invoke discovered getters. */
public final class Inspector {
    private record Entry(Object value, String origin, int depth) {}
    private record Job(String handle, int offset) {}
    private final String session;
    private final IdentityHashMap<Object, String> reverse = new IdentityHashMap<>();
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, String> roots = new LinkedHashMap<>();
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private final Map<Class<?>, List<Field>> layouts = new WeakHashMap<>();
    private int maxHandles = 4096, maxDepth = 4;
    private long sequence, evicted, dropped, refreshAt, lastTick;
    private static final Map<String, Set<String>> READERS = Map.of(
        "zombie.characters.IsoGameCharacter", Set.of("getHealth"),
        "zombie.iso.IsoMovingObject", Set.of("getX", "getY", "getZ"),
        "zombie.iso.weather.ClimateManager", Set.of("getTemperature"),
        "zombie.GameTime", Set.of("getTimeOfDay", "getYear", "getMonth", "getDay"),
        "zombie.core.Core", Set.of("getVersionNumber", "getOptionPauseOnFocusloss"));
    Inspector(String session) { this.session = session; }
    public Object describe(Object value, String origin, int depth) {
        if (value == null) return null;
        Class<?> type = value.getClass();
        if (type == String.class) {
            String s = (String) value;
            return s.length() <= 4096 ? s : Json.object("kind", "string", "value", s.substring(0, 4096), "length", s.length(), "truncated", true);
        }
        if (type == Boolean.class || type == Byte.class || type == Short.class || type == Integer.class ||
            type == Long.class || type == Float.class || type == Double.class) {
            if (value instanceof Number n && !Double.isFinite(n.doubleValue())) return Json.object("kind", "non_finite", "class", type.getName());
            return value;
        }
        if (type == Character.class) return String.valueOf((Character) value);
        if (value instanceof Enum<?> e) return Json.object("kind", "enum", "class", type.getName(), "name", e.name());
        if (value instanceof ClassLoader || value instanceof Thread || value instanceof java.lang.reflect.AccessibleObject ||
            value instanceof java.lang.invoke.MethodHandles.Lookup)
            return Json.object("kind", "restricted", "class", type.getName(), "reason", "execution_or_vm_control_object");
        String id = reverse.get(value);
        if (id == null) {
            id = session + ":j" + (++sequence);
            if (entries.size() >= maxHandles) {
                var old = entries.entrySet().iterator().next();
                reverse.remove(old.getValue().value()); entries.remove(old.getKey()); evicted++;
                roots.values().removeIf(old.getKey()::equals);
            }
            entries.put(id, new Entry(value, origin, depth)); reverse.put(value, id);
            if (depth <= maxDepth && traversable(value)) enqueue(new Job(id, 0));
        }
        if (depth == 0) roots.put(origin, id);
        Class<?> actual = value instanceof Class<?> cls ? cls : type;
        return Json.object("kind", value instanceof Class<?> ? "java_class" : "userdata",
            "handle", id, "class", actual.getName());
    }
    private boolean traversable(Object value) {
        Class<?> type = value instanceof Class<?> cls ? cls : value.getClass();
        return type.isArray() || type.getClassLoader() != null;
    }
    private void enqueue(Job job) { if (jobs.size() < 8192) jobs.add(job); else dropped++; }
    public Object value(String id) {
        Entry entry = entries.get(id);
        if (entry == null) throw new BridgeRuntime.Failure("HANDLE_EXPIRED", "Java handle was evicted or belongs to another session");
        return entry.value();
    }
    public void configure(Map<String, Object> args) {
        int size = Json.integer(args, "max_handles", maxHandles, 128, 16384);
        maxDepth = Json.integer(args, "max_depth", maxDepth, 0, 16);
        if (size != maxHandles) { entries.clear(); reverse.clear(); roots.clear(); jobs.clear(); maxHandles = size; }
    }
    private List<Field> fields(Class<?> type) {
        if (layouts.size() >= 512) layouts.clear();
        return layouts.computeIfAbsent(type, cls -> {
            List<Field> result = new ArrayList<>();
            for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                Field[] fields = c.getDeclaredFields();
                Arrays.sort(fields, Comparator.comparing(Field::getName));
                result.addAll(Arrays.asList(fields));
            }
            return result;
        });
    }
    private Object read(Entry entry, Field field) {
        boolean stat = Modifier.isStatic(field.getModifiers());
        if (stat && !JavaDiagnostics.initialized(field.getDeclaringClass()))
            return Json.object("accessible", false, "reason", "class_initialization_not_confirmed");
        if (!stat && entry.value() instanceof Class<?>)
            return Json.object("accessible", false, "reason", "instance_required");
        try {
            if (!field.trySetAccessible()) return Json.object("accessible", false, "reason", "module_access_denied");
            Object value = field.get(stat ? null : entry.value());
            return Json.object("accessible", true, "value", describe(value, entry.origin() + "." + field.getName(), entry.depth() + 1));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Json.object("accessible", false, "reason", e.getClass().getSimpleName());
        }
    }
    public Map<String, Object> inspect(String id, int offset, int limit, boolean values) {
        Entry entry = entries.get(id); value(id);
        Class<?> type = entry.value() instanceof Class<?> cls ? cls : entry.value().getClass();
        List<Object> page = new ArrayList<>();
        int total;
        if (type.isArray() && !(entry.value() instanceof Class<?>)) {
            total = Array.getLength(entry.value());
            for (int i = offset; i < total && page.size() < limit; i++)
                page.add(Json.object("kind", "array_element", "index", i, "value",
                    values ? describe(Array.get(entry.value(), i), entry.origin() + "[" + i + "]", entry.depth() + 1) : null));
        } else {
            List<Field> fields = fields(type); total = fields.size();
            for (int i = offset; i < total && page.size() < limit; i++) {
                Field field = fields.get(i);
                Map<String, Object> item = Json.object("kind", "field", "field_index", i, "name", field.getName(),
                    "owner", field.getDeclaringClass().getName(), "type", field.getType().getTypeName(),
                    "static", Modifier.isStatic(field.getModifiers()), "modifiers", Modifier.toString(field.getModifiers()));
                if (values) item.putAll(Json.map(read(entry, field)));
                page.add(item);
            }
        }
        return Json.object("handle", id, "class", type.getName(), "origin", entry.origin(), "offset", offset,
            "total", total, "items", page, "next_offset", offset + page.size(), "has_more", offset + page.size() < total,
            "method_invocation", "reviewed_allowlist_only");
    }
    public Map<String, Object> query(Map<String, Object> args) {
        String target = Json.text(args, "target", "");
        String action = Json.text(args, "action", "inspect");
        int offset = Json.integer(args, "offset", 0, 0, 1000000), limit = Json.integer(args, "limit", 32, 1, 100);
        if (target.startsWith("class:")) {
            Class<?> cls = JavaDiagnostics.loadedClass(target.substring(6));
            target = (String) Json.map(describe(cls, args.get("target").toString(), 0)).get("handle");
        } else if (target.startsWith("root:java:")) {
            target = roots.get(target.substring(10));
            if (target == null) throw new BridgeRuntime.Failure("ROOT_NOT_FOUND", "Java root not collected");
        }
        Entry entry = entries.get(target); Object object = value(target);
        if (action.equals("methods")) {
            Class<?> type = object instanceof Class<?> cls ? cls : object.getClass();
            List<Object> metadata = new ArrayList<>();
            for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Method method : c.getDeclaredMethods()) {
                List<Object> parameters = new ArrayList<>();
                for (Class<?> param : method.getParameterTypes()) parameters.add(param.getTypeName());
                metadata.add(Json.object("name", method.getName(), "owner", c.getName(), "parameters", parameters,
                    "returns", method.getReturnType().getTypeName(), "modifiers", Modifier.toString(method.getModifiers()),
                    "readable", method.getParameterCount() == 0 && READERS.getOrDefault(c.getName(), Set.of()).contains(method.getName())));
            }
            return JavaDiagnostics.page(metadata, offset, limit);
        }
        if (action.equals("inspect")) return inspect(target, offset, limit, true);
        if (action.equals("field")) {
            Class<?> type = object instanceof Class<?> cls ? cls : object.getClass();
            if (type.isArray()) {
                int index = Json.integer(args, "field_index", -1, 0, Array.getLength(object) - 1);
                return Json.object("field_index", index, "value", describe(Array.get(object, index), entry.origin() + "[" + index + "]", entry.depth() + 1));
            }
            List<Field> fields = fields(type); Field selected = null;
            if (args.get("field_index") != null) selected = fields.get(Json.integer(args, "field_index", 0, 0, fields.size() - 1));
            else for (Field field : fields) if (field.getName().equals(args.get("member"))) { selected = field; break; }
            if (selected == null) throw new BridgeRuntime.Failure("FIELD_NOT_FOUND", "Java field not found");
            Map<String, Object> result = Json.object("name", selected.getName(), "owner", selected.getDeclaringClass().getName());
            result.putAll(Json.map(read(entry, selected))); return result;
        }
        if (action.equals("call")) {
            if (object instanceof Class<?> || args.get("arguments") instanceof List<?> list && !list.isEmpty())
                throw new BridgeRuntime.Failure("NOT_A_READER", "Only reviewed zero-argument instance methods");
            String name = Json.text(args, "member", "");
            try {
                Method method = object.getClass().getMethod(name);
                if (!READERS.getOrDefault(method.getDeclaringClass().getName(), Set.of()).contains(name))
                    throw new BridgeRuntime.Failure("NOT_A_READER", "Method has not been reviewed");
                return Json.object("value", describe(method.invoke(object), entry.origin() + ":" + name, entry.depth() + 1));
            } catch (NoSuchMethodException e) { throw new BridgeRuntime.Failure("NOT_A_READER", "Method has not been reviewed"); }
            catch (ReflectiveOperationException e) { throw new BridgeRuntime.Failure("READ_FAILED", e.getClass().getSimpleName()); }
        }
        throw new BridgeRuntime.Failure("ARGUMENT", "Java action must be inspect/field/methods/call");
    }
    public Map<String, Object> stats() {
        List<Object> list = new ArrayList<>();
        for (var root : roots.entrySet()) if (entries.containsKey(root.getValue()))
            list.add(Json.object("name", root.getKey(), "target", "root:java:" + root.getKey(), "handle", root.getValue()));
        return Json.object("roots", list, "handles", entries.size(), "created", sequence, "evicted", evicted,
            "pending_jobs", jobs.size(), "queue_dropped", dropped, "max_depth", maxDepth);
    }
    public void tick(Recorder recorder, long now, int budgetMs, int jobsPerTick, int interval, int refreshSeconds) {
        if (!recorder.enabled() || now - lastTick < interval) return;
        lastTick = now; long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        for (int i = 0; i < jobsPerTick && (i == 0 || System.nanoTime() < deadline); i++) {
            Job job = jobs.poll(); if (job == null) break;
            Entry entry = entries.get(job.handle());
            if (entry == null || entry.depth() > maxDepth) continue;
            try {
                var result = inspect(job.handle(), job.offset(), 16, true);
                recorder.offer("object", "java:" + entry.origin(), result);
                if (Boolean.TRUE.equals(result.get("has_more"))) enqueue(new Job(job.handle(), ((Number)result.get("next_offset")).intValue()));
            } catch (RuntimeException | LinkageError e) {
                recorder.offer("java_error", "java:" + entry.origin(), Json.object("code", e.getClass().getSimpleName()));
            }
        }
        if (jobs.isEmpty() && now >= refreshAt) {
            refreshAt = now + refreshSeconds * 1000L;
            for (var entry : entries.entrySet()) if (entry.getValue().depth() <= maxDepth && traversable(entry.getValue().value())) enqueue(new Job(entry.getKey(), 0));
        }
    }
}
