# PROJECT_STATE

- 日期：2026-09-20
- 分支：duo-ui-preview
- 本阶段：**全部下拉实验回退，恢复自制 HyperOS 控制中心**（用户拍板新方向：**单虚拟屏架构**，方案见 docs/单虚拟屏架构方案.md，明天公司继续）（用户准备推翻重置；NativeShade/MirrorShade/镜像 VD 通道/AIDL/编排残留全部移除，HomeControlPanel 接线恢复）；全部改动未提交 git

## 下拉实验全线回退（2026-09-20 上午，tools/revert_shade.py）

- **回退到"注释掉自制下拉框之前"**：ProjectionService.updateShadeBar 恢复 HomeControlPanel.beginDrag/dragOn/releaseOn 三行接线；删除 NativeShade.java、MirrorShade.java；移除 setNativeShade 三件套、outputForContent、sessionEnded、provider 的 mirror-mode/mirror-shade-test、AIDL createMirrorContent/releaseMirrorContent、FixedDualContentHost 的 createMirror/releaseMirror/id-0 注入放开、MobileHelperHost 的会话内 5↔6 直切（恢复"cannot change during a session"）。
- **装机验证**：会话 running（state 6 封面为主，灭屏后唤醒自动恢复）、封面下拉出自制面板（custom_cc_back.png：双卡+媒体+竖滑条+3×4 开关格完整）。
- **今天全天实验结论存档**（推翻重置时可参考）：① 原生下拉只在 display 0 存在；② 我们的 a11y 覆盖窗(311000)在 SystemUI(151000) 之上，主屏原生面板必须撤出才可见；③ 5↔6 换主屏在 composer 层硬清一帧，任何应用侧遮罩都盖不住；④ 无 OWN_CONTENT_ONLY 的 VD 可实时镜像 display 0（DisplayManager 保比例，buffer 必须与 VD 同尺寸）；⑤ INVISIBLE 的 TextureView 不分配 surface（等首帧要用 alpha=0）；⑥ shell input 能唤原生面板，a11y dispatchGesture 不能。（下拉→全屏镜像 display 0 遮罩+注入开真面板+触摸回注，远程全闭环验证）+ 回退到 DuoHome 双虚拟屏架构；全部改动未提交 git

## MirrorShade：非主屏的原生下拉（2026-09-20 上午）

- **问题**：原生下拉只存在于 display 0（主屏），固定持一个 state 意味着只有一块屏能下拉（另一块屏 NativeShade 拒绝）；换主屏又会 composer 级闪黑。"抄小米原版"不可行——那是 SystemUI 进程内特权实现（单屏设计），不能在我们进程里跑。
- **方案**：非主屏下拉触发（NativeShade 检测 physicalId!=0 时路由到新类 `MirrorShade`）→ 在该面板盖全屏 a11y 遮罩窗（黑底+居中 letterbox TextureView）→ helper `createMirrorContent` 建 display 0 全尺寸镜像 VD 喂 TextureView → `svc input swipe` 注入下拉到 display 0（真原生面板打开）→ 遮罩触摸经 letterbox 逆映射 `dualTouch(0,…)` 回注 → 350ms watch 检测 display 0 大 systemui 窗口消失即关（grace/重试同 NativeShade）→ `releaseMirrorContent` 释放 VD（新 AIDL=16，FixedDualContentHost.releaseMirror）+ 移除窗。用户看到/操作的是真原生面板（1 帧延迟），DuoHome 在下面等着。
- **验证（折叠态远程）**：provider 调试钩子 **mirror-shade-test** 触发 → open panel 1 → pull OK → mirror VD 368 (1168x1712) → 内屏截图见原生通知面板（mirror_shade2.png，居中 letterbox：内屏 0.707 vs 源 0.682 上下满左右窄边）→ BACK → "shade dismissed" → VD 释放干净（display-id 列表无残留）。
- **待办**：真手指体验（展开态在内屏下拉）待用户验证；注入 x=86% 开通知侧 vs 控制侧的左右判定问题仍在；letterbox 边缘触摸已被忽略（越界门）；深色模式不重绘/小米互传包名两个桌面侧老 bug 未修。（用户拍板：镜像换主屏方案因 composer 级闪黑放弃，Duo 桌面仍是主线；NativeShade 实验保留活跃）；见"镜像架构回退"节；全部改动未提交 git

## 镜像架构回退（2026-09-20 上午，本地未提交）

- **回退原因**：5↔6 会话内换主屏实测仍有瞬时黑屏（双冻结帧遮罩也盖不住——换绑发生在 composer 层，我们的遮罩窗同管线被一起清）。用户决定回到"DuDuoHome 桌面"主线（即'一直镜像'提议前的工作形态）。
- **回退方式**：外科手术式（全部未提交+另一会话改动混在同一工作树，不能 git 回退）：FixedDualOutput 撤掉 mirror/pane/crop/snapshot 全部恢复原版（保留 setNativeShade 三件套）；FixedDualGpu 撤掉 plain/cropRight/buffer 分离；FixedDualSession 撤掉 mirrorMode/单输出/换向编排（switchTo/FreezeMask 全删），保留 outputForContent（NativeShade 依赖）。回退脚本 tools/revert_mirror.py、revert_session.py 留档。
- **保留未撤（休眠可用）**：IHelperHost.createMirrorContent AIDL + FixedDualContentHost.createMirror（无 OWN_CONTENT_ONLY 镜像 VD）+ id-0 注入放开 + helper 会话内 5↔6 直切（fixedDualState switch 路径）+ ProjectionProvider "mirror-mode" 开关（duo_dual/mirror pref，当前 0）。
- **装机状态**：home 已切回 DuoHomeActivity；mirror pref=0；会话 running primary=内屏 state 5；内屏 DuoHome 截图验证恢复（revert_inner.png）。
- **本轮学到的结论**：① display 0=主屏、面板常亮（state hold 双屏不断电成立），但**换主屏=系统层窗口栈迁移，composer 会硬清一帧，应用侧遮罩无法覆盖**——任何依赖 5↔6 翻转的方案都有此天花板；② 镜像 VD（无 OWN_CONTENT_ONLY）通道可用且能 1:1 裁剪采样（buffer 尺寸必须与 VD 严格一致否则 DisplayManager letterbox 叠加）；③ 内屏 pane 物理方向：turn 变换补偿面板挂载旋转，折叠构建=物理上半=用户 hinge 右。（"一直镜像原生屏"：主屏原生直通+副屏实时镜像 display 0+触摸回注，三项截图实证；见"纯镜像架构"节）+ 原生面板接管实验（NativeShade，已被镜像方案取代）+ 双屏翻页卡顿三连修（另一会话）；DuoHome 全链停用保留；全部改动未提交 git

## 纯镜像架构（2026-09-20 凌晨，本地未提交）

- **用户拍板的方向**：项目初衷是开合不黑屏而非做桌面。架构=常驻 hold 双屏状态 + 副屏永远实时镜像 display 0 + 触摸回注；原生桌面/下拉/手势/多任务全部白送，DuoHome/GestureNav/HomeControlPanel/recents 管理全部不再需要。无状态翻转→无 rebind→开合不黑 by construction。
- **实现**：① helper 新 AIDL `createMirrorContent`（FixedDualContentHost.createMirror：createVirtualDisplay 无 OWN_CONTENT_ONLY=镜像 display 0，PUBLIC+TRUSTED 反射，120Hz，无 IME/无 SECONDARY_HOME）；② `FixedDualContentHost.touch/key` 放开 displayId==0 注入（key 对 0 直注 BACK/HOME，不走 launchHome）；③ `FixedDualOutput` 加 `mirror` 构造参数：VD 走 createMirrorContent，deliver() 里 inverse 旋转后加 srcW/srcH（display 0 尺寸）缩放矩阵再 `dualTouch(0,…)`，绕过 shadeBand/feedback；④ `FixedDualSession` mirrorMode（duo_dual/mirror pref，provider 方法 **mirror-mode** arg 0/1 切换）：maintain() 不再要求 home role，prepare() 只给**非 primary** 面板建输出（主屏=原生本体），contentReady/状态行按单输出；**镜像输出永不挂帘幕**（frame 的 block 对 mirror=false——一直亮是目的）。
- **验证（折叠态，state 6 封面=display 0）**：封面直通 MIUI 原生桌面（mirror_cover.png）✓；内屏（折叠隐藏面）实时镜像出 display 0 完整画面（mirror_inner2.png，帘幕修复前黑屏——旧 policy 折叠时 curtain 非可见面板）✓；内屏镜像面板注入下拉→封面 display 0 原生通知面板打开（touch_cover.png，回注链路全通）✓。
- **装机状态**：home 已切 `com.miui.home/com.miui.home.launcher.Launcher`（cmd package set-home-activity）；mirror-mode=1 在跑；session 折叠态启动→state 6（封面 primary，内屏镜像）。
- **右侧半区锚定（02:4x 轮）**：用户反馈"展开时镜像居中"——根因是镜像 VD 全屏（2364x1672 横），DisplayManager 把封面竖屏内容等比缩放居中留双边。修复：FixedDualOutput 镜像模式引入 paneW/paneH=右半（width/2×height），窗口(root+forwarder)宽度 paneW+Gravity.RIGHT、texture 同尺寸右贴、**镜像模式跳过 turn 旋转变换**（VD 按 pane 自身取向创建）、VD/GPU/surface 全按 pane 尺寸、deliver() 触摸映射 pane→display 0。折叠态截图验证：镜像完整贴右半、左半黑（right_half1.png）；右半区注入下拉→display 0 原生通知面板开（right_half_touch.png）✓。
- **待办/注意**：① **展开后需 re-toggle mirror-mode 让会话以横屏几何重建**（pane 尺寸取自会话启动时姿态；折叠态启动的会话展开后窗口几何是旧帧）——根治需 display rotation listener 动态重排（resize VD+dualSurface 重绑+gpu 重建）；② 目标形态是 state 5（内屏 primary）+封面镜像——需**展开态启动会话**（helper fixedDualState 只能 hold 当前 primary 不能翻转）；展开后 re-toggle mirror-mode 或重启会话即可；③ 用户真机开合体验（黑屏/亮度/延迟）待验；④ 镜像 VD 分辨率=pane 尺寸，开合旋转/纵横比映射未验；⑤ NativeShade/自制面板代码闲置保留；⑥ 三键导航在原生侧正常显示（镜像里同步可见）。① 目标形态是 state 5（内屏 primary）+封面镜像——需**展开态启动会话**（helper fixedDualState 只能 hold 当前 primary 不能翻转）；展开后 re-toggle mirror-mode 或重启会话即可；② 用户真机开合体验（黑屏/亮度/延迟）待验；③ 镜像 VD 分辨率=面板尺寸，DisplayManager 缩放 display 0 内容，横竖切换（开合）时的旋转/纵横比映射未验；④ NativeShade/自制面板代码闲置保留；⑤ 三键导航在原生侧正常显示（镜像里同步可见）。

## 原生面板接管实验（2026-09-20 凌晨，本地未提交）

- **关键发现**：封面物理屏就是 display 0（折叠态 primary），原生桌面/SystemUI 一直在我们覆盖窗后面——**不需要镜像**，撤出即可见、可触。
- **机制**（新类 `NativeShade`）：`ProjectionService.updateShadeBar` 里 HomeControlPanel 三行接线已注释改走 NativeShade；触发后 ① `removeShadeBarAt(0)` 撤 display 0 的 Duo home bar（`NativeShade.blocking()==id` 防重挂）；② `FixedDualOutput.setNativeShade(true)`：forwarder 加 FLAG_NOT_TOUCHABLE+INVISIBLE、texture INVISIBLE（**绝不能 GONE/移除**——SurfaceTexture 销毁会 fail 整个会话，onSurfaceTextureDestroyed 已加 nativeShade 守卫）；③ 经 helper `svc("input swipe …")` 注入下拉（**a11y dispatchGesture 实测唤不醒 HyperOS 面板，shell input 可以**，HomeMigrator 先例）；④ watch 每 350ms 扫 getWindows()：display 0 有大 systemui 窗口（≥35% 屏高）= 面板在；连续 3 次消失且重注入一次仍失败才恢复；45s 超时/灭屏/锁屏兜底恢复。
- **恢复**：`setNativeShade(false)` 反向还原 + `refreshShadeBars()` 重挂 bar。真机闭环：触发→原生面板开→BACK 关→"restore: shade dismissed"→桌面完整回归（out/cc-compare/native*.png）。
- **三键导航栏修复（同轮）**：撤出期间经 helper `settings put global policy_control immersive.navigation=*` 只隐藏导航栏（原生桌面全高、与我们桌面布局一致，HyperOS 尊重该 policy，final_launcher.png 验证 4×5 格+dock 满高无三键）；restore/sessionEnded 时 `settings delete global policy_control` 清理（FixedDualSession.close 兜底调用）。watch 增加 `latched` 标志：面板成功打开过一次后消失=真关闭，立即恢复不再重注入（修复重注入与快速关闭打架的循环）。
- **待办**：注入 x=86% 开的是通知侧而非控制侧（手工 swipe x=81% 开的是控制中心），左右判定待调；真手指体验未测；state 6（封面非 primary）路径未验证；HomeControlPanel 类与 OPEN_SHADE 调试广播仍闲置保留。

## 双屏翻页卡顿根因与修复（2026-09-20 凌晨，本地未提交）

- **用户症状**：双屏同开下桌面翻页滑动只有 50~70fps（对照：原生桌面同场景 110+）；用户对自适应无意见，只要滑动流畅。
- **根因（实锤）**：不是面板上限、不是虚拟屏 60Hz 帽——虚拟屏产帧实测可达 ~110fps；是**输出呈现与 120Hz vsync 错拍**：FixedDualGpu 4ms 自由轮询连续 swap，抖动 cadence 让 HyperOS 自适应在拖拽期间**追频抖动**（实测外屏 60↔72↔90↔120 乱跳，内屏 lead-follow 跟随）。MIUI 刷新率悬浮窗=面板档位=用户看到的 50~70。
- **修复（已装机 20260919b，验证过）**：`FixedDualGpu.draw()` 呈现节流 `now-lastPresentMs>=8` 才 draw+swap。验收：拖拽起手 ~1s 面板 60→120，**全程稳 120**（7 连采样无跳变），结束回落 60（自适应正常）。
- **失败方案（已回退勿重试）**：`postOnAnimation` vsync 节拍 + 输出窗口 8ms 保活 invalidate 环 → 三条全屏 GPU 管线互相挤兑，**翻页产帧崩到 12fps**（20260919a 即测即回退）。教训：输出窗口每次 invalidate=一次全屏 RT 合成，×2 屏 ×120Hz 不可承受；worker 自由轮询是吞吐基线。
- **刷新率钳制在 lhasa HyperOS 全部无效（实证）**：`min_refresh_rate`（SettingsObserver 不读）、`global user_preferred_refresh_rate`（无效）、`set-user-preferred-display-mode`（**存得上但面板 userPreferredModeId 恒 -1**，存 120 或 120.00001 都不解析成模式；挂 pin 状态下空闲照样双屏同掉 60）。`applyPrimaryRate` 是安慰剂，且 guard 会被实验遗留 pin 卡住（d0/d1 已手动清回 null）。窗口 `preferredRefreshRate`+`setFrameRate(120,ALWAYS)` 单独压不住追频，但配合呈现节流行为正确。
- **测量方法论**：adb `input swipe` 注入 ~30 事件/s，远程测产帧被输入限死（只能验面板档位/管线健康，**120Hz 手指路径只能用户实测**）；`renderFrameRate` 采样看档位；逻辑屏绑定会 swap（当前 d0=外屏 d1=内屏）；`input -d 1 swipe` 本机抛异常（无法远程驱动内屏）；装包期间 MIUI `killDueToPackageUpdate` 每 ~60s 杀进程重启（装后连串会话重建先查这个）。
- 改动：FixedDualGpu.java（呈现节流）、FixedDualOutput.java（仅标记日志）。三件套过（marker="paced free-run build 20260919b"）。

## 手指路径三连修（2026-09-20 凌晨第二班，build 20260920c，待用户手指实测）

- **用户手指实测打脸 b 版**："还是不到60，内屏比刚刚还卡"——面板档位修复≠体感修复，真瓶颈在手指路径；adb 注入 ~30 事件/s 测不出 120Hz 触摸下的表现（已确认是测量盲区）。
- **gfxinfo 实锤主线程窒息**：弹层动画（纯 Choreographer 驱动）中位帧时 **53ms≈19fps**（GPU 仅 11ms）——全进程单主线程（两块虚拟屏桌面 Activity+输出窗口+a11y 服务+触摸转发共享）被三处税拖死。
- **三处修复（c 版）**：① `ProjectionService.update` 会话 active 时 tick 40ms→≥500ms（原来稳态下仍以 25次/s 在主线程跑 isRoleHeld/isInteractive 同步 binder）；② `FixedDualContentHost.inject` 模式 0(WAIT_FOR_RESULT)→2(ASYNC)——原来每个转发触摸阻塞到目标窗口处理完，手指 120Hz 串行等待直接压垮投递率（**UserService version 33→34 必须 bump，宿主代码才会替换**）；③ `DuoHomeActivity.onCreate` 窗口 `preferredRefreshRate=120`（虚拟屏桌面窗口此前从未投票，Choreographer 疑似跑 60）。
- **验收（gfxinfo 同一动画）**：40 帧/中位 53ms/卡顿 27.5% → **194 帧/中位 12ms/卡顿 7.2%**——动画产帧打满 ~120fps。手指路径（ASYNC 投递率+120 投票）只能用户实测。
- 注意：adb 拖拽测面板档位仍受"翻页位置在边缘/弹层未关"混淆（边界橡皮筋无内容→面板 60 是正常自适应，不是回退）。

## 触摸投递三连修 d/e（2026-09-20 凌晨第三班，build 20260920e 已装机）

- **手指实测数据（c 版，采样 fpswatch.log）**：手指拖拽期间面板全程稳 120 ✓，但 cover 虚拟屏产帧仅 42~101/s（均值 ~70）——瓶颈=触摸投递率：每事件"共享 worker 排队（被 40ms dualContact 同步往返挤占）+ 同步 binder 往返"，串行 ~10-15ms/事件。
- **d 版（已回退其缺陷）**：`IHelperHost.dualTouch` 改 **oneway** + `MobileHelper` 触摸专用 `touchWorker` 线程。数据：产帧均值 ~70→~100/s、settle 冲 195/s。**缺陷：oneway 让宿主 binder 线程池并发处理，事件偶发乱序注入→拖拽位置后跳，手感反而差**（用户实测打回）。
- **e 版（现行）**：保留 oneway+专线，`FixedDualContentHost.touch()` 加 `synchronized` 恢复宿主侧严格顺序（UserService version 35 强制宿主重载）。**待设备冷却后用户手指复测**。
- **热限流发现（用户判断）**：连续数小时测试后机身过热，MIUI 锁帧——e 版手感差可能主要是热而不是代码；显示侧 `thermalRefreshRateThrottling={}` 为空但 MIUI 自有热策略不走此表，**复测前先冷却**。
- 装机运维：d 版装后进程僵死（系统有 a11y 绑定但不拉起，am start 报 top-most 假象）→ **`am force-stop` + 无障碍双开关重绑**即恢复（进程 17719 会话 199/200）。00:18 又见一次已知重装启动竞态 NPE（ConfigurationController，自愈）。

## GPU 直通复活（2026-09-20，build 20260920f 已装机，远程验证全过）

- **用户拍板"做成跟原生一样"后启用**：第三十三轮禁用的 direct 旁路按其记录的前提修活——`FixedDualGpu` 构造加 `BooleanSupplier directAllowed`，`FixedDualOutput` 传 `()->contentId>=0`；64 帧恒等 → goDirect 门控放行（swap 同步返回即已重定向，leaveDirect 带重试回退）。
- **直通=原生管线形状**：虚拟屏直接画进输出 TextureView（SF 合成虚拟屏 → GL 取帧 → shader 拷贝 → swap 四跳全免），折叠效果出现时 50ms 轮询发现 tilt/crop≠0 → leaveDirect 回 shader。
- **远程验证**：双渲染器 `direct presented=` 生效、无黑屏（外屏 mean=143.8/stddev=56.8 真实内容）、swipe 期间 **278 帧 @中位 5ms @0% jank**（shader 模式最好 194@12ms）、面板起手即 120 全程稳。
- **待用户手指实测**：①翻页跟手度 vs 原生；②**折叠/展开过渡**（leaveDirect 首次实战，若黑屏检查 swap 回退与 EGL 重挂重试）；③锁屏往返。
- 若直通后仍差最后一点：下一步候选 TextureView→SurfaceView（SF 直合成零拷贝，虚拟屏 buffer 直接上屏，需 hidden setBufferTransform 做旋转）。

## 翻页动画三件套（2026-09-20，build 20260920g 已装机，远程验证过待手指实测）

