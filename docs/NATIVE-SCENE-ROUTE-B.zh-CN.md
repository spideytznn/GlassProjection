# 路线 B：保留原生主显示，只转发物理输出

2026-09-16，与路线 A 并行研究。本轮检查本地源码、实际固件与只读设备信息，没有改变输入关联、分辨率、主屏状态或运行新输出原型。

## 结构与目标

让 Duo、普通应用、IME、SystemUI、小米最近任务与多窗口继续运行在逻辑 display 0。新增显示只作为采集/输出缓冲，不承载应用任务。将完整原生场景送到当前物理屏，沿用原有 Hall/角度检测和 GPU 开合效果。这样可避免当前方案中小窗、分屏回到 display 0 后藏在虚拟桌面背后的归属错位；这是结构上的预期，不是完整实测结论。

内部是否始终固定映射不是产品要求。此路线先避开硬件主屏交换的 OFF，再考察是否需要与原生交接结合。不能用永久拉伸低分辨率画面、禁用原生功能或冻结截图替代无黑屏目标。

## 已有基础与必须修改的点

1. `tools/helpers/LiveMirrorWindowProbe.java` 已有 display 0 的 GPU 采集及对自身输出的 `setSkipScreenshot` 处理；`tools/experiments/DualLiveRenderer.java` 有同一源到不同输出的原型。这些是历史可复用代码，不证明本轮全局场景已通过。
2. 当前 `FixedDualOutput` 明确以独立任务显示为源，没有排除自身捕获，并且是全屏可触摸窗口。直接把它的源改成主显示可能造成画面递归及输入再次被覆盖层截获，因此不能做一行替换后发布。
3. 旧 DualLiveRenderer 固定 1182 方形采集并放大 2 倍，只适合历史试验，不满足当前原生清晰度要求。新源/输出尺寸、有效裁剪区、旋转和密度需要独立记录。
4. 改变主显示逻辑尺寸的 `wm size` / setForcedDisplaySize 接口存在，但这影响应用布局，不只是纹理大小；必须保存原 override，遇到异常精确恢复。没有在本轮执行。

## 输入的新可验证线索

实际 shell 包权限记录包含已授予 ASSOCIATE_INPUT_DEVICE_TO_DISPLAY、INJECT_EVENTS、SET_ORIENTATION、WRITE_SECURE_SETTINGS。本机 InputManagerService 提供 add/removePortAssociation、add/removeUniqueIdAssociationByPort 及 descriptor 变体；关联入口检查上述关联权限并通知 native InputReader 更新。

本轮 `dumpsys input` 保存于 build/diagnostics/route-b-input.txt，可识别两个物理触摸端口 Xiaomi_Touch_Device_0 / Xiaomi_Touch_Device_1。现有触摸映射分别关联物理端口 0/1，所读记录中 UniqueIdByPort/ByDescriptor 为空。后续仍应按实际设备名称、viewport 与触摸样本确认对应关系，不能只凭名称后缀猜内外屏。

优先实验直接把目标物理触摸设备关联到原生场景所在的 viewport，让原生输入分发处理通知栏、IME和应用，而不是把所有触摸逐个发回一个仍可触摸的覆盖窗口。这能避免设计上的输入循环，但坐标缩放、旋转、双屏中途同时触摸的行为尚未验证。

闲置屏禁触是独立门槛。固件 disableInputDevice 需要 DISABLE_INPUT_DEVICE，当前 shell 包输出未见授予，不能依赖这个入口。将设备关联到不存在的 viewport 可能使设备不接收输入，但只能作为待验证候选，不能无恢复机制试用。InputManagerInternal 中还有 ignoredWindowNames 注入接口，它属于 system_server 内部能力，不能把反编译看到方法等同于 shell 可调用。

## 最小原型与恢复要求

| 阶段 | 只验证什么 | 通过证据 |
| --- | --- | --- |
| B1 原生场景输出 | 原生主显示不迁任务，源以原尺寸进入 GPU；自己的输出排除采集 | 原生通知栏、IME、多窗口在输出中可见；不递归、不重复缩放；首帧确实提交 |
| B2 输入关联 | 只改一个已识别触摸端口，原生主显示上无拦截注入的全屏触摸层 | 点击、边缘下拉、键盘、多指操作及返回路由正确；另一端口不误触 |
| B3 尺寸交接 | 从旧有效帧过渡到新布局首帧，切换完成后按目标屏原生像素输出 | 文本清晰、宽高比例正确、没有永久截图；尺寸和输入使用同一代配置 |
| B4 全周期 | 开合、中途反向、锁屏、助手退出、原生小窗/分屏、受保护画面 | 两屏同步外拍与帧日志；原生能力不缺失，异常能恢复普通系统输入/输出 |

B1/B2 必须单独运行，不与当前双虚拟任务会话叠加。任何输入关联/尺寸变更前保存当时实际覆盖值，独立看门狗先就绪；正常退出和进程异常均恢复原关联，而非盲目删除用户可能已有的映射。未证明受保护内容可用前不得宣称全局镜像兼容，也不以绕过安全画面保护作为方案。

## 当前结论

比当前双虚拟任务架构更符合复用原生交互的目标；有主显示采集和输入关联的代码/权限基础，可以推进独立原型。尚没有“保留全部原生交互、原生清晰度、锁屏正常且光学零黑帧”的完整证据，不能提前推荐替换日用版本。

公开交叉参考：[AOSP 输入路由](https://source.android.com/docs/core/display/multi_display/input-routing)。该文档说明触摸端口与显示 viewport 的关系；本机运行时关联方法以实际固件源码为准，不套用文档的历史版本能力边界。
