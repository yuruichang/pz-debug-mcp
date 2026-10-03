"""Extract API signatures, without redistributing game code."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REVIEWED = json.loads((ROOT / 'docs/reviewed-readers.json').read_text(encoding='utf-8'))
DENIED = re.compile(r'^(get(?:Next|New|Free|OrCreate|OrSet|OrDefault|And|Random|FileReader|FileWriter|ModFileReader|ModFileWriter|SandboxFile|Texture|SoundBuffer|SteamWorkshopStaging)|hasDataBreakpoint|hasDataReadBreakpoint)')


def policy(name, result, owner=None, args=None):
    if result == 'void':
        return False, 'not_a_reader'
    entries = REVIEWED['globals'] if owner is None else REVIEWED['methods'].get(owner, {})
    if (args or []) in entries.get(name, []):
        return True, 'reviewed_read_only'
    return False, 'unreviewed_interface'


def group(name):
    for category, tokens in {
        'lua_debug': ['Coroutine', 'Callframe', 'LocalVar', 'ClassField', 'ClassFunction', 'MethodParameter', 'Closure', 'Breakpoint', 'Debugger'],
        'players': ['Player', 'Character', 'Moodle', 'Perk', 'Trait'],
        'world': ['World', 'Cell', 'Chunk', 'Square', 'Map', 'Room', 'Building', 'Zone'],
        'weather_time': ['Climate', 'Weather', 'Rain', 'Wind', 'Time', 'Date', 'Season'],
        'vehicles': ['Vehicle', 'Car'], 'items_scripts': ['Item', 'Recipe', 'Script', 'Mod', 'Sandbox'],
        'network': ['Server', 'Client', 'Steam', 'Ping', 'Connection'],
        'audio_visual': ['Sound', 'Music', 'FMOD', 'Texture', 'Sprite', 'Screen', 'Render'],
    }.items():
        if any(token.lower() in name.lower() for token in tokens):
            return category
    return 'core_debug'


def parameters(text):
    if not text:
        return []
    result, start, depth = [], 0, 0
    for i, char in enumerate(text):
        depth += (char == '<') - (char == '>')
        if char == ',' and depth == 0:
            result.append(text[start:i].strip())
            start = i + 1
    return result + [text[start:].strip()]


def lua(value):
    if isinstance(value, dict):
        return '{' + ','.join('[' + json.dumps(k) + ']=' + lua(v) for k, v in value.items()) + '}'
    if isinstance(value, list):
        return '{' + ','.join(lua(v) for v in value) + '}'
    if isinstance(value, bool):
        return 'true' if value else 'false'
    return json.dumps(value, ensure_ascii=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--game-dir', type=Path, default=Path('E:/Steam/steamapps/common/ProjectZomboid'))
    options = parser.parse_args()
    jar = options.game_dir / 'projectzomboid.jar'
    output = subprocess.check_output(['javap', '-classpath', str(jar), 'zombie.Lua.LuaManager$GlobalObject'], text=True, encoding='utf-8')
    entries = []
    pattern = re.compile(r'^\s*public\s+(?:static\s+)?(.+?)\s+(\w+)\((.*?)\)(?:\s+throws\s+.+)?;$')
    for line in output.splitlines():
        match = pattern.match(line)
        if not match:
            continue
        result, name, args = match.groups()
        readable, reason = policy(name, result, args=parameters(args))
        entries.append({'name': name, 'parameters': parameters(args), 'returns': result,
                        'readable': readable, 'policy': reason, 'category': group(name)})
    entries.sort(key=lambda entry: (entry['name'], entry['parameters']))
    payload = {'schema': 1, 'target_build': '42.21.0', 'game_jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
               'globals': entries, 'rules': {'explicit_reviewed_allowlist': True, 'unreviewed_interfaces_disabled': True}}
    exposed = subprocess.check_output(['javap', '-c', '-private', '-classpath', str(jar), 'zombie.Lua.LuaManager$Exposer'], text=True, encoding='utf-8')
    names = set(re.findall(r'// class ([\w/$]+)', exposed))
    names.update({'java/lang/Object', 'java/util/List', 'java/util/ArrayList', 'java/util/Map', 'java/util/Collection'})
    names = sorted(name.replace('/', '.') for name in names if not name.startswith('['))
    build = ROOT / 'build/catalog'
    build.mkdir(parents=True, exist_ok=True)
    (build / 'types.txt').write_text('\n'.join(names), encoding='utf-8')
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(build), str(ROOT / 'tools/ExtractTypes.java')], check=True)
    subprocess.run([str(options.game_dir / 'jre64/bin/java.exe'), '-cp', f'{build};{jar};{options.game_dir}',
                    'ExtractTypes', str(build / 'types.txt'), str(build / 'types.json')], check=True, cwd=options.game_dir)
    types = json.loads((build / 'types.json').read_text(encoding='utf-8'))
    for owner, entry in types.items():
        for method in entry['methods']:
            method['readable'], method['policy'] = policy(method['name'], method['returns'], owner, method['parameters'])
    type_root = ROOT / 'Contents/mods/PZDebugMCP/42/media/lua/shared/PZDebugMCP/Types'
    type_root.mkdir(parents=True, exist_ok=True)
    # Small chunks avoid the Lua/Kahlua function constant limit.
    ordered = list(sorted(types.items()))
    chunks = []
    for i in range(0, len(ordered), 40):
        name = f'Part{i // 40 + 1:03}'
        (type_root / (name + '.lua')).write_text('return ' + lua(dict(ordered[i:i + 40])) + '\n', encoding='utf-8')
        chunks.append(name)
    (type_root / 'Index.lua').write_text('local result = {}\n' + ''.join(
        f"for name, entry in pairs(require 'PZDebugMCP/Types/{name}') do result[name] = entry end\n" for name in chunks) + 'return result\n', encoding='utf-8')
    payload['type_count'] = len(types)
    dest = ROOT / 'Contents/mods/PZDebugMCP/42/media/lua/shared/PZDebugMCP/Catalog.lua'
    dest.write_text('-- Generated from official API signatures; no game implementation included.\nreturn ' + lua(payload) + '\n', encoding='utf-8')
    (ROOT / 'docs').mkdir(exist_ok=True)
    (ROOT / 'docs/official-api-catalog.json').write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')
    print(f'Official API catalog: {len(entries)} signatures; {sum(e["readable"] for e in entries)} reader signatures')


if __name__ == '__main__':
    main()
