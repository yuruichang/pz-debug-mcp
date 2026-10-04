# 断点与原生调试 0.4.0

## 安装与启用

先退出游戏，运行完整构建、安装与JDWP启用脚本：

~~~powershell
.\build.ps1 -InstallMod
.\enable-debugging.ps1 -GameDir 'E:\Steam\steamapps\common\ProjectZomboid'
~~~

启动参数只添加本机127.0.0.1:8801的JDWP监听、suspend=n；保留原VM参数并创建备份。用-Disable撤销本项目的JDWP参数，端口可用-Port调整。服务器或其他启动器需在实际JVM入口加入同样参数，不能把桌面启动器配置当作服务端已启用。

需要完整JDK25运行JDI控制器；构建原生控制器需要Visual Studio C++ Build Tools和Windows SDK。bin内包含本项目控制器，Windows符号库从本机System32准备，不上传或分发系统DLL。

## 补丁表

pz_java_runtime(section=patches)现在从实际获准加载的JAR解析Patch注解，返回提供者、补丁类、目标类/方法、Advice类型及钩子签名。读取不初始化类，并核对批准的JAR摘要；不依赖优化版私有TargetInstrumentation类。

engine_preparation_logged与class_transform_reported分别表示引擎日志记录，application_evidence表述对应证据。声明或日志不能证明最终方法；观察摘要仍明确为观察器阶段。pz_java_debug(action=bytecode)从已连接VM读取当前方法bytecode的SHA256和分页字节码，不为读取主动重转换类。日志不完整、启动前证据和符号缺失都会明确保留缺口。

## Java断点

~~~json
{"tool":"pz_java_debug","arguments":{"action":"connect","port":8801}}
{"tool":"pz_java_debug","arguments":{"action":"breakpoint_add","class_name":"你的类名","method":"方法名","signature":"()V"}}
{"tool":"pz_java_debug","arguments":{"action":"status"}}
{"tool":"pz_java_debug","arguments":{"action":"frames","thread_id":"停止的线程编号"}}
{"tool":"pz_java_debug","arguments":{"action":"step","thread_id":"停止的线程编号","depth":"over"}}
{"tool":"pz_java_debug","arguments":{"action":"resume","thread_id":"停止的线程编号"}}
{"tool":"pz_java_debug","arguments":{"action":"disconnect"}}
~~~

支持已加载和未来加载类的断点、行断点、异常断点（exception=true）、threads/pause/frames及into/over/out。断点默认只暂停事件线程，控制器在独立进程中工作。frames返回调用栈、参数、this与局部变量；缺少LocalVariableTable时locals_available=false。object读取暂停对象的字段或数组，句柄只在本次会话有效，不执行Java表达式或方法。

## Lua断点

~~~json
{"tool":"pz_lua_debug","arguments":{"action":"connect"}}
{"tool":"pz_lua_debug","arguments":{"action":"breakpoint_add","file":"实际Lua原型文件名","line":10}}
{"tool":"pz_lua_debug","arguments":{"action":"frames"}}
{"tool":"pz_lua_debug","arguments":{"action":"step","depth":"into"}}
{"tool":"pz_lua_debug","arguments":{"action":"resume"}}
{"tool":"pz_lua_debug","arguments":{"action":"disconnect"}}
~~~

基于原版Kahlua的断点表与step/stepInto，在UIManager.debugBreakpoint入口接管本次远程暂停；独立邮箱继续服务。行号从1开始，file须匹配实际Prototype.filename，不能把任意磁盘路径当成已执行脚本。运行时变更会排队，queued=true表示尚未在游戏线程执行，status显示已生效断点及错误。

sources列出桥接已注册测试的实际原型文件名和可执行行号。内置example_debug只计算标量，不改变存档；可在其answer计算完成后的return行设置断点，再通过pz_run_test(name=example_debug)触发。调用等待期间用另一请求检查frames并继续，避免等待测试返回后才尝试恢复。

frames是暂停线程生成的纯数据快照，最多64帧，每帧128个局部；对象值仅提供类型和身份摘要。支持into/over/out，不执行任意Lua表达式。客户端维持30秒租约，正常断连或租约到期解除暂停并删除本会话添加的断点；原有UI断点不作为本会话删除。整体JVM或原生暂停期间租约也暂停，外部调试器恢复后再处理。

## Windows原生调试

~~~json
{"tool":"pz_native_debug","arguments":{"action":"attach"}}
{"tool":"pz_native_debug","arguments":{"action":"modules"}}
{"tool":"pz_native_debug","arguments":{"action":"symbols","pattern":"模块名!函数名"}}
{"tool":"pz_native_debug","arguments":{"action":"breakpoint_add","symbol":"模块名!函数名"}}
{"tool":"pz_native_debug","arguments":{"action":"resume"}}
{"tool":"pz_native_debug","arguments":{"action":"stack","limit":20}}
{"tool":"pz_native_debug","arguments":{"action":"locals","frame":0}}
{"tool":"pz_native_debug","arguments":{"action":"step","depth":"over"}}
{"tool":"pz_native_debug","arguments":{"action":"detach"}}
~~~

省略pid时从在线桥接取得游戏PID；其他测试进程必须显式给pid。DbgEng提供实际OS线程、模块、符号、机器码断点、寄存器、调用栈、into/over/out及1..65536字节内存读取。检查操作需要目标已暂停，state.paused不是JVM缓存的暂停推断。

符号从目标目录查找，不默认连接公共符号服务器。没有PDB时只能使用可得的导出符号/地址和寄存器，无法凭空还原FMOD/Bullet等私有C++局部变量或源码行；pdb_available=null表示符号尚未加载，false表示当前符号类型非PDB。补充合法匹配的PDB后才能做源码/类型层调试。

## 生命周期

每个MCP服务拥有自己的Java/原生控制器，关闭stdin是两者的resume/detach路径，正常客户端断连会关闭控制器并清理Lua会话。helper_state/last_error/pending报告控制器故障；超时不代表取消，未确认操作阻止重试，先disconnect/detach再重连。

目标进程退出、事件循环错误和协议错误明确报告。正常disconnect/detach及控制器EOF执行断点移除、单步请求清理、恢复和句柄清理。

原生控制器将普通首次异常交还应用处理，第二次异常才停止；断点和单步事件保持暂停。父进程守卫记录目标/线程身份、原始暂停计数和本会话断点的原始字节；控制器退出后，确认没有其他调试器接管才恢复已知多出的一个暂停计数与匹配的断点字节，结果见native_recovery/recovery。其他暂停计数和变化的代码保留并报告。

正常detach/EOF及运行期间控制器退出已验证目标继续推进。强杀若发生在未处理的调试异常上，Windows仍可能终止目标；此时守卫不能保证恢复，需重新启动测试存档。使用detach结束原生会话。

## 接口依据

- [Oracle JPDA连接与启动参数](https://docs.oracle.com/en/java/javase/25/docs/specs/jpda/conninv.html)
- [Microsoft调试引擎](https://learn.microsoft.com/en-us/windows-hardware/drivers/debugger/debugger-engine-overview)
- [Windows调试循环](https://learn.microsoft.com/en-us/windows/win32/debug/writing-the-debugger-s-main-loop)
