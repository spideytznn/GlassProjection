# 免 root 双屏连续切换：线上方案复核

检索日期：2026-09-14。范围为 Android/AOSP 官方文档与源码、小米开发者文档、相关开源项目及其实际调用代码。目标仍是本机免 root、双屏动画、原生主屏交换时无可见黑屏。本轮仅检索和只读源码核查，未安装第三方 APK、未改变手机设置或运行新切屏实验。

本轮没有找到已经证明可在这台小米固件上实现“原生主屏交换、全程零黑屏”的现成方案。这个结论限于已核查材料，并不等于证明所有厂商接口都不存在。存在有代码基础的替代架构：固定物理—逻辑显示映射，将应用运行于独立虚拟显示，再把内容和输入路由到当前使用的物理屏。它避开触发关屏的主屏交换，代价是超出普通动画插件的职责。

## 相关项目到底解决了什么

| 线索 | 实际实现或文档内容 | 对本项目的意义 |
| --- | --- | --- |
| Duo Open | 无障碍截图、覆盖层、铰链动画；作者记录新亮屏约有 0.4 秒系统黑场 | 没有找到它解决物理切屏黑场的证据；其时间是作者设备描述，不能用于推算本机 |
| Fold_Switcher | `cmd device_state state` 或 `device_state.requestState` | 与我们已有状态请求属于同一路径，换工具不会自动绕过本机 OFF |
| MiRearScreenSwitcher | 在小米背屏设备上用 `activity_task` Binder 调用移动任务 | 是任务移动，不是拦截显示电源；本机折叠副屏承载任务的限制仍然存在 |
| Jetpack WindowManager 双屏模式 | 以 Activity 会话在另一屏呈现内容；需查询设备能力，离开主应用可能结束会话 | 可实现应用内双屏，不提供系统全局无黑屏主屏交换保证 |
| scrcpy 虚拟显示 / Flex display | 创建虚拟显示、启动应用、动态调整尺寸、设置虚拟屏输入法策略 | 为“不交换主屏而改变显示内容和布局”提供现成代码基础 |
| 小米大屏适配指南 | 配置变更、窗口尺寸、折叠姿态监听与应用连续性 | 已查指南未提供阻止主屏交换 OFF 的公开调用协议 |

来源：[Duo Open](https://github.com/marcoazeem/duo-open)、[Fold_Switcher 调用实现](https://github.com/eiyooooo/Fold_Switcher/blob/0ff1c76a69d08b94b496f33249eda3d7a78e97d5/app/src/main/java/com/eiyooooo/foldswitcher/wrappers/ShizukuExecutor.kt#L125)、[MiRearScreenSwitcher 调用实现](https://github.com/AntiOblivionis/MiRearScreenSwitcher/blob/7e0ae1a35aea58c1f94019cf3cb54681adb54b9d/android/app/src/main/java/com/tgwgroup/MiRearScreenSwitcher/TaskService.java#L141)、[Google 双屏模式文档](https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/support-foldable-display-modes)、[scrcpy 虚拟显示文档](https://github.com/Genymobile/scrcpy/blob/master/doc/virtual-display.md)、[小米大屏适配指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2043)。

两份本地只读源码快照分别固定为 Fold_Switcher `0ff1c76a69d08b94b496f33249eda3d7a78e97d5`、MiRearScreenSwitcher `7e0ae1a35aea58c1f94019cf3cb54681adb54b9d`。MiRearScreenSwitcher 旧仓库已迁移；本轮跟随其 README 找到新地址。其代码硬编码 Binder 事务号 50，本轮未在手机执行，不能跨固件直接照搬。

## 为什么迁移任务也不能直接照搬

本机已取得的 `LogicalDisplay.validateCanHostTasksLocked()` 对折叠/翻盖设备的非默认内置显示显式返回 false；主显示 0 返回 true。先前运行时记录也显示副屏不能承载任务。这是本机证据，不是其他小米背屏机型的通用结论。

因此，把应用直接移动到物理副屏不能仅凭“另一个小米项目成功”就视为可行。新建独立虚拟显示不属于这条“非默认内置显示”的同一判断分支，但其任务承载、焦点与系统界面仍须实机验证。

## 原生交换的关屏路径依然存在

本轮读取的 AOSP `LogicalDisplayMapper.resetLayoutLocked()` 明确将逻辑显示 ID 变化判定为切换，并说明先 OFF 后 ON 用于隐藏窗口尺寸变化。它与本机反编译代码和实验日志方向一致，但公开分支并非本机完整 native 实现。[AOSP LogicalDisplayMapper](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/display/LogicalDisplayMapper.java)

折叠后“继续使用应用/不锁屏”设置管理的是折叠锁屏行为，不能据此推断内部主屏重映射无需 OFF。[AOSP 折叠锁屏设置](https://source.android.com/docs/core/display/foldables/fold-lock-behavior-setting)

本机现有结果：保持主屏映射时，用户确认双屏镜像/动画无闪黑；真实交换时仍闪黑；提前 ON 加截图已获“缩短但仍可观测”的反馈。详细时间与限制见 [实机试验记录](DUAL-DISPLAY-TRIAL.zh-CN.md)。

## 尚值得验证的替代架构

以下为基于源码和已有实机能力作出的工程推断，尚未实现或证明全周期无黑屏。

1. 保持一个固定的双屏设备状态，使物理屏对应的逻辑显示 ID 不因动画交接而交换。
2. 创建能承载应用的独立虚拟显示，先运行自有测试界面，再验证普通应用和桌面。
3. 将虚拟显示输出送入当前 GL 渲染器，继续遵循已确认的右半屏规则。无需先编码成网络视频再解码，可研究直接使用 Surface 输出。
4. 随使用内外屏改变虚拟显示的尺寸，让应用重新布局；以已有截图层覆盖布局重建空档。
5. 将触控、焦点及输入法路由到虚拟显示；物理显示的“主/副身份”不交换。

动态尺寸和虚拟显示捕获可参考 [scrcpy NewDisplayCapture](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java)。输入与显示是独立关联的系统概念，不能只完成画面投送就宣布交互可用。[AOSP 输入路由](https://source.android.com/docs/core/display/multi_display/input-routing)

最小验证应按以下顺序推进：

- **显示闭环**：虚拟显示能承载测试 Activity，调整尺寸后两块物理屏仍不发生交换导致的 OFF。
- **操作闭环**：两屏上的点击、滑动、返回、输入法和焦点均正确；仅显示镜像不算通过。
- **开合端点**：完全合拢/展开、锁屏/解锁、方向变化时，固定双屏状态是否被系统撤销或强制关屏。
- **桌面兼容**：原生桌面、最近任务、通知及应用迁移是否可用。若需替换桌面或改变系统导航，应先明确产品范围，不默认实施。

任一基础验证失败，就不能把这条路线包装成可发布方案。即便成功，它也会从动画服务演变为管理显示和输入的系统辅助工具；这是选择这条路线的实际成本。

## 建议

若坚持原生主屏交换，目前保留“提前 ON＋截图”作为减轻黑场的实验，不承诺完全消除。若愿意改变内部实现而保留用户可见的双屏动画目标，优先做固定映射＋虚拟显示的最小验证，比继续增加 ON 洪泛更有明确的技术依据。厂商连续切换接口仍是未证实线索，本轮未找到匹配本机的公开契约。
