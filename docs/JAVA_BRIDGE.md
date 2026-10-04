# Java 桥接 0.3.1

## 数据与线程

ZombieBuddy 加载 media/java/PZDebugMCP.jar。Main 注册 PZDebugJava，获取当前代理的 Instrumentation，并安装只返回 null 的字节码观察器。进入存档后 Lua 调用 open，创建本次会话。

- 游戏回调线程：原版审核读取、Lua 表适配、Java 对象字段/数组、已初始化模组静态字段、车辆、测试和受控重载。
- Java 后台线程：请求校验和持久领取、独立心跳、缓存返回、JVM 管理数据、环形文件写盘和追踪事件排空。
- Python MCP：沿用 stdio；每 0.5 秒把已提交记录保存到 SQLite 历史归档。

游戏对象不会直接交给后台序列化。Java 字段读取生成标量或会话句柄，不调用对象的 equals/hashCode/toString。句柄最多 4096 个，容量可配置；淘汰或调整容量后旧句柄失效。

Java 默认每 100 ms 最多处理四个字段页，每页 16 项，协作预算 2 ms、对象深度四层。Lua 适配层沿用各自相同的默认预算。单次反射操作无法强制中断。Java 图队列上限 8192；自动模组静态根最多 256 个已初始化应用类，超出部分仍可按需检查。

## 三个新工具

| 工具 | 内容 |
| --- | --- |
| pz_java_runtime | summary、metrics、threads、classes、mods、patches、roots |
| pz_inspect_java | inspect、field、methods；call 仅允许既有审核的零参数实例方法 |
| pz_trace_java | 精确 Java 方法的 start/read/stop |

示例：

~~~json
{"tool":"pz_java_runtime","arguments":{"section":"mods"}}
{"tool":"pz_java_runtime","arguments":{"section":"classes","filter":"你的模组包名"}}
{"tool":"pz_inspect_java","arguments":{"target":"class:你的已加载类名","action":"inspect"}}
{"tool":"pz_java_runtime","arguments":{"section":"roots"}}
{"tool":"pz_inspect_java","arguments":{"target":"返回的 Java 句柄","action":"field","member":"字段名称"}}
{"tool":"pz_java_runtime","arguments":{"section":"threads","limit":20}}
~~~

Class 目标来自 Instrumentation 的已加载类目录，不通过类名加载或初始化新类。同名类由不同加载器加载时拒绝歧义。静态字段先使用 Unsafe.shouldBeInitialized 确认初始化完成；检查使用本机启动器已有的模块导出，检查不可用时明确拒绝静态取值。

私有应用字段可以读取；JDK 模块封装、需要实例或缺少初始化依据的字段返回 accessible=false 及原因。ClassLoader、线程、反射执行对象和 MethodHandles.Lookup 不生成可执行对象句柄。没有任意方法执行、字段写入、任意 Lua 执行或原生内存读取入口。

原 getPlayer 等查询会返回 Java 句柄，仍可使用 pz_query_debug 的 field/inspect/call。Lua 表句柄继续留在适配层。pz_list_debug_interfaces 的 Java 对象范围列出字段/数组；方法签名通过 pz_inspect_java(action=methods) 浏览。

## 暂停与独立邮箱

游戏请求位于 Lua/PZDebugMCP/{client,server}/；JVM、状态、缓存错误和方法追踪使用其 runtime/ 子目录。两个邮箱共享会话，各自使用独立序号、进程锁、领取和响应标记。

游戏请求被领取后等待 Lua 回调时，诊断邮箱仍可服务。超时不会取消已经领取的游戏请求；恢复回调后先检查过期时间，避免执行已过期请求。整个 JVM 暂停、进程结束或写盘故障会阻断心跳。

game_thread_age_ms 表示最近游戏发布距离现在的时间。心跳在线不代表游戏快照仍在更新。原版错误缓存每 500 ms 发布，保留最近 64 项，每项消息最多 1024 个字符；截断标记与 gap 明示缺口，完整堆栈可用 pz_read_console 取回。

失焦暂停仍在游戏线程自动关闭，并每秒校验；手动暂停和断点暂停不会解除。

## 方法追踪

追踪需要可修改、已初始化、已加载的应用类和可用的重转换能力。类名、方法名和参数类型必须精确匹配；不接受通配符、构造器、抽象或 native 方法，不追踪 JDK、桥接或 ZombieBuddy 内部方法。

~~~json
{"tool":"pz_trace_java","arguments":{"action":"start","class_name":"你的类名","method":"方法名","parameters":["int"],"duration_seconds":10}}
{"tool":"pz_trace_java","arguments":{"action":"read","trace_id":"返回的追踪编号"}}
{"tool":"pz_trace_java","arguments":{"action":"stop","trace_id":"返回的追踪编号"}}
~~~

一次一个追踪，最长 30 秒，保留最多四组历史，每组最多 600 项。记录进入时的标量参数、返回值、异常类型、线程和耗时；对象参数只记录类型与身份摘要，不在回调中遍历对象。原参数、返回值和异常保持不变。

最多安装八个不同方法的观察钩子。停止/到期后不再采样，惰性钩子保留到 JVM 重启，避免卸载过程中重复重转换其他模组的补丁。八个目标用完后需要完整重启再选新目标。启动追踪可能出现一次类重转换开销；默认不启用追踪。

## 补丁信息和证据

本机优化版的目标注册表提供包与精确目标类；它表示注册意图，不证明某个方法最终成功应用全部补丁。其他 ZombieBuddy 实现缺少该注册表时明确报告不可用。

观察器只记录安装之后经过该观察阶段的类字节数、SHA-256、加载器摘要和重定义标记，队列最多 16 项、单项最大 512 KiB，近期元数据最多 256 项；不保存或上传游戏字节码。观察器不主动重转换类，也不改写字节码。

这些摘要是 observer_input_after_installation，可能还有后续变换器，并非最终 JVM 实现。旧加载阶段不能补回。旧覆盖报告中因未知运行时补丁而阻断的方法继续禁用，不能因迁移到 Java 自动标为只读通过。

## 归档和版本边界

新记录策略为 java_fields_reviewed_lua_v1。Java 队列最多 1024 项，16 个轮换段，每段最多 128 项或 256 KiB；溢出和写盘故障通过计数/last_error 报告。只有关闭写入后的序号才发布到索引，Python 同时识别旧审核策略并保留原历史。

0.3.1 面向 Java 25、PZ 42.21 和 ZombieBuddy 2.3.2 的旧包名 API，未适配 3.x。若 Java 未加载，status.backend 会明确为 lua_fallback；此时新增 Java 工具不可用，应检查加载日志、依赖、JAR 允许状态与版本。

JVM 管理接口提供平台线程栈、死锁和堆/GC 指标；虚拟线程未包含。完整断点、单步、局部变量、JFR 会话管理以及 Bullet/FM0D/OpenGL 原生内部调试仍未实现。
