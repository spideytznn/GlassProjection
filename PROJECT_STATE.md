# PROJECT_STATE

- 日期：2026-09-16（晚）
- 分支：duo-ui-preview
- 本阶段：①状态栏死局调查（结案）②自建状态栏+双下拉面板 v1（已装机，待用户手测）③桌面 UI 重构（代码完成，冒烟测试挂起）

## 当前目标

1. 自建下拉与状态面板替代死掉的 MIUI shade：v1 已装机，待用户手测样式与交互。
2. 桌面 UI 重构（去重 + 文件夹/小组件重设计）已完成构建，冒烟测试未跑。

## 自建面板 v1（已装机 18:4x，APK=projection-lab/build/outputs/apk/debug/）

- **DuoNotifications**（新，已注册清单+已授权）：NotificationListenerService；静态 snapshot/observe(多观察者)/cancel/cancelAll；过滤自身与组摘要，倒序，cap 30。
- **HomeStatusBar**（重写）：MIUI 布局，左时间+通知数圆角徽标，右 Wi-Fi 弧线+电池；磨砂底（35% 黑 scrim + HomeGlass 模糊）；**触摸按 x 分左右**：左半→通知中心，右半→控制中心（下拉>16dp 或点击触发）。
- **HomeControlPanel**（重写，双模式 boolean control）：
  - control=true（右拉）：日期+电池行 → 亮度条 → 4 磁贴（手电筒/自动旋转/勿扰/深色模式）。
  - control=false（左拉）：通知卡片列表（图标 38dp + 应用名/时间 + 标题 + 两行正文；点击打开应用；长按删除）+ 全部清除。
  - 磁贴：圆角 22 方块 + TileIcon 自绘线性图标（torch/rotate/dnd/dark）+ 11sp 标签；**开启态图标/标签变 MIUI 蓝 0xff3c7bfa**（HyperOS 语义），底 0x26ffffff。
- **ProjectionService**：新增 updateShadeBar/removeShadeBar 静态管理（shadeBars/shadeWindows/shadeHosts 按 displayId）。状态栏悬浮窗类型=**TYPE_ACCESSIBILITY_OVERLAY**，由服务进程创建（Activity 无 token，会 BadTokenException）；高度=该屏 statusBars inset（兜底 28dp）；磨砂透明。
- **DuoHomeActivity**：onResume→updateShadeBar(this)；onPause/onDestroy→removeShadeBar(this)；**onWindowFocusChanged(true) 重试**（装机后进程重启时 Home 早于无障碍服务重连，首次会静默失败）；openShade(side) 包级可见。
- **FixedDualOutput**：转发窗恢复全屏（顶部触摸进虚拟屏交给自绘状态栏）。
- 清单：+SYSTEM_ALERT_WINDOW；+DuoNotifications 服务。已授权：通知监听、DND、WRITE_SETTINGS(appops)、SYSTEM_ALERT_WINDOW(appops)。
- 实证截图：bar6.png=单条自绘状态栏（左 18:43 / 右 wifi+电池96）盖住 MIUI 栏 ✓。

## 状态栏死局调查（结案，勿重复排查）

- 固定双屏下 MIUI shade 完全失效：虚拟屏 11/12 只有 StatusBar 没有NotificationShade；唯一 NotificationShade 在物理 display 0 且休眠（cmd statusbar expand 无渲染）；真实下拉手势会进虚拟屏、虚拟屏状态栏图标有动画但无面板。
- **窗口层级实证（display 0）**：应用 Activity=21000 < TYPE_APPLICATION_OVERLAY=111000 < StatusBar(2000)=151000 < NotificationShade(2040)=171000 < NavigationBar(2019)=241000 < **TYPE_ACCESSIBILITY_OVERLAY(2032)=311000**。盖状态栏必须用无障碍 overlay；其 token 只有无障碍服务进程持有。
- InsetsController.hide/FLAG_FULLSCREEN 在 HyperOS 虚拟屏上无法隐藏状态栏（focus 重试亦然）。
- 重试模式：装机后进程重启，Home onResume 早于无障碍服务重连（instance==null）→ onWindowFocusChanged(true) 重试 updateShadeBar。
- inner(旋转屏)的状态栏条映射到物理侧边，物理 insets 查询返回 0——旋转面板需经 placement 矩阵从虚拟屏坐标映射。

## 构建环境

- `JAVA_HOME=C:/vsCodeProject/tools/jdk-17.0.20.1+1 /c/vsCodeProject/tools/gradle-8.7/bin/gradle.bat -I tools/mirror-init.gradle.kts :projection-lab:assembleDebug`
- SDK 手工铺设于 C:/vsCodeProject/tools/android-sdk；curl 一律 `--ssl-no-revoke`(+`-L`)；Java 到 dl.google.com 被 reset（镜像 init 解决）。
- adb=C:/vsCodeProject/tools/platform-tools/adb.exe；**connect 端口会轮换**（当前 44699）；截图=`adb shell "screencap -p -d <id> /data/local/tmp/x.png"`（引号）+ `MSYS_NO_PATHCONV=1 adb pull`；logcat 中文错误是 GBK。

## 下一步

1. **用户手测 v1**：HOME 后若弹选择框→选"玻璃桌面→总是"（重装会清偏好）；验证单状态栏、左拉通知中心/右拉控制中心、磁贴（手电筒/深色）、亮度条、通知卡片点击/长按删/全部清除、通知数徽标。
2. 按反馈迭代样式（澎湃OS4 柔光玻璃：控制/通知中心背景带壁纸色光影过渡——现只有暗色毛玻璃）。
3. 挂起：UI 重构冒烟测试（connectedDebugAndroidTest，跳过 HomeWidgetBindingSmoke）+ 文件夹新预览截图（拖拽建文件夹→解散还原）。
4. v2 候选：控制中心横滑切换通知中心；Wi-Fi/蓝牙磁贴（helper 需加通用 exec 通道）；内屏（旋转屏）顶部条映射。
5. 本阶段全部 UI 改动尚未提交 git。

## 失败方案 / 教训

- Activity 上下文加 TYPE_ACCESSIBILITY_OVERLAY → BadTokenException（token null）；必须由无障碍服务创建。
- TYPE_APPLICATION_OVERLAY(111000) 压不过系统状态栏(151000)——"覆盖系统栏"只能用 TYPE_ACCESSIBILITY_OVERLAY。
- `cmd statusbar expand-notifications` 对固定双屏拓扑静默无效，不能作为 shade 存活判据。
- 多 catch 不能有父子关系；PendingIntent.send/getApplicationIcon 抛受检异常 catch Exception；Settings.Secure.UI_NIGHT_MODE 新 SDK 不公开→UiModeManager.setNightMode。
- 管道吃退出码→`> log 2>&1; echo EXIT=$?`；Git Bash 转换 /data 路径→MSYS_NO_PATHCONV=1；javac 中文输出 GBK→python decode('gbk')。
- FolderFanTest 断言曾写错；JDK 下载需 -L；sdkmanager/wrapper 均被网络重置→手工铺 SDK。