- **用户指出布局缺陷**：dock 是 body 最右的兄弟列，pager 的进出边界=dock 左缘而非屏幕边缘；图标"在 dock 左侧一点点出现"。**修复（全出血 pager）**：body 外包 `bodyFrame`（FrameLayout）——pager 负边距抵消 root 水平 padding（insets listener 动态设置，clip 链 root/bodyFrame/body/panel 全 clipChildren=false），dock 用 `FrameLayout.LayoutParams(RIGHT)` 悬浮于 bodyFrame 上层，body 内留同宽 spacer 保持加权网格宽度不变；每页 `frame.setPadding(pageInsetLeft,0,pageInsetRight,0)`（左=屏幕左 padding，右=右 padding+68+dock 边距）让落位图标避让 dock。**效果：页面从物理屏幕边缘进出、从 dock 玻璃栏下方穿过、静止落位与旧版逐像素一致**（截图验证：4 列网格位置不变、dock 贴右缘、无遮挡；88dp 让位与 dock 占位精确吻合）。
- **P0 物理落位（HomePager 重写）**：`PhysicsScroller` 替代五次缓出魔改——UP 时 VelocityTracker 取松手速度，startScroll 按摩擦模型闭式解（指数衰减插值 x(t)=D(1-e^-st)/(1-e^-s)，牛顿解 s 使初速=松手速度，dur=1.1·1000·D/v0 夹 [180,460]ms；K<1 或速度反向退化为 150-260ms 短补间）。**消除松手瞬间"急停一拍"的速度不连续**。
- **P1 壁纸视差**：onPageScrolled → `WallpaperManager.setWallpaperOffsets`（每帧 binder，try/catch 包裹；MIUI 原生默认壁纸随翻页漂移）。`setPageMargin(12dp→0)`（页间隙会让图标离边缘偏移）+ `setPageCount` 供视差归一。
- **远程验证**：中段跟手（mid-swipe 像素差 143.7）、页提交（diff 17.0）、第 0 页右滑正确弹回、无崩溃；**教训：`input swipe` 起点 x>926 会打在 dock 占位条上（spacer 不可见但吃触摸）——远程测试滑动起点选网格中央**。
- **待用户手指实测**：跟手度/松手连续感/壁纸漂移/图标从边缘进出+穿 dock。P2 视觉风格（层叠景深/视差平移/纯平移）等用户摸完基础版再选。

## 残影修复 + 视差平移（2026-09-20，build 20260920h 已装机，远程验证过）

- **用户实测发现静止残影**：g 版全屏截图实锤——dock 玻璃栏中下段透出邻页图标半透明残影。**几何根因双重**：① ViewPager 自己不裁 children（靠祖先裁剪），祖先链 clipChildren 全关后邻页越界绘制；② **负边距只能平移不能加宽**（LinearLayout 按父宽测 MATCH_PARENT 子级）——pager 实际只到屏幕右缘内 66px，邻页恰好从这 66px 伸进 dock 区。
- **修复**：pager 包进 `home-bleed` FrameLayout——负边距+**显式宽度=宿主宽+左右 padding**（OnLayoutChangeListener 基于宿主宽计算，防自引用膨胀），bleed 层自带裁剪（clipChildren=true 默认）：翻页时页面照常从物理边缘进出/穿 dock，静止时一切越界绘制裁在屏幕边缘。截图验证：dock 内只剩自己的图标、右缘窄条干净、网格与 dock 之间空白工整。
- **视差平移（用户选的 P2）**：`setPageTransformer`——进入页 translationX=-0.15·position·width（跟手慢 15% 的克制层次），position=0 归零保证静止态逐像素对齐。中段/提交截图验证翻页正常（diff 20.3/14.7），无崩溃。
- 装机运维：h 版装后 helper 重连卡 idle 两次 → **`am start DesktopActivity` 踢活**（老手法，有效）。
- **边缘对齐终修（用户三纠：图标离屏幕边缘一点点就被切断）**：h/i 版公式只对窄屏成立——宽屏（内屏）bleed 左边距漏算组件面板占位（诊断日志实锤 absPanel=1020、want=1410≠2364，页面在 41% 屏宽处被切；外屏日志正常但用户肉眼仍见 33px 缺口，归因组件面板同源）。**k 版：bleed 改窗口绝对定位**——宽度=rootWidth、左边距=-absPanel（OnLayoutChangeListener 沿父链累计 panel 绝对左偏移，无自引用反馈），`pageInsetLeft=absPanel` 同步重锚（窄屏 33/宽屏 1020），静止网格两种布局都回原位。日志实证：外屏 bleed=[0,1168]、内屏 [0,2364]。**教训：嵌套加权布局里的全出血子视图，定位必须按窗口坐标算，不能只抵消单层 padding；负边距不会加宽 MATCH_PARENT 子视图。**
- **用户再纠偏：网格离 dock 太远**——h 版 bleed 宽度只算了 host+左右 padding，漏了 dock 占位 209px，页内容区比原 workspace 窄、整体左移。**i 版一行修复**：`want=host+bleedLeft+pageInsetRight`（pageInsetRight 已含 sideRight+dockFootprint）→ bleed=[0,1168] 全屏、页内容=[33,926] 恰好落回原 workspace 矩形。截图实测：网格右缘 77% 屏宽 ↔ dock 左缘 79%，间隙 23px 紧邻如初；无残影；翻页正常（mid diff 15.7，视差使中段差值变小属预期）。




- 下一步：①用户手指实测（悬浮窗应显 ~120 不跳）；②若面板稳 120 仍有顿挫→查虚拟屏 Choreographer/触摸转发同步 binder 延迟（dualTouch 改 oneway+时间戳修正另开一轮）。

## 边缘淡入淡出（2026-09-20，build 20260920m 已装机）

- **k 版窗口锚定被用户打回回滚（l 版恢复 i 版公式）**——内屏 bleed 左边距漏算组件面板占位的问题仍在，用户改选**视觉方案**：不再修裁剪几何，翻页边缘加淡入淡出消解硬切线。
- **实现（HomePager transformer 一处）**：保留 15% 视差位移，alpha=|position|≤0.6 ? 1 : (1-|position|)/0.4——行程最后 40% 线性淡出/淡入，到屏幕边缘透明度归零，图标不再出现硬切；position=0 时 alpha=1 静止态逐像素不变。
- 远程验证：中段截图确认旧页渐隐/新页渐显/无硬切线。参数（0.6 起淡点、0.4 淡出带）可按用户手感再调。
- 遗留：内屏（wide）bleed 裁剪线在组件面板右缘（未修几何，被淡出掩盖）；若日后要真边缘对齐，参考 k 版思路但需修正页面内容锚定（pageInsetLeft 应=absPanel 而非叠加 sideLeft——k 版错误点已定位未验证）。

## 磨砂边缘带尝试（2026-09-20，build n→o 已回退）

- 用户要求"左右边框模糊虚化、不要硬边裁切"→ 实现 n 版：bleed 内加左右 44dp 磨砂带（HyperOS setBackgroundBlur 20dp + 0x8C121B1F 渐变填充，翻页期间 alpha 0→1，静止隐藏；HomePager 加 scrollActive 回调驱动）。
- **用户实测"太难看了，不要这个模糊了，回退"** → o 版完整回退（带、字段、回调全删），恢复 m 版状态（纯 alpha 边缘淡入淡出 + 15% 视差）。**教训：系统模糊条叠在壁纸上观感脏，此路不通；边缘柔化只保留透明度渐隐方案。**

## iOS 节奏调校（2026-09-20，build 20260920p 已装机）

- **用户反馈"现在是线性平移吗？感觉很拖沓，查苹果的动画速度"**——确认：慢速松手路径确实是线性补间（拖沓根源）。苹果翻页实况（HIG+逆向共识）：iOS7 起为**临界阻尼弹簧**（damping=1 无回弹）+ 继承松手速度，慢速松手走 ease-in-out，整页 ~250-300ms，从不线性。
- **调整**：① Friction 无速度分支线性→smoothstep（t²(3-2t) 平滑 ease-in-out），时长 180-280ms 按距离；② 快速松手系数 1.1→0.9、上限 460→380ms（更干脆）；③ 视差 15%→10%；④ 边缘淡出带 40%→28% 行程（起淡点 0.6→0.72）。磨砂带已按用户要求回退（n→o）。
- 会话恢复运维照旧（force-stop+双开关+DesktopActivity 踢活）。

## 控制中心 HyperOS 化（2026-09-19 深夜）

- **目标**：按用户提供的 HyperOS 控制中心截图重做 `HomeControlPanel` 控制页；通知页/PaneHost 手势/玻璃背景全保留，外层静态 API（open/beginDrag/dragOn/releaseOn/closeIfOpen/bottomCovered）未动。
- **布局**（density 2.75，内容列=右对齐 36%/64% 权重）：①两连 pill 卡 58dp——Wi-Fi 卡（开=白底蓝图标墨字"已连接"，关=磨砂）+蜂窝卡（常蓝底白字，标题取 TelephonyManager.getNetworkOperatorName，副标题已开启/已关闭，点击均走 svcToggle）；②媒体行 148dp——媒体卡(1.9f 权重,cast 角标+"暂无播放"+prev/play/next 按键经 AudioManager.dispatchMediaKeyEvent) + 亮度竖滑条(白填充橙日) + 音量竖滑条(蓝填充白喇叭)；③3×4 圆形开关网格：蓝牙/自动亮度/手电筒/静音/飞行模式/方向锁定/扫一扫/深色模式/勿扰模式/省电模式/投屏/小米互传。
- **Tile 模型**：`Tile{label,glyph,accent,solid,BooleanSupplier active,Runnable action}` 替代逐个字段；涂色规则：开=白底 accent 图标，开且 solid=彩底白图标（省电绿/互传粉常亮），关=0x33787880 磨砂白图标。新增功能：自动亮度(SCREEN_BRIGHTNESS_MODE)、静音(setRingerMode)、方向锁定(ACCELEROMETER_ROTATION 取反)、省电(svc settings put global low_power)、扫一扫/小米互传(resolveActivity 探测包名，fallback toast)、投屏(ACTION_CAST_SETTINGS)。
- **TileIcon** 新增 10 字形：CAST/SUN/SPEAKER/BELL(斜杠)/SCAN/BATTERY/TRANSFER/PREV/PLAY/NEXT；`VerticalSlider` 自绘（底部填充圆角卡+底缘 20dp 定色图标，竖向触摸取值，替代横向 PillSlider——已删除；PaneHost.overSlider 换引用）。
- **两个布局坑**：①GridLayout 行 spec 带权重在 wrap 高度网格里把后两行折叠光（logcat "y3-y0<=0 inconsistent"），行 spec 必须不带权重、列才带；②内容总高 1712px 超屏 ~27px，三轮微调：媒体行 158→150→148、网格 topMargin 14→12→8、cell 76→73、底部手柄 40→32dp。
- **装机**：设备旧包为异机 debug key（65a6a8b4…，本机 0a e7…不匹配）→ 卸载重装（用户拍板不备份数据）；adb 重授权：enabled_accessibility_services + accessibility_enabled=1 + enabled_notification_listeners 追加 DuoNotifications（settings put 一次成功，HyperOS 未拦截）；**Shizuku 授权因 uid 变化失效——下次应用请求时设备端会弹 Shizuku 授权框，需手点一次允许**，否则 svc 类开关走系统面板 fallback。手势验证用 `input -d 0 swipe 950 15 950 1100 300` 注入成功。
- **遗留**：米家设备控制网格卡未做（无设备数据源）；媒体卡未接 MediaSession（拿不到当前播放态，仅转发 media key）；改动未提交 git。

## 固定双屏默认化 + 预览校验 + MiDuo 搬运（第二十三轮，2026-09-17 上午）

- **默认双屏（隐藏不删除，用户拍板）**：`FixedDualSession.enabled` 默认 false→true（老用户不动开关直接进双屏）；传统投影全链保留休眠（双屏 active 本就提前 return）；`DuoHomeActivity.menu()` 移除"固定双屏（试用）"项；DesktopActivity 作用范围/恢复正常画面/切屏时机三组分区按 `legacy=!enabled` 隐藏（blur/stretch/startAngle 滑条保留，FixedDualGpu 在用）；AnimationSettings/GestureNavigation 未动。**恢复传统模式**：`adb shell content call --uri content://io.github.sixzleo.tabfold.projection.surface --method fixed-dual-session --arg 0`（已改持久化 pref，arg 1 恢复双屏）。
- **预览前置校验**：`DesktopActivity.openDesktop()` 先 ROLE_HOME（缺→弹"去设置"，onActivityResult 重查）再 `ProjectionService.instance`（缺→弹窗跳无障碍），双过才启动桌面；按钮改"打开桌面"。
- **MiDuo 搬运**（对照 参考/MiDuo-实现分析.md）：新增 `HomeWidgetScale`（UNSPECIFIED→EXACTLY 测量+provider min 兜底+等比缩小居中；DOWN 递归命中可交互子 View→requestDisallowInterceptTouchEvent 防 pager/scroll 抢手势；两处挂载点换用，尺寸推送挂包装层）；configure 增加 `WIDGET_FEATURE_CONFIGURATION_OPTIONAL` 跳过；`FolderFan.mergeZone` MiDuo 热区公式替换 28%~72% 框；编辑态根视图外包 content FrameLayout+拖拽中可见的顶部"拖到此处移除"胶囊（空 overlay 穿透触摸）；`folder()` 改 3×3 分页+页点（`folderPage()`）；`HomeApps` 静态 catalog 缓存（label 未变复用位图）。**未搬及原因**：伪 widget 时钟/天气（新功能族另开一轮）、pending 四元组（id 直查无增益）、挤开移动（list remove+insert 等价）、预置分类夹（待迁移稳定）、DuoGlass 参数（视觉刚定稿不盲调）。
- **装机**：旧包为异机 debug key（INSTALL_FAILED_UPDATE_INCOMPATIBLE）→ run-as 备份 shared_prefs(317KB 含 duo_home 布局)→卸载→安装→**首启前**流式恢复→adb 重授权（enabled_accessibility_services/accessibility_enabled/appops SYSTEM_ALERT_WINDOW+WRITE_SETTINGS/cmd notification allow_listener/cmd package set-home-activity，全部生效）。冒烟：桌面启动无崩溃、入场动画正常；用户连 helper 后 **FixedDualSession running（双渲染器出帧，外屏截图像素统计确认为正常桌面内容）**；`cmd role holders` 子命令不存在（用 resolve-activity -c HOME 验证）。
- **环境**：Gradle 8.7 wrapper 缓存曾损坏（仅 .part）已手动修复（curl --ssl-no-revoke 下载+sha256 校验+解压+.ok 标记）；构建命令 `JAVA_HOME=<JDK17> ./gradlew.bat -I tools/mirror-init.gradle.kts :projection-lab:assembleDebug --offline`。**换机构建签名不同必走备份/卸载/恢复**。

## 玻璃参数/文件夹/自动分类直接采用 MiDuo（第二十四轮，2026-09-17 上午）

- **玻璃参数（DuoGlass 角色表）**：`HomeStyle` 新增 7 个角色材质 `{blurDp, 模糊时填充α, 无模糊回退α}`（基色 0xff121b1f，MiDuo 深色基色）+ `glassSurface/glass(view,radius,role)`；`HomeGlass.apply` 增加 fill+双α 重载——模糊生效时填充降到低α（透明感），模糊不可用/关闭时自动升到回退α（保证可读），detach 时复位。挂载点映射：dock 栏=Dock(22dp/.055/.36)、组件栏容器=WidgetFrame(18/.035/.25)、搜索与编辑按钮=Floating(20/.08/.56)、应用/小组件分段=Control(15/.04/.30)、文件夹图标=Control（桌面）/Folder（面板头部）、HomeSheet=Card(22/.065/.40)、下拉控制面板=Screen(28/.11/.78，GlassFade 基色换 0xff121b1f 由 HomeGlass 驱动α)。旧 `glass(view,radius,color)` 删除；红色移除胶囊/拖拽高亮等功能色保留。
- **文件夹语义（MiDuo k0）**：`HomeLayout.extract()` 移出成员后落到**文件夹旁**（文件夹消失时占其原位），不再甩到最后一页尾部；其余语义（剩1降级、merge 追加去重、dissolve 原地展开）经比对本就等价，未动。
- **自动分类（MiDuo O6 全量移植）**：新增 `HomeClassify`——12 组首屏常用候选（图库/设置/时钟/文件/微信/支付宝/小红书/地图/米家/应用商店/日历/天气，每组多包名互换）+ 10 个固定 id 分类夹（AI/影音视听/体育运动/时尚购物/效率办公/聊天社交/新闻资讯/实用工具/旅行交通/金融理财，共 ~90 组包名候选，组内首个命中即取，共用 used 集防重复），**≥2 命中才建夹**；布局=首屏常用+拼音序补满 20 格，分类夹从第 2 页开始。接入点 `applyPendingCatalog`：仅当**首次目录到达时桌面仍为空**（fresh）且未标记 `classified_seeded` 才执行——已迁移/手排布局永不触发（真机验证 duo_home 无分类 id）。面板 store 构造时同步置位。
- 装机验证：同签名覆盖安装，桌面存活无崩溃，helper 自动重连，FixedDualSession running，无 GlassHomeMaterial 告警（模糊正常）。**自动分类路径未真机触发**（需全新桌面），依赖代码审查。

## 应用页面全量照搬 MiDuo（第二十五轮，2026-09-17 上午）

- **HomeLayout 重写为 MiDuo 槽位模型**：每条目持有 `slot`（page*24+y*4+x，MiDuo 密度 **4×6=24 格/页**，原 4×5）；`move()` 实现 A2.d 挤开语义（空格直落/占用格向源方向顺移一位/widget 目标拒绝且挡道的 widget 使整个顺移作废/widget 只能落全空矩形）；`dissolve` 首成员占文件夹原格、其余向后找空位；`extract` 落文件夹旁；`pin` **dock 与桌面互斥**（入坞即下格）；`undock`（MiDuo 非桌面源：空格直落/占用则顺延到后一个空位）；`resize` 仅在自身空矩形内放行；`ensurePlaced` 兜底打包未定位条目（旧数据/导入器自动迁移）。`grid()` 跳过未定位条目、放置即 touch()——这两点是为修真 bug（首版 grid 把未定位条目假映射到 0 号格 + ensurePlaced 不失效缓存导致槽位碰撞）。
- **JVM 单测**（/tmp/slotest，11 项全过）：迁移打包/顺序、挤开方向、widget 固定与自由移动、merge 保位、extract 相邻、dissolve 首成员占位、pin 互斥、undock 落位、resize 校验。
- **HomeStore**：item JSON 增写 `slot`；旧数据无 slot 读取时自动按原顺序打包（真机验证：153 条目全部定位 0..152 连续无缝，布局保序）。
- **DuoHomeActivity 适配**：拖放/菜单全部槽位化（前移后移=相邻格、widget 前后移=±一行、移动到页面=页首格）；**dock 图标非编辑态长按可直接拖出到桌面**（"dock:"+key 源）；resize 应用前走 layout.resize 校验并提示重叠；文件夹面板宽度 540→360dp（MiDuo 紧凑卡）。
- **FolderFan**：换 MiDuo 四角钉边几何（成员 38%、10% 内缩，原居中 45%）。
- **HomeWidgets.defaultSpan** 上限参数化（x≤COLUMNS=4，y≤ROWS=6）；HomeWidgetPicker 预估同步改。
- 装机验证：install -r 无崩溃，dual session running，旧布局自动迁移成功（slot 字段已落盘）。**注意 prefs 内 JSON 是 &quot; 转义存储，grep 验证要用转义模式**。

## 密度回退/面板同步/密度补偿/搜索重做（第二十六轮，2026-09-17 中午）

- **页密度回 4×5**（用户反馈 4×6 太挤）+ **FolderFan 恢复原设计**（居中 45%，弃 MiDuo 四角 38%）。**网格版本迁移**：encode 写 `grid` 标记（"4x5"）；read 时标记与当前密度不符 → 按 slot 排序保视觉顺序、清 slot 重打包（真机 153 条目保序重排 0..152、8 页 ✓）。
- **双屏面板同步主桌面**（修"双屏看不见文件夹"）：原面板 store 只在首次创建时拷一次种子。现 **duo_home 每次保存递增 layout_rev，面板 store 构造发现 rev 不一致即重新镜像**（保留面板自己的 widget 重打包）。实测 duo_inner/duo_cover 均含"系统工具"文件夹。
- **内外屏布局不一致**三因：①compact/wide 常数分叉（dock 顶边距 52/64、格子 92/96、行距 100/96）→ 统一 64/96/96；②两屏同报 440dpi 但真实 PPI 不同（外 385.288/内 381.913，仅 SurfaceFlinger 视口可见）→ **compensatedDensity：外屏虚拟显示密度≈444**（ROM 把 metrics.xdpi 归一成 440 读不出真值，lhasa 按视口实测比值兜底）；③诊断教训：**SurfaceFlinger regionblur 日志刷爆 logcat 缓冲**，一次性启动日志秒没 → 诊断改写进 FixedDualSession.status（provider 可读 `dpi=inner/cover`；旧会话 440/440，补偿后应 440/444，**待解锁确认**）。
- **弹层方形灰底修复**：HomeSheet 的 Dialog 窗口主题默认方形背景透出圆角外 → `getWindow().setBackgroundDrawable(transparent)`。
- **搜索重做（MiDuo 风格）**：触发器=dock 栏底部 68×52 玻璃圆角方形（Floating）；搜索面板改**底部弹出**（HomeSheet 新增 bottom 变体：BOTTOM 锚定、上滑入场、键盘 ALWAYS_VISIBLE、自动聚焦光标置尾）；pickApp 加 bottom 参数，search() 走底部变体，其余选择器保持居中。
- **最近任务结论**：反编译确认 MiDuo 的 RECENTS 只是手势枚举→交回系统；第三方无自绘任务列表 API。现状=虚拟屏 am 启动 MIUI recents。**待用户定夺**。
- 装机运维：MIUI force-stop 后解绑无障碍（adb settings put 恢复）；helper 重连卡住时起一次 DesktopActivity 即恢复；锁屏时 maintain 不拉会话（设计行为）。

## 文件夹全屏模糊底（第二十七轮，2026-09-17 下午）

