# Duo 固定双屏开发记录（2026-09-15）

## 用户确认的模式边界

- 自制 Duo 桌面：整个会话固定物理主屏映射，禁止按角度请求另一主屏；内外屏各自保留布局、内容和开合动画管线。
- 开合过程中，两条内容和动画管线持续工作。不能把 openAngle / closeAngle 用作 Duo 的内外屏切换条件。
- 完全展开只把外屏输出遮黑并吞掉触控；完全合拢只把内屏输出遮黑并吞掉触控。遮挡不等于销毁或暂停内容。
- 原小米桌面：保留原来的按角度切屏能力。必须由场景控制器隔离两条路径，不能同时持有设备状态请求。

## 已验证

1. shell 权限能创建可信虚拟屏，系统设置真实 Activity 已在虚拟屏运行；创建后释放，没有把物理主屏换过去。
2. 固定 state 5 的真实开合：日志记录 base CLOSED → TENT → OPENED；committed 始终为 5，逻辑 0 的物理 uniqueId 始终 local:4639175402683733248。日志 build/fixed-dual-fold-trial.txt。
3. 限时试验退出后 override 清空。两个物理屏的无障碍遮挡窗口能创建并清除。
4. 原 FoldPose.angle() 依赖旧助手的 Hall 数据；停止旧助手后会变成 0，即使 rawAngle=179。独立双屏不能使用这个投影用角度判断姿态。

## 最新代码与验证限制

FixedDualPolicy 已删除 openAngle / closeAngle 和方向滞回，只接收展开、接触、可用、唤醒信号。中间姿态返回 BOTH，端点只控制遮挡。200 次状态循环模型测试通过。

真实开合日志使用的是修正前的角度遮挡规则，仅证明物理主屏映射固定，不能当成最新双屏动画的验收。最新端点遮挡代码尚未做实机视觉验收。

FixedDualTrial 仍只是最长 30 秒的拓扑/遮挡调试入口，未暴露为长期设置。它的临时端点识别使用本机物理 fold_status 和铰链端点，生产版需要独立、可持续的接触传感器生命周期；不得把旧助手停掉之后的 Hall 过期当成合拢。

## 尚未完成

- 两套独立虚拟桌面/应用内容挂到两块物理屏；物理副屏本身不能直接承载 Activity。
- 双输出分别接入现有开合 shader，避免镜像同一份布局或只在单屏执行动画。
- 物理输入映射到对应虚拟内容，以及手势、键盘、小组件、应用返回的显示归属。
- Duo 与原小米桌面的模式进入/退出及单一状态所有者，睡眠、锁屏、进程死亡恢复。
- 黑屏范围与触控吞掉的实测。当前窗口能创建不等于所有系统手势均已验证。

不能声称“长期双屏已完成”或“完全无切换感”已验收。


## 2026-09-16 实现与回归修复（覆盖前面的“尚未接入”记录）

- 已加入 FixedDualContentHost：两个可信任务显示，DuoInnerActivity / DuoCoverActivity 各自运行；物理输出按 uniqueId 绑定，整个会话的 state 5 或 6 不随开合改变。
- 两套独立组件宿主 ID 2702/2703，独立布局持久化；初次建立布局只复制应用排列，已有宿主 2701 的组件 ID 不会被删除或错误重用。原桌面组件没有自动迁移到新宿主，不能声称组件迁移完成。
- 打开系统装饰标志后虚拟桌面可显示系统壁纸。已实测外屏菜单点击、启动笔记应用和返回；没有接受笔记应用的隐私授权。
- 两块物理输出保持常驻，端点只操作遮黑层；不销毁内容显示。输入会转发至对应虚拟显示，旋转坐标与既有 ProjectionMath.turn 一致。
- 新增设置项“启用/退出固定双屏（试用）”。退出恢复旧投影助手；切回小米默认桌面时退出该模式。
- 原生最近任务可通过 am start --display 指定目标，并保持原生 Activity；底边上滑停留触发。选择卡片、清理完成、空白返回的所有显示归属还未全面验收。

用户报告的两项回归必须优先修复：输入法不弹出；倾斜又误触模糊。

