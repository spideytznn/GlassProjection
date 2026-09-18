# PROJECT_STATE

- 日期：2026-09-17（上午）
- 分支：duo-ui-preview
- 本阶段：**固定双屏默认化（隐藏而非删除传统投影）+ 桌面预览双重前置校验 + MiDuo 参考实现搬运**；已装机验证 FixedDualSession running；全部改动未提交 git

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
