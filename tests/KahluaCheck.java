import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.*;

public final class KahluaCheck {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        var platform = J2SEPlatform.getInstance();
        var environment = platform.newEnvironment();
        var thread = new KahluaThread(platform, environment);
        thread.debugOwnerThread = Thread.currentThread();
        run(thread, environment, Files.readString(root.resolve("tests/harness.lua")), "harness");
        var sources = (KahluaTable) environment.rawget("sources");
        Path shared = root.resolve("Contents/mods/PZDebugMCP/42/media/lua/shared");
        try (var paths = Files.walk(shared)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".lua")).toList()) {
                String name = shared.relativize(file).toString().replace('\\', '/').replaceFirst("\\.lua$", "");
                sources.rawset(name, Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        run(thread, environment, Files.readString(root.resolve("tests/kahlua_cases.lua")), "cases");
        System.out.println("Kahlua integration checks passed");
    }
    private static void run(KahluaThread thread, KahluaTable env, String source, String name) throws Exception {
        Object[] result = thread.pcall(LuaCompiler.loadstring(source, name, env), new Object[0]);
        if (!Boolean.TRUE.equals(result[0])) throw new AssertionError(Arrays.toString(result));
    }
}
