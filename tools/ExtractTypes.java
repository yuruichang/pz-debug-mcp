import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

public final class ExtractTypes {
    private static String q(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
    public static void main(String[] args) throws Exception {
        StringJoiner classes = new StringJoiner(",", "{", "}");
        int count = 0;
        ArrayDeque<String> pending = new ArrayDeque<>(Files.readAllLines(Path.of(args[0])));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!seen.add(name)) continue;
            try {
                Class<?> type = Class.forName(name, false, ClassLoader.getSystemClassLoader());
                StringJoiner parents = new StringJoiner(",", "[", "]");
                if (type.getSuperclass() != null) {
                    parents.add(q(type.getSuperclass().getName()));
                    pending.add(type.getSuperclass().getName());
                }
                for (Class<?> parent : type.getInterfaces()) {
                    parents.add(q(parent.getName()));
                    pending.add(parent.getName());
                }
                StringJoiner methods = new StringJoiner(",", "[", "]");
                Method[] declared = type.getDeclaredMethods();
                Arrays.sort(declared, Comparator.comparing(Method::toGenericString));
                for (Method method : declared) {
                    if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) continue;
                    StringJoiner parameters = new StringJoiner(",", "[", "]");
                    for (Class<?> parameter : method.getParameterTypes()) parameters.add(q(parameter.getName()));
                    methods.add("{\"name\":" + q(method.getName()) + ",\"parameters\":" + parameters +
                        ",\"returns\":" + q(method.getReturnType().getName()) + "}");
                }
                classes.add(q(name) + ":{\"parents\":" + parents + ",\"methods\":" + methods + "}");
                count++;
            } catch (LinkageError | ReflectiveOperationException error) {
                classes.add(q(name) + ":{\"parents\":[],\"methods\":[],\"unavailable\":" + q(error.getClass().getSimpleName()) + "}");
            }
        }
        Files.writeString(Path.of(args[1]), classes.toString());
        System.out.println("Public type signatures: " + count);
    }
}