- **用户需求**：文件夹点开后模糊底直接铺满全屏（不要带边距的面板）；搜索弹框保留圆角卡片但修掉方形灰框；应用页文件夹图标还原原样式（蓝灰 glass，已还原）。
- **"方形外框"根因（两层）**：①`HomeGlass` 圆角裁剪代码因**缺 import（ViewOutlineProvider/Outline）从未编译进包**——之前多轮 "BUILD SUCCESSFUL" 实为 gradle 增量状态错乱的**假构建**（APK 时间戳不变、新字符串不在 dex 里），装的都是旧代码；修复 import 后必须**删 APK 重建+校验 dex 标记+比对时间戳**三件套确认产物真的更新。②HyperOS `setBackgroundBlur` 的圆角参数在这版 ROM 被忽略（模糊区退化为方形）→ `HomeGlass` 构造时统一 `setOutlineProvider+setClipToOutline` 把填充/描边/模糊整体裁成圆角。
- **全屏模糊未铺满的真正原因**：`outside` 容器被系统栏 insets 加了 padding（左右 16dp、状态栏+12dp、导航+12dp），模糊面板和 scrim 只能画在 padding 之内 → padding 露出一圈**清晰未模糊壁纸** = 用户看到的"外框"（像素证据：面板宽 1080=1168−2×44px，44px=16dp 精确吻合）。**修复**：fullscreen 分支不再给 outside 加 padding，insets 转为 content 内部 padding（头部避开状态栏），模糊/遮罩铺满整窗。**验证**：打开文件夹后四角清晰度 stddev 1.8~2.0（纯糊）vs 中心图标区 49.9（高细节），视觉确认边缘全糊。
- 构建教训入库：**判别假构建三件套**——删产物重建、`unzip -p apk classes*.dex | grep <新字符串标记>`、ls 时间戳。
- **点空白退出（用户需求）**：全屏文件夹 content 自身 onClick=dismiss（覆盖头部/留白区），滚动容器与分页器挂 `blankTapCloses` 观察器（ACTION_UP 位移<8dp 且 <300ms 即 dismiss，返回 false 不干扰滚动/翻页；子控件图标自己消费点击不冒泡）。真机两轮实测：开→糊角 1.8，点空白→回到桌面 ✓。
- 装机运维补充：MIUI force-stop 后解绑无障碍需 adb 重写 `enabled_accessibility_services`；helper 断线重连卡住时启动一次 DesktopActivity 即恢复；`dpi=440/440` 补偿值 444 已写入 FixedDualSession.status 待下次会话重建生效（lhasa 真实 PPI 比值兜底，metrics.xdpi 被 ROM 归一读不出）。

## 搜索弹窗方角修复（第二十八轮，2026-09-17 下午）

- **根因确认（截图+角部放大）**：HyperOS `setBackgroundBlur(int,float[])` 的圆角参数在此 ROM 被忽略，视图级 `clipToOutline` 管不到 SurfaceFlinger 合成的模糊层 → 卡片圆角外四个角露出方形模糊斑。
- **修复**：①弹窗（HomeSheet）改用**标准窗口级模糊** `FLAG_BLUR_BEHIND + LayoutParams.setBlurBehindRadius(40dp)`（API 31 公开 setter；注意 `blurBehindRadius` 字段本身编译不可见，必须用 setter），内容卡换 `HomeStyle.glassStatic`（圆角渐变填充+rounded 裁剪，**不再挂视图层 HomeGlass**，避免双重模糊与方角）；②`HomeGlass` 圆角参数数组改传 8 值（部分 ROM 只认 8 值格式），保住 dock/控制面板等仍在用视图层模糊的场景。
- **验证**：角部放大图卡片圆角干净、无方形斑块，模糊透过卡片可见（窗口模糊生效），外部壁纸清晰；像素扫描卡片边界 x=48..1122、顶部 y=160=状态栏+12dp ✓。
- 构建教训再次确认：`blurBehindRadius` 字段编译不可见需用 setter；每次改完用"删 APK 重建+dex 标记"三件套。

## 方角终极修复：反编译 HyperOS framework 找到根因（第二十九轮，2026-09-17 下午）

- **用户反馈 dock 也有方角 → 判定同一类问题，且要求保留系统模糊 API（拒绝了壁纸快照假模糊方案，HomeBackdrop 已删）**。
- **根因（反编译真机 framework.jar 实锤）**：`android.view.View.updateBackgroundBlur()`（framework.jar:17862）对 `setBackgroundBlur(radius,float[])` 的数组长度分派：**length==4** → `setUseMiCornerRadii(false)` + `BackgroundBlurDrawable.setCornerRadius(r0..r3)`（写入 8 个标准字段 mCornerRadiusTLX..BRY，**会被发布进 SurfaceFlinger 的 BlurRegion**）；**length==8** → `setUseMiCornerRadii(true)` + `setMiCornerRadii(arr)`（只写 MI 专用字段 `mMiCornerRadii`，**这 ROM 的 SurfaceFlinger 对第三方窗口不读 MI 字段** → 发布的 cornerRadii=[0×8] → 方形模糊区）。**运行时证据**：`dumpsys SurfaceFlinger` 里 blurRegions 的 cornerRadii 全为 [0×8]，而 radius（61/55/50）与我们的配置精确对应。
- **修复**：`HomeGlass` 圆角数组回归 **4 值**（同样值×4）。修复后 SurfaceFlinger 转储：dock/组件面板/按钮/搜索条所有区域 cornerRadii=[77/61/72×8] 非零 ✓，dock 方角消失（用户确认）。
- **搜索弹窗仍方角的第二根因**：MIUI 把 Dialog 窗口**收缩到内容视图边界**（窗口转储 frame=[29,614]...非全屏），窗口 dim/边界只盖卡片，圆角外露方形暗框。**修复**：①stage 全屏容器（卡片放 stage 内按 gravity 定位）；②窗口 `setFitInsetsTypes(0)`+`FLAG_LAYOUT_IN_SCREEN` 强制真全屏；③insets 从 outside padding 改为**卡片 margin**（不缩窗口）；④resize() 扣除 margin 计算可用尺寸。**最终截图验证：四角干净、模糊透卡、外部均匀压暗 ✓**。
- 调试广播新增 `<pkg>.OPEN_SEARCH`（打开搜索面板，配合装机验证）。
- **诊断方法论沉淀**：①模糊问题直接 `dumpsys SurfaceFlinger | grep cornerRadii` 看系统收到的区域参数，一眼定位是应用没传对还是系统没实现；②窗口尺寸问题看 `dumpsys window windows` 的 Frames frame=[]；③logcat 会被 SurfaceFlinger regionblur 日志刷爆，别依赖它看一次性日志。
- framework 反编译产物在 `C:/Users/spideytznn/AppData/Local/Temp/fw/fwsrc/`（jadx，含完整 MI 模糊体系：setMiBackgroundBlurRadius/Type/Path、setPassWindowBlurEnabled、getSupportedMiBlur 门控等，后续调模糊可再查）。
- 附带：点空白退出（全屏文件夹）已实现在 14:34 包中并实测通过。

## 双屏最近任务问题（第三十轮进行中，2026-09-17 傍晚，用户指示先提交晚点修）

- **用户报告（双屏模式专属，单屏正常）**：①recents 点"清理全部"后停在空态页，不回桌面；②点任务卡片应用不跳转（偶尔卡死/无反应）；通知中心点通知同样无法进应用。
- **已定位的证据**：①旧会话留下**僵尸虚拟屏**（display 454/447 卡在 removing），MIUI 把任务恢复/resume 路由到死屏 → 应用永远不显示（日志 `Skipping resume: display id=xxx is removing` + `moveTaskToFront` 成功但画面不变）；②MIUI recents 空态在虚拟屏上不会自动退出，且文案有两种：全屏"近期没有任何内容" / 小卡片"无近期任务"。
- **已实施的修复（装机 18:5x 日志版）**：①`FixedDualSession.activeContentIds()` + `DuoHomeActivity.validateSecondaryHomes()`——maintain/close 时自动 finish 掉不在当前会话的 DuoSecondaryActivity（实测日志 `Finishing stale secondary home on display 472` ✓）；②`ProjectionService.checkDualRecentsEmpty`——双屏时监听 com.miui.home 活动窗口，匹配两种空态文案后对该 display 发 HOME（主屏路径已实测触发；虚拟屏路径加了 `recents watch display=... empty=...` 调试日志待观察）。
- **未收尾**：①虚拟屏上点卡片→应用跳转的完整复测（僵尸清理后预期恢复，未验证）；②通知中心点通知进应用（同一根因，未单独验证）；③validateSecondaryHomes 在 maintain 每 tick 调用，成本低但可再收敛。
- 注意：双屏会话中弹出"选择默认桌面"系统弹窗会黑屏片刻（用户遇到后自愈）。

## 默认桌面切换黑屏修复 + 恢复流程纠偏（第三十一轮，2026-09-18 上午）

- **用户故障序列**：装日志版→开无障碍→选玻璃投影为默认桌面→**整机黑屏**→锁屏解锁→无障碍被 MIUI 解绑+回退系统桌面→重开无障碍进桌面→**进了非双屏模式**。
- **三个症状对因**：①黑屏=HOME 角色授予瞬间正处桌面切换动画，maintain() 立即请求固定显示拓扑（DeviceStateManager state 5/6），拓扑翻转与转场撞车→整机黑；锁屏触发会话 close→再释放拓扑→二次翻转。②无障碍掉线=MIUI 在黑屏混乱后解绑（老问题，adb 可恢复）。③非双屏=**恢复的备份数据带 preview.9 时代的 duo_dual/enabled=false**，当前代码尊重该值→单屏遗留管线照常显示（保留方案起效）。
- **修复**：`FixedDualSession.maintain` 增加**角色稳定期**——进程首次观测到 ownHome 起 2.5s 内不起会话（`ownHomeSince` 跟踪，失去角色清零），让系统完成桌面转场后再请求拓扑。装机 08:56 版验证 running ✓。
- **运维纠偏**：跨版本恢复 shared_prefs 后要检查 duo_dual/enabled——旧备份可能带 false，恢复后用 `content call fixed-dual-session --arg 1` 拉正。

## 黑屏连环修复（第三十二轮，2026-09-18 上午，本地未推送）

- **今日三次黑屏复盘**：共同点都是"装包/无障碍重启 → 旧会话拓扑刚被 binder 死亡释放 → 新进程几秒内再请求固定显示拓扑"——短时间内多次主屏映射翻转，MIUI 显示策略挂死，两块物理屏被我们的 OPAQUE 黑色覆盖层盖住=整机黑。08:52 首次启动没黑是因为那是当天唯一一次干净翻转。锁屏/解锁也可能叠加（frame 检测锁屏的 close 在熄屏后可能不执行，PROJECT_STATE 早有记录）。
- **修复（装机 09:06 版，已验证 running+屏幕正常）**：①`FixedDualSession.maintain` 进程首调强制 8s 等待（nextStart 初值），让前一个进程的死亡释放落地；②`MobileHelperHost.fixedDualState` 增加**释放后 8s 冷却**（拒绝新拓扑请求返回 ERROR topology cooldown，app 侧 nextStart 10s 重试后自然过冷却）——UserService version 32→33 强制替换旧宿主（无线宿主走 app_process 每次全新加载无需版本）；③此前 09:01 版已修**僵尸清理误杀**：validateSecondaryHomes 只在**无会话**时执行（08:56:59 日志"live=[13]"证明搭建中 live 集不完整时误杀了 display 14 的合法桌面导致会话雪崩）。
- **待观察**：锁屏/解锁稳定性、装包后首次起会话是否稳定单次翻转。若再黑屏：`am force-stop` 立即清覆盖层救急，然后抓 `dumpsys display` 与 DeviceState 日志。
- 用户指示：**暂不推送**。

## 黑屏真根因：direct 直通旁路（第三十三轮，2026-09-18 上午，本地未推送）

- **实测证据链**：定时采样显示 t=8s 起黑、t=16s 会话已 running 但外屏 lum=0-1 持续 80s+；渲染统计 `direct presented=6`（昨天同期全是 `source/pyramid presented=600+`）；覆盖层 TextureView 图层 frame=3；虚拟屏上桌面 Activity 存活且 resumed；面板 ON、未锁屏。
- **根因**：2e05dcf 新增的 GPU 直通旁路（FixedDualGpu identityFrames≥64 → goDirect → dualSurface 把 TextureView 表面直借虚拟屏）。昨天测试从未触发（一直走 shader 路径），今天稳定折叠态首次触发即黑。时序缺陷：goDirect 可能在虚拟屏创建完成前（contentId=-1）触发，dualSurface(-1) 静默无效 → 虚拟屏继续渲染到已无人消费的 EGL 输入表面 → 永久黑。这同时解释"切换双屏桌面黑很久"（每次会话稳定 256ms 后必进 direct 必黑）。
- **修复**：直通旁路**禁用**（`if(false&&...)goDirect()`），回到昨天验证过的 shader 路径。装机验证：running + 外屏 mean=139/stddev=47，20 秒稳定态不再变黑 ✓。后续如要恢复直通需先修：①contentId>=0 才允许 goDirect；②swap 后校验虚拟屏实际输出表面；③leaveDirect 恢复路径实测。
- **同包已带**：通知点击进入应用修复（DuoNotifications.Item.open → PendingIntent.send 携带 ActivityOptions.setLaunchDisplayId(面板 displayId)，不再落到被盖住的主屏）。
- 待用户实测：锁屏/解锁、recents 点卡片、通知点击、清理后台回桌面。

## 自研任务切换器 + 通知重绑（第三十四轮，2026-09-18 中午，本地未推送）

- **结论定案**：MIUI 最近任务在虚拟屏上不可救（任务路由到不可见显示器、焦点悬空导致两次 input-ANR 击杀应用）。**虚拟屏的 APP_SWITCH 改为自研切换器**：FixedDualContentHost.key(APP_SWITCH) 发 `<pkg>.OPEN_RECENTS` 广播（带 display extra）→ 对应 DuoHomeActivity 打开 `recentsSheet()`。
- **样式（用户指定）**：纸张堆叠——HomePager 横向卡片轮播（负 pageMargin 露邻卡 + PageTransformer 缩放/透明）、240×340dp 圆角玻璃卡（图标+应用名+位置）、**全屏无边框模糊底**（HomeSheet fullscreen 变体，与文件夹一致）、点卡片=launch（走验证过的 setLaunchDisplayId 链路）+dismissSheets、点空白关闭（blankTapCloses）。数据=进程内 LRU（launch() 时 noteRecent，上限 8，进程重启后为空）。
- **通知监听重绑**：ANR/装包后 HyperOS 保持授权但不重绑——`cmd notification disallow_listener + allow_listener` 强制重绑，本轮实测 bound=1 ✓（app 内 ensureBound 逻辑同款，需 helper 就绪）。
- **广播触发验证**：OPEN_RECENTS --ei display <cover content id>（content id 从会话状态取，viewports 解析会拿到陈旧条目）；display 过滤=实例 displayId 精确匹配。注意 adb 无线连接频繁抖动：装包后端口可能变（扫描脚本：python 并发探 192.168.2.166:20000-65535 取开放口再 connect）。
- 待用户手测：卡片切换器实际观感（需先从桌面点开几个应用填 LRU）、通知中心内容、锁屏稳定性。

## 全屏下拉面板与底部小白条冲突排查（2026-09-18，只排查未改代码）

- **现象**：固定双屏下拉面板拖到底（全屏铺满）后，底部白色手势条仍能触发（上滑 HOME、长按 RECENTS）。
- **根因**：双屏下物理屏触摸全走 "Duo X touch forward" 转发窗（`FixedDualOutput.java:68-85`），每个事件**先**喂 `FixedDualGestureFeedback.touch()`（`FixedDualOutput.java:56/:73`），返回 true 即吞掉、不转发虚拟屏。`FixedDualGestureFeedback.java:28` 把内容屏**底部 28dp 无条件**判为 BOTTOM（home）手势起点——`desktop` 标志（`DuoHomeActivity.barePanel`）只用于关左右边缘手势，管不到底部；顶部 48dp 倒是为 shade 触摸条保留（判 null 放行转发）。面板全屏化（`HomeControlPanel.releaseDrag`→MATCH_PARENT，`HomeControlPanel.java:266`）后，底部 52dp 关闭把手（`:198`）和通知列表下缘正落在这 28dp 内：点按被吞、上滑触发 HOME（白胶囊 `onDraw` `:57`）、按住 430ms 触发 RECENTS。面板侧 `setSystemGestureExclusionRects`（`:255`）只防 SystemUI 系统手势，防不了自家转发层 feedback；且 shade 是 NOT_FOCUSABLE overlay，面板打开时 `barePanel`（resumed+焦点+无弹窗+无 IME）仍为 true——但即使为 false 也没用，底部区根本没被任何条件门控。
- **单屏模式不受影响**：`GestureNavigationOverlay.BottomView` 是独立小窗（底部 40dp），面板窗口后加、z 序更高会盖住它；问题集中在双屏转发路径。
- **修法方向（未实施）**：让 `feedback.touch` 感知"该 display 的 shade 全屏打开"（HomeControlPanel 暴露 per-display 全屏态），打开时底部 28dp 放行转发（或只在面板非全屏时保留 BOTTOM 手势）。

## 卡顿/ANR 第三击：空态扫描堵塞主线程（第三十五轮，2026-09-18 中午，本地未推送）

- **ANR 实锤（第 3 次，10:40:41）**：`Subject: Input dispatching timed out (Duo cover touch forward is not responding. Waited 5000ms for MotionEvent(DOWN))`。我加的 checkDualRecentsEmpty 在**每个**无障碍事件（含高频 WINDOW_CONTENT_CHANGED）上 getWindows()+全树递归搜双文案，全在主线程 → 触摸转发排队超时 → ANR 杀进程 → 会话重建黑屏。这就是"为什么这么卡"的答案。
- **修复**：仅在 `com.miui.home` 的 **TYPE_WINDOW_STATE_CHANGED**（低频）触发 + **3 秒节流**（lastDualRecentsScan）。装机 10:59 验证 running + 外屏 mean=138 ✓。
- **教训入库**：无障碍服务的 onAccessibilityEvent 里做任何 getWindows()/树遍历必须节流+事件类型过滤——CONTENT_CHANGED 每秒可达数十次。
- 今日三次 ANR 复盘：均为 input ANR，前两次（09:43/09:48）主因是 MIUI recents 在虚拟屏上焦点悬空（已用自研切换器绕开），第三次是本条扫描堵塞。

## 通知点击显示目标修正（第三十六轮，2026-09-18 下午，本地未推送）

- 用户反馈：后台卡片点击已好，通知点击仍不进应用。根因=我上一版把通知启动锚定到**物理屏**（shade 覆盖层所在屏，应用被自己的 overlay 盖住不可见）；正确目标是该面板对应的**虚拟内容屏**。
- 修复：`FixedDualSession.contentIdForPhysical(physicalId)`（outputs 里 physicalId→contentId 映射）；HomeControlPanel 卡片点击 `content = active()?contentIdForPhysical(physical):physical`（非双屏回退物理=正确）。
- 自测：shell 测试通知 → 通知中心卡片渲染 ✓（方差 33.4）；真实 contentIntent 启动待用户实测。

## 锁屏输入陷阱修复（第三十七轮，2026-09-18 下午，本地未推送）

- **用户复现**：wifi 断开后手机停在灰屏无法操作。窗口转储实锤：锁屏壁纸+系统通知遮罩在显示，而双屏会话仍 running——熄屏时 Choreographer 渲染循环随 vsync 停摆，frame() 里的锁屏检测（locked→close）永远不执行 → 触摸转发覆盖层压在锁屏上拦截解锁手势。即 PROJECT_STATE 锁屏调研里预言的"熄屏后 close 可能不执行"坑首次真实触发。
- **修复**：会话注册 **ACTION_SCREEN_OFF 广播接收器**，熄屏立即 stop()（撤覆盖层+释放拓扑），解锁后由 maintain 正常重拉（稳定期+冷却已兜底时序）。
- **实测**：adb 熄屏 → 会话立即关闭；亮屏解锁 → 会话自动恢复 running，屏幕 mean=135 ✓。
- 救急手段：锁屏被卡时 `am force-stop` 即可恢复锁屏操作。

### 下一步

1. 用户手测：合并热区手感、拖到顶部移除、文件夹 >9 成员分页、widget 缩放与滑动手势共存、锁屏/解锁双屏表现（结合下方锁屏调研结论）。
2. 验证 adb arg 0 恢复传统模式后 legacy 分区重现、arg 1 回双屏。
3. 候选：内置自绘时钟/天气 widget（MiDuo 负 slot）、预置自动分类文件夹、锁屏黑帘保活方案。
4. 验证满意后提交 git（连同 shade/迁移遗留一起）。

## 弹窗玻璃"先暗后亮"修复（2026-09-17，代码已改未构建）

- 现象：文件夹全屏弹窗 / 搜索底部弹窗打开时，模糊背景先偏暗，随后一次性跳变变亮（非渐变）。
- 根因与下拉面板"先闪黑"同源（第二十四轮 DuoGlass 双 α 引入）：HomeSheet 经 `HomeStyle.glass` 初始化填充时用**回退 α**（文件夹 ROLE_SCREEN=.78、搜索 ROLE_CARD=.40，`HomeStyle.java` glassSurface/flatSurface）；`HomeGlass` 等异步 `addCrossWindowBlurEnabledListener` 回调（本机实测约 100ms/5-6 帧）后 `BLUR.invoke` 创建模糊层并 `syncFill()` 把填充**瞬切**到模糊 α（.11/.065，无动画）→ "先暗后突然变亮"。主题 dim(0.25)+scrim(0x52) 恒定/渐变只压暗，非变亮来源。
- **修复（只改 `HomeStyle.glass()` 一处，自动覆盖全部 7 个调用点：两个弹窗+dock 栏+搜索按钮+组件面板+分段控件+文件夹图标）**：①`glass()` 在 `HomeGlass.apply` 接管填充后立即把初始 α 设为 role[1]（模糊 α）——监听器晚几帧到达时 syncFill 目标值相同，跳变消失；②`HomeGlass.apply` 返回值 void→boolean：ROM 缺模糊 API（反射 Method 为 null）时返回 false，fill 保持回退 α 不被改亮（非 HyperOS ROM 可读性不回归）；③update()/onPreDraw() 两条失败 catch 补 `fill.setAlpha(fallbackAlpha)`（此前失败路径会停在构造时 α）。glassSurface 注释同步修正；glassStatic（静态兜底面，现无调用者）不受影响。
- **未构建未装机**（用户指示构建晚点再说）：下次构建走"删 APK 重建+dex 标记+时间戳"三件套；装后验证文件夹/搜索打开无暗→亮跳变（模糊层到达时的"清晰→磨砂"柔过渡保留，与下拉面板手感一致）。