1. 对每个自建虚拟显示设置并读取验证 IWindowManager.set/getDisplayImePolicy，值为 DISPLAY_IME_POLICY_LOCAL(0)。通过 ServiceManager 的 window Binder 访问，避免本机 Android shell 进程调用 WindowManagerGlobal 时的 ApplicationSharedMemory 未初始化异常。当前设备已成功创建启用该策略的两个显示。
2. 删除这次新增的 RuntimeShader 近似模糊。FixedDualGpuProgram / FixedDualGpuPyramid 由 tools/sync_dual_renderer.py 从原 LiveMirrorWindowProbe / LiveBlurPyramid 提取。折叠片元着色器、高斯级联、gamma 与方差插值沿用原实现；只适配独立桌面矩形输入。透明覆盖模式改用原着色器已有的源图合成分支，使完整桌面持续可见。
3. 独立 FixedDualContact 在 shell 端继续采样原数字 Hall 传感器，不依赖会被停掉的 EarlyDisplayHelper。Session / GPU 重新使用 FoldPose.angle()、fullyOpened()、blocksProjection()；渲染跟随使用原 ProjectionAngleMotion 和 ProjectionEntrance。主屏选择不参与该动画链路。
4. 编译、lint 和全部 tools/test_models.py 通过；手机运行 state 6 时记录两套 content display 252/253。用户的实机倾斜与内外屏输入法反馈仍待确认，不可把代码编译/截图视为完整验收。

仍需收尾：锁屏当前会退出会话，启用试用设置后解锁可重新建立；因此还不满足“包含锁屏生命周期也永不更换主屏”的完整长期目标。部分应用复用旧任务/最近任务选卡可能回到默认显示，需继续验证。双屏性能、方向变化、所有组件和分屏交互未全面验收。不要把试用版描述成全部完成。


## 2026-09-16 清晰度与侧滑反馈修复

用户确认输入法和倾斜误判修复有效，随后报告清晰度下降以及返回震动/动画丢失。

- 根因：清晰画面也通过半分辨率模糊金字塔 level0 采样。生成器现为 sigma=0 的采样增加原生 external texture 分支；包括平整桌面、清晰接收半屏和合成底图，均直接读取独立显示的原始缓冲。原折叠高斯链路和几何参数保持不变。
- GPU 绑定第 7 纹理单元作为原生输入，采用 SurfaceTexture 的原始坐标矩阵；输入/输出内屏 2364×1672、外屏 1168×1712。现场截图确认文字朝向正确、清晰区域不再走缩小纹理。
- FixedDualGestureFeedback 复用 NavigationGestureGate，恢复侧边跟手箭头、底边指示条与 CONFIRM 触觉反馈；桌面主界面仍不拦截侧滑返回。手势捕获后不把下滑过程误传给应用。
- 编译、lint、全部模型测试通过；修正版已安装并运行。内屏菜单侧滑返回的日志为 ACTION BACK feedback=true，系统接受了触觉反馈请求。未用截图推断用户实际震感强度。

## 2026-09-16 系统交互审计与灰色返回弧形

- FixedDualGestureFeedback 改为从触摸侧边延展的灰色贝塞尔弧形、白色箭头，随触点纵向移动，松手/取消后 180 ms 收回。双输出共用此实现。实机截图 build/duo-arc.png 确认左边弧形可见，菜单侧滑返回和触觉请求有效。
- 顶部 48 dp 不再参加侧滑返回识别；多指打断已捕获的边缘手势后吞完剩余序列，避免向应用发送缺少 DOWN 的 MOVE/UP。
- 固定双屏运行期间关闭旧物理主屏手势窗口，refresh 也不再重新创建旧窗口。实机窗口列表确认不再残留 Glass gesture back/home。
- barePanel 只在 Duo 窗口有焦点、未显示编辑/组件/对话框且 IME 不可见时禁用侧滑；键盘或其他窗口出现后允许返回。
- 实测发现最近任务 BACK 原先落到小米 SecondaryDisplayLauncher。shell 现在在 BACK 时查询该显示当前任务，仅对小米最近任务/副屏 HOME 改为启动对应 Duo。清理/空白返回采用仅在最近任务打开期间启用的 250 ms 当前任务检查；发现 SecondaryDisplayLauncher 后恢复对应 Duo，选中应用则停止检查。实测无障碍事件没有可靠报告此虚拟窗口返回，因此未依赖该事件。该补偿路径仍可能短暂露出系统副屏桌面，尚不能称无缝。
- 双屏桌面入场请求改为各 Activity 独立保存，避免另一块屏或物理 HOME 消耗全局动画请求。

