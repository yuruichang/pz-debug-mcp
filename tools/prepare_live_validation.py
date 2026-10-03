import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
build = ROOT / 'build/audit'
runtime = json.loads((build / 'runtime-approved.json').read_text(encoding='utf-8'))
coverage = json.loads((ROOT / 'docs/interface-coverage.json').read_text(encoding='utf-8'))
subprocess.run(['javac','-encoding','UTF-8','-cp','E:/Steam/steamapps/common/ProjectZomboid/projectzomboid.jar','-d',str(build),str(ROOT / 'tools/ExtractAliases.java')],check=True)
subprocess.run(['E:/Steam/steamapps/common/ProjectZomboid/jre64/bin/java.exe','-cp',f'{build};E:/Steam/steamapps/common/ProjectZomboid;E:/Steam/steamapps/common/ProjectZomboid/projectzomboid.jar','ExtractAliases',str(build / 'aliases.tsv'),str(build / 'types.txt'),str(build / 'superclasses.tsv')],check=True)
aliases = dict(line.split('\t') for line in (build / 'aliases.tsv').read_text(encoding='utf-8').splitlines())
approved = [r for r in runtime['approved'] if r['runtime'] != 'blocked_runtime_transform']
manifest = {'schema':1,'review_id':runtime['review_id'],'aliases':aliases,
            'superclasses':dict(line.split('\t') for line in (build / 'superclasses.tsv').read_text(encoding='utf-8').splitlines()),
            'transformed':dict.fromkeys(coverage['transformed_classes'],True),
            'initialized_startup':['java.lang.System','java.lang.Boolean','java.lang.String','java.lang.Integer','java.lang.Double','java.lang.Math','java.lang.StrictMath',
                'zombie.Lua.LuaManager$GlobalObject','zombie.Lua.LuaManager','se.krka.kahlua.vm.KahluaThread',
                'zombie.core.Core','zombie.characters.IsoPlayer','zombie.iso.IsoWorld','zombie.iso.IsoCell','zombie.GameTime','zombie.iso.weather.ClimateManager',
                'zombie.network.GameClient','zombie.network.GameServer','zombie.SandboxOptions','zombie.scripting.ScriptManager','zombie.debug.DebugOptions',
                'zombie.core.SpriteRenderer','zombie.core.PerformanceSettings','zombie.ui.TextManager','zombie.core.znet.SteamUtils'],
            'chunks':[]}
folder = build / 'runtime-files'
folder.mkdir(exist_ok=True)
for start in range(0,len(approved),100):
    name=f'approved-{start//100+1:03}.json'
    (folder / name).write_text(json.dumps({'approved':approved[start:start+100]},separators=(',',':')),encoding='utf-8')
    manifest['chunks'].append(name)
(folder / 'manifest.json').write_text(json.dumps(manifest,separators=(',',':')),encoding='utf-8')
module = (ROOT / 'tools/live_validation.lua').read_text(encoding='utf-8').replace('__REVIEW_ID__',runtime['review_id'])
# Embed in an existing mapped mod file, so it can be loaded without restarting the game.
example = '''local B = require 'PZDebugMCP/Bridge'
local previous = PZDebugMCPExample
local state = { ticks = previous and previous.ticks or 0 }
PZDebugMCPExample = state
local function tick() state.ticks = state.ticks + 1 end
local validator = (function()
''' + module + '''
end)()
local function cleanup()
    Events.OnTick.Remove(tick)
    B.tests.example_counter = nil
    B.tests.interface_validation_start = nil
    B.tests.interface_validation_step = nil
    B.tests.interface_validation_results = nil
end
local function init()
    Events.OnTick.Remove(tick)
    Events.OnTick.Add(tick)
    B.registerTest('example_counter',function() return {ticks=state.ticks,passed=true} end)
    B.registerTest('interface_validation_start',function() return validator.begin() end)
    B.registerTest('interface_validation_step',function() return validator.step() end)
    B.registerTest('interface_validation_results',function(args) return validator.resultsPage(args) end)
end
B.registerReloadable('example_counter',{path='media/lua/shared/PZDebugMCP/Example.lua',cleanup=cleanup,init=init})
if not B.reloading then init() end
return state
'''
(ROOT / 'Contents/mods/PZDebugMCP/42/media/lua/shared/PZDebugMCP/Example.lua').write_text(example,encoding='utf-8')
print(f'Live proof manifest: {len(approved)} entries, {len(manifest["chunks"])} chunks; exact binding checks enabled')