## 下拉面板"先闪黑"排查+修复（2026-09-17 下午，已修复装机验证，未提交 git）

- **根因（logcat 时间线实锤）**：`HomeControlPanel` 面板背景 `GlassFade(0xff121b1f)` 构造时先置**回退 α .78**（`HomeControlPanel.java:143`），`HomeGlass` 要等异步 `addCrossWindowBlurEnabledListener` 回调 + `BLUR.invoke` 创建模糊层后才把填充降到 .11（`HomeGlass.java:69-91`）。真机广播复现（OPEN_SHADE side 0，虚拟屏 321 镜像路径）：窗口 show 15:24:57.130 → MIUI 记录 `mMiBlurUsed:false` 57.171 → SF 首次画 shade 模糊层 `regionblurRadius:77`（=28dp×2.75）57.223。**93ms/5-6 帧近黑相位**后瞬间变玻璃 = 用户看到的"先闪黑一下"。第二十四轮 DuoGlass 双 α 角色化引入（此前固定 0x8c tint 无此相位）。
- **修复①（已回滚）**：HomeGlass attach 时同步调 `isCrossWindowBlurEnabled()` 预置模糊——时间线完美（show→regionblur 15ms/1 帧）但**用户手测"顿一下"**（同步 binder 调用+BLUR.invoke 在 addView 路径上拖慢首帧），已回滚（dex 标记清零确认）。
- **修复②（现行，HomeControlPanel.java:143 一行）**：初始填充直接用 **blur α .11**（原为回退 α .78）——构造期就定好，**零同步调用零额外开销**；模糊层照旧 ~117ms 后异步建立（背景清晰→磨砂的柔和过渡），若 ROM 回报模糊不可用监听器自动加深回 .78。装机验证：show 16:31:38.021 → regionblur 38.138（117ms，异步如预期）、0 GlassHomeMaterial 告警；黑相位在代码路径上不可能出现（首帧前 α 已是 .11）。**待用户手测确认手感**。
- **重装后会话不自动恢复的坑**：连装两次后 fixed-dual 停在 idle，telemetry 显示"连续投影助手未连接"——**起一次 DesktopActivity（am start -n PKG/.DesktopActivity）即恢复**（helper 重连），再 HOME 回桌面；本次恢复后 content=353（虚拟屏 id 已变）。
- **并行会话干扰实证**：本会话期间另一进程改了 DuoHomeActivity(15:36)/HomeSheet(15:40)/HomeStyle(15:24)（mtime 还原），我的两次 Edit 报"file modified since read"即此因；15:40/15:47/16:28 三次构建均含这些外来改动（用户反馈"另一进程改的时候下拉好像正常"的时期对应 fix① 包 15:41-15:47，无法归因，未深究——用户指示直接修）。
- **次级问题（未动，"打开偏慢"优化项）**：触发→窗口 show 约 255ms（56.875 广播→57.130 show），主线程构造整块面板（windowContext+相机枚举找 torch+双 pane+通知列表）；相机枚举可后台化。
- **取证教训**：物理屏 `screenrecord --display-id <SF大编号>` **录不到 a11y overlay 层**（全程 11 帧全同、1.92fps，只有底层 DuoHomeActivity），取证 a11y overlay 必须 `screencap -d`（含全合成）或看 SurfaceFlinger logcat 时间线；MSYS_NO_PATHCONV=1 下 adb pull 目标不能写 /c/...（进目录用相对路径）。
- **旁路发现**：物理屏最顶部（状态栏区，y≈20）注入滑动会打到**原生 SystemUI**（backgroundBlur mergeSnapshot 报错，我们的面板不开）——状态栏输入消费者优先于 overlay；用户实际下拉起点略低才命中我们的条/转发层。若后续报"顶部下拉无反应"优先查这里。
- 环境：设备 IP 换为 192.168.2.166（端口轮换，mdns 查）；工具目录迁移到 `C:/vsCodeProject/tools/`（platform-tools/adb、jdk17、gradle-8.7、android-sdk）；本机 Python312+PIL，`pip install imageio-ffmpeg`（清华镜像）拿 ffmpeg 抽帧。

## 原生下拉"镜像"可行性调研（2026-09-17 下午，已完成，未改代码）

- **结论：纯"遮罩+镜像原生 shade"是死路；可行的是"镂空让位直接露出"（首选）或"让位+采集重排"（次选）**。根因三条：①像素镜像只能拍到源屏**合成结果**——display 0 的 shade 被我们自己的 OPAQUE 输出窗盖住（a11y overlay 层 31 > NOTIFICATION_SHADE 层 17），不先让位则任何镜像看到的都是自己的窗；②SystemUI shade 窗口的 SurfaceControl 第三方拿不到（WMS 侧 mirrorDisplay 需 READ_FRAME_BUFFER 系统权限），只能整屏镜像；③把 display 0 的镜像挂回 display 0 的窗口 = SurfaceFlinger 层环（深度 50 致命中止，系统级崩溃），只能镜像到 display 1。
- **路线 A（推荐）镂空让位**：shade 打开时 display 0 输出窗视觉镂空（输出窗本就整窗 NOT_TOUCHABLE，只需透明）+ forwarder 触摸窗缩窗避让该矩形 → 原生 shade 物理露出、真实触摸直达（滚动/甩动/输入全原生、零延迟）。限制：只能出现在 display 0 的物理位置（=双屏视觉底部），方向/可读性待真机验证。
- **路线 B（次选）MediaProjection 全屏采集重排**：接入 FixedDualGpu 作第二输入纹理，可自由摆放/旋转/跨屏。代价：Android 14+ 每会话 consent 弹窗（会被自己 overlay 盖住，须进双屏前授权 + mediaProjection FGS 常驻）、30-100ms 延迟；**关键未验证点：全屏采集是否含自身 overlay 窗口**（含则同样要先让位，价值大减）。
- **已排除**：SurfaceControl.mirrorSurface（上述①②③全中；HiddenApiBypass 可绕 blocklist 但救不了遮挡与递归）；a11y takeScreenshot 轮询（百 ms 级 + 同样含自身 overlay）。
- **现成基建可复用**：开合原生 shade = svc 白名单 `cmd statusbar expand-notifications / expand-settings / collapse`（已实证作用于 display 0，HyperOS 通知/控制分体正好两命令对应左右半条）；shade 开合检测可监听 a11y TYPE_WINDOW_STATE_CHANGED（systemui 包名）；连续触摸注入管线（FixedDualContentHost 的 setDisplayId+injectInputEvent）可复用于镜像区转发（display 0 forwarder 须同步避让防自环）。
- **待真机验证**：①expand-notifications 后截外屏图看 shade 物理位置/方向/可读性；②MediaProjection 全屏采集是否含自身 overlay；③shade 展开时 IME/焦点表现（通知回复场景）；④display 0 forwarder 缩窗后底部 HOME/返回手势让位的代价。

## 锁屏接管可行性调研（2026-09-17 下午，已完成，未改代码）

- **结论：完整接管系统锁屏（替换认证界面）不可能**（TYPE_KEYGUARD_DIALOG 需系统权限，HyperOS 不开放）。**可行的是"视觉接管 + 会话保活"**：TYPE_ACCESSIBILITY_OVERLAY 本就压在 keyguard 之上（GestureNavigationOverlay 锁屏时主动隐藏即为旁证；非固定模式"连续锁屏投影"也是 overlay 盖锁屏），可自绘锁屏界面；虚拟屏本身永远没有 keyguard（keyguard 只挂 display 0）。
- **"锁屏没效果"根因**：会话的锁检测只挂在 Choreographer.doFrame 循环里（`FixedDualSession.java:75-76` locked→close()），熄屏后 vsync 停、close() 可能不执行；两个 OPAQUE 全屏输出窗盖住物理屏一切（含 keyguard）；虚拟屏 DuoSecondaryActivity 不受 keyguard 管辖。docs/FIXED-DUAL-DESKTOP.zh-CN.md:142 已列为未完成项。
- **"解锁进桌面闪一下"根因**：锁屏→会话 close()（撤输出窗/虚拟屏/拓扑请求）→解锁后 maintain()（ProjectionService.java:430，40ms tick）≤40ms 内全套重建：拓扑请求+输出窗+VirtualDisplay+**全新 DuoSecondaryActivity 实例**（onCreate→store.read→render() 整棵视图树重建，DuoHomeActivity.java:249-285），窗口透明+SHOW_WALLPAPER 期间裸露壁纸；display 0 的 DuoHomeActivity 每次解锁回桌面还播 260ms 入场动画（alpha0+scale .965，DuoHomeActivity.java:200-235）。
- **推荐方案（未实施）**：锁屏时不 close()，保活虚拟屏与 Activity，用 FixedDualOutput 现成"黑帘 View"做锁屏纱罩，ACTION_USER_PRESENT（+isKeyguardLocked 轮询兜底）掀帘→零重建零闪烁，可进一步在帘上画玻璃锁屏（时钟/通知）。**安全关键：锁屏期间必须禁用触摸转发层（NOT_TOUCHABLE），否则触摸直达虚拟屏桌面=未解锁可操作/启动应用**（这也是作者 close() 的原始动机）。display 0 的入场动画可按"解锁返回"跳过/缩短。
- 待真机验证：锁屏期间 shell 持有的 VirtualDisplay/拓扑请求（state 5/6）是否存活；黑帘在亮屏瞬间是否立即可见；虚拟屏 Activity 锁屏期间是否保持 resumed。

## 澎湃OS 4 性能范式调研（2026-09-17，已完成，未改代码）

- HyperCore 优化演进：OS2 微架构调度器（解析指令流水线，CPU 空转 -19%/高负载 IPC +16%/关键线程调度延时 -46%）→ OS3 热点编译加速 + 窗口绘制下沉（窗口动画丢帧 -18.9%、桌面图标渲染负载最高 -60%）→ OS4 负载精算 + 内存预载 + 全新应用运行环境（内存占用 -25%+、30 应用启动总耗时 -17.5%）。**全部在内核/调度器/ART/SurfaceFlinger 层，应用自动受益，无应用侧 API 可接入**；dev.mi.com 澎湃OS 文档中心无独立性能分类，应用侧范式 = Android 官方最佳实践 + 小米"系统适配"文档（其中桌面适配/小部件适配与我们直接相关）。
- 技术栈确认：**纯 Java（无 Rust、无自有 C++），传统 View 手工 UI，AGSL+RenderEffect+GLES 渲染**；minSdk 33/targetSdk 35，**未配 release buildType（R8/minify 默认关）**、无 Baseline Profiles。
- 可落地优化候选（性价比排序）：① release 开 R8 + shrinkResources（当前零配置，免费收益最大）② Baseline Profiles（launcher 属重启动路径应用）③ 图标两级缓存（MiDuo 待借鉴项，正对应系统"桌面图标渲染负载"优化方向）④ onDraw 分配审查 + LruCache 系统化 ⑤ HandlerThread 关键线程命名（便于系统调度器识别关键线程）。**不引入 Rust/C++**（瓶颈不在 native 计算层，安卓 Rust 用于系统组件而非应用性能范式）。**补充查证（同日）**：小米确以 Rust+Flutter 重写自家核心系统应用——OS3.1 起天气/图库移除 MIUI SDK，OS4 扩展至系统桌面 Launcher 7.0/电话/日历/文件管理等（Beta 包名带 -R 后缀，Android 17 无线 adb 亦 Rust 化）；动机=清 MIUI 包袱+模块化+人车家多端统一。**均为小米内部系统组件，未向第三方开放 Rust SDK，对本项目技术栈结论无影响**。

## MiDuo 参考桌面逆向分析（2026-09-17 上午，已完成）

- 对象：`参考/MiDuo-1.0.5.apk`（com.jake.duolauncher，Compose 桌面）；jadx 装在 `C:/vsCodeProject/tools/jadx`，反编译源码在 `参考/miduo-decompiled/sources`，**完整分析报告见 `参考/MiDuo-实现分析.md`**（含 file:line）。
- 关键结论：widget 绑定链路与本项目 HomeWidgets 几乎一致（bindIfAllowed+系统弹窗，无静默绑定）；多出可借鉴点 = pending widget 四元组中断恢复、`widgetFeatures` 判 configure、**负数 slot 内置伪 widget（时钟/天气/日历自绘，绕开 MIUI 私有 provider）**、span 优先 targetCellWidth/Height、HostView 缩放包装+可交互子 View 命中拦截；文件夹 = `folder:<uuid>` 格位占位 + 独立 folders 列表、拖拽重叠合并热区公式 min(cellW*0.82, 1.35*iconSize)、打开面板 3×3 分页玻璃浮层、预置自动分类夹（固定 UUID+包名候选+≥2 才建）。
- **第二轮（--show-bad-code 重反编译到 `参考/miduo-simple/`，skipped 全清零）已补齐**：图标预览确认 2×2 取前 4（成员 38% 尺寸、背景圆角 24%、Control 玻璃）；**剩 1 个成员自动解散、末位 app 回填文件夹原格位**；格位移动"挤开不交换"（widget 格不可推、桌面/dock 互斥）；落点权重 folder=3>widget=2>格=1、删除区最先；DuoGlass 九角色参数表（blur/noise/elevation/pressedScale，含深色与按下修正）；图标两级缓存（磁盘只存元数据+占位图标，扫完换真图）+ Collator 本地化排序。详见 `参考/MiDuo-实现分析.md` 第三、四节。


## 追加：test-base-state 一夜实证（2026-09-17 深夜，分支已删，结论归档于此）

- **物理折合 PoC ✓**：hold `state 5` 下真折全程 committed 锁 5、双面板零断电、override 跨完整折合周期存活（无需 re-arm）；base（铰链姿态）照常更新。
- **hold-from-closed ✓**：折合态 hold 5 → display 0 立即重绑内屏 + 双亮 = 「开盖前预点亮内屏」，规格②③④机制全通（预挂帧+hold+端点淡出+真实截图冻结帧，外屏挂载走 SCVH+attachAccessibilityOverlayToDisplay，FLAG_PRESENTATION 置位）。
- **规格⑤（合拢换绑零黑）固件层无解 ✗**：CLOSED 提交必令外屏 ~0.5s committedState=OFF（=黑）；`set-user-preferred-display-mode` 预设 120Hz 不能消除。三星无此问题因有 CONCURRENT_OUTER_DEFAULT（零换绑），小米状态表无此状态。
- **路线决策：走 FixedDualSession**（合拢不释放，外屏跑 display 0 实时镜像（mirror-lease 通道现成）+ 触摸注入（覆盖帧收触摸→a11y dispatchGesture 注回 display 0）；系统接管只在灭屏时发生）。
- 编排实现细节（若重建参考 test-base-state reflog：EarlyDisplayModel 双屏模型/premount 门控/rearm 滞回/stall 锁存；EarlyDisplayHelper.prepareTransition 模式预设+截图；DualCover 双帧挂载）。
- 运维坑：force-stop 应用会经 provider 死亡连带杀 shell controller；prefs 文件是 animation_settings.xml；HyperOS force-stop 后无障碍不自动重绑需 toggle。

## 调研：bunkaich/Folduo 开合不黑屏原理（2026-09-17，Z Fold7 实验项目）

