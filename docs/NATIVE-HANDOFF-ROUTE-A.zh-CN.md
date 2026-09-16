# 路线 A：保留原生主屏交换的无黑屏交接

2026-09-16。用户已明确：不切主屏不是要求，开合时不黑屏才是要求。本轮与路线 B 并行，仅检查本机固件反编译和历史实测材料；没有发起设备状态请求、修改设置或安装程序。

## 当前判断

原生交换最有利于保留小米通知栏、最近任务、小窗和分屏的正常显示归属，但当前固件的正常交换路径显式要求参与屏幕进入 OFF。历史双向交换均有实际 OFF 日志及肉眼闪黑；提前 ON、预置截图虽曾缩短黑场，仍未消除。**目前没有找到可直接接入生产版本的免 root 连续交换接口。** 这是现有接口与样本的结论，不是断言硬件永远不支持。

把切换延迟到折叠端点不能自动满足要求：展开端点内屏已可见，合拢端点外屏已可见，这时被接管的屏幕仍可能闪黑。仅在用户本来熄屏时交换属于补充策略，不能替代亮屏开合验收。

## 本轮核查到的调用链与额外分支

本机材料根目录为 `build/diagnostics/dual-display-research/decompiled/`，均为同机固件反编译；反编译控制流可能有恢复误差，因此不依据复杂的重构分支单独下结论。

| 位置 | 已核实语义 | 对方案的约束 |
| --- | --- | --- |
| `services/sources/com/android/server/display/LogicalDisplayMapper.java:469` | 在设置 pending 状态之前调用 `resetLayoutLocked(..., true)`；之后等待 transitioning 显示 OFF | 不能用预绘制帧的 ready 状态代替此电源条件 |
| 同文件 `:588` | `areAllTransitioningDisplaysOffLocked` 检查 `DisplayDeviceInfo.state == 1` | 此处检查框架 state，不是光学亮度或呈现 fence，也不是 committedState |
| 同文件 `:617` | 正常就绪、`force` 或 `PowerManager.isHangUp()` 可进入 `transitionToPendingStateLocked` | 存在例外不代表存在可用的客户端“无黑切换”接口 |
| `services/sources/com/android/server/display/state/DisplayStateController.java:55` | disabled、transitioning 或 proximity 都可强制 OFF；transitioning 还跳过普通熄屏动画 | 系统熄屏动画时长和桌面淡入不是主要控制入口 |
| `services/sources/com/android/server/display/LogicalDisplay.java:470` | blank 显示选择 layer stack `-1`；厂商延迟分支也保留 blank 处理 | 单独维持面板 ON 仍可能显示全黑 |
| `services/sources/com/android/server/display/LocalDisplayAdapter.java:1159` | 调用 SurfaceFlinger 电源模式后再记录 committedState，并发出厂商状态通知 | committed ON 不能独立证明已经显示第一帧内容 |

进一步检查了两个容易误认为可用入口的例外：

- `finishStateTransitionLocked(true)` 来自内部消息处理超时，不是已确认的 Binder 或 shell 方法。仅强制提交映射也没有撤销已发出的 OFF、亮度和 blank 事务。
- `isHangUp` 分支对应厂商投屏挂起状态。`miui/sources/com/android/server/power/PowerManagerServiceImpl.java:1098` 的 `hangUpNoUpdateLocked` 会通知显示挂起并把全局 wakefulness 改成 4；`:1194` 一带关联投屏/协同与 `screen_project_hang_up_on`。它不是“保持交接屏幕发光”的独立锁。本轮不改这些设置，不把该分支当成正向候选。

另外，`LocalDisplayAdapter.java:1193` 之后的预亮/AOD 回调还有独立触控冻结/解冻处理。这只证明部分预亮路径需要协调输入，不证明普通交换必然走这个条件分支；未来系统适配不能只看画面成功就宣布交互成功。

