import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.constant.*;
import java.security.MessageDigest;

/** Effect metadata extraction only; game classes are never initialized or invoked. */
public final class BytecodeAudit {
    private static final Map<String, ClassModel> classes = new HashMap<>();
    private static final Set<String> missing = new HashSet<>();
    private static final Map<String,String> hashes = new TreeMap<>();
    private static ClassModel load(String name) {
        if (classes.containsKey(name)) return classes.get(name);
        if (missing.contains(name) || classes.size() >= 6000) return null;
        try (InputStream stream = ClassLoader.getSystemResourceAsStream(name + ".class")) {
            if (stream == null) { missing.add(name); return null; }
            byte[] bytes = stream.readAllBytes();
            ClassModel model = ClassFile.of().parse(bytes);
            hashes.put(name.replace('/', '.'), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            classes.put(name, model);
            return model;
        } catch (Throwable error) { missing.add(name); return null; }
    }
    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?,?> map) {
            var parts = new StringJoiner(",", "{", "}");
            for (var entry : map.entrySet()) parts.add(quote(entry.getKey().toString()) + ":" + json(entry.getValue()));
            return parts.toString();
        }
        var parts = new StringJoiner(",", "[", "]");
        for (Object child : (Iterable<?>) value) parts.add(json(child));
        return parts.toString();
    }
    private static String typeName(ClassDesc type) {
        String desc = type.descriptorString();
        if (desc.startsWith("[")) return typeName(ClassDesc.ofDescriptor(desc.substring(1))) + "[]";
        if (desc.startsWith("L")) return desc.substring(1, desc.length()-1).replace('/', '.');
        return Map.of("V","void","Z","boolean","B","byte","C","char","S","short","I","int","J","long","F","float","D","double").get(desc);
    }
    private static Map<String,Object> analyze(ClassModel owner, MethodModel method) {
        String ownerName = owner.thisClass().asInternalName();
        String methodName = method.methodName().stringValue();
        String descriptor = method.methodType().stringValue();
        int flags = method.flags().flagsMask();
        var result = new LinkedHashMap<String,Object>();
        result.put("id", ownerName.replace('/', '.') + "#" + methodName + descriptor);
        result.put("owner", ownerName.replace('/', '.'));
        result.put("name", methodName);
        result.put("descriptor", descriptor);
        var parameters = new ArrayList<String>();
        for (ClassDesc type : method.methodTypeSymbol().parameterList()) parameters.add(typeName(type));
        result.put("parameters", parameters);
        result.put("returns", typeName(method.methodTypeSymbol().returnType()));
        result.put("public", (flags & ClassFile.ACC_PUBLIC) != 0);
        result.put("static", (flags & ClassFile.ACC_STATIC) != 0);
        result.put("final_dispatch", (flags & (ClassFile.ACC_STATIC | ClassFile.ACC_FINAL | ClassFile.ACC_PRIVATE)) != 0 || (owner.flags().flagsMask() & ClassFile.ACC_FINAL) != 0);
        result.put("native", (flags & ClassFile.ACC_NATIVE) != 0);
        result.put("abstract", (flags & ClassFile.ACC_ABSTRACT) != 0);
        var effects = new TreeSet<String>();
        var calls = new ArrayList<Map<String,Object>>();
        var reads = new ArrayList<Map<String,Object>>();
        var initialized = new TreeSet<String>();
        boolean previousThis = false;
        if ((flags & ClassFile.ACC_SYNCHRONIZED) != 0) effects.add("synchronizes");
        Set<Label> seenLabels = Collections.newSetFromMap(new IdentityHashMap<>());
        int byteOffset = 0;
        if (method.code().isPresent()) for (CodeElement element : method.code().get()) {
            if (element instanceof LabelTarget target) seenLabels.add(target.label());
            if (!(element instanceof Instruction instruction)) continue;
            String op = instruction.opcode().name();
            if (instruction instanceof FieldInstruction field) {
                String fieldOwner = field.owner().asInternalName();
                if (op.equals("PUTFIELD") || op.equals("PUTSTATIC")) effects.add("writes_field");
                else {
                    reads.add(Map.of("owner", fieldOwner.replace('/', '.'), "name", field.name().stringValue(), "type", typeName(field.typeSymbol()), "static", op.equals("GETSTATIC")));
                    if (op.equals("GETSTATIC") && !fieldOwner.equals(ownerName)) initialized.add(fieldOwner.replace('/', '.'));
                    if (op.equals("GETFIELD") && !previousThis) effects.add("nullable_receiver");
                }
            }
            if (instruction instanceof InvokeInstruction call) {
                String calledOwner = call.owner().asInternalName();
                ClassModel calledClass = load(calledOwner);
                MethodModel target = calledClass == null ? null : calledClass.methods().stream().filter(m -> m.methodName().equalsString(call.name().stringValue()) && m.methodType().equalsString(call.type().stringValue())).findFirst().orElse(null);
                boolean dispatch = op.equals("INVOKESTATIC") || op.equals("INVOKESPECIAL") ||
                    (calledClass != null && (calledClass.flags().flagsMask() & ClassFile.ACC_FINAL) != 0) ||
                    (target != null && (target.flags().flagsMask() & (ClassFile.ACC_FINAL | ClassFile.ACC_PRIVATE)) != 0);
                calls.add(Map.of("id", calledOwner.replace('/', '.') + "#" + call.name().stringValue() + call.type().stringValue(), "owner", calledOwner.replace('/', '.'), "name", call.name().stringValue(), "descriptor", call.type().stringValue(), "fixed_dispatch", dispatch));
                if (op.equals("INVOKESTATIC") && !calledOwner.equals(ownerName)) initialized.add(calledOwner.replace('/', '.'));
            }
            if (op.matches("[ILFDABCS]ASTORE")) effects.add("writes_array");
            if (op.matches("[ILFDABCS]ALOAD")) effects.add("array_index");
            if (op.equals("ARRAYLENGTH")) effects.add("nullable_array");
            if (Set.of("NEW","NEWARRAY","ANEWARRAY","MULTIANEWARRAY").contains(op)) effects.add("allocates");
            if (op.startsWith("MONITOR")) effects.add("synchronizes");
            if (op.equals("ATHROW")) effects.add("throws");
            if (op.equals("INVOKEDYNAMIC")) effects.add("dynamic_dispatch");
            if (Set.of("IDIV","LDIV","IREM","LREM").contains(op)) effects.add("division_guard_needed");
            if (op.equals("CHECKCAST")) effects.add("cast_guard_needed");
            if (instruction instanceof BranchInstruction branch && seenLabels.contains(branch.target())) effects.add("loop_guard_needed");
            if (method.code().get() instanceof CodeAttribute code) {
                final int currentOffset = byteOffset;
                if (instruction instanceof BranchInstruction branch && code.labelToBci(branch.target()) <= byteOffset) effects.add("loop_guard_needed");
                if (instruction instanceof LookupSwitchInstruction sw && (code.labelToBci(sw.defaultTarget()) <= currentOffset || sw.cases().stream().anyMatch(c -> code.labelToBci(c.target()) <= currentOffset))) effects.add("loop_guard_needed");
                if (instruction instanceof TableSwitchInstruction sw && (code.labelToBci(sw.defaultTarget()) <= currentOffset || sw.cases().stream().anyMatch(c -> code.labelToBci(c.target()) <= currentOffset))) effects.add("loop_guard_needed");
            }
            if (instruction instanceof StoreInstruction local && local.slot() == 0 && (flags & ClassFile.ACC_STATIC) == 0) effects.add("receiver_reassigned");
            previousThis = instruction instanceof LoadInstruction local && local.slot() == 0 && local.typeKind() == TypeKind.REFERENCE && (flags & ClassFile.ACC_STATIC) == 0;
            byteOffset += instruction.sizeInBytes();
        }
        result.put("effects", effects);
        result.put("calls", calls);
        result.put("reads", reads);
        result.put("required_initialized", initialized);
        return result;
    }
    public static void main(String[] args) throws Exception {
        for (String root : Files.readAllLines(Path.of(args[0]))) load(root.replace('.', '/'));
        var summaries = new TreeMap<String,Map<String,Object>>();
        var processed = new HashSet<String>();
        while (processed.size() < classes.size()) {
            for (ClassModel owner : new ArrayList<>(classes.values())) {
                String name = owner.thisClass().asInternalName();
                if (!processed.add(name)) continue;
                for (MethodModel method : owner.methods()) {
                    var summary = analyze(owner, method);
                    summaries.put(summary.get("id").toString(), summary);
                }
            }
        }
        Files.writeString(Path.of(args[1]), json(Map.of("methods", summaries, "missing_classes", missing, "class_sha256", hashes)));
        System.out.println("Effect summaries: " + summaries.size() + "; referenced classes: " + classes.size());
    }
}