- **核心 = 绕过系统原生切换**：Shizuku(shell UID) UserService 里反射调 `DeviceStateManager.requestState()` 常驻三星固件私有状态 `CONCURRENT_INNER_DEFAULT`/`CONCURRENT_OUTER_DEFAULT`（按状态名+property 10/11/12 筛选），两块面板同时保持逻辑点亮，系统"折起→灭一块屏"路径整个不发生；三星合盖会自动取消 override → DisplayListener 发现面板消失后自动 re-arm。
- **顺序化过渡（任一时刻两屏都有不透明像素）**：截源屏（反射 `IWindowManager.captureDisplay`，`setExcludeLayers` 排除自己的覆盖层防自递归）→ 源屏盖不透明快照（SurfaceView 帧提交回调确认 committed）→ **先**给目标屏盖内侧右半裁剪映射帧 → 才 `startActivityFromRecents + setLaunchDisplayId` 移任务（裸 reparent 在 Fold7 不重绘）→ 32ms 轮询 `dumpsys window visible-apps` 到 HAS_DRAWN（1800ms 超时）→ 截目标屏真实帧替换 → 180ms 淡出。角度阈值：滞回 12°，端点 ≥176°/≤1° 稳定 120ms。
- **角度三源**：公开 TYPE_HINGE_ANGLE 只有 0/90/180 粗值；细粒度靠三星私有传感器(type 65686)或 hack：对三星互动壁纸 `WallpaperManager.sendWallpaperCommand("<pkg>.READ_ANGLE")`，壁纸把 mCurrentAngle 打进 logcat，shell 侧 `logcat -s SprWallpaper|FoldInteractive` 正则抽值。
- **渲染**：每屏一个 TYPE_APPLICATION_OVERLAY + AGSL RuntimeShader，CPU 6 级高斯金字塔按方差插值连续模糊；外屏混入内侧快照做"磨砂透视"，输出 alpha 恒 1（不透明）。其姿态/投影类恰好也叫 `GlassProjection.java`（本项目同名由来）。
- **对我们的启示**：CONCURRENT_* 是三星固件私有，HyperOS 无此状态，"开合不黑"在小米上不能照搬 DeviceStateManager 路线（我们的 FixedDualSession 投屏路线是替代方案）；可搬的是**过渡顺序纪律**（先盖后移、帧提交确认、HAS_DRAWN 轮询、淡出收尾）与滞回阈值设计。
- 源码副本：`C:\Users\spideytznn\AppData\Local\Temp\folduo\`（16 文件）。

### 追加：小米等价接口已实测找到（2026-09-17，本机 lhasa 实证）

- **`OPENED_PRESENTATION`(id 5) / `OPENED_REVERSE_PRESENTATION`(id 6) = 小米版 CONCURRENT**。`adb shell cmd device_state print-states` 全表：0 CLOSED / 1 TENT / 2 HALF_OPENED / 3 OPENED / 4 OPENED_REVERSE / 5 OPENED_PRESENTATION / 6 OPENED_REVERSE_PRESENTATION。
- **触发比三星更容易**：Android 17 自带 `cmd device_state state <id>` / `state reset`（emulated 覆盖，shell 权限即可，无需反射）。**实测 `cmd device_state state 5` 后 dumpsys display 外屏 Display 1 从 `state OFF` → `state ON, committedState ON`（两屏同亮）；`state reset` 恢复**。
- 架构差异（利好）：本机内外屏**常驻注册为两个稳定逻辑 Display**（0=内屏 1672×2364、1=外屏 1168×1712，均 INTERNAL + FLAG_ALLOWED_TO_BE_DEFAULT_DISPLAY），无三星"物理↔逻辑 ID 互换"坑；我们应用（uid 10279）在 display 1 上本就有帧率投票。
- 待验证（下一步 PoC）：① OPENED_PRESENTATION 下系统在外屏显示什么、Presentation/Activity 放 display 1 是否正常；② override 与系统自身状态请求的竞争（Override Request 全局排他）、物理折合时 emulated override 的行为与 re-arm 时机；③ 开合全程 hold→reset 的黑屏时序。
- **明日 PoC（新分支 `test-base-state`，自 origin/main@18489f3 建，未切换，不并 duo-ui）**：
  1. **真机物理折合验证**（核心未知项）：hold `cmd device_state state 5` 后实折，看 vendor/HAL 是否遵守 committed override（外屏是否保持 ON）；再实开验证反向。
  2. Shizuku UserService 通道跑 `cmd device_state state 5 / reset`（确认 shell 经 Shizuku 与 adb 同效）。
  3. hold 期间在外屏（display 1）挂 Presentation/Activity 画内容，验证 swap 瞬间窗口去向（预期掉到内屏）。
  4. 我们做 HOME 的接管测速：释放后 DuoHome 在新 display 0 画首帧耗时（对比抖音 splash 秒级）。
  5. 角度源确认：本机 TYPE_HINGE_ANGLE 细粒度分辨率（对照 Folduo 的 0/90/180 问题）。
- 本机 adb 端口已变为 `192.168.31.51:40421`（旧 35473 失效）。

### 追加 2：切主屏黑屏时序实测（2026-09-17，base-state 仿真合盖）

- **实验方法**：`cmd device_state base-state 0` 仿真合盖（框架层等价物理折合，可逆 `reset`），`screencap -a`（注意：`-d` 参数要物理 display id，见 `dumpsys SurfaceFlinger --display-id`）逐相位取证，截图在 `build/tmp/e1_*.png/e2_*.png/e3_*.png`。
- **相位结果**：① hold `state 5` → 两屏 ON，**外屏点亮但纯黑**（presentation 空画布，系统不画任何东西，内容权完全归我们）；② hold 中合盖 → committed 保持 5，**切换被完全屏蔽**，两屏稳定 ON 无任何电源事件；③ 释放 `state reset` → committed→0，系统执行真切换：内屏 OFF、外屏接管，**外屏电源连续（无 OFF→ON 闪烁）**；4s 后外屏完整渲染出前台 app（抖音消息页+状态栏，activity 走了 splash 重启）。
- **小米也是 swap 模型**：释放后 screencap 后缀尺寸翻转（_0 变 1168×1712、_1 变 1672×2364）——合盖时 display 0 从内屏重绑到外屏，与三星 mapper 同类。**挂逻辑 display 1 的 Presentation 在 swap 瞬间会掉到内屏**（Folduo 的避坑点在小米同样存在）。
- **结论**：面板电源层"切主屏黑一下"已被消除（外屏接管前就点亮且不断电）；剩余的是**内容交接空窗**——display 0 重绑后真实 app/launcher 需在新主屏 resume/重排才有画面，裸跑会看到这段黑。对策：① 我们做 HOME，DuoHome 进程常驻，swap 后第一时间在 display 0 画第一帧（比冷启动快一个量级）；② 可配合截图覆盖 + HAS_DRAWN 后淡出（Folduo 纪律）；③ 真机物理折合路径未验证——vendor 折叠服务/HAL 是否也遵守 committed override 未知，需实折 PoC。

## 迁移引擎现状（第二十一轮，HomeMigrator）

- **SCAN 端到端通过**：`am broadcast -a <pkg>.MIGRATE_SCAN`（需 MIUI 桌面前台+我们的 activity 存活注册接收者）。流程：逐页扫描 a11y 树 → 空页（仅 dock）停止 → HOME 回第一页 → 逐个打开文件夹读内容 → 行协议输出到 logcat（SCAN_BEGIN…SCAN_END 分块）。
- **APPLY 端到端通过**：`am broadcast -a <pkg>.MIGRATE_APPLY --es layout "$(cat /data/local/tmp/layout.txt)"`（需我们为默认桌面且 DuoHomeActivity 在 display 0 存活）。HomeApps label→key 映射 → HomeLayout 构建（顺序驱动）→ widget provider 匹配（ALIASES 表：音乐→小米音乐）→ bindAppWidgetIdIfAllowed → store.save+render。**实测：图标 6 个+系统工具文件夹（12 应用）+dock 4 个全部迁移成功，布局顺序与原生一致**（migrated.png 视觉确认）。
- **关键坑（已解决）**：① MIUI 大文件夹 2x2 预览缩略图可直接点开 app——**必须点标题区（bottom-25px）**才能打开文件夹；② dispatchGesture 在该机不可靠 → 手势全部走 Shizuku `input tap/swipe/keyevent`（svcSync 同步版；白名单加了 input 前缀，UserService version 32）；③ 翻页后停最后一页 → 读文件夹前 `input keyevent 3` 回第一页；④ 中文 label 无分隔符 token 拆分无效 → 精确匹配+ALIASES。
- **widget 现状**：时钟/天气是 com.miui.home 私有组件，第三方桌面拿不到 provider（486 个 installed providers 里没有）→ **不可迁移（系统限制）**；小米音乐 provider 可匹配但 `bindAppWidgetIdIfAllowed` 返回 false（HyperOS 不给静默 bind）→ **下一步走 requestBindAppWidget/ACTION_APPWIDGET_BIND 用户确认流**（即用户要求的"授权先行"）。

## 小组件授权闭环（第二十二轮，已完成）

- `HomeWidgets.addForMigration(provider,page)` 暴露标准 bind 流（弹系统 ACTION_APPWIDGET_BIND 确认框）；`HomeMigrator` 静默 bind 被拒→自动走它。
- **弹窗闪退 bug 修复**：MIGRATE_APPLY/SCAN 曾被每个桌面实例各执行一次，第二个实例的 bind→cancelPending() 把第一个实例弹窗的 pending 取消→弹窗自动关闭。**广播接收者已加 display==0 单实例守护**。
- **弹窗"看不到"**：弹窗开在发起 startActivityForResult 的 activity 所在 display 0=外屏；用户手机展开看内屏自然看不到。按钮是 HyperOS 文案：勾选框"始终允许玻璃投影创建微件并查看其数据"+取消/**创建**。已代点（勾选+创建）。
- **实证闭环**：`dumpsys appwidget` 显示 host 2701 widgets.size=1（id=32 小米音乐）；桌面截图音乐组件真实渲染（封面+内容）。**端到端迁移全部打通：图标+文件夹(12应用)+dock+可绑定小组件**。时钟/天气仍不可迁（MIUI 私有）。
- 注意：后续新 widget 若非"始终允许"覆盖的 provider 仍会弹确认框（预期行为）。


1. **widget 授权确认流**：apply 时 bind 被拒 → 发 `AppWidgetManager.requestBindAppWidget`（activity 异步确认），聚合多个 widget 依次弹；桌面预览入口点击时先行触发授权。
2. **4×5 网格**：HomeLayout.cells()（boolean[16]/i%4/x+width>4）、DuoHomeActivity.appPage()（cols=4、fitAppRows 反推）、HomeWidgets.gridGeometry()（/4f）、HomeStore.read() span 夹 [1,4] → 引入 COLUMNS=4/ROWS=5/PAGE_SIZE=20 常量改造。
3. **桌面预览入口下移**（app 主界面权限步骤在前、预览在后）。
4. 迁移收尾：MIUI 私有组件（时钟/天气）用独立 app 等价 provider 或自绘占位卡片替代（可选）。
5. apply 广播当前被每个 DuoHomeActivity 实例各执行一次（幂等无害，待按 display 过滤）。

## 明日首要（用户实测反馈 2026-09-17 凌晨）

- **用户看到的桌面只是普通 4×5 空布局——迁移的文件夹和小组件没有出现在用户实际使用的桌面上！**（我验证的是 duo_home/display 0 的截图；嫌疑：用户看的是固定双屏虚拟屏的 duo_inner/duo_cover 独立 store——迁移只写了 duo_home；或最近重装把 duo_home 布局重置。明日先查三个 store 内容确认用户看的是哪份，然后把迁移结果同步/应用到用户实际桌面（或迁移时三 store 一起应用）。
- **svc 通道接入无线调试直连后端**（减少 Shizuku 依赖）：IHelperHost 是统一接口，Shizuku/无线直连二选一都走它——把 svc/gesture 依赖标注到直连后端可用。
- 桌面预览入口下移 + 时钟/天气等价替代（原计划遗留）。

## 触摸条生命周期修复 + 边缘返回删除（2026-09-17 上午，preview.9 已撤回）

- **严重问题修复**：透明触摸条原先进程级常驻——用户把默认桌面换回 MIUI 后仍劫持原生下拉。现在：`refreshHomeScope` 检查 ROLE_HOME，非默认桌面→清空全部条；`DuoHomeActivity.onDestroy` 重新加回 removeShadeBar（第 7 轮误删）。实证：我方桌面=3 条、切 MIUI=0 条、切回=恢复。
- **边缘横滑返回已删**（用户要求，底部把手已够用）：PaneHost 的 edgeSwipe 判定与文档删除。
- **preview.9 已 revert**（4a06491），修复后重新打包。

## 文件夹/大文件夹调研结论（2026-09-17 凌晨，明日实施）

- **澎湃OS 产品形态**（调研：MIUI14 引入大文件夹；HyperOS4 支持拖拽调尺寸）：三档尺寸 **1x1（传统小文件夹）/ 2x2（四宫格）/ 4x2（横条）**；2x2 内直接显示 3x3 应用缩略、**点击缩略直接启动应用**、点标题/空白展开全量面板；长按弹出尺寸切换。澎湃取消 4x7 就是为了 2x2/4x2 的网格对齐数学（我们 4x5 已天然对齐）。
- **Launcher3 实现范式**（googlesource Launcher3 folder/ 包）：FolderIcon（折叠态：PreviewBackground 圆底 + ClippedFolderIconLayoutRule 排预览图标，最多 N 个）/ Folder（展开视图 FolderGridOrganizer 排网格）/ FolderAnimationManager（开合 AnimatorSet 图标飞行）。参考文章有九宫格预览改造和透明背景改造的现成路径。
- **我们的落地方案**：
  1. 数据：HomeLayout.Item 已有 spanX/spanY（widget 在用）——文件夹直接复用做尺寸（1x1/2x2/4x2），cells() 装箱已支持任意 w/h，改动极小；store JSON 已序列化 span 字段 ✓ 向后兼容。
  2. 渲染：FolderFan 升级三档——1x1 现状扇形；2x2 九宫格（3x3 缩略=格宽/3，每个缩略 onClick 直接 launch，标题条+右上展开角标）；4x2 横排 4x2 缩略+标题。
  3. 展开：现有 HomeSheet folder() 面板保留；补 Launcher3 式开合动画（图标从缩略位飞到展开位）。
  4. 交互：长按文件夹→尺寸菜单（三档切换改 span + save + render）；拖入 merge/拖出 extract 已有。
  5. 迁移：scan 的 FOLDER 行已有 bounds（462px=2x2）——apply 时按 cell 单位换算 span 写入（当前当 1x1 处理，明日改）。
  6. **三存储同步**（明日第一件事）：apply 同时写 duo_home/duo_inner/duo_cover（用户实测：主屏有文件夹、固定双屏没有——后两者仍是旧种子布局）。

## 4×5 网格（第二十三轮，已完成）

- `HomeLayout`：`COLUMNS=4, ROWS=5, PAGE_SIZE=20` 常量化（cells()/span 夹全部改用常量）；`DuoHomeActivity.appPage` cols=常量、`fitAppRows` rows=ROWS；`HomeWidgets.gridGeometry`/`resizeChoices`；`HomeStore.read` span 夹常量。
- 实证（grid45.png）：5 行网格生效、迁移内容完好（音乐组件+图标+文件夹+dock）、尺寸正常。

## 桌面任务剩余

1. **桌面预览入口下移**（app 主界面菜单排序：权限步骤在前、桌面预览放后面）——待做。
2. 时钟/天气 MIUI 私有组件等价替代（可选：公开 provider 或自绘卡片）。
3. 迁移 UX 打磨：扫描→应用串成一键入口（目前 set-home 轮转靠 adb 脚本，见速查）。

## 构建与部署速查

- JDK `C:/Users/spideytznn/ZCodeProject/.tools/jdk17/jdk-17.0.20.1+1`；`./gradlew.bat -I tools/mirror-init.gradle.kts :projection-lab:assembleDebug --offline`
- adb=`C:/Users/spideytznn/ZCodeProject/.tools/platform-tools/adb.exe`；设备 192.168.31.51（端口轮换→`adb mdns services` 查）；外屏截图 `-d 4639175068132267009`；锁屏时装机必败（先查 mDreamingLockscreen）；装机后进程可能不拉起→`am start -n PKG/.DuoHomeActivity` 或无障碍开关重绑（不抢前台）。
- 迁移操作序列：set-home ours + am start → set-home miui → HOME×2 → MIGRATE_SCAN → set-home ours + am start → push layout.txt → MIGRATE_APPLY。

## 未提交改动清单（约 11 文件，待分组提交）

HomeControlPanel（iOS 面板+手势体系全套）、HomeStatusBar（透明触摸条）、ProjectionService（shade bar 管理+自愈钩子）、DuoHomeActivity（openShade/接收者/迁移接入）、FixedDualSession/FixedDualOutput（状态栏 z 序修复）、MobileHelper(+/svc/svcSync/v32)+MobileHelperHost(+svc)+IHelperHost.aidl(+svc)、DuoNotifications(+ensureBound)、HomeWidgets(+store())、HomeLayout（未动）、**HomeMigrator（新）**、local.properties（新）。


## 第十二轮（00:1x）：双页同屏滑动架构 + 通知监听自愈

- **滑动重构（顺畅根因）**：旧版"滑出→重建→滑入"三段式必有顿挫；改为 **PaneHost 双真实页面**（notifPane/controlPane 并排、translationX 定位，scroll ∈ [0,W]），手势 1:1 跟手（带 ±18% 橡胶带）、松手按投影位置+速度（140ms lookahead）一次 ValueAnimator 连续滚到最近页；切页不重建视图（build 一次、paintTiles 复用）；点击空白=关闭（pane clickable）。方向：通知页左滑→控制、控制页右滑→通知。
- **通知监听自愈**：`DuoNotifications.ensureBound()`（连着就跳过；经 Shizuku svc 白名单 `cmd notification disallow/allow_listener` 强制重绑，onListenerConnected 自动刷新观察者）；`ProjectionService.onServiceConnected` postDelayed 8s 调用；面板提示分两种文案（helper 在=点此重试自愈 / 不在=去授权页）。**实证：重装后自动绑上新进程（app=12050）**。
- adb 端口又轮换（45391→40421，Wi-Fi/无线调试重启）；`adb mdns services` 秒查。
- 双向滑动真机复测通过（m2=控制、m3=通知）；0 crash。p1/p2 那轮左滑偶发未触发（事件被吃），重测正常——用户手测若复现再查。

## 第二十轮（02:5x）：通知列表加长 + 迁移 PoC 成功

- 通知列表 bottomMargin 130→52dp（约 4.5 张卡可见）。
- **HomeMigrator PoC 实证通过**（新文件 + `am broadcast -a <pkg>.MIGRATE_SCAN` 触发，logcat -s DuoMigrate 读结果）：MIUI 桌面 a11y 树完整给出——小组件（id=widget_container，时钟/天气/音乐 462px=2x2，音乐内含 com.miui.player RemoteViews 细节→可反查 provider）、图标（id=icon_icon，desc=应用名，4 列×231px 网格可换算）、**文件夹（id=folder，desc=名称，子节点直接含预览应用如 米家/万能遥控）**、dock（电话/浏览器/短信/相机）。**切 MIUI 桌面用 `cmd package set-home-activity com.miui.home/com.miui.home.launcher.Launcher`（可远程切来切去）**。
- Phase 2 待做：翻页遍历多屏+文件夹展开抓全文；label→包名映射（LauncherApps）；4×5 网格重构（HomeLayout/渲染/FolderFan/三存储）；小组件 requestBindAppWidget 批量授权（桌面预览入口先行）；预览入口下移。
- 今晚全部改动仍未提交 git（约 9 个文件 + HomeMigrator 新文件）。


- **控制中心**：底部整行（通知中心/设置胶囊）删除；头部行改为 [时间 | 电池 | 齿轮图标按钮 38dp（TileIcon.GEAR 新图标，ripple，开系统设置）]；头部高 42dp。
- **通知中心**：42sp 大时钟+日期两行删除，改为一行 40dp 顶栏：[时间 22sp medium 左][日期 12sp][弹簧][全部清除右]——通知列表多出 ~120dp 空间；notifHead 计数字段停用（null 安全）。
- 装机成功（进程 12634）。pill()/pillText() 成为无引用方法（待桌面重构时清理）。


- 通知列表 ScrollView `bottomMargin=dp(130)`：底部留出玻璃空白把手区；PaneHost DOWN 时记 `onList`（overView 命中测试泛化），**空白区/控制页任意上拉即收起**（不受列表滚动位置限制），列表本体仍需在顶。
- 删除通知列表尾部"打开控制中心"胶囊（refreshNotifications 的 trailing 保留逻辑一并清理）；控制页"通知中心/设置"保留。
- 装机注意：锁屏时 pm install 直接 USER_RESTRICTED（对话框弹不出来）——先查 mDreamingLockscreen 再装。


- **调研结论**（AOSP SystemUI：NotificationPanelViewController/NotificationStackScrollLayout/SwipeHelper）：标准做法=**VelocityTracker + ViewConfiguration 系统最小甩动阈值**（约 50dp/s≈138px/s，远松于我之前的 0.35px/ms≈960px/s！）；列表与面板的交接用嵌套滚动。参考：googlesource SystemUI shade 目录、androiddesignpatterns 嵌套滚动文。
- **落地**：PaneHost 用 VelocityTracker（dispatchTouchEvent 喂点）+ `getScaledMinimumFlingVelocity()` 判定一切甩动（横滑切页 4dp 位移+minFling 即切、按方向；收起=抬升 6% 屏高或上甩≥minFling）。手工铺的 android.jar 裁掉了 NestedScrollingParent2/3（实证 zip 无此类）→ 嵌套滚动做不了，改用拦截式（ScrollView 屏蔽已被 no-op 覆写废掉，拦截可靠）：通知页列表顶上拉 14dp 即接管跟手。
- **边缘侧滑=返回**：DOWN 在左右 28dp 边缘带内且横向拖动 12dp → 立即 close()（等同系统返回语义）。
- 删除 peakVx/peakVy/fallbackVx 手工速度采样（VelocityTracker 取代）；destroy 回收 tracker；DISMISS 拖动改增量式（修掉自差为 0 的笔误）。


- **手势全程记录峰值速度 peakVx/peakVy**（逐段采样 4-150ms 窗口，intercept MOVE 也采样）：横滑释放取 max(末端速度, fallback, 峰值)——**接触期任一时刻快过阈值即按甩向切换**（0.35px/ms+6dp 位移），位置阈值只剩慢拖兜底（1/4 屏）。
- **上拉 vs 列表滚动**：快速垂直上拉（peakVy<-1.1px/ms）**无论列表滚到哪都收起**；慢速上拉仅列表在顶时收起（否则滚动列表）；释放判定也取峰值。
- **装机流程教训**：重装后启动竞态崩溃频发，`am start` 会抢用户前台——**改用无障碍开关重绑拉活**（settings put 清空再写回 + accessibility_enabled 翻转），不抢屏（实证 13678 存活）。写进部署套路。


- 横滑再放宽：判定 16dp/1.4 倍；渐变分母 0.5W（拖 1/4 屏即完成切换读数）；释放阈值 projected>=0.25；flick 0.35px/ms/8dp/前瞻 200ms。
- 上滑"闪一下"=列表 overscroll 光晕 → `notifList.setOverScrollMode(OVER_SCROLL_NEVER)`；收起动画改为**与下拉镜像**：整面板滑回 -0.6 屏高（同入场距离）、320ms、无面板透明度渐隐（仅 scrim 平行淡出）。
- 底部上滑失败=系统手势导航区（回到桌面手势）→ 新增 **DuoHomeActivity.onResume → HomeControlPanel.closeIfOpen(displayId)**：回到桌面时自动收起面板（原生 shade 行为）。DISMISS 阈值放宽 14dp/1.4 倍、8% 屏高或 -0.7px/ms。
- 远程验证受限（用户前台使用手机）；装机完成（进程 7640），手势留手测。装后注意：桌面活动未重建时广播无效——按 HOME 即恢复。


- **根因（ScrollView 吞手势）**：android.widget.ScrollView 判定拖动后调用 `requestDisallowInterceptTouchEvent(true)` 屏蔽父容器拦截 → 真实手指的横滑/上滑全被列表吃掉（adb 注入的超快滑动赶在屏蔽前生效=远程测试通过而手测失败的真相）。修复：`notifList` 匿名子类 **覆写 requestDisallowInterceptTouchEvent 为空**。
- **翻页效率**：拖动渐变映射分母改为屏宽 0.6（拖 1/3 屏即完成渐变读数）；释放阈值 projected>=0.34；flick 阈值降 0.55px/ms、位移 12dp、速度前瞻 180ms。FADE 判定 20dp/1.5 倍、DISMISS 判定 16dp/1.5 倍、DISMISS 关闭阈值 10% 屏高或 -0.9px/ms。
- **重装后偶发启动崩溃（新发现）**：`ConfigurationController.updateLocaleListFromAppContext` NPE @ handleBindApplication（23:49/00:03 两次，重装后首拉起竞态；通常自动重试成功，00:03 那次卡死循环）→ 进程死→无 strip/无面板/双屏停。恢复手法：**`am start -n PKG/.DuoHomeActivity` 手动拉活**（实证 20902 存活）。若再现考虑装后自动 am start。
- 手势远程验证被用户使用中打断（当前前台=小红书），**留待用户手测**。


- **切换改为交叉淡化**（用户要求）：双页同屏叠放（translation 弃用），`fade∈[0,1]` 实时映射拖动进度（alpha 渐变），松手 230ms ValueAnimator 补完；非活动页 setVisibility(INVISIBLE)（避免触摸穿透与上层遮挡）。**音量条贴通知条的接缝问题随滑动取消自然消失**。
- **惯性轻甩**：速度采样 track() 在 MOVE 一律执行（此前 UNDECIDED 不采样→flick 永远 0 速度的 bug）；速度>0.9px/ms 且位移>16dp 即甩切；**无中间 MOVE 的超快甩动**（注入器只发 DOWN/UP）在 UP 兜底判定（总位移+总时间算 fallbackVx）。阈值：位移 28dp/20dp 判定进入 FADE/DISMISS。
- **上滑收起**：通知列表在顶（scrollY<=2）或控制页时，向上拖 20dp 进入 DISMISS——面板跟手 0.55 系数上移，松手抬升超面板高 12% 或上甩（<-1.2px/ms）→ close()，否则弹回。控制页任意位置可上滑关；通知页先滚到顶才触发（不干扰列表滚动）。
- 实证（y1/y2/y3.png）：左轻甩→控制 ✓、右轻甩→通知 ✓、上滑→收起回桌面 ✓；进程 13740 存活。
- 遗留：DISPLAY 切换、y=300 首测失败一例（旧版本 bug，已修）。

## 桌面任务（用户已拍板）：4×5 + 小组件 + 布局迁移 + 授权先行

- 用户确认按无障碍抓取方案做；**小组件授权先行**：点击"桌面预览"入口时就先弹授权（迁移恢复小组件需要 bind 权限）。
- **桌面预览入口要下移**：app 主界面里权限类步骤在前、桌面预览（新功能）放后面。
- 实施顺序（下轮开工）：① a11y 抓取 PoC（MIUI 首屏图标/文件夹/坐标导出清单验证）② HomeLayout 4×5 重构（含 FolderFan/三存储）③ 迁移引擎（a11y 遍历多屏+文件夹展开抓取→LauncherApps 包名映射→生成布局）④ 小组件：位置迁移+requestBindAppWidget 批量授权流 ⑤ 桌面预览入口下移+授权先行 UI。


- 需求：4×5 网格（澎湃OS 风格）；首启按用户**原生 MIUI 桌面**排布迁移：应用、大小文件夹（3x3 等多种布局）、小组件。
- 关键约束：MIUI 桌面数据库 /data/data/com.miui.home/databases/launcher.db **shell(Shizuku) 无权读**（app 私有 0700，需 root）。可行路径：**用现有无障碍服务"屏幕抓取"MIUI 桌面**——遍历各屏（手势翻页）读 AccessibilityNodeInfo 树拿图标/文件夹/坐标，文件夹逐个打开抓内容；小组件拿不到 provider 绑定（需重新 bindAppWidgetId，系统会弹授权，可批量请求）。另一路径：让用户在 MIUI 桌面"备份与重置"导出布局（若有）或 root。**下一步先做 PoC：a11y 树抓取首页图标+坐标验证可行性。**
- 注意 4×5 = 单屏 20 格，需同时改 HomeLayout/渲染/文件夹扇形（FolderFan）与 DPI 适配；duo_home/duo_inner/duo_cover 三份存储都要迁移逻辑。


- **滑动方向修正**（用户澄清"往左往右"=字面手指方向）：通知中心**往左滑**→控制中心；控制中心**往右滑**→通知中心（标准 pager 语义：通知=左页、控制=右页）；反向滑动回弹。
- **"通知没权限"根因**：授权没丢（enabled_notification_listeners 一直在），是**重装进程重启后 HyperOS 不自动重绑** NotificationListenerService（ServiceRecord app=null）。修复：`cmd notification disallow_listener X; cmd notification allow_listener X` 强制重绑（已验证 app=非空）。**以后每次 adb 装机后都要跑这两条**（写进部署流程）。
- 屏幕再次锁上（视觉验证 sw 截图全黑 11679B=熄屏特征）；滑动方向留用户手测。


- **PaneHost 手势容器**（body 的 FrameLayout 包装）：横向拖拽 >44dp 且 dx>1.7dy 拦截 → 跟手位移（0.8 系数）→ 松手超 20% 宽即切屏（滑出+反向滑入动画 200/240ms），否则回弹；**滑条区域 DOWN 即判 VERTICAL 永不拦截**（滑条独占横向拖动，overSlider 用屏幕坐标命中测试）；纵向滚动自动让位（dy>28dp 判纵向）。方向语义：通知中心右滑→控制中心，控制中心左滑→通知中心；广播/胶囊切换带同款滑动动画（switchTo(control,direction)）。
- **实证**：双向滑动切换均成功（sw3/sw4.png）；流量按钮真实生效（mDataConnectionState 2↔0，坐标 (718,730)——**布局变化后按钮行 y≈700-760，早期 y=600 全打在音量滑条上**）；Wi-Fi 按钮此前实证（把无线 adb 都断了）。已全部恢复。
- **adb 端口轮换**：Wi-Fi 重开后 35473→45391；`adb mdns services` 直接给新端口（_adb-tls-connect._tcp），无需扫描。
- dataOn 初值改读 `Settings.Global "mobile_data"`（HyperOS 上该 key 与实际连接状态可能不同步，图块颜色按设置值近似）。
- 遗留（低优先）：svcToggle fallback 打开 `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` 会落到系统设置主页（helper 未就绪时才触发）；蓝牙状态读取受 BLUETOOTH_CONNECT 权限限制（可切不可读时显示灰）。


- **通知中心拉满到屏幕底**：panel 直接 MATCH_PARENT（fraction/resize 全删）；淡出虚化保留；点空白处/列表空白收起（panel+scroll 的 click→close）。
- **控制中心 4×2 圆形小按钮**（52dp 圆 + 11sp 标签）：Wi-Fi/蓝牙/数据/飞行模式/手电筒/自动旋转/勿扰/深色；激活=蓝圆白图标（手电筒白底深图标、勿扰靛紫）；新 TileIcon 图标 WIFI/BLUETOOTH/CELL/AIRPLANE 自绘。
- **IHelperHost 新增 `String svc(String command)=12`**（MobileHelperHost 白名单前缀 svc/cmd/settings、禁 ;&&|` 换行；返回 stdout）；**UserService version 30→31**（必须 bump 否则 Shizuku 不重载宿主）；`MobileHelper.svc(cmd, Consumer<String>)` 主线程回调。
- 开关逻辑：Wi-Fi/蓝牙/数据/飞行模式经 svc 切换；helper 不可用→回退系统面板（INTERNET_CONNECTIVITY/BLUETOOTH_SETTINGS/AIRPLANE_MODE_SETTINGS）+toast。状态读取：wifi=WifiManager、airplane=Settings.Global、bt=adapter(可能 SecurityException)、data=乐观值。
- **实证：点击 Wi-Fi 按钮真实关闭了系统 Wi-Fi**（svc 通道工作）——但 adb 走的正是该 Wi-Fi，连接当场断开；已请用户在面板上点回 Wi-Fi。**教训：不要通过无线 adb 测试 Wi-Fi 开关，先测流量/蓝牙。**
- 待办：Wi-Fi 恢复后重连（端口可能轮换！）；验证 mobile_data 翻转；蓝牙按钮状态显示（BLUETOOTH_CONNECT 权限缺失时显示灰但可切）。


