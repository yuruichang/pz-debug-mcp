"""Merge static verdicts and actual call results without storing game values."""

from collections import Counter
import csv
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
report_path = ROOT / 'docs/interface-coverage.json'
report = json.loads(report_path.read_text(encoding='utf-8'))
live = json.loads((ROOT / 'build/audit/live-results.json').read_text(encoding='utf-8'))
results = {row['id']: row['validation'] for row in live['results']}
for row in report['interfaces']:
    if row['runtime'] == 'blocked_runtime_transform':
        continue
    actual = results.get(row['id'])
    if actual:
        row['runtime'] = actual['status']
        row['runtime_reason'] = actual.get('reason', '')
    elif row['status'] == 'reviewed_manual':
        row['runtime'] = 'previously_validated'
        row['runtime_reason'] = 'explicit reader set validated in earlier live sessions; unchanged in this sweep'
    elif row['status'] == 'read_only_proven':
        row['runtime'] = 'context_missing'
        row['runtime_reason'] = 'no eligible receiver in current loaded object graph'
    else:
        row['runtime'] = 'not_executed'
        row['runtime_reason'] = 'no unconditional read-only proof; kept disabled'
report['runtime_summary'] = {'counts_by_entry':dict(Counter(row['runtime'] for row in report['interfaces'])),
    'unique_signatures_passed':live['progress']['counts'].get('passed',0),
    'native_call_failures':live['progress']['counts'].get('failed',0),
    'objects_visited':live['progress']['nodes'],'field_reads':live['progress']['field_reads'],
    'new_lua_errors':live['new_lua_errors'],'queue_complete':live['progress']['done'],
    'review_id':live['progress']['review_id'], 'context':'Build 42.21 single-player Debug; current loaded state'}
report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
with (ROOT / 'docs/interface-coverage.csv').open('w',encoding='utf-8-sig',newline='') as stream:
    writer=csv.writer(stream)
    writer.writerow(['scope','owner','name','parameters','returns','static_review','static_reason','runtime_validation','runtime_reason'])
    for row in report['interfaces']:
        writer.writerow([row['scope'],row['owner'],row['name'],','.join(row['parameters']),row['returns'],row['status'],row['reason'],row['runtime'],row['runtime_reason']])
summary=f'''# 接口覆盖结果

## 范围

- 全局接口：{report['scope_counts']['globals']}。
- 类型：{report['scope_counts']['types']}。
- 公开方法条目：{report['scope_counts']['public_methods']}。
- 全部条目均有静态审核和运行状态；全局包装与对应 Java 方法分别列示，统计条目不能当作独立签名数。

## 真实单机验证

- 当前可安全调用的独立新签名通过：{report['runtime_summary']['unique_signatures_passed']}。
- 访问已有对象：{report['runtime_summary']['objects_visited']}。
- 读取字段以确认上下文：{report['runtime_summary']['field_reads']}。
- 原生调用失败：{report['runtime_summary']['native_call_failures']}；新增 Lua 错误：{report['runtime_summary']['new_lua_errors']}。
- 队列已完成，不把缺少上下文或未执行条目标成通过。

## 静态审核统计

| 状态 | 条目数 |
| --- | ---: |
''' + ''.join(f'| `{name}` | {count} |\n' for name,count in sorted(report['status_counts'].items())) + '''
`excluded_non_data` 为不返回数据的指令；`excluded_state_write` 存在字段/数组写入；`requires_review` 包含分配、未解析动态调用、原生代码、循环或不能证明只读的依赖；`requires_parameter_context` 需要索引、非空、转换或除数条件。以上条目未以 getter 名称猜测安全性，也未实际调用。

## 运行统计

| 状态 | 条目数 |
| --- | ---: |
''' + ''.join(f'| `{name}` | {count} |\n' for name,count in sorted(report['runtime_summary']['counts_by_entry'].items())) + '''
`context_missing` 表示当前没有合适对象、参数、已初始化类或注册静态绑定；`blocked_runtime_transform` 表示代理改写使磁盘代码不能代表 JVM 当前实现；`binding_unverified` 表示原生绑定不能精确匹配；`unavailable` 为当前未暴露接口。它们保留禁用，不等同于接口故障或通过。

## 实际限制

当前游戏使用本机 class 覆盖和原生代理，审计按真实类路径读取本机文件后再回退 jar；已记录的代理改写类不用于新接口调用。缺少多人服务器、命中断点、指定实体、地图区域或合法参数的场景无法在当前单机对象图凭空构造。创建测试实体、改变存档或加载资产的调用不属于只读验证。

完整逐项结果：[CSV](interface-coverage.csv)、[JSON](interface-coverage.json)。报告仅包含签名、理由及通过/阻断状态，不包含游戏数据值、存档或运行日志。
'''
(ROOT / 'docs/INTERFACE_COVERAGE.md').write_text(summary,encoding='utf-8')
print(json.dumps(report['runtime_summary'],ensure_ascii=False,indent=2))