**通知栏/控制中心尚未恢复。** 本机 SystemUI 创建了虚拟显示 StatusBar，但 NotificationShade 仍在物理 display 0。直接向虚拟状态栏注入下滑与鼠标点击未使面板迁移；expand-settings/expand-notifications 仍指向物理主屏。固件自己的 shade_display_override 仅支持 status_bar_latest_touch 策略，不支持新版 AOSP 的数字 displayId 或 SpecificDisplayIdPolicy 参数。已只读核查本机 APK 和 shell IWindowManager 方法：任意移动系统 WindowToken 的方法未暴露在 IWindowManager Binder，不能靠所测命令恢复。没有改系统全局策略、替换系统组件或为了通知栏切主屏。

剩余需要系统窗口桥接：通知/控制中心，以及同类音量面板、权限/系统选择器跨显示归属。后几项尚未逐个实测，不能描述为已确认全部损坏或全部正常。最近任务选卡、清理、空白返回仍需分别验收，不能用侧滑返回成功代替全部验收。

本轮最终包 assembleDebug / lintDebug 通过并已安装。会话固定内屏 primary uniqueId，content display 265/266（重建时 ID 会变化）。实测补充：
- 内屏菜单侧滑 BACK，日志 feedback=true，灰色弧形真机截图已观察。
- 底边上滑停留进入原生 RecentsActivity；侧滑返回 DuoInnerActivity，DuoEntrance 记录 visible entrance start。
- 原生最近任务左侧空白点击后，shell 记录 Restore recent home display=265，随后 DuoInnerActivity 恢复并记录 visible entrance start。此项功能已验证，尚未量化/消除中间副屏桌面短暂露出的可能。
- Duo 应用搜索框点击可呼出输入法（mInputShown=true / mImeWindowVis=3），侧滑后键盘关闭（false / 0），再次侧滑关闭搜索；短上滑 HOME 正常。
- 未执行清空全部最近任务，也未逐个操作用户应用的卡片、分屏或小窗。外屏共用代码已安装，但本轮实机验证在内屏完成。

## 2026-09-16 原生副屏 HOME 接入（替代最近任务轮询补偿）

上述最近任务绕回小米桌面的根因已进一步确认：DuoInnerActivity / DuoCoverActivity 以 singleTask 普通应用启动，没有注册 SECONDARY_HOME。系统每个内容显示的 HOME task 仍是小米 SecondaryDisplayLauncher。

- 新增 DuoSecondaryActivity，使用 singleTop、MAIN + SECONDARY_HOME + DEFAULT；系统可在两个内容显示各持有一个原生 HOME 实例。主屏 DuoHomeActivity 和物理主屏映射保持原有职责。
- 按内容显示名称继续使用 duo_inner / duo_cover 布局和宿主 ID 2702 / 2703，不迁移或清空已有组件。
- shell 启动/回桌面使用 SECONDARY_HOME Intent 和 ACTIVITY_TYPE_HOME(2)。删除上一轮的最近任务 250 ms 轮询、任务查询和 BACK→重启桌面特判；BACK 交回对应显示原生按键路由。
- 本机验证两个显示 268/269 的 root task 和子任务均为 type=home，DuoSecondaryActivity 实例分别是 t1177/t1179，未创建小米 SecondaryDisplayLauncher。直接 am 启动原生最近任务（绕过本项目手势和补偿逻辑），点空白及原生 BACK 都回到原有 Duo HOME 实例。
- 编译与 lint 通过。新代码不依赖轮询恢复桌面。最终画面过渡仍需要视觉验证：本轮 screenrecord 只得到黑帧，不能据此宣布动画验收。内部 DuoEntrance 日志仅证明动画被触发。
- 通知面板仍留在 display 0；副屏 HOME 接入并未自动修复通知/控制中心路由。