- **HomeControlPanel = 无障碍 overlay 窗口**（"Duo home shade"，服务 windowContext per display）：任何应用之上可呼出；可在 App 前台使用。
- **视觉（第八轮，用户反馈驱动）**：全宽无边距无边框；背景 `GlassFade` drawable（**dock 同色系 0x8c343b43 tint + HomeGlass 磨砂**，底部 110dp 线性渐变到全透明，无硬边圆角）；两模式统一高度 **62%**（`PANEL_HEIGHT_FRACTION`）；通知 ScrollView 开 verticalFadingEdge(88dp) 让卡片融进淡出区；"打开控制中心"胶囊挪进列表尾部。
- **resize bug 修复**：原来仅靠 insets 回调，root 未布局时 getHeight()=0 直接 return 且不再触发 → 高度从未生效（满屏贴底）。现加 `root.addOnLayoutChangeListener(→resize())`（同 HomeSheet 手法）。
- HomeStatusBar 纯透明触摸条常驻（onPause/onDestroy 不撤）；strip 回调直接 `HomeControlPanel.open(displayId,side)`。
- 实证（g_notif/g_ctrl.png）：不贴底（~35% 留白）、底部渐变淡出无硬边、玻璃透质、全宽、组件清晰；进程存活。


- **HomeControlPanel 不再是 Activity Dialog，改为无障碍 overlay 窗口**（TYPE_ACCESSIBILITY_OVERLAY，"Duo home shade"，服务 windowContext per display）：可在任何应用之上呼出；面板磨砂背景从 y=0 覆盖原生状态栏（衔接自然，无需遮罩条）；setShadeCover/Cover 逻辑全删。
- **HomeStatusBar 纯透明触摸条常驻**：DuoHomeActivity onPause/onDestroy 不再 removeShadeBar（App 前台仍可呼出）；strip 回调直接 `HomeControlPanel.open(displayId,side)`（不再路由 host/shadeTarget/hostOn 已删）。
- 面板内部 activity 依赖全部替换：service context + NEW_TASK startActivity + 本地 Toast。
- **通知中心高度 86%→68%**（NOTIF_HEIGHT_FRACTION）。
- 实证（s_idle/s_notif/s_ctrl/s_over_app.png）：顶部 y=0 覆盖无断层；通知中心 ~66% 不贴底；设置 App 前台时 strip 仍在（dumpsys 16 处 Duo home bar）、面板盖 App + scrim 压暗；进程 16469 存活。
- 广播 OPEN_SHADE 现在会在多个 display 各开一份面板（每个 DuoHomeActivity 实例都转发）——真实触摸只触发所在屏一份，无影响；已知无害怪癖。


- `HomeStatusBar` = **纯透明触摸条**（TYPE_ACCESSIBILITY_OVERLAY 顶条，无任何视觉内容）：下拉>16dp 或点击 → 按左右半区打开通知中心/控制中心；同时接管原生栏触控（原生 shade 在固定双屏本就死掉）。
- 平时：**原生 MIUI 状态栏可见**（时间/电量/通知图标原生渲染，挖孔自行处理——内屏摄像头遮挡问题随之消失）。
- 下拉时：`HomeControlPanel` 打开 → `ProjectionService.setShadeCover(displayId,true)` 把该屏触摸条变成 0xd916161c 深色头部（160ms 淡入）**盖住原生栏**，与面板连成一体；关闭时淡出恢复透明。固定双屏下 cover 落在面板所在的虚拟屏条上（镜像内生效）。
- 已删除：自绘时间/WiFi/电池/通知徽标、磨砂遮罩、HomeGlass、右侧摄像头避让、`shadeCountObserved` 观察者。
- 实证：空闲态仅一组原生栏无重叠（idle_zoom.png）；下拉时原生栏被深色头部遮住、大时钟正常（shadecover.png）；当前进程 0 crash。

## v2-v6 演进摘要（全部未提交 git，涉及 HomeControlPanel/HomeStatusBar/ProjectionService/DuoHomeActivity/FixedDualSession/FixedDualOutput）

1. iOS 风格双面板（顶部下滑全宽、0xd916161c 磨砂、PillSlider 镂空滑条、2×2 磁贴、每屏一面板 currentByDisplay）。
2. 左拉闪退修复：torch 回调仅控制中心注册+判空（注册即回放当前状态是根因）。
3. 固定双屏状态栏消失五连修：镜像后重挂（z 序）/addView 重试×3/服务连接补挂 refreshShadeBars/面板路由 shadeTarget→hostOn/每屏面板。
4. 状态栏视觉三轮后改为方案 v3（本节）。
5. 调试基建：`am broadcast -a <pkg>.OPEN_SHADE --ei side 0|1`；DuoHomeActivity.homeInstances/hostOn/shadeAlive。

## 下一步

1. **用户手测**：外屏顶部下拉（左=通知/右=控制）、固定双屏开关反复、面板内互切、磁贴/滑条/通知卡操作。
2. 通过后提交 git（建议拆：①面板 iOS 重构+闪退修复 ②固定双屏状态栏修复 ③状态栏 v3 方案）。
3. 候选迭代：通知卡横滑删除；Wi-Fi/蓝牙磁贴（MobileHelper 需加通用 exec）；cover 头部加磨砂与面板一致。
4. 挂起遗留：桌面 UI 冒烟测试、文件夹预览截图。


## 固定双屏状态栏消失修复（21:xx 第二轮，未提交 git）

- **根因（多因叠加）**：① 固定双屏的镜像输出窗（independent output，不透明全屏 TYPE_ACCESSIBILITY_OVERLAY）与自绘状态栏同类型，按 add 顺序定 z 序——镜像后建会把已有状态栏压到下面（焦点事件再触发才会浮回，时隐时现）；② addView 失败（display 重建瞬间）后 catch 直接 return 永不重试；③ 进程重启后活动先 resume、无障碍后连接，instance==null 静默丢弃，onWindowFocusChanged 重试不保证再触发；④ 固定双屏下物理屏 bar 打开的面板落在 display-0 activity 上，被不透明镜像盖住（看起来"点了没反应"）。
- **修复四件套**：`ProjectionService.onServiceConnected`→`DuoHomeActivity.refreshShadeBars()`（连接即补挂）；`updateShadeBar` addView 失败重试 ×3（2s 间隔，host 失活则停）；`FixedDualOutput` 建完镜像窗→`refreshShadeBars()`（bar 确定压镜像之上）；bar 回调经 `FixedDualSession.shadeTarget(physicalId)`→`hostOn(contentId)` 把面板开到镜像内虚拟 activity 上。
- 新增基建：`DuoHomeActivity.homeInstances` 全实例表 + `hostOn(displayId)`/`shadeAlive()`。
- **按屏面板（21:3x 第三轮补充）**：`HomeControlPanel.current` 单例去重在多屏下会抢占——全局唯一面板落在最先注册的 activity（往往是 display-0 物理屏，被不透明镜像盖住→看不见）。改为 `currentByDisplay` 按 displayId 各持一个面板；dismiss 时按 activity display 清除。三截图验证：固定双屏下 bar/控制中心/通知中心全部透过镜像可见（w_bar/w_ctrl/w_notif.png），新进程 0 crash。
- 验证脚本 `build/tmp/verify.sh`（后台等待解锁→双屏恢复→z 序 dump→bar/控制/通知三截图→crash 检查）；z 序判读：dumpsys window windows **先列的是高层**，bar 行号须在 mirror 之前。
- dumpsys window windows 列表顺序 = topmost first（本次实证）；虚拟屏 screencap -d 129/130 失败（shell 拥有，不可截）。
- 注意：`maintain()` 要求 ROLE_HOME（默认桌面）+亮屏+解锁+MobileHelper ready+10s 退避——重装后锁屏期间双屏不会自动恢复，解锁即恢复。

## v2 验证结论（截图实证 build/tmp/*.png）

- 外屏（display 1）玻璃桌面正常，自绘状态栏：时间+通知数徽标+WIFI+电池 ✓
- 通知中心（side 0）：大时钟/日期/2 张卡片/全部清除/打开控制中心 ✓
- 控制中心（side 1）：亮度+音量滑条（图标镂空可见）/2×2 磁贴（深色模式=激活蓝）/通知中心+设置胶囊 ✓
- 面板内互切三轮无崩溃；当前进程 0 crash（旧版 17:00-20:02 反复 NPE 的记录全在 crash buffer）。

## 状态栏精修（21:5x-22:1x 第四/五轮，未提交 git）

- 视觉模型对比批评驱动：时间 13sp 常规→**15sp sans-serif-medium + includeFontPadding=false**；左右留白对称 16dp。
- **电池重构**：原 canvas 里画文字导致基线飘、比例失调 → 图标（26×14dp：1.5dp 描边 + 半圆右凸起 + 圆角填充 + 充电深色闪电）与百分比**分离成标准 TextView**（12.5sp medium，垂直居中由布局保证）；充电绿 0xff7ee787 / 低电红 0xffff6961。
- Wi-Fi：三弧改标准 135°→90° 扇形、2.1dp 粗线圆帽、断网 35% 白；通知徽标全圆胶囊 + medium。
- **第五轮（用户反馈）**：去掉 35% 黑磨砂遮罩与 HomeGlass——**完全透明状态栏**（实证：无 MIUI 重影/双图标，mirror 合成不含虚拟屏系统栏）；时间/电池文字加 2dp 柔和黑投影保证亮壁纸可读。
- **避让内屏摄像头**：`updateShadeBar` 计算 `clearance=max(displayCutout().right, 横屏?56dp:0)` → `bar.setEndClearance`（内屏横屏虚拟屏摄像头在 bar 右端；外屏竖屏实测右侧仍 16dp 不受影响）。


- 视觉模型对比批评驱动：时间 13sp 常规→**15sp sans-serif-medium + includeFontPadding=false**；左右留白对称 16dp。
- **电池重构**：原 canvas 里画文字导致基线飘、比例失调 → 图标（26×14dp：1.5dp 描边 + 半圆右凸起 + 圆角填充 + 充电深色闪电）与百分比**分离成标准 TextView**（12.5sp medium，垂直居中由布局保证）；充电绿 0xff7ee787 / 低电红 0xffff6961（GitHub 色板）。
- Wi-Fi：三弧改为标准 135°→90° 扇形、2.1dp 粗线圆帽、断网 35% 白；点 1.4dp。
- 通知徽标：全圆胶囊（radius 999）+ medium 10.5sp。
- 复评：时间/徽标/Wi-Fi 通过，电池 9/10（nb_zoom.png 实证）。

## 装机经验（本次换机重装踩坑，重要）

- 设备已换成**本机 debug 签名**（cert 0ae7bee4…=dist 签名，与仓库 dist 包同源）。换机器续开发必查：`apksigner verify --print-certs` 对比。
- HyperOS adb 安装：锁屏直接 USER_RESTRICTED；解锁后弹 `AdbInstallActivity`（7 秒倒计时自动拒绝），自动确认脚本要点 **Button "继续安装"**（精确匹配 text，别匹配含"安装"的标题文字）。`build/tmp/deploy.sh` 已有完整流程（等解锁→装→run-as 恢复 prefs→授权→HOME）。
- **screencap `-d` 要用 SurfaceFlinger 大编号**：外屏=`-d 4639175068132267009`（1168×1712），内屏=4639175402683733248；小编号 0/1 全报 invalid。外屏截图看 UI 用 `analyze_image`（截图 PNG Read 会上传 CDN）。
- 虚拟屏（duo displays）本次未重建（display 列表只有 0/1/124）——DuoHomeActivity 直接跑在物理外屏 display 1 上，功能正常；双虚拟屏路径待后续确认是否需要 FixedDualSession 手动开启。
- 重新授权全靠脚本命令（见下），重装后桌面布局/duo_dual 开关等 8 个 prefs 从 `build/tmp/gp_backup.b64` 完整恢复。

## 恢复授权命令（重装后跑一遍）

```
settings put secure enabled_accessibility_services io.github.sixzleo.tabfold.projection/io.github.sixzleo.tabfold.projection.ProjectionService
settings put secure accessibility_enabled 1
appops set io.github.sixzleo.tabfold.projection SYSTEM_ALERT_WINDOW allow
appops set io.github.sixzleo.tabfold.projection WRITE_SETTINGS allow
cmd notification allow_listener io.github.sixzleo.tabfold.projection/io.github.sixzleo.tabfold.projection.DuoNotifications
```

## v2 改动明细（未提交 git）

- **闪退根因（已修）**：`HomeControlPanel.show()` 无条件 `registerTorchCallback`，注册即回调当前状态 → `paintTiles()`；通知模式下四个磁贴未初始化 → `paintTile(null)` NPE。v2：仅控制中心模式注册/注销 torch 回调 + `paintTiles()` 判空。
- **HomeControlPanel 全量重写（iOS 风格下拉 shade）**：
  - 顶部下滑全宽面板（底部 32dp 圆角、`0xd916161c` 重磨砂 + HomeGlass 模糊、入场 340ms 下落/关闭上滑淡出），替代原居中 440dp 卡片。
  - token：TEXT `0xfff2f2f7`、MUTED `0x99ebebf5`、模块 `0x42787880`、卡片 `0x463a3a44`、iOS 蓝 `0xff0a84ff`、勿扰靛 `0xff5e5ce6`、手电筒激活白底深色图标。
  - 控制中心：紧凑头(时间+电池⚡) → 亮度/音量自绘 PillSlider（白色填充+太阳/喇叭 DST_OUT 镂空）→ 2×2 磁贴（勿扰=月牙 DST_OUT）→ [通知中心|设置] 胶囊；音量为新增（STREAM_MUSIC）。
  - 通知中心：42sp 大时钟+日期 → 通知数+全部清除 → 卡片列表 → [打开控制中心] 胶囊；面板内互切 `switchTo` 重建+淡入；静态 `open()` 去重防叠窗。
  - 电池 receiver 面板内注册/注销；torch 回调传 main handler。
- **HomeStatusBar**：时间/电池 sans-serif-medium；电池充电画深色闪电、低电红。
- **DuoHomeActivity**：`openShade`→`HomeControlPanel.open(...)`；调试广播 `am broadcast -a <pkg>.OPEN_SHADE --ei side 0|1`（虚拟屏/远程验证用）。
- 新增 `local.properties`（sdk.dir=ZCodeProject/.tools/android-sdk；keystore 亦在本机）。

## 构建环境（本机已验证）

- JDK `C:/Users/spideytznn/ZCodeProject/.tools/jdk17/jdk-17.0.20.1+1`；SDK 同目录 android-sdk；`./gradlew.bat -I tools/mirror-init.gradle.kts :projection-lab:assembleDebug --offline`
- adb=`C:/Users/spideytznn/ZCodeProject/.tools/platform-tools/adb.exe`；设备 192.168.31.51:35473。

## 下一步

1. **用户手测**：外屏左半下拉→通知中心、右半下拉→控制中心；磁贴（手电筒/勿扰/深色）、滑条、通知卡点击/长按删、全部清除、面板内互切。
2. 按反馈迭代（候选：Wi-Fi/蓝牙磁贴需 helper exec 通道；通知卡片左右滑删除；内屏 display 0 顶部条）。
3. 验证满意后提交 git（连同桌面 UI 重构遗留一起）。
4. 挂起：UI 重构冒烟测试、文件夹预览截图。

## 失败方案 / 教训（新增）

- `registerTorchCallback` 注册即回放当前状态——按模式懒初始化的控件必须按模式注册或判空。
- 跨机续开发先比对 debug keystore 指纹，签名不同只能卸载重装（先 run-as base64 备份 shared_prefs，恢复在首启前）。
- run-as 不能写 /data/local/tmp（SELinux）；备份用 `exec-out ... tar cf - | base64` 流式。
- MIUI 安装弹窗按钮匹配必须精确 text=="继续安装"（标题"USB安装提示"也含"安装"二字，误点标题=超时被拒）。
- 旧教训仍有效：Activity 不能建 TYPE_ACCESSIBILITY_OVERLAY；TYPE_APPLICATION_OVERLAY 压不过系统栏；管道吃退出码；MSYS_NO_PATHCONV=1；logcat/javac 中文 GBK。

## 诊断：双屏桌面帧率 60Hz 锁死（2026-09-17）

