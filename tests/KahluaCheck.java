import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.*;
import se.krka.kahlua.converter.*;
import se.krka.kahlua.integration.expose.LuaJavaClassExposer;
import zombie.Lua.KahluaNumberConverter;

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
        var converters = new KahluaConverterManager();
        KahluaNumberConverter.install(converters);
        var exposer = new LuaJavaClassExposer(converters, platform, environment, environment) {
            @Override public boolean shouldExpose(Class<?> type) {
                return type == ReadableObject.class || type == Object.class || type == Class.class ||
                    type == java.lang.reflect.Field.class || type == java.lang.reflect.Method.class || type == String.class;
            }
        };
        exposer.exposeLikeJava(ReadableObject.class);
        exposer.exposeLikeJava(java.lang.reflect.Field.class);
        exposer.exposeLikeJava(java.lang.reflect.Method.class);
        exposer.exposeLikeJava(Class.class);
        for (var method : Reflection.class.getDeclaredMethods()) {
            environment.rawset(method.getName(), null);
            exposer.exposeGlobalClassFunction(environment, Reflection.class, method, method.getName());
        }
        environment.rawset("realObject", new ReadableObject());
        run(thread, environment, "assert(getNumClassFields ~= nil,'missing fields API'); " +
            "assert(getNumClassFields(realObject)==1); local f=getClassField(realObject,0); " +
            "assert(getClassFieldVal(realObject,f)==76);", "native_surface");
        run(thread, environment, "local B=PZDebugMCP; local J=B.Json; " +
            "getPlayer=function() return realObject end; " +
            "local r=request('query_debug',{target='getPlayer'}); assert(r.ok); " +
            "local h=r.result.data.value.handle; " +
            "local a=request('query_debug',{target=h,member='getHealth'}); assert(a.ok,J.encode(a)); assert(a.result.data.value==76); " +
            "local b=request('query_debug',{target=h,action='field',member='health'}); assert(b.ok,J.encode(b)); assert(b.result.data.value==76); " +
            "assert(not request('query_debug',{target=h,member='setHealth',arguments=J.array({1})}).ok);", "native_java");
        System.out.println("Kahlua integration checks passed");
    }
    private static void run(KahluaThread thread, KahluaTable env, String source, String name) throws Exception {
        Object[] result = thread.pcall(LuaCompiler.loadstring(source, name, env), new Object[0]);
        if (!Boolean.TRUE.equals(result[0])) throw new AssertionError(Arrays.toString(result));
    }
    public static final class ReadableObject {
        public double health = 76;
        public double getHealth() { return health; }
        public void setHealth(double value) { health = value; }
    }
    public static final class Reflection {
        public static int getNumClassFields(Object object) { return object.getClass().getDeclaredFields().length; }
        public static java.lang.reflect.Field getClassField(Object object, int index) { return object.getClass().getDeclaredFields()[index]; }
        public static Object getClassFieldVal(Object object, java.lang.reflect.Field field) throws Exception { return field.get(object); }
        public static int getNumClassFunctions(Object object) { return object.getClass().getDeclaredMethods().length; }
        public static java.lang.reflect.Method getClassFunction(Object object, int index) { return object.getClass().getDeclaredMethods()[index]; }
        public static int getMethodParameterCount(java.lang.reflect.Method method) { return method.getParameterCount(); }
        public static String getMethodParameter(java.lang.reflect.Method method, int index) { return method.getParameterTypes()[index].getName(); }
    }
}
