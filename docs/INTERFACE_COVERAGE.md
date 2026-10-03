# 接口覆盖结果

## 范围

- 全局接口：764。
- 类型：1152。
- 公开方法条目：24834。
- 全部条目均有静态审核和运行状态；全局包装与对应 Java 方法分别列示，统计条目不能当作独立签名数。

## 真实单机验证

- 当前可安全调用的独立新签名通过：573。
- 访问已有对象：975。
- 读取字段以确认上下文：10916。
- 原生调用失败：0；新增 Lua 错误：0。
- 队列已完成，不把缺少上下文或未执行条目标成通过。

“通过”表示在记录的参数、当前对象和可证明只读的边界内原生调用返回成功，不代表穷尽所有输入或业务分支。缺少安全证明的接口未调用或取回其数据。

## 静态审核统计

| 状态 | 条目数 |
| --- | ---: |
| `excluded_non_data` | 9679 |
| `excluded_state_write` | 1402 |
| `read_only_proven` | 5694 |
| `requires_parameter_context` | 2650 |
| `requires_review` | 6146 |
| `reviewed_manual` | 27 |

`excluded_non_data` 为不返回数据的指令；`excluded_state_write` 存在字段/数组写入；`requires_review` 包含分配、未解析动态调用、原生代码、循环或不能证明只读的依赖；`requires_parameter_context` 需要索引、非空、转换或除数条件。以上条目未以 getter 名称猜测安全性，也未实际调用。

## 运行统计

| 状态 | 条目数 |
| --- | ---: |
| `binding_unverified` | 16 |
| `blocked_runtime_transform` | 929 |
| `context_missing` | 4130 |
| `not_executed` | 19877 |
| `passed` | 606 |
| `previously_validated` | 22 |
| `unavailable` | 18 |

`context_missing` 表示当前没有合适对象、参数、已初始化类或注册静态绑定；`blocked_runtime_transform` 表示代理改写使磁盘代码不能代表 JVM 当前实现；`binding_unverified` 表示原生绑定不能精确匹配；`unavailable` 为当前未暴露接口。它们保留禁用，不等同于接口故障或通过。

## 实际限制

当前游戏使用本机 class 覆盖和原生代理，审计按真实类路径读取本机文件后再回退 jar；已记录的代理改写类不用于新接口调用。缺少多人服务器、命中断点、指定实体、地图区域或合法参数的场景无法在当前单机对象图凭空构造。创建测试实体、改变存档或加载资产的调用不属于只读验证。

完整逐项结果：[CSV](interface-coverage.csv)、[JSON](interface-coverage.json)。报告仅包含签名、理由及通过/阻断状态，不包含游戏数据值、存档或运行日志。
