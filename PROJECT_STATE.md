# PROJECT_STATE

- 日期：2026-09-17（凌晨）
- 分支：duo-ui-preview
- 本阶段：**桌面原生布局迁移引擎端到端打通**（图标/文件夹/dock 完整迁移 ✓，widget 差授权确认流）；上一晚的 shade 全套改动与迁移代码**均未提交 git**

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
