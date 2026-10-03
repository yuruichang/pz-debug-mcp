"""Conservative per-signature effect audit; names are never a safety proof."""

from collections import Counter
import csv
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'build/audit'


def normalize_type(value):
    value = re.sub(r'<.*>', '', value)
    if value.startswith('['):
        depth = len(value) - len(value.lstrip('['))
        base = value[depth:]
        base = base[1:-1].replace('/', '.') if base.startswith('L') else {
            'I': 'int', 'J': 'long', 'F': 'float', 'D': 'double', 'B': 'byte', 'C': 'char', 'S': 'short', 'Z': 'boolean'}[base]
        return base + '[]' * depth
    return value


def signature(owner, name, parameters, result):
    return owner, name, tuple(map(normalize_type, parameters)), normalize_type(result)


def classify(method, methods, cache=None, active=None):
    cache = {} if cache is None else cache
    active = set() if active is None else active
    key = method['id']
    if key in cache:
        return cache[key]
    if key in active:
        return {'status': 'requires_review', 'reason': 'recursive_dependency', 'required_initialized': []}
    active = active | {key}
    effects = set(method['effects'])
    result = {'status': 'read_only_proven', 'reason': 'field_and_primitive_operations',
              'required_initialized': list(method['required_initialized'])}
    if method['native'] or method['abstract']:
        result.update(status='requires_review', reason='native_implementation' if method['native'] else 'abstract_dispatch')
    elif 'writes_field' in effects or 'writes_array' in effects:
        result.update(status='excluded_state_write', reason='writes_field' if 'writes_field' in effects else 'writes_array')
    elif effects & {'allocates', 'dynamic_dispatch', 'synchronizes', 'throws', 'loop_guard_needed', 'receiver_reassigned'}:
        result.update(status='requires_review', reason=','.join(sorted(effects & {'allocates', 'dynamic_dispatch', 'synchronizes', 'throws', 'loop_guard_needed', 'receiver_reassigned'})))
    elif effects & {'nullable_receiver', 'nullable_array', 'array_index', 'division_guard_needed', 'cast_guard_needed'}:
        result.update(status='requires_parameter_context', reason=','.join(sorted(effects)))
    else:
        for call in method['calls']:
            # Clock and mathematical intrinsics do not read or mutate game/asset state.
            math_safe = call['owner'] in {'java.lang.Math', 'java.lang.StrictMath'} and call['name'] in {
                'sin','cos','tan','asin','acos','atan','atan2','sqrt','cbrt','abs','min','max','floor','ceil','rint','round','toRadians','toDegrees','hypot','exp','log','log10','pow'}
            clock_safe = call['id'] in {'java.lang.System#currentTimeMillis()J', 'java.lang.System#nanoTime()J'}
            if math_safe or clock_safe:
                continue
            if not call['fixed_dispatch']:
                result.update(status='requires_review', reason='unresolved_virtual_dispatch:' + call['id'])
                break
            dependency = methods.get(call['id'])
            if dependency is None:
                result.update(status='requires_review', reason='missing_dependency:' + call['id'])
                break
            proof = classify(dependency, methods, cache, active)
            if proof['status'] != 'read_only_proven':
                result.update(status=proof['status'], reason='dependency:' + call['id'] + ':' + proof['reason'])
                break
            result['required_initialized'].extend(proof['required_initialized'])
    result['required_initialized'] = sorted(set(result['required_initialized']))
    cache[key] = result
    return result


def build():
    metadata = json.loads((BUILD / 'effects.json').read_text(encoding='utf-8'))
    effects = metadata['methods']
    console = Path('C:/Users/WINDOWS/Zomboid/console.txt').read_text(encoding='utf-8', errors='replace')
    transformed = set(re.findall(r'\[ZB\] Transformed: ([\w.$]+)', console))
    types = json.loads((ROOT / 'build/catalog/types.json').read_text(encoding='utf-8'))
    globals_catalog = json.loads((ROOT / 'docs/official-api-catalog.json').read_text(encoding='utf-8'))
    reviewed = json.loads((ROOT / 'docs/reviewed-readers.json').read_text(encoding='utf-8'))
    indexed = {signature(m['owner'], m['name'], m['parameters'], m['returns']): m for m in effects.values()}
    cache, rows, approved = {}, [], []
    for scope, owner, entry in [
        *[('global', 'zombie.Lua.LuaManager$GlobalObject', entry) for entry in globals_catalog['globals']],
        *[('method', owner, entry) for owner, data in sorted(types.items()) for entry in data['methods']],
    ]:
        method = indexed.get(signature(owner, entry['name'], entry['parameters'], entry['returns']))
        row = {'scope': scope, 'owner': owner, 'name': entry['name'], 'parameters': entry['parameters'], 'returns': entry['returns']}
        manual = reviewed['globals'] if scope == 'global' else reviewed['methods'].get(owner, {})
        if entry['parameters'] in manual.get(entry['name'], []):
            row.update(status='reviewed_manual', reason='existing_explicit_review', runtime='pending', required_initialized=[])
        elif entry['returns'] == 'void':
            row.update(status='excluded_non_data', reason='void_return', runtime='not_executed', required_initialized=[])
        elif method is None:
            row.update(status='requires_review', reason='bytecode_not_available', runtime='not_executed', required_initialized=[])
        else:
            row.update(classify(method, effects, cache))
            row['runtime'] = 'pending' if row['status'] == 'read_only_proven' else 'not_executed'
        if method:
            row['id'] = method['id']
            row['static'] = method['static']
            row['class_sha256'] = metadata['class_sha256'].get(owner)
        else:
            row['id'] = owner + '#' + entry['name'] + '(' + ','.join(entry['parameters']) + ')' + entry['returns']
            row['static'] = scope == 'global'
        rows.append(row)
        if row['status'] == 'read_only_proven':
            if owner in transformed or any(call['owner'] in transformed for call in method['calls']):
                row['runtime'] = 'blocked_runtime_transform'
                row['runtime_reason'] = 'actual JVM method body differs or may differ from disk bytecode'
            approved.append(row)
    report = {'schema': 1, 'target_build': globals_catalog['target_build'], 'game_jar_sha256': globals_catalog['game_jar_sha256'],
              'scope_counts': {'globals': len(globals_catalog['globals']), 'types': len(types), 'public_methods': sum(len(t['methods']) for t in types.values())},
              'status_counts': dict(Counter(r['status'] for r in rows)), 'transformed_classes': sorted(transformed),
              'audit_classpath': ['game_directory', 'projectzomboid.jar'], 'interfaces': rows}
    (ROOT / 'docs/interface-coverage.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    with (ROOT / 'docs/interface-coverage.csv').open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(['scope','owner','name','parameters','returns','static_review','reason','runtime_validation'])
        for row in rows:
            writer.writerow([row['scope'], row['owner'], row['name'], ','.join(row['parameters']), row['returns'], row['status'], row['reason'], row['runtime']])
    runtime = {'schema': 1, 'review_id': hashlib.sha256(json.dumps(approved, sort_keys=True).encode()).hexdigest(),
               'game_jar_sha256': report['game_jar_sha256'], 'approved': approved}
    (BUILD / 'runtime-approved.json').write_text(json.dumps(runtime, separators=(',', ':')), encoding='utf-8')
    print(json.dumps({'scope_counts': report['scope_counts'], 'statuses': report['status_counts'],
                     'safe_globals': sum(r['scope'] == 'global' for r in approved),
                     'safe_zero_arg_methods': sum(r['scope'] == 'method' and not r['parameters'] and not r['static'] for r in approved)}, indent=2))


if __name__ == '__main__':
    build()
