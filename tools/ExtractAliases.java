import java.nio.file.*;
import java.util.*;
import se.krka.kahlua.integration.annotations.LuaMethod;

public final class ExtractAliases {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("zombie.Lua.LuaManager$GlobalObject", false, ClassLoader.getSystemClassLoader());
        var rows = new ArrayList<String>();
        for (var method : type.getDeclaredMethods()) {
            LuaMethod annotation = method.getAnnotation(LuaMethod.class);
            if (annotation == null) continue;
            String alias = annotation.name().isEmpty() ? method.getName() : annotation.name();
            rows.add(method.getName() + "\t" + alias);
        }
        Collections.sort(rows);
        Files.write(Path.of(args[0]), rows);
        if (args.length > 1) {
            var supers = new ArrayList<String>();
            for (String name : Files.readAllLines(Path.of(args[1]))) {
                try {
                    Class<?> current = Class.forName(name, false, ClassLoader.getSystemClassLoader());
                    if (current.getSuperclass() != null) supers.add(name + "\t" + current.getSuperclass().getName());
                } catch (LinkageError | ClassNotFoundException ignored) { }
            }
            Files.write(Path.of(args[2]), supers);
        }
    }
}
