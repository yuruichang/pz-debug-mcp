package com.yuruichang.pzdebug;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import se.krka.kahlua.vm.*;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Uses Kahlua's own breakpoints and stepping, with an independent bounded pause lease. */
public final class LuaDebugger {
    private LuaDebugger() {}
    private record Breakpoint(String id,String file,int line) {}
    private static final Object monitor=new Object();
    private static final Map<String,Breakpoint> owned=new LinkedHashMap<>();
    private static final ArrayBlockingQueue<Runnable> commands=new ArrayBlockingQueue<>(64);
    private static volatile KahluaThread thread;
    private static volatile boolean enabled,installed,paused,cleanupPending;
    private static volatile long leaseDeadline;
    private static Map<String,Object> snapshot=Json.object();
    private static List<Object> sources=List.of();
    private static long sourcesRefreshed;
    private static String lastError,stepAction="resume",session;
    private static int stepOutDepth;
    private static java.lang.instrument.ClassFileTransformer transformer;
    public static final class PauseAdvice {
        @Advice.OnMethodEnter(skipOn=Advice.OnNonDefaultValue.class,suppress=Throwable.class)
        public static boolean enter(@Advice.Argument(0) String file,@Advice.Argument(1) long line) {
            return LuaDebugger.onBreakpoint(file,line);
        }
    }
    static synchronized void install() {
        if(installed)return;
        var inst=JavaDiagnostics.instrumentation;
        if(inst==null||!inst.isRetransformClassesSupported())throw new BridgeRuntime.Failure("LUA_DEBUG_UNSUPPORTED","Retransformation required for Lua pause interception");
        Class<?> cls=zombie.ui.UIManager.class;
        boolean[] applied={false};
        transformer=new AgentBuilder.Default().disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(new AgentBuilder.Listener.Adapter(){
                @Override public void onTransformation(net.bytebuddy.description.type.TypeDescription type,ClassLoader loader,
                    net.bytebuddy.utility.JavaModule module,boolean loaded,net.bytebuddy.dynamic.DynamicType dynamic){applied[0]=true;}
            })
            .type(named(cls.getName()),loader->loader==cls.getClassLoader())
            .transform((builder,type,loader,module,domain)->builder.visit(Advice.to(PauseAdvice.class)
                .on(named("debugBreakpoint").and(takesArguments(String.class,long.class)))))
            .installOn(inst);
        if(!applied[0]){inst.removeTransformer(transformer);transformer=null;throw new BridgeRuntime.Failure("LUA_DEBUG_INSTALL_FAILED","Pause interception not confirmed");}
        installed=true;
    }
    static void gameTick(KahluaThread current) {
        synchronized(monitor) {
            thread=current;
            Runnable command;
            for(int i=0;i<64&&(command=commands.poll())!=null;i++) {
                try{command.run();}catch(RuntimeException e){lastError=e.getClass().getSimpleName()+": "+e.getMessage();}
            }
            if(enabled&&System.currentTimeMillis()-sourcesRefreshed>1000)refreshSources();
        }
    }
    private static void refreshSources() {
        List<Object> next=new ArrayList<>();
        KahluaTable environment=zombie.Lua.LuaManager.env;
        Object bridge=environment==null?null:environment.rawget("PZDebugMCP");
        Object tests=bridge instanceof KahluaTable table?table.rawget("tests"):null;
        if(tests instanceof KahluaTable table) {
            var iterator=table.iterator();
            while(iterator.advance()&&next.size()<128) {
                Object entry=iterator.getValue();
                Object run=entry instanceof KahluaTable t?t.rawget("run"):null;
                if(run instanceof LuaClosure closure) {
                    Prototype p=closure.prototype;
                    TreeSet<Integer> lines=new TreeSet<>();
                    if(p.lines!=null)for(int line:p.lines)if(line>0)lines.add(line);
                    next.add(Json.object("test",scalar(iterator.getKey()),"file",p.filename,"name",p.name,"executable_lines",new ArrayList<>(lines)));
                }
            }
        }
        sources=next;sourcesRefreshed=System.currentTimeMillis();
    }
    private static void schedule(Runnable command) {
        synchronized(monitor) {
            if(paused)command.run();
            else if(!commands.offer(command))throw new BridgeRuntime.Failure("LUA_DEBUG_BUSY","Lua command queue is full");
        }
    }
    private static Object scalar(Object value) {
        if(value==null)return null;
        Class<?> c=value.getClass();
        if(c==String.class){String text=(String)value;return text.substring(0,Math.min(text.length(),4096));}
        if(c==Boolean.class)return value;
        if(c==Double.class||c==Float.class||c==Long.class||c==Integer.class||c==Short.class||c==Byte.class) {
            return Double.isFinite(((Number)value).doubleValue())?value:null;
        }
        return Json.object("class",c.getName(),"identity",System.identityHashCode(value));
    }
    private static Map<String,Object> capture(KahluaThread vm,String file,long line) {
        List<Object> frames=new ArrayList<>();
        Coroutine coroutine=vm.currentCoroutine;
        int depth=coroutine.getCallframeTop();
        for(int i=depth-1;i>=Math.max(0,depth-64);i--) {
            LuaCallFrame frame=coroutine.getCallFrame(i);
            if(frame==null)continue;
            List<Object> locals=new ArrayList<>();
            for(int v=0;v<Math.min(frame.getLocalVarCount(),128);v++) {
                String name=frame.getLocalVarName(v);int index=frame.getLocalVarStackIndex(v);
                Object value;
                try{value=coroutine.getObjectFromStack(index);}catch(RuntimeException e){value=null;}
                locals.add(Json.object("name",name,"stack_index",index,"value",scalar(value)));
            }
            Prototype prototype=frame.closure==null?null:frame.closure.prototype;
            int pc=Math.max(0,frame.pc),current=prototype!=null&&prototype.lines!=null&&pc<prototype.lines.length?prototype.lines[pc]:-1;
            frames.add(Json.object("frame",depth-1-i,"file",prototype==null?null:prototype.filename,
                "name",prototype==null?null:prototype.name,"line",current,"pc",pc,"java",frame.isJava(),"locals",locals));
        }
        return Json.object("session",session,"file",file,"line",vm.currentLine>0?vm.currentLine:line,
            "thread_id",Long.toString(Thread.currentThread().threadId()),"depth",depth,"frames",frames);
    }
    public static boolean onBreakpoint(String file,long line) {
        KahluaThread vm=thread;
        if(!enabled||vm==null||vm.debugOwnerThread!=Thread.currentThread())return false;
        synchronized(monitor) {
            if(!enabled)return false;
            int depth=vm.currentCoroutine.getCallframeTop();
            if(stepAction.equals("out")&&depth>=stepOutDepth) {
                vm.step=true;vm.stepInto=true;return true;
            }
            try {snapshot=capture(vm,file,line);}
            catch(RuntimeException e){lastError="SNAPSHOT: "+e.getMessage();return false;}
            paused=true;
            vm.step=false;vm.stepInto=false;
            while(enabled&&paused) {
                long remaining=leaseDeadline-System.currentTimeMillis();
                if(remaining<=0){lastError="PAUSE_LEASE_EXPIRED";disconnectPaused();break;}
                try{monitor.wait(Math.min(remaining,500));}
                catch(InterruptedException e){Thread.currentThread().interrupt();disconnectPaused();break;}
            }
            return true;
        }
    }
    private static void disconnectPaused() {
        commands.clear();
        removeOwned();
        enabled=false;paused=false;cleanupPending=false;stepAction="resume";
        if(thread!=null){thread.step=false;thread.stepInto=false;}
        monitor.notifyAll();
    }
    private static void disconnectRunning() {
        enabled=false;commands.clear();cleanupPending=true;
        schedule(()->{
            synchronized(monitor){
                removeOwned();
                if(thread!=null){thread.step=false;thread.stepInto=false;}
                stepAction="resume";cleanupPending=false;
            }
        });
    }
    private static void removeOwned() {
        if(thread!=null) for(Breakpoint bp:owned.values()) if(thread.hasBreakpoint(bp.file(),bp.line()))thread.breakpointToggle(bp.file(),bp.line());
        owned.clear();
    }
    static Object request(Map<String,Object> args) {
        String action=Json.text(args,"action","status");
        synchronized(monitor) {
            leaseDeadline=System.currentTimeMillis()+30000;
            return switch(action) {
                case "connect" -> {
                    if(enabled)throw new BridgeRuntime.Failure("LUA_DEBUG_CONNECTED","Disconnect before creating a new Lua debugger session");
                    if(cleanupPending)throw new BridgeRuntime.Failure("LUA_DEBUG_CLEANUP_PENDING","Wait for game-thread cleanup before reconnecting");
                    if(thread==null)throw new BridgeRuntime.Failure("LUA_THREAD_UNAVAILABLE","Enter the world before connecting Lua debugging");
                    install();enabled=true;session=UUID.randomUUID().toString();lastError=null;sourcesRefreshed=0;yield status();
                }
                case "status","heartbeat" -> status();
                case "sources" -> Json.object("sources",sources,"source","registered_bridge_test_prototypes","session",session);
                case "disconnect" -> {
                    if(paused)disconnectPaused();
                    else disconnectRunning();
                    yield status();
                }
                case "breakpoint_add" -> {
                    if(!enabled)throw new BridgeRuntime.Failure("LUA_DEBUG_DISCONNECTED","Connect Lua debugging first");
                    String file=Json.text(args,"file","");int line=Json.integer(args,"line",0,1,Integer.MAX_VALUE);
                    if(file.isEmpty()||file.length()>4096)throw new IllegalArgumentException("Lua source filename required");
                    if(owned.size()>=128)throw new BridgeRuntime.Failure("BREAKPOINT_LIMIT","At most 128 Lua breakpoints");
                    String id=UUID.randomUUID().toString();
                    schedule(()->{synchronized(monitor){
                        if(thread.hasBreakpoint(file,line))throw new BridgeRuntime.Failure("BREAKPOINT_EXISTS","An existing UI breakpoint is not owned by this session");
                        thread.breakpointToggle(file,line);owned.put(id,new Breakpoint(id,file,line));
                    }});
                    yield Json.object("breakpoint_id",id,"queued",!paused,"line",line,"file",file);
                }
                case "breakpoint_remove" -> {
                    String id=Json.text(args,"breakpoint_id","");
                    schedule(()->{synchronized(monitor){Breakpoint bp=owned.remove(id);if(bp!=null&&thread.hasBreakpoint(bp.file(),bp.line()))thread.breakpointToggle(bp.file(),bp.line());}});
                    yield status();
                }
                case "pause" -> {
                    if(!enabled)throw new BridgeRuntime.Failure("LUA_DEBUG_DISCONNECTED","Connect Lua debugging first");
                    schedule(()->{thread.step=true;thread.stepInto=true;stepAction="into";});yield status();
                }
                case "frames" -> {
                    if(!paused)throw new BridgeRuntime.Failure("NOT_PAUSED","Lua VM is not stopped in this debugger");
                    yield snapshot;
                }
                case "resume","step" -> {
                    if(!paused)throw new BridgeRuntime.Failure("NOT_PAUSED","Lua VM must be paused");
                    stepAction=action.equals("resume")?"resume":Json.text(args,"depth","into");
                    if(!Set.of("resume","into","over","out").contains(stepAction))throw new IllegalArgumentException("Step into/over/out");
                    stepOutDepth=thread.currentCoroutine.getCallframeTop();
                    thread.lastCallFrameIdx=stepOutDepth;
                    thread.step=!stepAction.equals("resume");
                    thread.stepInto=!stepAction.equals("over");
                    paused=false;monitor.notifyAll();yield status();
                }
                default -> throw new BridgeRuntime.Failure("UNKNOWN_OPERATION","Unsupported Lua debugger operation");
            };
        }
    }
    private static Object status() {
        List<Object> list=new ArrayList<>();
        for(Breakpoint bp:owned.values())list.add(Json.object("breakpoint_id",bp.id(),"file",bp.file(),"line",bp.line()));
        return Json.object("connected",enabled,"installed",installed,"paused",paused,"session",session,
            "breakpoints",list,"queued_commands",commands.size(),"cleanup_pending",cleanupPending,
            "last_error",lastError,"pause_lease_ms",30000);
    }
    static void maintenance() {
        synchronized(monitor) {
            if(enabled&&System.currentTimeMillis()>leaseDeadline) {
                if(paused)disconnectPaused();
                else disconnectRunning();
            }
        }
    }
    static void close() {
        synchronized(monitor){
            if(paused)disconnectPaused();
            else disconnectRunning();
        }
        if(transformer!=null&&JavaDiagnostics.instrumentation!=null)JavaDiagnostics.instrumentation.removeTransformer(transformer);
        installed=false;transformer=null;
    }
}
