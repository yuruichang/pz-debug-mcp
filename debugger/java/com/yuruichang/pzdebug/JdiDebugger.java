package com.yuruichang.pzdebug;

import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** External JDI controller: remains responsive when the target VM is suspended. */
public final class JdiDebugger {
    private volatile VirtualMachine vm;
    private volatile String session, lastError;
    private final Map<String, Spec> breakpoints = new LinkedHashMap<>();
    private final Map<String, String> breakpointErrors = new LinkedHashMap<>();
    private final Map<Long, EventSet> stops = new ConcurrentHashMap<>();
    private final Set<Long> manualStops = ConcurrentHashMap.newKeySet();
    private final ArrayDeque<Object> events = new ArrayDeque<>();
    private final Map<Long, ObjectReference> objects = new LinkedHashMap<>();
    private record Spec(String id, String cls, String method, String signature, int line, boolean exception) {}
    private static final class Failure extends RuntimeException {
        final String code;
        Failure(String code, String message) { super(message); this.code = code; }
    }
    private static String text(Map<String,Object> a, String key, String fallback) { return Json.text(a,key,fallback); }
    private void connected() { if (vm == null) throw new Failure("DEBUGGER_DISCONNECTED","Connect to the JDWP listener first"); }
    private synchronized void event(Object item) { events.add(item); while (events.size()>128) events.remove(); }
    private void attach(Map<String,Object> a) throws Exception {
        detach();
        String host=text(a,"host","127.0.0.1");
        if (!Set.of("127.0.0.1","::1","localhost").contains(host)) throw new Failure("LOCAL_ONLY","JDWP connection must use loopback");
        int port=Json.integer(a,"port",8801,1,65535);
        AttachingConnector connector=Bootstrap.virtualMachineManager().attachingConnectors().stream()
            .filter(c->c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var args=connector.defaultArguments();
        args.get("hostname").setValue(host); args.get("port").setValue(Integer.toString(port));
        args.get("timeout").setValue("5000");
        VirtualMachine target=connector.attach(args);
        vm=target; session=UUID.randomUUID().toString(); lastError=null;events.clear();
        Thread watcher=new Thread(()->watch(target),"PZ-JDI-events"); watcher.setDaemon(true); watcher.start();
    }
    private void watch(VirtualMachine target) {
        try {
            while(vm==target) {
                EventSet set=target.eventQueue().remove(250);
                if(set==null) continue;
                boolean keep=false;
                for(Event event:set) {
                    if(event instanceof ClassPrepareEvent prepared) {
                        synchronized(this) {
                            if(vm!=target)break;
                            Spec spec=breakpoints.get(String.valueOf(event.request().getProperty("id")));
                            if(spec!=null)try{install(spec,prepared.referenceType());}
                            catch(Exception e){
                                breakpointErrors.put(spec.id(),e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
                                event(Json.object("kind","breakpoint_error","request_id",spec.id(),"message",breakpointErrors.get(spec.id())));
                            }
                        }
                    } else if(event instanceof LocatableEvent location) {
                        if(event instanceof StepEvent) target.eventRequestManager().deleteEventRequest(event.request());
                        long tid=location.thread().uniqueID();
                        stops.put(tid,set); keep=true;
                        event(Json.object("kind",event instanceof StepEvent?"step":event instanceof ExceptionEvent?"exception":"breakpoint",
                            "thread_id",tid,"class",location.location().declaringType().name(),
                            "method",location.location().method().name(),"line",location.location().lineNumber(),
                            "request_id",event.request()==null?null:event.request().getProperty("id")));
                    } else if(event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        if(vm==target) disconnected("VM_EXITED");
                    }
                }
                if(!keep) set.resume();
            }
        } catch(VMDisconnectedException e) { if(vm==target) disconnected("VM_DISCONNECTED"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        catch(Exception e) {
            if(vm==target) {
                lastError="EVENT_LOOP: "+e.getClass().getSimpleName();
                try { target.dispose(); } catch(RuntimeException ignored) {}
                disconnected(lastError);
            }
        }
    }
    private synchronized void disconnected(String reason) {
        vm=null; lastError=reason; stops.clear(); manualStops.clear(); objects.clear(); breakpoints.clear();breakpointErrors.clear();
    }
    private synchronized void detach() {
        VirtualMachine old=vm; vm=null;
        if(old!=null) { try { old.dispose(); } catch(RuntimeException ignored) {} }
        stops.clear(); manualStops.clear(); objects.clear(); breakpoints.clear();breakpointErrors.clear();session=null;
    }
    private ThreadReference thread(Map<String,Object> a) {
        connected();
        long id=Long.parseLong(text(a,"thread_id","0"));
        return vm.allThreads().stream().filter(t->t.uniqueID()==id).findFirst()
            .orElseThrow(()->new Failure("THREAD_NOT_FOUND","Thread is no longer present"));
    }
    private Object value(Value value) {
        if(value==null) return null;
        if(value instanceof BooleanValue v) return v.booleanValue();
        if(value instanceof CharValue v) return Character.toString(v.charValue());
        if(value instanceof ByteValue v) return v.byteValue();
        if(value instanceof ShortValue v) return v.shortValue();
        if(value instanceof IntegerValue v) return v.intValue();
        if(value instanceof LongValue v) return v.longValue();
        if(value instanceof FloatValue v) return Float.isFinite(v.floatValue())?v.floatValue():null;
        if(value instanceof DoubleValue v) return Double.isFinite(v.doubleValue())?v.doubleValue():null;
        if(value instanceof StringReference s) {
            String text=s.value(); return text.substring(0,Math.min(4096,text.length()));
        }
        ObjectReference ref=(ObjectReference)value;
        if(objects.size()>=2048) objects.remove(objects.keySet().iterator().next());
        objects.put(ref.uniqueID(),ref);
        return Json.object("object_id",Long.toString(ref.uniqueID()),"class",ref.referenceType().name(),"session",session);
    }
    private void install(Spec spec,ReferenceType type) throws Exception {
        var manager=vm.eventRequestManager();
        if(spec.exception()) {
            ExceptionRequest r=manager.createExceptionRequest(type,true,true);
            r.putProperty("id",spec.id()); r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); r.enable(); return;
        }
        List<Location> locations=new ArrayList<>();
        if(spec.line()>0) locations.addAll(type.locationsOfLine(spec.line()));
        else {
            for(Method m:type.methodsByName(spec.method())) {
                if((spec.signature().isEmpty()||m.signature().equals(spec.signature()))&&!m.isAbstract()&&!m.isNative())
                    locations.add(m.location());
            }
        }
        if(locations.isEmpty()) throw new Failure("LOCATION_NOT_FOUND","No executable location or line debug information");
        if(locations.size()>32) throw new Failure("LOCATION_LIMIT","Specify a narrower method signature");
        for(Location location:locations) {
            BreakpointRequest r=manager.createBreakpointRequest(location);
            r.putProperty("id",spec.id()); r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); r.enable();
        }
    }
    private Object add(Map<String,Object> a) throws Exception {
        connected();
        if(breakpoints.size()>=128) throw new Failure("BREAKPOINT_LIMIT","At most 128 requests");
        String cls=text(a,"class_name","");
        if(cls.isBlank()) throw new Failure("ARGUMENT","class_name required");
        Spec spec=new Spec(UUID.randomUUID().toString(),cls,text(a,"method",""),text(a,"signature",""),
            Json.integer(a,"line",0,0,Integer.MAX_VALUE),Boolean.TRUE.equals(a.get("exception")));
        if(spec.line()==0&&spec.method().isBlank()&&!spec.exception()) throw new Failure("ARGUMENT","Line or method required");
        try {
            var prepare=vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter(cls); prepare.putProperty("id",spec.id());
            prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); prepare.enable();
            for(ReferenceType type:vm.classesByName(cls)) install(spec,type);
            breakpoints.put(spec.id(),spec);
        } catch(Exception e) { remove(spec.id()); throw e; }
        return Json.object("breakpoint_id",spec.id(),"pending",vm.classesByName(cls).isEmpty());
    }
    private void remove(String id) {
        if(vm!=null) {
            var m=vm.eventRequestManager(); List<EventRequest> owned=new ArrayList<>();
            owned.addAll(m.breakpointRequests());owned.addAll(m.classPrepareRequests());owned.addAll(m.exceptionRequests());
            for(var request:owned) if(id.equals(request.getProperty("id"))) m.deleteEventRequest(request);
        }
        breakpoints.remove(id);
        breakpointErrors.remove(id);
    }
    private Object status() {
        return Json.object("connected",vm!=null,"session",session,"last_error",lastError,
            "stopped_threads",stops.keySet().stream().map(String::valueOf).toList(),
            "manual_suspensions",manualStops.stream().map(String::valueOf).toList(),
            "breakpoints",breakpoints.size(),"breakpoint_errors",new LinkedHashMap<>(breakpointErrors),"events",new ArrayList<>(events));
    }
    private Object frames(Map<String,Object> a) throws Exception {
        ThreadReference t=thread(a);
        if(!t.isSuspended()) throw new Failure("NOT_PAUSED","Pause the thread before inspecting its stack");
        int limit=Json.integer(a,"limit",32,1,64); List<Object> out=new ArrayList<>();
        for(int i=0;i<Math.min(t.frameCount(),limit);i++) {
            StackFrame f=t.frame(i); Location loc=f.location();
            List<Object> locals=new ArrayList<>(); boolean available=true;int localCount=0;
            try {
                var variables=f.visibleVariables();localCount=variables.size();
                for(var entry:f.getValues(variables.subList(0,Math.min(128,localCount))).entrySet())
                    locals.add(Json.object("name",entry.getKey().name(),"type",entry.getKey().typeName(),"value",value(entry.getValue())));
            } catch(AbsentInformationException|NativeMethodException e) {available=false;}
            List<Object> args=new ArrayList<>();boolean argumentsAvailable=true;
            try{for(Value v:f.getArgumentValues())args.add(value(v));}
            catch(NativeMethodException e){argumentsAvailable=false;}
            out.add(Json.object("frame",i,"class",loc.declaringType().name(),"method",loc.method().name(),
                "signature",loc.method().signature(),"line",loc.lineNumber(),"code_index",loc.codeIndex(),
                "locals_available",available,"locals_total",localCount,"locals_truncated",localCount>128,
                "locals",locals,"arguments_available",argumentsAvailable,"arguments",args,"this",value(f.thisObject())));
        }
        return Json.object("session",session,"thread_id",Long.toString(t.uniqueID()),"frames",out);
    }
    private void resume(ThreadReference t) {
        EventSet set=stops.remove(t.uniqueID());
        if(set!=null) set.resume();
        if(manualStops.remove(t.uniqueID())) t.resume();
    }
    private Object bytecodes(Map<String,Object> a) throws Exception {
        connected();
        if(!vm.canGetBytecodes()) throw new Failure("UNSUPPORTED","Target VM cannot expose current method bytecodes");
        List<Object> out=new ArrayList<>();int offset=Json.integer(a,"offset",0,0,Integer.MAX_VALUE),limit=Json.integer(a,"limit",100,1,100);
        for(var type:vm.classesByName(text(a,"class_name",""))) for(var method:type.methodsByName(text(a,"method",""))) {
            if(!text(a,"signature","").isEmpty()&&!method.signature().equals(a.get("signature"))) continue;
            if(method.isAbstract()||method.isNative()) continue;
            byte[] bytes=method.bytecodes();
            int start=Math.min(offset,bytes.length),end=Math.min(start+limit,bytes.length);
            out.add(Json.object("class",type.name(),"method",method.name(),"signature",method.signature(),
                "length",bytes.length,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                "offset",start,"hex",HexFormat.of().formatHex(bytes,start,end),"has_more",end<bytes.length,
                "source","current_vm_method_bytecodes"));
        }
        if(out.isEmpty()) throw new Failure("METHOD_NOT_FOUND","Loaded concrete method required");
        return Json.object("methods",out,"session",session);
    }
    private synchronized Object execute(String op,Map<String,Object> a) throws Exception {
        return switch(op) {
            case "connect" -> {attach(a);yield status();}
            case "status" -> status();
            case "disconnect","close" -> {detach();yield status();}
            case "breakpoint_add" -> add(a);
            case "breakpoint_remove" -> {connected();remove(text(a,"breakpoint_id",""));yield status();}
            case "threads" -> {
                connected();List<Object> out=new ArrayList<>();
                for(var t:vm.allThreads()) out.add(Json.object("thread_id",Long.toString(t.uniqueID()),"name",t.name(),
                    "suspended",t.isSuspended(),"status",t.status()));
                yield Json.object("threads",out,"session",session);
            }
            case "pause" -> {ThreadReference t=thread(a);if(!t.isSuspended()){t.suspend();manualStops.add(t.uniqueID());}yield status();}
            case "resume" -> {if(text(a,"thread_id","").isEmpty()){connected();for(var t:vm.allThreads())resume(t);}else resume(thread(a));yield status();}
            case "frames" -> frames(a);
            case "step" -> {
                ThreadReference t=thread(a);if(!t.isSuspended())throw new Failure("NOT_PAUSED","Thread must be paused");
                var manager=vm.eventRequestManager();
                for(StepRequest r:new ArrayList<>(manager.stepRequests()))if(r.thread().equals(t))manager.deleteEventRequest(r);
                int depth=switch(text(a,"depth","into")){case"into"->StepRequest.STEP_INTO;case"over"->StepRequest.STEP_OVER;case"out"->StepRequest.STEP_OUT;default->throw new Failure("ARGUMENT","Step depth into/over/out");};
                StepRequest r=manager.createStepRequest(t,StepRequest.STEP_LINE,depth);
                r.addCountFilter(1);r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);r.enable();resume(t);yield status();
            }
            case "object" -> {
                connected();ObjectReference ref=objects.get(Long.parseLong(text(a,"object_id","0")));
                if(ref==null||!Objects.equals(session,a.get("session")))throw new Failure("OBJECT_EXPIRED","Object belongs to another debugger session");
                List<Object> out=new ArrayList<>();int limit=Json.integer(a,"limit",32,1,100);
                if(ref instanceof ArrayReference array) {
                    int offset=Json.integer(a,"offset",0,0,array.length());
                    for(Value v:array.getValues(offset,Math.min(limit,array.length()-offset)))out.add(value(v));
                } else {
                    var fields=ref.referenceType().allFields();int offset=Json.integer(a,"offset",0,0,fields.size());
                    for(Field f:fields.subList(offset,Math.min(offset+limit,fields.size())))
                        out.add(Json.object("name",f.name(),"type",f.typeName(),"value",value(ref.getValue(f))));
                }
                yield Json.object("values",out,"session",session);
            }
            case "bytecode" -> bytecodes(a);
            default -> throw new Failure("UNKNOWN_OPERATION","Unsupported Java debugger operation");
        };
    }
    public static void main(String[] args) throws Exception {
        JdiDebugger debugger=new JdiDebugger();
        try(var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
            var output=new PrintWriter(new OutputStreamWriter(System.out,StandardCharsets.UTF_8),true)) {
            String line;
            while((line=input.readLine())!=null) {
                String id=null;Map<String,Object> response;
                try {
                    var request=Json.map(Json.decode(line));id=Json.text(request,"id","");
                    if(!id.matches("[a-f0-9]{32}")||Json.integer(request,"protocol",0,1,1)!=1)throw new Failure("PROTOCOL","Invalid debugger request");
                    Object result=debugger.execute(text(request,"operation",""),Json.map(request.get("arguments")));
                    response=Json.object("protocol",1,"id",id,"ok",true,"result",result);
                } catch(Exception e) {
                    response=Json.object("protocol",1,"id",id,"ok",false,"error",Json.object(
                        "code",e instanceof Failure f?f.code:e instanceof AbsentInformationException?"NO_DEBUG_INFO":e instanceof VMDisconnectedException?"VM_DISCONNECTED":"JDI_ERROR",
                        "message",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()));
                }
                output.println(Json.encode(response));
            }
        } finally {debugger.detach();}
    }
}