- 现象：双屏模式桌面帧率不高。`dumpsys display` 实测两个内容虚拟屏（Duo inner/cover content）`renderFrameRate 60.0`，supportedModes 仅 `fps=60.0`、`alternativeRefreshRates=[]`（内外物理面板本身 120Hz）。
- 根因：`FixedDualContentHost.create` 建 VirtualDisplay 时从未调 `Surface.setFrameRate`，HyperOS 按无提示默认生成 60Hz 单模式；桌面 DuoSecondaryActivity 跑在该虚拟屏上，Choreographer 只能 60Hz。
- 次因：`FixedDualGpu.draw` 用 `worker.postDelayed(this,8)` 轮询替代 vsync 驱动，非对齐有 4–8ms 抖动；源提到 120 后此周期需换 Choreographer 或缩短。
- **修复（2026-09-17 已装机验证）**，三层：
  1. 虚拟屏：`FixedDualContentHost.create` 改用 API 35 `VirtualDisplayConfig.Builder.setRequestedRefreshRate(120f)`（`setFrameRate` 投票与 `cmd display set-user-preferred` 对虚拟屏都无效，模式表创建时按 60 生成）；minSdk 33 保留旧路径+投票兜底。
  2. 主屏：HyperOS 自适应静态时把主屏降到 60，应用 Choreographer 跟主屏 vsync。overlay 窗口 `preferredRefreshRate=120` HyperOS 不理（代码保留）。有效方案：helper 在首个内容屏创建时 exec `cmd display set-user-preferred-display-mode W H 120 0 false`（W/H 解析 `wm size -d 0`），`close()` 时 clear；已有用户 preference 不动。
  3. GPU 采样：`FixedDualGpu` 轮询 8ms→4ms。
- 验证：虚拟屏 renderFrameRate 120.0；主屏动画时 120.00003、静态回落 60（MIUI 自适应行为，preferred 无法钉死 render rate）；presented==source 无积压。
- 遗留：装机后 helper/Shizuku 绑定恢复可能要等 ~1 分钟（status=idle），耐心或 `am start .DesktopActivity`；`helper-connect` 是无线通路专用（需外部 binder），别拿来诊断 Shizuku 路径。
- **翻页体感 60 复查（2026-09-17）**：注入 `input -d <虚拟屏> swipe` 实测，翻页期间帧产出满 120（gfxinfo 中位帧耗 5ms、jank 1%、面板 ramp 到 120 ≤350ms）。非锁帧/掉帧，体感来自：①面板从 idle 60 爬到 120 有 ≤350ms 延迟，短动画前半段常在 60；②ViewPager 固定时长缓动（总时长与 60 时代相同）。可选优化：反射换 ViewPager Scroller（更短 settle + decelerate 曲线）；手机设置里把刷新率从自适应改成固定 120 可消掉爬升（耗电换体感）。
- **翻页 settle 优化（2026-09-17 已装机）**：`HomePager` 构造时反射替换 `ViewPager.mScroller`（viewpager 1.1.0，字段名已从反编译源确认），settle 时长 `0.55×` 映射并夹 160–320ms（库内原值：慢放 150–200ms、fling 最长 600ms + MAX_SETTLE_DURATION 上限），曲线维持五次 ease-out；ReflectiveOperationException 静默回退库行为。装机冒烟：翻页帧流正常、jank 0.83%。调参入口在 `HomePager` 的 `startScroll` 重写（系数 0.55 / 下限 160 / 上限 320）。
- **翻页 20% 提交阈值（2026-09-17 已装机）**：用户要求拖过 20% 页宽即提交（原库为半页规则；实测反编译确认低速分支阈值其实是 0.4/0.6 truncator、且 `pageOffset` 取自滚动位置）。实现：`HomePager.dispatchTouchEvent` 在 `direction==1` 且 UP、|dx|≥0.2×width 时把 UP 改为 ACTION_CANCEL（库对 CANCEL 只 endDrag 不起回弹），随后自己 `setCurrentItem(current±1,true)` 走平滑滚动；<20% 原样透传（stock 回弹）。调参：0.2f 那处。
- **失败方案（重要）**：曾用"改写 UP 坐标进提交区"——无效且有害：低速分支根本不读 UP 坐标，而 VelocityTracker 会把改写跳变当成真实速度，8% 左滑也会幻影提交。任何"改坐标"类 hack 都要先查 VelocityTracker 污染。
- **测试方法沉淀**：`content=A,B` 的 A/B 是创建完成序不是固定 inner/cover——用 `dumpsys display` viewport 里 "Duo cover content" 字样定位（注意 uniqueId 含逗号，grep 模式别用 `[^,]+`）；`input -d <id> swipe` 的末速度恒定=距离/时长，低于 ViewConfiguration 最小 fling 速度（50dp/s）才会走半页取整分支；页码持久化在 `duo_cover.xml` 的 `&quot;page&quot;:N`（run-as 可读）；物理屏 screencap 对比法会被秒级时钟污染，勿用。
- **双屏 vs 单屏体感差异定位（2026-09-17）**：单屏（DuoHomeActivity 直绘物理屏）不卡、双屏卡 → 差异在管线跳数：触摸 overlay→binder→helper 注入（+1 跳）、渲染虚拟屏→SurfaceTexture→GL→物理窗口合成（+2 跳）。刷新率请求已全部到位（preferred mode + min_refresh_rate=120 + TextureView ALWAYS 投票），MIUI 自适应空闲仍回 60 属系统策略。
- **管线减负（2026-09-17 已装机）**：①`FixedDualContentHost` 触摸注入反射改为静态缓存（原每事件 2 次 getMethod，120Hz 输入流下的抖动源）；②`FixedDualSession.frame` 状态字符串从每帧拼接到 250ms 节流；③`FixedDualOutput.frame` 的 `feedback.cancel()` 改为仅在 blocked 跳变时调用（原每帧 invalidate 强逼 120Hz 遍历）。
- **下一级方案（未做，需用户拍板）**：摊平状态绕过 GL 管线——createVirtualDisplay 直接吃 overlay 窗口 surface（或 SurfaceControl 直挂），折叠过渡才切回 GL；可再砍 1-2 帧延迟，但属于较大重构。

## 微调：侧边返回水滴突起改黑底（2026-09-17）

- `FixedDualGestureFeedback.onDraw` 里水滴填充色 `Color.rgb(92,94,98)`（灰）→ `Color.BLACK`，白箭头保持不变；仅此一处，`GestureNavigationOverlay` 的 EdgeView 是白色描边箭头、无填充底，未动。
- 已离线重建 `projection-lab-debug.apk`（删旧包防假构建，17:56 新产物），待装机。

## 翻页流畅度专题（2026-09-17 晚）

- **用户校准**：只有翻页卡，下拉面板顺；且**非双屏模式也比原生卡** → 翻页瓶颈在页面绘制/合成成本，不在双屏传输层。
- 实测（单屏模式、display 0 直测）：翻页中位帧耗 8ms、90 分位 13ms vs 120Hz 预算 8.3ms → 尾部掉帧；下拉面板中位仅 5ms（区域小）。
- **已装机改动**：
  1. `HomePager` 手势期间（DRAGGING/SETTLING）给页面开 `LAYER_TYPE_HARDWARE`，IDLE 释放（ViewPager 1.1.0 的 scrolling cache 已是空操作，此为等价物）；实测提升小（13→12ms），页面重录不是大头。
  2. 双屏直连模式：`FixedDualGpu` 恒等参数 0.25s 后 `goDirect()`——销毁 EGL 窗口，经新增 AIDL `dualSurface`(=13) 让 `VirtualDisplay.setSurface` 直吃 TextureView，GL 线程转 50ms 轻量监视；折叠效应出现即 `leaveDirect()` 切回。切换竞态加固：`MobileHelper.dualSurface` 改同步 binder + EGL 重连一次重试（此前竞态曾致 Present failed 会话重启）。
- **待用户手测**：①折叠/展开一次验证 direct↔shader 切换无黑闪（远程无法模拟铰链）；②翻页体感对比。
- **下一候选（需用户拍板，涉及视觉取舍）**：翻页手势期间暂停 HomeGlass 每视图合成模糊（HyperOS `setBackgroundBlur`，SF 端每帧全窗计费，gfxinfo 看不到），IDLE 恢复——MiDuo/原生同款"手势中降特效"策略，代价是滑动瞬间玻璃变纯填充色。
- 测量注意：装包后 `content=A,B` 的 id 又会变；双屏 running 时 display 0 是 overlay，`input -d 0` 打的是转发管线，别当单屏测。

## 回滚（2026-09-17 晚，用户反馈"越来越卡"）

- 用户反馈：比最初提刷新率问题时更卡。排查：direct 模式稳定无抖动（计数冻结）、Thermal 0、电池 37.3°C——非热降频、非切换抖动。
- 判定：①120 解锁后内容帧（8-13ms）撑不稳 8.3ms 预算 → 节奏不均（8.3/16.6 交替）比原锁定 60 的稳定节奏更伤观感；②硬件层改动手势起手栅格化全屏两页 + 与每视图模糊冲突，疑似负优化。
- **已回滚装机**：`HomePager` 硬件层（layerize 全撤）；`FixedDualContentHost` 的 min_refresh_rate=120 地板（保留 preferred mode 持有 + TextureView ALWAYS 投票 + 虚拟屏 120 模式 + 直连模式 + settle/20% 提交 + 三项减负）。
- **教训**：帧率上限解锁前先确认内容帧预算能撑住，否则"不稳定的120"比"稳定的60"更卡；LAYER_TYPE_HARDWARE 与 HyperOS setBackgroundBlur 每视图模糊共存会负优化。
- 待用户体感确认回滚版；若仍卡 → 两条路二选一：A. 手势期间降特效（模糊暂停等）真撑 120；B. 内容改回稳定 60（虚拟屏 setRequestedRefreshRate(60)），保节奏一致。

## 翻页控件定位收口（2026-09-17 深夜）

- 用户判断"翻页控件有问题"获数据确认：下拉面板也是跟手交互且顺（同一输入链路）→ 排除输入链路。A/B 实测（单屏 display 0）：**纯回弹动画帧稳定 8ms；跟手拖拽帧 5↔20ms 剧烈跳动（p90=20ms）** → 卡点在拖拽路径，且在"绘制前"段（抓到过单帧 PerformTraversalsStart→DrawStart 13.7ms，即布局/回调段），GPU 段 2-4ms 无辜。
- 双屏 direct 模式下桌面帧反而 5-6ms 健康（虚拟屏 DuoSecondaryActivity 较轻？待复核）。
- 硬件层无效的原因自洽：layers 不阻止 requestLayout/回调，只缓存绘制。
- **下一步（新会话做，需干净上下文）**：Perfetto 抓一次拖拽（sched/input/view/binder tags）定位每帧 20ms 的具体回调；或二分法禁页面内容（先 widgets、再 FolderFan 预览、再文字阴影）对比拖拽帧。嫌疑清单：ViewPager 拖拽中触发的 populate/measure、DuoHomeActivity 80ms deliverCatalog 轮询链、AppWidgetHostView、文本 shadow。
- 环境：双屏已恢复（pref=true、direct 模式 running）；单屏测试曾用 run-as 改 pref + force-stop + settings put 重绑无障碍（SharedPreferences 内存缓存，改文件必须重启进程）。
- framestats 解析注意：HyperOS 输出列序与标准不同且时基混用（ns realtime + uptime），SwapBuffers 常为 -1（Vulkan）；逐段解析需先 dump 一行核对列义。

## 小白条独立占位：底部手势带与应用内容分离（2026-09-18）

- **现象/根因**：应用内底部菜单点不动。物理侧 forwarder 全屏收触摸，先过 `FixedDualGestureFeedback.touch()`，底部 28dp 带内的按下被手势门无条件吞掉（home/最近任务手势起点）；而虚拟显示是整屏高度，第三方应用底栏恰好延伸进这条带 → 带内控件永远收不到点击。
- **修复（用户拍板：给小白条单独占位、应用底部上移）**：`FixedDualGestureFeedback.GESTURE_BAND_DP=28` 常量单源；`FixedDualOutput` 新增 `contentHeight=height−28dp`，TextureView 布局、GL 画布（FixedDualGpu 构造尺寸）、虚拟显示创建（createDualContent）三处统一缩短；底部露出 root 黑底。旋转/触摸逆矩阵、shader 全部不动；**GL bypass（FixedDualGpu `if(false&&...)` 与 goDirect/leaveDirect）零改动——用户明确不再使用 bypass、不许改造**。
- **小白条可见性**：常驻静止淡药丸（α130、92×4dp、带中心 cy=−dp(14)）标示保留带；手势中放大上浮 `cy=−dp(14+4*progress)`；左右水滴返回分支不变。
- **构建**：三件套已过（删 APK 重建、classes3.dex 含 contentHeight、时间戳 09-18 10:59）。compile 任务显示 UP-TO-DATE 但 dex 标记确认产物为新代码。
- **装机验证（11:05 重建同代码包，排查会话执行，勿重复安装）**：install -r 后会话自动恢复（content=cover 1168×1635 / inner 2364×1595，各缩 77px=28dp×2.75 ✓，无黑屏无崩溃）；通知监听 adb 重绑 ✓。冒烟（外屏对照注入）：①静止态条带纯黑+中央白药丸（像素：带左 mean0/std0、药丸区 mean26/std52、面板区 mean184）②带内上滑（y1680→1380）→ `DuoGesture ACTION HOME`+面板收起（窗口 15→8）③把手区上滑（y1560→1260，条带上方）→ **无 DuoGesture 日志**+面板照常收起（15→8）——**面板底部触摸不再被小白条劫持**（另一会话用户报告的"下拉到底后小白条仍触发"同根因，一并解决）。待测：应用内底栏可点、内屏旋转侧药丸位置、折叠过渡。
- 若 28dp 体感不合适，只调 GESTURE_BAND_DP 一处（手势带、占位、药丸同步）；虚拟显示变矮后应用布局整体重排属预期。

## 条带观感：黑底改为应用底色延伸（2026-09-18 续）

- 用户反馈保留带"非得黑色吗"→ 评估两条路：①窗口级真透明（TRANSLUCENT）会露出我们覆盖层底下系统渲染的桌面残影，且全窗混合给 SurfaceFlinger 加一整层合成负担（120Hz 稳定性刚调好），弃；②**shader 底边延伸**：画布恢复整屏高度，虚拟显示仍止于 contentHeight，`at()` 采样按 `contentFraction` 折算+钳制 → 条带显示应用自己最底一行颜色的延伸（全面屏手势导航的标准观感），`composite()` 对条带做 22% 渐进压暗保证白药丸对比度。窗口保持 OPAQUE，零合成开销。
- **shader 三处同步改**（源头 `tools/helpers/LiveMirrorWindowProbe.java` + `tools/sync_dual_renderer.py` 替换模式 + 跑脚本重新生成 `FixedDualGpuProgram.java`，仅 3 行差异；Pyramid 零差异）：uniform `contentFraction`；`at()` toScreen 后 `p.y=min(p.y,cf)/cf`（identity/模糊两条路径统一生效，remap 在 sync 注入的 identity 采样之前）；probe 端固定 1f 保持原渲染。
- 接线：`FixedDualGpu` 构造增 contentHeight（输入 SurfaceTexture 缓冲、金字塔 update 尺寸按输入高；bind/画布仍全高）；`FixedDualOutput` 画布/TextureView 恢复 (width,height)，虚拟显示保持 contentHeight。**GL bypass 依旧零改动**。
- **与并行会话的 ShadeBandView 已合流**（对方在我改完后新增：下拉面板展开时条带铺 PANEL_TINT 并作为下滑关闭手柄，条带触摸先于手势门拦截）。叠加语义：面板关=应用底色延伸+常驻药丸；面板开=PANEL_TINT 实色盖住条带（盖在药丸之上）、条带点/拖=收起面板。编译与共包验证通过。
- 三件套：APK 12:05；classes3.dex 含 contentFraction×2、ShadeBandView×3。**未装机**——待真机看：应用底色延伸是否自然、浅色应用上白药丸可见性（22% 压暗够不够）、面板开合时条带切换、折叠过渡。

## 下拉面板全屏遮盖小白条（2026-09-18，用户手测通过）

- **用户拍板**：面板是更高层级——平时小白条有保留带（上一节），面板全屏时把带整个盖住。实现（11:23 包，已装机）：①`HomeControlPanel.bottomCovered(displayId)`（panel 高度够到内容屏底 28dp 内即 true，PANEL_TINT 放宽为包可见）；②`FixedDualOutput` 新增 `ShadeBandView`（PANEL_TINT 实色、只画条带矩形、与 feedback 同旋转/平移变换，add 序在 feedback 之上=盖住药丸），`frame()` 每帧按 bottomCovered 刷可见性；③两处触摸监听合并为 `deliver()`：面板盖住时带内 DOWN 走 `shadeBandTouch`（点/拖>10dp=收起面板，不喂手势门），否则照旧先过 feedback。GL bypass 零改动。
- **装机**：11:23 包 install -r 成功（当前手机上就是这个包；上节 12:05 共包**仍未装**，装它才能看到应用底色延伸条带）。装包后 content display 连续换代（22→25/26→37/38，一小时后自行稳定），通知监听已重绑。**用户手测确认好用**。
- **调试教训（adb 对照测试被坑）**：`OPEN_SHADE` 调试广播会在 display 0 也开一份面板（旧怪癖），该窗口后加、z 序高于 forwarder——物理屏注入被它直接吃掉、截图也被它污染（之前"平滑渐变"其实是它铺满 display 0 的 GlassFade）。**远程测试一律走真实路径**（注入顶部条下拉，面板只开在内容屏），或先 `dumpsys window` 确认无 display-0 shade。
- 运维：adb 端口又轮换（46791 失效→全端口扫描得 43223）；本连接输出流频繁丢空包，长命令结果落 `/data/local/tmp` 再 cat 才稳。

## 桌面态条带纯透明：透出物理壁纸（2026-09-18 下午，13:44 包已装机验证）

- **用户需求**：小白条保留带在**桌面**时纯透明，直接显示壁纸（应用态仍走上一节的底色延伸+22% 压暗）。
- **实现**：①shader（probe 源 + sync 脚本再生成）增 `bandClear` uniform：`composite()` 里 `bandClear>.5&&strip>0` → 输出 `vec4(0.)`（预乘透明），压暗分支在其后；②`FixedDualGpu` volatile `bandClear` 进重绘变化条件（init 默认 0，probe 端不设=保持原渲染）；③`FixedDualOutput`：输出窗 OPAQUE→**TRANSLUCENT**、TextureView `setOpaque(false)`、root 背景黑→透明（blocked 态黑 View 兜底）、`frame()` 每帧按 `DuoHomeActivity.barePanel(contentId)` 推开关；④防残影：`FixedDualSession.outputOn(displayId)` 新增，`DuoHomeActivity.render()` insets 里物理输出屏实例底部加 `GESTURE_BAND_DP` 让位（防自家 dock 从透明条带里透出）。EGL 本就 RGBA8 无需改。
- **装机验证（13:44 包）**：①桌面条带像素=壁纸级亮度纹理（mean 203-215，旧黑底 mean0），1635 接缝处无跳变；②**翻页判别**：注入翻页后内容区 diff mean 19.4、条带 diff=0——确认看到的是镜像虚拟内容+条带真透明（非整窗透明），且条带像素来自屏后壁纸源；③条带上滑仍 `DuoGesture ACTION HOME`（药丸照常）；④真实路径下拉（顶部条注入）面板照常开合、盖住条带；渲染计数健康（source=141/153 presented 匹配）。
- **架构确认（排查副产品）**：v3 条带方案下 cover 屏的顶部条属 display 0 实例 → 面板开在 **display 0 物理窗**（后加、z 序高于 forwarder），条带遮盖/药丸抑制由面板窗自身完成；inner 屏的可见条带属内容屏（镜像内）→ 面板开在内容屏 → FixedDualOutput 的 ShadeBandView/shadeBandTouch 在该路径生效。两路自洽。**注意：display 0 面板底部 padding 区（把手下方 ~14dp）无响应**，关面板要点把手区（y≈1553-1674）。
- **未测**：应用态条带观感（需内容屏前台有应用；barePanel=false → 底色延伸+压暗，也是上一节遗留待测项）；内屏（display 1）屏后无壁纸窗口，透明条带在内屏等效黑底（与旧观感相同，无回归）。**性能备注**：输出窗变 TRANSLUCENT 后 SF 对该层全窗混合，120Hz 长期稳定性待观察（翻页/滑动若掉帧优先回查这里，回滚=PixelFormat 还原 OPAQUE + setOpaque(true)）。

## 条带三需求：桌面透壁纸/过渡动画/沉浸全屏（2026-09-18 下午）

- **需求1 桌面纯透明（并行会话已实现，本轮确认合流）**：root 窗口 TRANSLUCENT + shader `bandClear`（barePanel 时条带打成全透明露物理壁纸）+ 桌面内容 band padding。本轮把 bandClear 从 0/1 硬切改为浮点，与我的改动共用。
- **需求2 应用条带柔化+动画**：①`at()` 采样改**镜像延续**（条带显示应用底部内容的倒影延伸，接缝处连续，替代生硬的单行涂抹）；②`bandClear` 改浮点 uniform，FixedDualGpu draw 循环逐帧追赶（0.22 步进，~150ms）→ 桌面壁纸↔应用延伸之间**交叉淡化过渡**（premultiplied alpha：`bandA=1-strip*bandClear`）。shader 三处同步（helper+sync 脚本+重生成）。
- **需求3 沉浸式全屏收起（机制+adb 试验通道）**：`setBandReserved(false)` → 药丸隐藏（`setPillVisible`，手势门保持接管条带触摸=原厂行为）+ GL 输入缓冲 `resizeContent` 拉满全高 + `VirtualDisplay.resize`（IHelperHost.aidl 新事务=14：resizeDualContent→MobileHelperHost→FixedDualContentHost.resize，密度表随建随查）。控制：`adb shell content call --uri content://io.github.sixzleo.tabfold.projection.surface --method dual-band --arg "<contentId> <0|1>"`（contentId 见 fixed-dual-session status 的 content=）。**自动检测未做**：外部无法观测第三方应用沉浸态（MiDuo 也没有，其手势层常驻）；后续可选 a) 虚拟显示加真导航栏窗口成为 insets 提供者（WMS 自动随沉浸隐藏，shell 权限待真机验证）；b) dumpsys insets 轮询。恢复保留带后应用菜单避让逻辑照旧。
- 三件套：APK 14:08；classes3.dex bandTarget×2、resizeDualContent×3、dual-band×1。**未装机**：待验证镜面延伸观感、home↔app 交叉淡化、dual-band 0（视频铺满、药丸消失、底边手势仍回桌面）/1（恢复）。resize 期间 buffer 与 display 两路异步可能有 <100ms 拉伸瞬变，属试验期已知。