平台接口参考：[AOSP 多屏系统装饰与副屏 Launcher](https://source.android.com/docs/core/display/multi_display/system-decorations)。设备自身 RootWindowContainer.resolveSecondaryHomeActivity / canStartHomeOnDisplayArea 也确认 secondary HOME 不应使用 singleTask。

新增只读实机检查 `tools/experiments/audit_dual_home.py --adb <adb路径>`：从当前会话获取内容显示 ID，再检查真实 dumpsys activity 里的 HOME 类型、每屏唯一 Duo 实例，以及是否残留小米副屏桌面。移除补偿后的最终包安装并重启助手后，271/t1197 与 272/t1199 两屏均通过。此检查不启动应用、不改设置，不验证动画或通知面板。

## 2026-09-16 清晰帧性能与真实返回画面

- FixedDualGpu 不再无条件为每个输入帧生成 7 级模糊纹理。只有 shader 可能采样模糊纹理时更新；清晰期间保留 dirty 状态，开始折叠时即使输入画面未变化也补算。原 Hall/角度逻辑、几何、着色器和高斯算法不变。
- fixed-dual-session 查询加入每屏 source/pyramid/presented/tilt/needsBlur 计数。实机完全展开时内屏 source=433、pyramid=0、presented=433，外屏 source=295、pyramid=0，证明该清晰状态下不再进行无用的模糊计算；不是帧率或功耗量化测试。
- 指定物理 ID 的 screenrecord 在此模式只录到黑帧；不指定 ID 的当前默认输出录制可得到实际合成画面。`build/duo-home-native-return.mp4` 和逐帧图证实旧自定义入场逻辑会在系统 HOME 过渡期间隐藏内容，留下单独移动的玻璃模糊块，之后又淡入一次。内部动画日志不能证明观感正常。
- DuoSecondaryActivity 现在直接保留系统 HOME 过渡，不再等待系统动画结束后隐藏/淡入桌面。旧主屏兼容入口的逻辑保留。
- HomeGlass 在 pre-draw 同步控件及祖先的实际 alpha/visibility，防止控件淡出时 OEM compositor blur 仍保持完全可见。跨窗口模糊开关与半透明材质降级仍保留。

最终普通运行录屏 `build/duo-home-system-transition.mp4` 及 `build/duo-home-system-transition-frames.png` 已查看：内屏最近任务点空白返回时，Duo 图标/文字/玻璃一起随系统 HOME 窗口移动归位；没有上一版的孤立模糊块和第二次淡入。该证据覆盖此入口的实际输出，不证明所有入口、外屏或折叠过程均流畅。编译/lint 通过，修正版已安装，内容显示为 277/278。完整目标仍有通知栏/控制中心、锁屏连续性、全部小窗/分屏/组件入口等待完成项。

## 2026-09-16 通知跨屏的固件策略限制

本轮核对的是手机实际 framework.jar、services.jar 和 MiuiSystemUI.apk，不能套用新版 AOSP 的数字 displayId 命令。

1. 本机 SystemUI 的 ShadeDisplaysInteractorImpl 确实有移动 shade WindowContext 的实现；ShadeDisplaysRepositoryImpl 的 displayIdFromPolicy 会在 isMirroringEnabled 为 true 时强制返回 display 0。DisplayRepositoryImpl 的 mirroringSettingFlow 观察 Settings.Secure.mirror_built_in_display。
2. 原值为 1。尝试设置为 0 后立即读回仍为 1，主屏 uniqueId 与两套内容显示未改变。DisplayManagerService 的设置观察器调用 SecondaryDisplayPolicy.forceEnableMirrorBuiltInDisplaySettingIfNeeded；当 DesktopModeHelper.canEnterDesktopMode=false 时强制写回 1。
3. 用独立 shell app_process 加载手机自己的 services.jar，并使用系统 Context 只读查询得到：isDesktopModeSupported=false、isDesktopModeDevOptionsSupported=false、isDeviceEligibleForDesktopMode=false、canEnterDesktopMode=false、isDesktopExperienceDevOptionSupported=false。底层 enableDesktopWindowingMode=true，但 enableDesktopModeThroughDevOption=false。因此不能假设打开一个普通开发者选项就能解除限制。
4. 没有修改桌面模式开发者选项或重启手机；mirror_built_in_display 当前仍为原值 1。两屏原生 HOME 检查再次通过。

通知栏/控制中心仍未解决。已询问用户后续优先保留固定双屏、继续系统级适配，还是允许重新评估切屏方案；回复前维持既定固定主屏约束。此限制不代表其他桌面、组件、触摸路径已全部完成。

## 2026-09-16 小组件实机归属审计

只读 dumpsys appwidget 已保存至 build/diagnostics/widgets-current.txt。原主桌面 host 2701 仍持有日历 SmallDateWidget（ID 70）；内屏 host 2702、外屏 host 2703 均已注册更新回调，但 widgets.size 均为 0。因此目前不能用原主桌面的组件验证结果证明双屏组件添加、配置、点击和更新已通过。旧组件仍保留，未删除或转移。

HomeWidgetBindingSmoke 原先固定读取 duo_home，即使传入双屏 Activity 也可能检查或恢复错误的布局。现改为读取 Activity.panelStore()，并先核对所用组件注册表与活动宿主一致。assembleDebugAndroidTest 通过；本轮没有执行绑定验收，没有安装或启动 instrumentation，以免重启正在运行的固定双屏会话。现有 HomeSmoke 的启动入口仍只启动主桌面，后续双屏验收还需要对应显示的真实入口，不能仅凭本次测试修正宣称双屏验收完成。

普通 uiautomator dump 中断了无障碍服务并结束固定双屏会话，读到物理 display 0 后方主桌面（含旧日历）；此时未找到内容窗口的结果无效，不能作为双屏无障碍故障证据。已恢复会话，新内容显示 280/281，主屏 uniqueId 不变。新增 tools/experiments/DualUiDump.java，独立 shell UiAutomation 使用 FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES，开启 FLAG_RETRIEVE_INTERACTIVE_WINDOWS 后等待 idle，通过 getWindowsOnAllDisplays 查询。恢复后实机返回 0、1、280、281，并成功读取内屏 Duo 空组件栏和应用页面。因此后续必须使用保留无障碍服务的工具，并在检查前后确认会话仍运行；不再使用普通 uiautomator dump。

同一会话内通过内屏组件栏“＋”进入真实选择器，搜索 com.android.calendar 并选择“日期”。实际绑定生成 ID 158 / host 2702 / SmallDateWidget，原 ID 70 保留。内容显示 280 的 AppWidgetHostView 收到原生日历 RemoteViews 并显示当前日期。点击日期区域启动小米 AllInOneActivity（t1234）且仍位于 display 280，随后发送原生 BACK。此项覆盖内屏组件栏的无配置提供者添加、首次内容更新和点击启动路由；不覆盖持续更新时序、外屏、带配置页提供者或应用页混排。新增日期组件保留在内屏供使用。

外屏逻辑显示 281 通过实际“应用/小组件”切换、组件栏“＋”、搜索和日期条目添加，生成独立 ID 159 / host 2703，收到日期 RemoteViews。点击在 display 281 启动小米日历 t1235，BACK 返回。随后通过编辑→组件设置→“放到当前应用页”移动同一 ID 到应用网格，退出编辑后点击仍在 display 281 启动日历 t1236。持久注册表为 [159]，pending_widget 与 pending_widget_page 均为 -1。内屏 ID 158、旧主桌面 ID 70 不变。

此轮是向外屏内容显示注入输入并读取它的真实窗口；物理手机仍完全展开、外屏按规则黑屏，因此不声称已目视验收外屏的物理显示、触控映射或切换动画。外屏日期组件留在应用页面，供后续合拢时验收；尚需检查带配置页组件、长期更新和视觉尺寸。

内屏配置取消验收：先添加了小米倒数日 ID 160，但它没有声明独立 configure Activity，因此通过正常组件菜单移除此临时实例，不作为配置测试证据。新增只读 WidgetConfigurationProbe.java，通过真实 AppWidgetProviderInfo.configure 列出配置提供者。随后从内屏选择器搜索 com.github.android 并添加 GitHub 组件，pending_widget=161，系统 APPWIDGET_CONFIGURE 启动 ContributionWidgetSettingsActivity，确定位于 display 280、原 HOME task 1231。原生 BACK 取消后返回 Duo，pending_widget/pending_widget_page 均为 -1，注册表仍为 [158]，系统 appwidget 中 ID 160/161 均不再存在，host 2702 只保留原日期组件。已退出编辑模式，固定会话仍为 280/281。此证据覆盖原生配置页启动和取消清理，不覆盖保存配置成功、外屏配置页或系统授权弹窗（本机已有绑定授权）。

## 2026-09-16 正式启用偏好与重建恢复

检查发现此前会话仅由 ADB 临时 start 启动，没有 duo_dual.xml；因此 maintain 在会话结束后不会自动重新启用。已通过真实桌面菜单退出临时会话，再从主桌面菜单“启用固定双屏（试用）”启动，独立读取磁盘确认 duo_dual/enabled=true。新会话 content=283/284，primary 仍为 inner uniqueId；两个原生 HOME 检查通过（t1238/t1240）。

重建后内屏日期组件仍为 ID 158 / host 2702，外屏仍为 ID 159 / host 2703，两个新 Activity 中均重新显示真实 AppWidgetHostView，外屏仍在应用网格。此项证明主动结束/重新启动会话时已有组件的宿主和位置可恢复，未验证进程死亡、重启手机或真实锁屏循环。

锁屏连续性仍未完成：FixedDualSession.frame 遇到 isKeyguardLocked 会 close，释放物理状态请求、输出和内容显示；maintain 只会在持久启用、默认 HOME、解锁、交互中、助手就绪后重建。现在持久启用已保存，但不得把“以后可自动重建”描述为“锁屏保持原会话”。保持原生锁屏在当前可见物理屏可用的同时保留固定拓扑，仍需要系统窗口归属方面的适配。

启停菜单修正：此前文字和点击仅检查 active()，持久 enabled=true 而会话中断时错误显示“启用”，无法从该菜单关闭自动恢复。现在以 enabled || active 决定启停，菜单打开时捕获同一个值用于文字及点击，避免菜单显示“退出”后会话恰好结束、点击反而启用的竞态。临时 ADB 会话仍可退出。

该修正 assembleDebug / lintDebug 通过并安装。安装结束后旧助手确已退出，启动新的 StandaloneHelper 并收到 READY；没有发送 fixed-dual-session arg 1，保存的 enabled=true 已自动恢复双屏（content=286,285，inner primary uniqueId 不变）。因此本轮同时补充了包更新后、助手重新就绪时自动恢复会话的实际证据；不等同于锁屏无重建。

## 2026-09-16 原生小窗路由实测

确认 display 286 为内屏、285 为外屏。内屏运行系统设置后进入小米 RecentsActivity，长按最前 WLAN 任务，原生菜单显示“不支持分屏/小窗”；此结果仅限该任务。换日历后，同一菜单明确显示支持分屏和小窗，证明入口并非统一被禁用。

点击日历“小窗”后，系统创建/移动日历 task 1258 到物理 display 0，mode=freeform，内屏内容显示 286 回到 Duo。此为小米小窗跨显示归属故障，不能声称原生小窗已在内容屏复用成功。按设备 am help 的 display move-stack 将此唯一测试任务移回 286 后，系统把它改成 mode=fullscreen；该命令不能保留所需的小米小窗行为，未接入产品。随后对 286 发送 BACK 并恢复已有 SECONDARY_HOME。未修改主屏映射，也未强制窗口模式或修改全局分屏开关。分屏按钮尚未点击，不能由小窗结果推断其最终表现。

后续分屏独立实测：内屏 display 286 的日历原生最近任务菜单支持分屏，点击后日历 task 1259 被放入物理 display 0 的 split main stage（root 4 / main 5，mode=multi-window）；选择第二个应用的 RecentsActivity 则仍在 display 286，界面只占右半部分。尚未选择第二个应用，已经出现跨显示拆分，不把该流程视作可用分屏。

清理本次试验：先对 286 发送 BACK 退出选择，再对物理 0 发送 BACK 结束该日历分屏任务，然后恢复 286 原有 Duo HOME。实机 main/side stage 均为 visible=false、sz=0，两个内容显示和物理主屏的前台均回到各自 Duo，未触碰其他应用任务，也未修改主屏映射或分屏全局配置。原生分屏与小窗需要系统级的显示路由适配；当前 launcher 布局层不能宣称已满足复用要求。

适配环境复查：当前 shell id=2000、SELinux 域 shell；command -v su 无结果，已安装包名查询没有发现 Magisk/LSPosed/KernelSU/APatch/Xposed。此为未发现现成适配环境，不是证明设备绝不可能获得系统级能力。没有尝试提权、解锁引导程序或修改系统属性。已结合通知栏、小窗、分屏实测再次请用户明确下一步优先保留固定主屏继续系统适配研究，还是允许为原生交互调整显示架构。回复前保持已启用的固定双屏，不把部分组件验收替代整体完成。