## 三类黑场必须分开测量

1. **物理 OFF**：系统电源调用的开始/返回、两屏 committedState，加同步外部拍摄。仅录屏不能看见面板真实熄灭。
2. **面板有电但图层 blank**：对应物理 uniqueId 的有效 layer stack 是否被切成 `-1`，投影矩形和可见图层是否恢复，亮度是否为零。
3. **有效栈已绑定但新内容未准备好**：目标缓冲区提交/呈现、捕获首帧、窗口尺寸变化与截图撤除时刻。捕获首帧不等于屏幕呈现，提交次数不等于端到端帧率。

历史样本：原生 `6→5` 请求到两屏 committed ON 约 833 ms，反向约 837 ms；补发 ON 轮约 997 ms，但用户感到黑场缩短。不能将这些数值当成光学黑场，更不能据此计算改善百分比。截图提前准备轮仍被用户确认有可观察闪黑。

## 候选与优先级

| 候选 | 需要的新增依据 | 处理 |
| --- | --- | --- |
| 原生交换前预显示，首帧就绪后撤过渡内容 | 必须另外解决物理 OFF 与 blank，否则只覆盖内容重建空档 | 保留为首帧衔接组件，不再单独宣称无黑屏方案 |
| 厂商连续交换协议 | 同固件明确的电源/映射/亮度事务语义和调用权限、恢复协议 | 尚未找到；不枚举未知 HAL 指令 |
| 系统内部连续交换实现 | Mapper 不再以 OFF 为唯一正常交接门槛，电源/图层/亮度/输入/窗口同步协调 | 技术方向明确，但当前没有现成系统适配环境；不能承诺仅 root 或一个 Hook 即解决 |
| 直接反复 ON、强行反复写图层栈 | 无可证明的抢占/持有协议，旧试验已有反证 | 不重复；不是本轮下一实验 |

## 下一最小实验和恢复条件

路线 A 的下一实机动作应为**一次受控原生交换基线采样**，而不是再次叠加 ON 洪泛；它用于给路线 B 同一光学验收基线，并量出物理关屏与内容空档各占什么范围。现有日志已证明存在 OFF，若没有外部拍摄或新的呈现时间采样能力，就不重复同类交换。

实验准备：选无敏感内容的 Duo/系统设置画面，记录两屏 uniqueId、当前状态请求所有者、亮度和原助手恢复方式；使用现有独立限时原型，不替换生产助手资产。由主控 agent 独占设备操作。用户配合一次慢速展开，外部视频同时拍到相关面板，并在画面中包含与设备日志可对齐的可见标记。

采样应覆盖：状态请求时刻、每个物理屏 OFF/ON 调用及完成、layer stack 解绑/恢复、窗口几何稳定、新源帧及实际呈现、第一处可操作触控。先跑一个方向即可，发现异常立即停止。若新证据仍是两屏物理 OFF，结论仍为路线 A 现有免 root 接口不满足，不继续用更多截图轮次换取表面进展。

恢复条件：限时结束或助手失联均取消本次设备状态请求、移除本次图层、恢复原会话所有者；检查两屏映射/电源与当前姿态一致、输入恢复、无测试进程或图层残留。不要假设修改物理 layer stack 会随进程退出自动复原，因此本基线不直接改物理栈。锁屏/来电发生即退出实验，不能用遮罩压住系统安全界面。

## 本轮交付边界

完成了新的内部 force/hang-up 分支核查，未发现能绕开原生 OFF 且保留正常输入与电源语义的公开调用入口。没有新实机结果，也未证明零黑帧。旧实测索引见 [双屏试验](DUAL-DISPLAY-TRIAL.zh-CN.md)、[免 root 连续性研究](NO-ROOT-DISPLAY-CONTINUITY.zh-CN.md)，路线比较见 [无黑屏交接研究](NO-BLACK-HANDOFF-RESEARCH.zh-CN.md)。
