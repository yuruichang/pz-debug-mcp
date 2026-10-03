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
        environment.rawset("realOpaque", new OpaqueObject());
        run(thread, environment, "assert(getNumClassFields ~= nil,'missing fields API'); " +
            "assert(getNumClassFields(realObject)>=2); local f=getClassField(realObject,0); " +
            "assert(getClassFieldVal(realObject,f)==76);", "native_surface");
        run(thread, environment, "local B=PZDebugMCP; local J=B.Json; " +
            "local P=require 'PZDebugMCP/ReadPolicy'; P.methods['KahluaCheck$ReadableObject']={getHealth={['']=true}}; " +
            "getPlayer=function() return realObject end; " +
            "local r=request('query_debug',{target='getPlayer'}); assert(r.ok); " +
            "local h=r.result.data.value.handle; " +
            "local a=request('query_debug',{target=h,member='getHealth'}); assert(a.ok,J.encode(a)); assert(a.result.data.value==76); " +
            "local b=request('query_debug',{target=h,action='field',member='health'}); assert(b.ok,J.encode(b)); assert(b.result.data.value==76); " +
            "assert(not request('query_debug',{target=h,member='setHealth',arguments=J.array({1})}).ok); " +
            "assert(not request('query_debug',{target=h,member='getMystery'}).ok); " +
            "request('query_debug',{target=h,action='inspect',limit=100}); assert(realObject:getSideEffectCount()==0); " +
            "assert(realObject:getIdentityCalls()==0,'Object hashCode/equals must not run');", "native_java");
        int errorsBefore = KahluaThread.m_errors_list.size();
        run(thread, environment, "local B=PZDebugMCP; SandboxVars.opaque=realOpaque; SandboxVars.getClass=function() error('must not execute') end; " +
            "request('query_debug',{target='root:SandboxVars'}); for i=1,20 do clock=clock+100; B.tick() end;", "opaque_collection");
        if (KahluaThread.m_errors_list.size() != errorsBefore) throw new AssertionError("Routine collection emitted Lua errors");
        var identityUtil = platform.newTable();
        exposer.exposeGlobalClassFunction(identityUtil, KahluaUtil.class,
            KahluaUtil.class.getMethod("identityHashCode", Object.class), "identityHashCode");
        environment.rawset("KahluaUtil", identityUtil);
        var listA = new ArrayList<String>();
        var listB = new ArrayList<String>();
        environment.rawset("listA", listA);
        environment.rawset("listB", listB);
        run(thread, environment, "SandboxVars.a=listA; SandboxVars.b=listB; " +
            "local s=request('query_debug',{target='root:SandboxVars'}).result.data.value.handle; " +
            "nativeListHandle=request('query_debug',{target=s,action='table',member='a'}).result.data.value.handle; " +
            "local b=request('query_debug',{target=s,action='table',member='b'}).result.data.value.handle; " +
            "assert(nativeListHandle~=b,'Equal Java lists must have distinct handles'); " +
            "local P=require 'PZDebugMCP/ReadPolicy'; P.identityKey=function() return 'collision' end; " +
            "local a2=request('query_debug',{target=s,action='table',member='a'}).result.data.value.handle; " +
            "local b2=request('query_debug',{target=s,action='table',member='b'}).result.data.value.handle; " +
            "assert(a2~=b2,'Identity collisions must not merge objects'); nativeListHandle=a2;", "native_identity");
        listA.add("changed");
        run(thread, environment, "local s=request('query_debug',{target='root:SandboxVars'}).result.data.value.handle; " +
            "local a=request('query_debug',{target=s,action='table',member='a'}).result.data.value.handle; " +
            "assert(a==nativeListHandle,'Mutable Java objects must retain their handle');", "mutable_identity");
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
        private int sideEffects;
        private int identityCalls;
        public double getMystery() { sideEffects++; return 1; }
        public int getSideEffectCount() { return sideEffects; }
        public int getIdentityCalls() { return identityCalls; }
        @Override public int hashCode() { identityCalls++; return 123; }
        @Override public boolean equals(Object other) { identityCalls++; return this == other; }
    }
    public static final class OpaqueObject { }
    public static final class Reflection {
        public static int getNumClassFields(Object object) { return object.getClass().getDeclaredFields().length; }
        public static java.lang.reflect.Field getClassField(Object object, int index) { return object.getClass().getDeclaredFields()[index]; }
        public static Object getClassFieldVal(Object object, java.lang.reflect.Field field) {
            try { return field.get(object); } catch (IllegalAccessException error) { return "<private>"; }
        }
        public static int getNumClassFunctions(Object object) { return object.getClass().getDeclaredMethods().length; }
        public static java.lang.reflect.Method getClassFunction(Object object, int index) { return object.getClass().getDeclaredMethods()[index]; }
        public static int getMethodParameterCount(java.lang.reflect.Method method) { return method.getParameterCount(); }
        public static String getMethodParameter(java.lang.reflect.Method method, int index) { return method.getParameterTypes()[index].getName(); }
    }
}
