import asyncio,json,os,sys,time
from pathlib import Path
from mcp import ClientSession,StdioServerParameters
from mcp.client.stdio import stdio_client

root=Path(__file__).resolve().parents[1]
async def main():
    params=StdioServerParameters(command=sys.executable,args=['-m','pz_debug_mcp.server','--zomboid-dir','C:/Users/WINDOWS/Zomboid','--timeout','10'],env={**os.environ,'PYTHONUTF8':'1'})
    async with stdio_client(params) as (reader,writer):
        async with ClientSession(reader,writer) as session:
            await session.initialize()
            async def call(name,args=None):
                r=await session.call_tool(name,args or {})
                txt='\n'.join(c.text for c in r.content if c.type=='text')
                if r.isError: raise RuntimeError(name+': '+txt)
                return json.loads(txt)
            baseline=(await call('pz_read_errors',{'limit':100}))
            if '--resume' not in sys.argv:
                await call('pz_reload_mod_lua',{'module':'example_counter'})
            start=(await call('pz_run_test',{'name':'interface_validation_start'}))['result']
            print(json.dumps({'start':start}),flush=True)
            began=time.monotonic(); last=0
            for _ in range(5000):
                progress=(await call('pz_run_test',{'name':'interface_validation_step'}))['result']
                if time.monotonic()-last>10:
                    print(json.dumps({'progress':progress}),flush=True);last=time.monotonic()
                if progress.get('done') or progress.get('stopped'): break
                await asyncio.sleep(0.05)
            results=[]; after=0
            while True:
                page=(await call('pz_run_test',{'name':'interface_validation_results','arguments':{'after':after,'limit':100}}))['result']
                results.extend(page['items']);after=page['cursor']
                if not page['has_more']:break
            errors=await call('pz_read_errors',{'after':baseline['cursor'],'session':baseline['session'],'limit':100})
            report={'progress':progress,'results':results,'elapsed_seconds':time.monotonic()-began,
                    'new_lua_errors':sum(e['kind']=='lua_error' for e in errors['events'])}
            (root/'build/audit/live-results.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
            print(json.dumps({'final':progress,'elapsed_seconds':report['elapsed_seconds'],'new_lua_errors':report['new_lua_errors']}),flush=True)

asyncio.run(main())
