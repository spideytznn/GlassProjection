# 无黑屏交接：目标修订与路线比较

2026-09-16。用户最新澄清：不要求固定主屏身份，真正要求是开合过程中不出现黑屏。此前“不切主屏”是实现假设，已不再是硬约束。保留 Duo 布局、原有开合检测与动画，尽量复用小米系统交互，仍是目标。此次仅调研，未改变手机运行架构或发起开合切屏试验。

用户随后要求两条路线同时研究，已并行形成独立结果：[路线 A：原生交接](NATIVE-HANDOFF-ROUTE-A.zh-CN.md)、[路线 B：原生场景转发](NATIVE-SCENE-ROUTE-B.zh-CN.md)。A 继续研究系统切换时序与物理 OFF；B 优先准备主显示采集及原生触摸关联的独立可行性原型。研究并行，设备变更实验必须串行，避免电源、输入、显示参数相互污染。两份报告均不代表已实现零黑帧。

## 本机证据

本次只读确认固件仍为 OS4.0.11.0.XPNCNXM。重读本机 services.jar 反编译及已有试验日志：

- DisplayStateController.updateDisplayState 在 isDisplayInTransition 时返回 OFF，并跳过普通熄屏动画。这不是只缺一个桌面转场。
- LogicalDisplayMapper.areAllTransitioningDisplaysOffLocked 检查参与切换的显示已 OFF，之后才正常提交映射变更；还有超时/特殊分支，不能把某一个等待条件当作唯一修改点。
- LogicalDisplay.configureDisplayLocked 在 blank 时选择 layerStack=-1。仅补发 ON，面板也可能没有内容。
- 旧 ON 补发试验确实缩短过主观黑场，但系统 OFF 仍执行。普通动画层不能覆盖已断电的面板；把切换延迟到端点也不等于消除黑屏。
- 当前双虚拟屏承载应用已证明输入法和部分组件可用，但原生通知栏、小窗、分屏跨显示归属不正确，锁屏还会重建。不能继续把这一架构当成唯一解。

详细历史证据见 [免 root 研究](NO-ROOT-DISPLAY-CONTINUITY.zh-CN.md) 和 [当前双屏实测](FIXED-DUAL-DESKTOP.zh-CN.md)。历史测试不作为本轮新测试。

## 候选路线

| 路线 | 原生交互 | 无黑屏的关键条件 | 当前判断 |
| --- | --- | --- | --- |
| 原生主屏交换，加预显示与首帧交接 | 最接近系统原生路径 | 必须同时解决真实 OFF、图层空白与目标首帧，不能只加截图 | 保留为对照与系统适配研究；现有免 root 接口尚未证明可零黑帧 |
| 原生主显示承载所有应用与系统界面，只转发画面和输入到物理屏 | 避免把 SystemUI/多窗口移到任务虚拟屏；有希望保留其原有归属 | 输出须不递归捕获自己；正确处理尺寸、密度、旋转、输入、受保护画面及锁屏 | 优先做独立可行性原型；尚未证明完整可用，不等同于当前双虚拟屏架构 |
| 系统/厂商级连续切屏适配 | 可望直接协调原生路径 | 在系统显示策略中协调电源、图层、亮度、首帧与触控交接 | 未发现现成可用适配环境；不承诺 root 本身就能解决 |

第二条可以保持内部映射，也可以最终结合受控交接；固定与否不是验收条件。现有 tools/helpers/LiveMirrorWindowProbe.java 已包含排除自身覆盖层的主显示采集，提供可复用的基础，但旧局部镜像原型不能证明全屏系统交互、原生像素密度与安全窗口均可转发。

## 下一步的验证门槛

先验证原生主显示输出路线：保持实际应用、通知栏和原生多窗口在 display 0，检查输出到另一物理屏后是否显示完整、能操作、没有反馈递归；检查不同内外屏尺寸下文字是否原生清晰。若输入法、锁屏或受保护窗口无法正确工作，应记录失败并回到路线比较，不能以黑块或功能缺失替代通过。

原生切屏对照需要同步记录物理电源、图层绑定、首个有效呈现和外部拍摄。理想交接顺序为：旧画面保持有效→新屏准备有效画面→交接输入与原生窗口→撤掉过渡画面；这是设计要求，不是已验证能力。如果底层仍强制两屏一起 OFF，则截图和淡入只能缩短空白，不能宣称满足无黑屏。

验收同时覆盖展开、合拢、慢速停留、中途反向、桌面与应用、通知面板与原生多窗口。最终端点按用户原要求允许闲置屏变黑；被使用的屏幕和交接过程不能出现意外黑场。外部同步拍摄是必要证据，屏幕录制无法可靠证明物理面板没有熄灭。原生交互缺失、分辨率降低、长时间冻结不能作为换取无黑屏的默认代价。

## 公开资料交叉核对

- [AOSP 物理显示切换过渡源码](https://android.googlesource.com/platform/frameworks/base/+/05ea9022ebd7ea29c72dbc7f4cb3789025c183e9/services/core/java/com/android/server/wm/PhysicalDisplaySwitchTransitionLauncher.java)：组织窗口过渡及 ready 状态，不等于提供屏幕保电接口。
- [AOSP LogicalDisplayMapper](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/display/LogicalDisplayMapper.java)：逻辑/物理显示映射与设备状态切换。公开分支只作交叉参考，本机判断以上述实际固件为准。
- [AOSP 折叠锁定行为设置](https://source.android.com/docs/core/display/foldables/fold-lock-behavior-setting)：折叠后保持唤醒与进入睡眠的产品策略，不应与显示映射切换中的短暂 OFF 混为一谈。