## 真机验证轮：黑条根因修复 + 透壁纸确认 + 药丸未渲染排查中（2026-09-18 下午）

- **用户报"条带是黑的"，截图取证（screencap -d 4639175068132267009 + PIL 像素分析 + 视觉模型）**：条带 (20,27,31)=PANEL_TINT 均色、无药丸 → ShadeBandView 在面板未开时常驻。**根因**：shadeCover addView 默认 VISIBLE，frame() 的 `if(shade!=shadeUp)` 首帧 false==false 永不触发 → 修复=构造时显式 GONE。已装机验证：黑条消失。
- **透壁纸验证通过**：修复后条带 (202,205,208) 有结构、三次截图间完全静止（mean diff 0.24/255）而上方内容在变、无接缝（seam 0.6）→ 桌面态条带=物理壁纸透出 ✓（root TRANSLUCENT + texture opaque(false) 由并行会话先行铺好）。
- **fade 路径 22% 压暗是死代码已修**（mix(alpha=1) 时 color 不参与）→ helper/tool/产物三处同步，压暗改作用于混合结果。
- **dual-band adb 通道真机走通**：`content call --method dual-band --arg "61,0"` → "OK band collapsed"（arg 用逗号，空格会被远端 shell 拆开）。收起前后条带像素不变属预期（收起后显示虚拟桌面自己的带位壁纸，与透出的物理壁纸同图）。
- **未解：常驻药丸不渲染**。四张截图全屏胶囊检测均无（深色背景上也无）；shot8 手势中途也只有 +13.5 偏移非中心凸起 → 疑 feedback 视图 onDraw 没执行或画在别处。同窗口 ShadeBandView 能画出（视图绘制链路本身没问题）。已加探针：onDraw 首次调用日志（尺寸/pillVisible/progress）+ Output 侧 root.post 日志（attached/尺寸/vis/turn/band）。**instrumented 包已装上手机（掉线前 install Success），重连后跑 am start 恢复 + `adb shell "logcat -d | grep DuoBand"` 取证**。
- **无线 adb 掉线**：重装后设备 offline，旧端口 43223 不通、mdns 无发现（已知问题）→ 等用户提供新端口。
- 本地临时截图已清理。

## 排查：开合动画与 main 的差距（2026-09-18）

- **结论：不是回归 bug，是 2e05dcf 起 fixed dual 成为唯一对用户开放的桌面模式，传统开合动画管线被整体旁路，两条管线开合语义不同。**
- 旁路点 `ProjectionService.updateState()`（ProjectionService.java:434）：`FixedDualSession.active()` → `allowed=false;reset();closeWindow();return;`。`FixedDualSession.enabled()` 默认 true（`duo_dual` pref）；设置页「切屏时机 60°/120°」「悬停」「作用范围」「滑动恢复」已藏进 `if(legacy)` 仅 adb 关闭时可见。
- 切屏语义：main=角度阈值+滞回（>60° 切内屏、<120° 切外屏、FoldHoldGate 3s 悬停、动画仅桌面/锁屏）；分支=`FixedDualPolicy.update()` 只认物理端点（触点闭合→仅外屏、完全展平→仅内屏、**中间角度双屏同亮 BOTH**），端点切换为瞬时黑幕（FixedDualCurtains/FixedDualOutput.black），无阈值无悬停；动效公式未变（ProjectionMath 与 main 零 diff，FixedDualGpu.draw ≈ DesktopProjection.onDraw 平移，两屏各跑一份、作用于虚拟显示）。
- 时序变慢：首启 +8s（nextStart）、拿到 home 角色 +2.5s settle（d8b1523）、重试 10s、熄屏即关会话（ACTION_SCREEN_OFF）→ 感知"动画出现晚/偶尔没有"。
- 未提交改动（band 三需求）另有观感差异：`contentFraction` 压缩全采样、条带镜像延伸+bandClear 交叉淡化、bypass 显式禁用（`if(false&&…)`）。
- 下一步（待用户拍板）：a) 接受新语义；b) adb 关 `duo_dual` 回传统管线对照；c) 若要旧手感，在 FixedDualPolicy 恢复角度阈值/渐变或缩短 settle 时序。

## 倒影雾化 + 三缺陷修复轮（2026-09-18 傍晚，真机迭代 6 轮构建）

- **药丸不渲染根因确认并修复**：探针日志证明 onDraw 正常执行（尺寸/状态全对）但像素不可见 → 该 ROM 合成器上 TextureView 层压过同窗口普通视图（11:05 版药丸可见是因为当时纹理不覆盖条带）。**修复：feedback 视图移入 forwarder 独立窗口**（永远在输出窗口之上）。真机验证 pill lift 17.6→49.5 ✓。
- **面板态黑条修复**：ShadeBandView（实色 PANEL_TINT 延续）整体删除——面板打开时条带改走和应用一致的镜像+雾化（面板底边的倒影），"点条带收面板"手势保留（shadeBandTouch 不变）；药丸在面板态主动隐藏（frame() 统一管 pillVisible=bandReserved&&!shade&&!blocked，setPillVisible 带变更守卫防 120Hz 无效重绘）。
- **通知点击跳转修复（待用户真通知复验）**：原 contentIntent.send + setLaunchDisplayId 对不可变 PendingIntent（如今绝对主流）会忽略显示域 → 落到默认屏（藏在覆盖层后）= 看似无反应。改为 getLaunchIntentForPackage + startActivity(launchDisplayId)（与面板磁贴同模式，SAW 豁免后台启动）。
- **倒影模糊迭代**：5 点十字→9 点双环（稀疏大半径=重影伪影，细节不降反升）→ **13 tap 双半径交替圆盘采样（r=4+30*band px 渐进）**：细节 5.53→2.28→0.81 单调雾化无重影 ✓。**SF 系统雾化（BackgroundBlurDrawable/HomeGlass 反射）在 a11y overlay 窗口上试了两轮（TRANSPARENT/TRANSLUCENT）均不执行**（SF 不处理、且残留 50% 变暗），已撤——shader 圆盘模糊为最终方案。
- **shader 压暗修正在位**：`dim=1-(0.22+0.4*strip)*strip` 渐进作用于混合结果；镜像映射 `y>cf → cf-(y-cf)`。
- adb：无线端口易变，mdns 发现不到时对 192.168.2.166 扫 30000-50000 段可找到（本轮 43003）。`input -d <虚拟屏id>` 注入到不了 a11y overlay，**注入物理屏由 forwarder 转发**才是正确通道（顶部下滑开面板/底部手势都这样测）。
- 全部改动未提交；构建 15:2x 三件套过（marker 30.*band）。

## 排查：开合瞬间左右拉伸/上下压缩——band 改动的金字塔采样畸变（2026-09-18）

- **用户感知**：打开瞬间内容左右拉伸、上下压缩；正常时中轴线（铰链边）一侧应始终对齐屏幕高度。远程 origin/duo-ui-preview（=d8b1523，代码与 HEAD 相同）无此问题 → 差异来自未提交的 band 系列改动。
- **根因**：`FixedDualGpu` 输入缓冲从全高 `height` 改为 `contentHeight`（`setDefaultBufferSize`、`createDualContent`、`pyramid.update(...,width,contentHeight)`），但 shader 的 `screen` uniform 仍是画布 `(width,height)`。`at()` 里 `extent=screen/max(screen)` 的 letterbox 逆映射必须与 `FixedDualGpuPyramid.update` COPY 阶段的 `extent=(w/longest,h/longest)` 同一套尺寸（缓冲尺寸），现在画布/缓冲不一致 → 金字塔采样在失配轴上做**以中心为锚的线性缩放**，系数 `height/contentHeight`（28dp 条带 ≈ 77-84px，约 3.5-4% 宽高比畸变）。铰链边因此不再钉在屏边/中轴。
- **为何只在开合瞬间**：`needsPyramid = opacity≥.001 && tilt≠0 && blurStrength>0`——只有折叠动效进行中的帧走金字塔路径（畸变）；`tilt==0 && crop==0` 静止帧走 native 直采（正确）→ 动画中变形、到位瞬间弹回正确比例，正是"打开的瞬间拉伸"的来源。bypass 已被 `if(false&&…)` 禁用，不影响此分析。
- **为何 probe 没测出**：LiveMirrorWindowProbe 硬编码 `contentFraction=1f`（缓冲=画布），该路径从未在 contentFraction<1 下验证过。
- **顺带发现（三处同步隐患）**：工作区 `FixedDualGpuProgram.java` 生成的圆盘采样是 `r=3.+26.*band`，而 `tools/sync_dual_renderer.py` 里是 `r=4.+30.*band`——生成物与脚本不同步，下次重生成会悄悄改观感。
- **修复方向（未实施）**：`screen` 改为源缓冲尺寸 `(width,contentHeight)`，并在 `resizeContent()`（dual-band 收起/恢复）里同步更新；注意 `at()` 的 band 采样支路 `px.y=1./(screen.y*contentFraction)` 目前按画布语义写死，改 screen 语义后此处须改为 `1./screen.y`，两处必须一起改。sigma 的 `canonicalSize` 也会随 screen 一致化为缓冲尺寸（与 probe 语义一致）。

## 倒影让位于开合动画（2026-09-18 晚）

- 用户反馈：倒影盖在开合遮罩动画上、且倒影了动画内容——动画应是最上层。两层修复：
  1. **遮罩面板（下拉面板）开合期间**：frame() 的 bandClear 条件从 barePanel 扩为 `clear||shade`（bottomCovered）——面板挂载期间（含滑入滑出全程，按高度判定不受 translation 影响）条带整体透壁纸，面板成为唯一顶层视觉，不再镜像面板内容；动画结束 destroy 后交叉淡化回应用镜像。
  2. **折叠开合动画期间**：shader 新增 `bandMix` uniform（0..1，draw 循环按 `tilt==0&&crop==0` 置目标、0.3 步进追赶），`cf=mix(1,contentFraction,bandMix)`——折叠效果激活时条带并入纸张本体（底部行延伸，无镜像/无压暗/无 punch），动画结束条带淡入回归。
- 真机验证：面板开（shade window 存在）时条带 RGB 与桌面态完全一致=(104.7,109.9,113.0)=透出壁纸 ✓（面板底镜像值应为 ~140 已不出现）。折叠路径为代码审查+下次物理开合确认。
- 运维：注入开面板的下滑要慢（400ms 全程）且确认 `dumpsys window windows | grep -c "Duo home shade"`>0 再截图——快速下滑可能不开面板，之前两轮误测由此而来。
- 三件套：bandMix×3 in classes3.dex，APK 15:5x，install -r 完成。

## 修复：开合瞬间左右拉伸/上下压缩——金字塔采样错位（2026-09-18 晚）

- **用户补充定位**：仅双屏同开（BOTH 中间角度段）可见，端点单屏正常——与根因吻合：端点处折叠动效不活跃（展平 tilt=0 走 native 直采、闭合透明度 0），中间角度段金字塔模糊路径全程活跃。
- **两个叠加错位**：①`screen` uniform 传画布 `(width,height)` 而金字塔按缓冲 `(width,contentHeight)` 构建 → at() 的 extent 逆映射在失配轴上以中心为锚缩放 `height/contentHeight`（~3.5-4%）；②上一节 bandMix 的 `cf=mix(1,contentFraction,bandMix)` 折叠时把整体映射缩向 1 → 内容区纵向拉伸同比例（两错近似抵消成"整体缩放"而非宽高比畸变，掩盖了症状）。
- **修复（驱动侧 + 三处同步）**：①`FixedDualGpu` `screen` 改传缓冲 `(width,contentHeight)`，`resizeContent()` 同步刷新（sigma/pixelScale 缩放随之一致为缓冲基准）；②`at()`：`cf` 恒为 `contentFraction`，`yS=y>cf?cf+(cf-y)*bandMix:y`——bandMix 只在**条带内**选镜像(1)/底边行延伸(0)，内容区画布→缓冲映射恒 1:1（上节"折叠时条带并入纸张"的正确实现，不再连带拉伸内容区；静止态与原公式代数等价）；③圆盘采样 `px.y` 改 `1./screen.y`（缓冲 texel）。composite() 的 strip/bandMix 压暗与 punch 门控保留不变。
- probe FOLD_FRAG + sync_dual_renderer.py 已同步；重生成校验：FixedDualGpuProgram 仅含预期两处 delta（at() 映射 + px），FixedDualGpuPyramid 字节不变；probe 在 contentFraction=1 下行为不变。上节记录的 r=3+26/4+30 不同步已被并行轮次自行对齐（均 4+30）。
- **验证**：`assembleDebug`（init 脚本+离线）过；dex 标记 `yS/max(cf`×1、旧 `cf-(y-cf):y)`×0、旧 `1./(screen.y*max`×0、时间戳 15:37（假构建三件套之标记法）。**未装机**——手机 offline 等端口，且已装的 15:5x bandMix 包不含本修复；装机后预期：双屏同开折叠全程内容区 1:1（中轴线一侧钉住屏幕高度），条带动画中底边延伸、静止后淡入镜像。

## 保留带收窄 28dp→20dp（2026-09-18 晚）

- 用户反馈条带占屏过多。GESTURE_BAND_DP 28→20（单源常量：显示高度 contentHeight、手势门、桌面 padding、shadeBandTouch 全部派生自动跟随）；药丸位置公式从硬编码 dp(14+4p) 改为 dp(GESTURE_BAND_DP*.5f+2p) 随带高居中。
- 真机验证：内容平坦至 y≈1655，过渡起于 1657=1712−55px ✓（20dp×2.75）；过渡为平滑渐变无硬线；药丸新位置 lift=24.9 清晰。屏幕多得 22px 内容高度。
- 若还要更窄（如 16dp）只改常量一处。

## 方案转向：放弃条带倒影，任务显示铺满全高（2026-09-18 晚）

- **用户拍板**："别搞什么模糊倒影了，直接就透明，不要取色了，让应用自己的底面留多一点空间"——放弃镜像/圆盘采样/压暗/壁纸 punch 的整条取色链路，任务显示（虚拟显示+GL 缓冲）**直接铺满整屏**，应用自己的底面填满原条带区，即已验证的 dual-band 收起态常态化。
- **实现**：①`FixedDualOutput.contentHeight=height`（不再扣 GESTURE_BAND_DP），新增 `bandTop=height−bandPx` 仅作触摸区；②`shadeBandTouch` 判定改用 bandTop（"点条带收面板"手势保留）；③`DuoHomeActivity` insets 的条带避让从"仅物理输出屏实例（outputOn）"扩展为"会话激活即全部实例"（内容屏全高后 dock 需让位）；④shader 侧零改动——contentFraction 恒 1，at()/composite() 的镜像/圆盘/压暗/bandClear 分支全部代数失效（保留代码不删，避免与并行轮次冲突）；⑤dual-band adb 钩子退化为药丸开关（resize 两态同值）。
- **联动确认**：并行轮次 15:45 的 GESTURE_BAND_DP=20 已包含在 15:57 构建中（bandTop/桌面避让自动跟随）；手机 15:57:24 装的即此包（含 20dp + 全高 + 上节的采样错位修复）。
- **代价（用户已知）**：应用底部 bandPx 区域可见但触摸仍归手势门（对齐 HyperOS 全面屏平台行为）；桌面 dock 上移 bandPx（原物理屏让位逻辑推广）；桌面条带从"透物理壁纸"变为"透自身壁纸"（同一张图，观感应无差）。
- **验证状态**：构建过；装机过；装后手机停在锁屏（KeyguardDrawComplete=false）→ 会话 idle 属设计行为，解锁后自动起，待用户解锁真机开合验证（全高应用、无倒影、动画 1:1）。

## 通知面板底部留白缩小（2026-09-18 晚）

- 用户反馈：下拉通知面板最底部空白偏多。HomeControlPanel 两处常量缩小：列表下方 grab-zone `listSize.bottomMargin` 52→36dp、底部关闭手势条高度 52→40dp（面板底部 padding `max(safe.bottom,14dp)` 不动），总留白约 118→90dp。
- 三件套验证：旧 APK 删除重建、HomeControlPanel*.class 15:59 重编译、APK 时间戳 15:59；16:00 `install -r` 成功（同签名保数据）。
- 冒烟：装后无障碍绑定在、进程起（ps 可见，pidof 不可见属正常）、fixed-dual 会话 running；OPEN_SHADE 广播后 `dumpsys window` 出现 "Duo home shade" 窗口，两虚拟显示 HOME 后归零——面板开/关正常。验证时用户正在主屏用聊天应用，未受影响（面板只在虚拟显示）。
- 底部留白视觉效果待用户下次下拉确认；若仍偏多，继续降这两处即可（再各减 8-12dp）。

## 药丸按背景反色：GL 亮度探针（2026-09-18 晚）

- **用户需求**：小白条白色透明，白底页面看不清——按背景亮度反色。
- **实现**：①`FixedDualGpu` 新增 `setLuminanceProbe(x,y,w,h,sink)`——draw 循环里 `glReadPixels`（drawArrays 之后、eglSwapBuffers 之前）读药丸区域 128×2 像素，仅在有新内容帧时且 ≥300ms 限频，Rec.709 亮度均值、alpha<40 像素跳过，结果 main.post 回主线程；探针坐标画布空间 y=bandPx/2（GL row 0=画布底=药丸带，turn 无关：feedback 与 texture 同变换）。②`FixedDualGestureFeedback.setPillLuminance(lum)` 滞回切换（>0.70 变黑、<0.55 回白），`onDraw` 药丸色 `pillDark?BLACK:WHITE`（侧滑箭头的白色不动）。③`FixedDualOutput.onSurfaceTextureAvailable` 接线 `gpu.setLuminanceProbe(width/2-64, bandPx/2, 128,2, feedback::setPillLuminance)`；双屏各自探各自条带。
- **成本**：每 ≥300ms 一次 1KB 读回 + GL 同步，在独立 GL 线程，静态页不触发（无新帧不重绘不探）。
- **验证**：构建过（dex 标记 setLuminanceProbe/setPillLuminance ×1）；16:07:47 装机（含并行轮次 15:59 的面板留白改动，未被覆盖）。装机时手机锁屏 → 会话 idle 属设计，解锁后生效；**反色效果待用户白底页面实测**。

## 崩溃排查：glReadPixels 探针损坏堆 → 全删，反色改 PixelCopy（2026-09-18 晚）

- **用户报障**：固定双屏几秒后闪退回单屏、无障碍突然掉。取证：**3 个 tombstone 全是我方进程的 SIGSEGV，位置全在 ART ConcurrentCopying GC 扫堆（HeapTaskDaemon）**=堆损坏晚期爆炸；时间 16:08:24/16:15:39/16:16:43，**全部在 16:07:47（glReadPixels 探针版）装机之后**，而 15:57 全高构建跑了 ~10 分钟零 tombstone。更细的相关性：16:15:38.9 输出挂载日志后 0.8s 即死——正是首次探针触发点（新帧+300ms）。16:07 构建里唯一新增的原生内存写入=探针的 `glReadPixels`（从 EGL 窗口表面回读到 1KB direct buffer）。
- **根因判定**：GL 窗口表面回读在驱动侧踩雷（疑似驱动按整表面尺寸写回，越界砸堆；GC 延迟爆出）。**该路径禁止复活**——注释已写进代码（FixedDualOutput pillProbe 上方）。
- **修复**：①FixedDualGpu 的探针字段/setter/draw 循环读回**全部删除**（dex 标记 glReadPixels×0）；②反色改走 **PixelCopy(Surface, strip, bitmap, callback)** 异步系统拷贝（FixedDualOutput.pillProbe，400ms 循环、in-flight 防重入、close() 停止；srcRect 画布空间底部条带）；③翻转时打 `DuoBand pill dark/light lum=` 日志便于远程对照 screencap 校验采样方向（PixelCopy 对 Surface 的 srcRect Y 朝向未文档化，若反了改为 top=bandPx/2）。
- **弯路记录**：①PixelCopy 没有 TextureView 重载（只有 SurfaceView/Surface/Window），用 `surface` 字段；②multi-catch RuntimeException|IllegalArgumentException 父子类编译错；③一次构建失败后 `install -r` 装了旧包（假构建陷阱重现）——判定以 BUILD SUCCESSFUL + dex 标记 + 时间戳三件套为准。
- **附带发现**：16:12:44 ActivityManager `Force stopping ... SwipeUpClean`——MIUI 上划清理也会杀我们 + 解绑无障碍（与崩溃无关的另一条掉线路径）；建议用户最近任务里锁定本应用。
- **装机**：16:29:25（PixelCopy 版）。待用户解锁验证：几分钟无崩溃（此前 1-37s 必崩）+ 白底页药丸变黑；之后远程 grep `DuoBand pill` 对照 screencap 确认采样方向。

## 崩溃修复验证通过 + 药丸再下沉（2026-09-18 晚）

- **PixelCopy 版验证**：tombstone 维持 3 个零新增（此前 1-37s 必崩，16:29 版运行数分钟稳定）；`DuoBand pill` 日志真实翻转（lum 0.537→light、0.803→dark），采样方向正确——崩溃根因（glReadPixels 回读）与反色功能双双确认。
- **用户微调**：药丸仍显眼 → 静止中心从"带内居中（离底 GESTURE_BAND_DP/2=10dp）"下沉到**离底 6dp**（新常量 `FixedDualGestureFeedback.PILL_BOTTOM_DP=6f`，onDraw cy 公式与 PixelCopy 采样条带同源跟随）；手势中的抬升 `2*progress` 保留。药丸底边距屏底约 4dp。
- 16:33:01 装机（dex 标记 PILL_BOTTOM_DP×1）；装后手机锁屏 → 会话 idle 属设计，解锁生效。
