package com.yuruichang.pzdebug;

import fixture.TestAgent;
import se.krka.kahlua.vm.*;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import java.util.*;

public final class DebugCheck {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static Map<String,Object> status(){return Json.map(LuaDebugger.request(Json.object("action","status")));}
    public static void main(String[] args)throws Exception {
        JavaDiagnostics.attach(TestAgent.instrumentation);
        var platform=J2SEPlatform.getInstance();var environment=platform.newEnvironment();
        KahluaThread vm=new KahluaThread(platform,environment);
        zombie.core.Core.debug=true;
        zombie.Lua.LuaManager.thread=vm;
        LuaDebugger.gameTick(vm);
        LuaDebugger.request(Json.object("action","connect"));
        String source="local function calc(input)\n local doubled=input*2\n local answer=doubled+7\n return answer\nend\nlocal result=calc(5)\nreturn result\n";
        LuaClosure closure=LuaCompiler.loadstring(source,"debug-fixture.lua",environment);
        name(closure.prototype);
        var bp=Json.map(LuaDebugger.request(Json.object("action","breakpoint_add","file","debug-fixture.lua","line",4)));
        LuaDebugger.gameTick(vm);
        Object[][] result={null};
        Thread owner=new Thread(()->{
            vm.debugOwnerThread=Thread.currentThread();
            result[0]=vm.pcall(closure,new Object[0]);
        },"Lua-debug-fixture");
        owner.start();
        try{waitPaused();}catch(AssertionError e){System.out.println("Result="+Arrays.toString(result[0])+" state="+status());throw e;}
        var frames=Json.map(LuaDebugger.request(Json.object("action","frames")));
        System.out.println(Json.encode(frames));
        check(!((List<?>)frames.get("frames")).isEmpty(),"Lua stack missing");
        boolean found=false;
        for(Object frame:(List<?>)frames.get("frames"))for(Object local:(List<?>)Json.map(frame).get("locals")) {
            var value=Json.map(local);
            if("answer".equals(value.get("name"))) {check(((Number)value.get("value")).intValue()==17,"Lua local value wrong");found=true;}
        }
        check(found,"Lua locals missing");
        LuaDebugger.request(Json.object("action","breakpoint_remove","breakpoint_id",bp.get("breakpoint_id")));
        LuaDebugger.request(Json.object("action","step","depth","out"));
        waitPaused();
        var step=Json.map(LuaDebugger.request(Json.object("action","frames")));
        check(((Number)step.get("depth")).intValue()<((Number)frames.get("depth")).intValue(),"Lua step out did not leave callee");
        LuaDebugger.request(Json.object("action","disconnect"));
        owner.join(3000);
        check(!owner.isAlive(),"Disconnect left Lua stopped");
        check(Boolean.TRUE.equals(result[0][0])&&((Number)result[0][1]).intValue()==17,"Lua behavior changed");
        LuaDebugger.request(Json.object("action","connect"));
        LuaDebugger.request(Json.object("action","breakpoint_add","file","debug-fixture.lua","line",4));
        LuaDebugger.gameTick(vm);
        Thread leaseOwner=new Thread(()->{
            vm.debugOwnerThread=Thread.currentThread();
            result[0]=vm.pcall(closure,new Object[0]);
        },"Lua-lease-fixture");
        leaseOwner.start();waitPaused();
        var deadline=LuaDebugger.class.getDeclaredField("leaseDeadline");deadline.setAccessible(true);
        deadline.setLong(null,System.currentTimeMillis()-1);
        LuaDebugger.maintenance();leaseOwner.join(3000);
        check(!leaseOwner.isAlive()&&!Boolean.TRUE.equals(status().get("connected")),"Expired lease did not resume Lua");
        check(!vm.hasBreakpoint("debug-fixture.lua",4),"Expired lease left owned breakpoint");
        LuaDebugger.request(Json.object("action","connect"));
        LuaDebugger.request(Json.object("action","breakpoint_add","file","debug-fixture.lua","line",4));
        LuaDebugger.request(Json.object("action","disconnect"));
        check(!Boolean.TRUE.equals(status().get("connected")),"Running disconnect stayed enabled");
        LuaDebugger.gameTick(vm);
        check(!vm.hasBreakpoint("debug-fixture.lua",4),"Disconnect executed a stale queued breakpoint");
        LuaDebugger.close();JavaDiagnostics.close();
        System.out.println("Actual Kahlua breakpoint, locals, step-out and disconnect checks passed");
    }
    private static void name(Prototype p){p.filename="debug-fixture.lua";for(Prototype child:p.prototypes)name(child);}
    private static void waitPaused()throws Exception{
        long end=System.currentTimeMillis()+5000;
        while(System.currentTimeMillis()<end){if(Boolean.TRUE.equals(status().get("paused")))return;Thread.sleep(10);}
        throw new AssertionError("Lua did not stop");
    }
}
