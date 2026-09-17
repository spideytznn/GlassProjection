# MiDuo (com.jake.duolauncher v1.0.5) 小组件与文件夹实现分析

- 来源：`参考/MiDuo-1.0.5.apk`，jadx 反编译输出：第一轮 `参考/miduo-decompiled/`，第二轮（`--show-bad-code`，找回全部 skipped 方法）`参考/miduo-simple/`，**以 miduo-simple 为准**
- 分析日期：2026-09-17（子智能体两轮并行调查，行号以 miduo-simple 产物为准）
- 总体：minSdk 31 / target 36，权限含 `BIND_APPWIDGET`、`WRITE_SECURE_SETTINGS`；桌面本体 **Jetpack Compose**，无 Room/SQLite，**全部状态存在 SharedPreferences("launcher") 的 "state" 键一个大 JSON（schema=8）**，带 state_v2…v7_backup 版本迁移备份。

---

## 一、AppWidget 小组件

### 1. Host 与绑定

- Host：`Z1/h9.java:14` `super(mainActivity, 1024)`（hostId=1024）；`onCreateView` 返回自定义 HostView `i9`；`onProvidersChanged` 刷新 picker。总控制器 `C1294i8`（`Z1/C1294i8.java:97` 构造 host）。`MainActivity.onStart/onStop` → `startListening/stopListening`（MainActivity.java:508/565）。
- 绑定流程 `C1294i8.a(...)`（C1294i8.java:162-212）：`allocateAppWidgetId` → `bindAppWidgetIdIfAllowed`（带 profile + options）→ 成功且有 configure 则 `startAppWidgetConfigureActivityForResult(701)`；失败则发 `ACTION_APPWIDGET_BIND` 系统确认对话框（和本项目 HomeWidgets.java 同一套路，**并未利用 BIND_APPWIDGET 权限静默绑定**）。
- 确认框结果处理 `Y/C0288h.java:63-79`：RESULT_OK 且 id 匹配 → 进入 configure；否则删除 id + 报错。
- **中断恢复**：SharedPreferences `widget_pending`（pendingWidget / pendingWidgetPlacement / pendingWidgetProvider / pendingWidgetProfileSerial / pendingWidgetStatus，`q()` C1294i8.java:494-512）+ `onSaveInstanceState`。恢复/换机静默重绑 `b(slot,size,z3)`（C1294i8.java:214-245）：allocate + bindIfAllowed + 写回 placement，失败 deleteAppWidgetId。configure 判定用 `widgetFeatures & 4 (reconfigurable) / & 1 (configuration_optional)`。
- **孤儿 id 回收** `t()`（C1294i8.java:520-548）：GC 不在数据模型集合里的 appWidgetId。←→ 本项目 HomeWidgets 构造函数同款清理。

### 2. 数据模型与持久化

- 条目 `Z1/I8.java`：`WidgetPlacement(slot, id=appWidgetId, page, column, row, spanX, spanY)`，序列化为逗号串。
- 持久化 `Z1/U2.java:536-580`：launcher/state JSON 内 `widgets` 数组 `{slot,id,page,column,row,spanX,spanY}`。
- **负数 slot = 内置伪 widget**（`Z1/AbstractC1252e6.java:15`）：-6 日历、-7 墨迹天气、-8 飞书、-10 地图、-2/-3 时钟/日期（自绘，不走系统 widget）。这是绕开"系统时钟/天气 provider 私有"的方案。

### 3. span 计算与测量

- `C1294i8.w(provider, grid)`（:564-568）把 minWidth/minHeight/minResize*/maxResize*/targetCell* 换算 dp → `L8 WidgetProviderSizing` → **核心 `N/t0.java:1595-1673 f0()`**：
  - 优先 targetCellWidth/Height（在网格范围内时）；
  - 否则 `U(dp,cellDp,gapDp)=ceil((dp+gap)/cellDp-ε)`（:615-624）；
  - 按 resizeMode(&1 水平/&2 垂直) clamp 到 minResize/maxResize span；preferred<1 格时递归缩放网格。
  - 产出 `P8 WidgetSpanConstraints(preferred/minimum/maximum O8, canResizeH/V, minimumFitsGrid)`。
- 行高换算 `t0.l(grid,rows)`（:2170）：前 2 行 topRowHeightDp、之后 appRowHeightDp。options bundle `t0.u(w,h)`（:2346-2362）：appWidgetMin/MaxWidth/Min/MaxHeight + `appWidgetSizes` SizeF 列表。
- **HostView 测量 `Z1/C1387s1.java`**（FrameLayout 包装）：onMeasure 以 provider min 尺寸为下限，先 UNSPECIFIED 再 EXACTLY，`scale=min(w/mw,h/mh,1)`、pivot(0,0) 缩放 + onLayout 居中平移；含 AdapterView/ScrollView 的 widget 跳过强制重测（`t0.B()` :217-236）。特例 hack：墨迹天气 4x2 强制 minHeight≥200dp（`Z1/D3.java:59-61`）。
- 尺寸更新：`Z1/i9.java` setAppWidget/onSizeChanged/onAttachedToWindow 时 post 比对 `getAppWidgetOptions`，变了才 `updateAppWidgetOptions(bundle)`（**全程不用旧 API updateAppWidgetSize**）。

### 4. Picker

- 底部弹层 `Z1/G8.java`（tag "visual-widget-picker"）：搜索框（按 appLabel/providerLabel/description/pkg 过滤）、Personal/Work profile 切换、LazyColumn。
- provider 列表：全源码**无** `getInstalledProviders()`，只有 `getInstalledProvidersForProfile(Process.myUserHandle())`（`C1294i8.java:365`）、`getInstalledProvidersForPackage`（`:459`，备份恢复用）、`getInstalledProvidersForProfile(工作user)`（`Z1/L5.java:74`，widget 替换场景）。B5（produceState + Dispatchers.IO）→ `A5.java:48-101` 映射成 WidgetCatalogEntry；**排序确定**：先 appLabel.lowercase 再 providerLabel.lowercase（`A5.java:100`）。B5 的构造调用点两轮均未找到（picker 全量查询的确切调用行存疑，推断为 getInstalledProvidersForProfile）。
- **预览图三级降级 `Z1/D8.java:47-104 n()`**：① API≥35 且 generatedPreviewCategories&1 → `getWidgetPreview(provider, profile, 1)`（RemoteViews，经包装后同样 Compose AndroidView 渲染）；② previewLayout 构造 RemoteViews；③ `loadPreviewImage(context, densityDpi)`；④ `loadIcon`；再降级 文本 label + "w × h"。
- 无空位时禁用并提示 "No room here"（Y1.java:278）；picker 条目支持直接拖出添加（G8.d onDragStart/onDrop）。

### 5. 增删改查

- 长按菜单 `Z1/C1221b5.java`：Reconfigure（`startAppWidgetConfigureActivityForResult(702)`，`C1294i8.p()` :465-492）、应用信息、移除（从网格移除 placement + `t()` 回收 id）。
- 拖动换位：拖拽状态机 `Z1/C1448y2`；落点 `Z1/A3.java:60-72` `A2.p(grid, I8.a(...新位置, mask=31))` + `u22.j()` 持久化。编辑态被拖项 alpha 0.3。
- 点击已放置 widget：`Z1/R5.java` → `b(slot,size,false)` 按当前尺寸重新 bind（刷新 options）。

### 6. Compose 嵌入与手势共存（关键机制）

- 桌面全 Compose；widget 经 **`AndroidView`** 嵌入：`Z1/T5.java:50`，factory=`D3.java:51-62`：`host.createView(...)` → 包进 `C1387s1`（测量/缩放/居中包装）。
- **触摸冲突解决 `Z1/i9.dispatchTouchEvent`（:25-43）**：ACTION_DOWN 时递归命中测试是否点在 widget 内部可交互子 View（`AbstractC0679I.a0`，:783-809），命中则 `requestDisallowInterceptTouchEvent(true)` 阻止外层 Compose Pager/滚动抢事件；UP/CANCEL/分离时恢复。

---

## 二、文件夹 Folder

### 1. 数据模型与持久化

- `Z1/S1.java:15` `FolderEntry(id, title, appIds:List<String>)`——成员存 **appId**（应用目录 id，非 ComponentName）。
- `Z1/C2.java:27` `HomeLayout(slots, dock, widgetPlacements, folders, widgetRestores, leadingSlots)`；slots 索引编码 `page*24+cell`（`Z1/A2.java:113-129`）；**文件夹在格位里以 `"folder:<uuid>"` 字符串占位**（O6.java:203；`AbstractC0679I.W()/X()` :703-726 校验前缀+UUID）。
- 持久化同上 launcher/state JSON：`folders:[{id,title,apps:[appId...]}]`（U2.java:549-551）。导出 "duo-launcher-layout.json" 同构（t0.r() :2241-2334）。
- **首次初始化预置自动分类文件夹** `Z1/O6.java:26-29`：10 个分类（AI、影音视听…），固定 UUID（"22000000-0000-…"），按包名候选列表匹配已装应用，≥2 个才建夹入位。

### 2. 创建交互：拖拽重叠合并（无菜单新建）

- 热区几何 `AbstractC0679I.R()`（:579-609）：`宽=min(cellW*0.82, 1.35*iconSize)；高=min(cellH*0.78, 1.18*iconSize)`，格中心水平居中、自格顶起算，指针落入才算。格内是文件夹→返回 folder 目标（加入）；是普通 app→普通落点（触发建夹）。
- 两 app 合并建夹 `AbstractC0679I.I()`（:421-454）：校验占位/成员唯一性/槽位可用 → 移除两个 app → 文件夹入槽 → `S1(folder, title, [first, second])`。
- 加入现有夹：`U2.h(folderId,appId)`（:175-191）→ `AbstractC0679I.x()`（:2804-2844，从旧位置/旧夹移出后 append）。

### 3. 打开后的 UI：玻璃浮层 + 分页 3×3 网格

- 打开状态由 Home 层持有（`Z1/E5.java:429-546`；`Z1/G5.java:180-184` 点击/落点设置 openedFolderId）。
- 面板 `N/Z.java:3462-3608 s()`：Popup 容器 + scrim(alpha 0.07) 点击关闭。
- 动画 `Z1/C1288i2.java:114`：`AnimatedVisibility` enter=fadeIn+slideIn(spring 180)、exit spring 140；面板套 **"DuoGlass"** 样式体系（`AbstractC1368q2.a()`，按角色枚举 Control/SettingsRow/Dock/Card/Elevated/**Folder**/Screen/Floating/WidgetFrame 取形状/模糊/描边/阴影）。
- 尺寸 `min(288dp, constraints-32dp)`（C1288i2.java:81-82）；**每页 9 个 = 3×3 LazyVerticalGrid**，页数=(n-1)/9+1，`HorizontalPager`（tag "folder-pager"）+ 页点指示器（选中 alpha 0.82/未选 0.28）。头部文件夹名，重命名时变 TextField。

### 4. 图标预览（第二轮补译确认）

- composable `N/Z.java:6856 t(S1, Map apps, iconSize, ...)`：**标准 2×2 四角拼接，取前 4 个成员**（`take(appIds,4)`，:6965）；第 i 个对齐 0→TopStart、1→TopEnd、2→BottomStart、3→BottomEnd。
- 每个成员 icon：`size(0.38f × iconSize)`（38%）+ padding 5 + 圆角 `b(6)`，Bitmap 直接取自入参应用表（**无独立缓存/磁盘缓存**）；任一成员有通知角标时左上画角标点（:6988-6997）；紧凑模式画标题。
- 背景：DuoGlass **Control 角色** + 圆角 `0.24f × iconSize`（:6946）；注册拖放热区 key `"folder-"+id`（:6923）。

### 5. 重命名/增删/解散

- 重命名：面板头部点击→TextField，提交 `U2.x(folderId,title)`（:675-690，trim 非空才改）。
- 移出成员：`U2.v(folderId,appId,target)`（:621-626）→ `AbstractC0679I.k0()`（反编译失败）；批量 `g0()`（:1298-1324）。
- 显式解散菜单 `Z1/C1450y4.java:59-134`：删格位 folder id → 从 folders 剔除 → 成员逐个放回桌面（第 1 个占原格位，其余找空位）→ 持久化。
- **"剩 1 个成员自动解散"已确认存在**：`AbstractC0679I.k0()`（miduo-simple `N/AbstractC0679I.java:2412`）——移出后剩 0 个：清格 + 删 folders 条目；**剩 1 个：最后一个 app 放回文件夹原格位（`c22.h(app, folder槽位)`）并删除该 S1**（:2450-2458）；剩 ≥2 只更新 appIds。移出目标仅支持 Z0 格/Remove；X0(dock)/Y0(其它夹) 不生效。

### 6. 图标加载

- `LauncherApps` + `R2 extends LauncherApps.Callback`（onPackageAdded/Changed/Removed/… 防抖重扫，含 density/locale/uiMode key）→ 协程扫描（S2.n 反编译失败）。
- 图标栅格化 `N/t0.java:2131-2168 k()`：AdaptiveIconDrawable 画 **144×144 圆角 34px** Bitmap；存 AppEntry 内存缓存。
- 启动：`MainActivity.u()`（:608-662）`LauncherApps.startMainActivity` + `ActivityOptions.makeScaleUpAnimation`，失败回退 `getLaunchIntentForPackage`。

---

## 三、交互与基础设施（第二轮 --show-bad-code 补译）

### 1. 网格几何与移动语义 `Z1/A2.java`

- 每页 24 格 = **6 行 × 4 列**（`h()`=flat/24 取页、`g()`=mod 24 取格，:642-648）；flat 索引连续，推移自然跨页；dock 是独立列表（X0），索引 ≤ -100000 是 leadingSlots（页 -1）。
- **桌面格移动是"挤开"不是交换**（`d()`，:89）：目标空→直接放；目标被占→取源↔目标区间内非 widget 占用格，区间元素向源方向顺移一格再放入；源不在桌面（如从 dock/文件夹来）→ 从目标格向后找第一个空格插入（可溢出到新增页）。widget 占用的格不可放也不可推。
- 放入桌面格时自动从 dock 移除该 app（互斥，:247-266）；dock 内重排 = remove+insert。
- `p()` 是 widget 放置校验：span 1..4 列×1..6 行、行不越界、与其它 widget 不相交、不压 app 占格，任一不满足返回原 state（拒绝）。

### 2. 拖拽手势状态机 `Z1/C1428w2.java n()`（state 0..5）

- awaitFirstDown → 按下点须命中有内容的 W0 源 → **系统默认长按超时**（app 用 awaitLongPressOrCancellation，widget 用带 timeout 的变体）→ 进入拖拽（state5）。
- 起点位移超过 view 尺寸置"拖远"标志（UI 显示删除区/抑制点击）；抬起时若 up 已被其它手势消费则当点击处理、目标置 null。
- **落点优先级 `C1448y2.b()`（:46-144）**：①Remove 删除区最先（源非 library 时）；②拖 app 命中当前可见页的格直接返回；③否则按权重取最大：**Y0(folder)=3 > widget/library=2 > Z0/X0/Remove=1**——folder 热区天然优先于普通格，最终再由 `R()` 细化指针是否落入合并热区。
- 完整松手分发在 `N/Z.java:9739-9913` onFinish：R() 返回 Z0 且拖 app 时——格空 no-op、已夹入夹（x()）、是 app 则先 k0 移出旧夹再 `I()` 建新夹（默认名 "Folder"）；Y0→入夹；Remove→删（Z0 清格/X0 清 dock/widget 删+restore）；widget+Z0→`A2.l()` 重叠校验后放置；源来自文件夹→`U2.v`→k0。`U2.i()`（:273）本身只是窄分发（Y0 入夹 / Z0 走 A2.d）。

### 3. DuoGlass 样式表 `Z1/AbstractC1368q2.java c()`（:146）

返回 `DuoGlassSpec(背景α,tintα,fallbackα,blurDp,noise,elevationDp,pressedScale)`，按角色（ordinal 0..8）：

| 角色 | 背景α | tintα | fallbackα | blur(dp) | noise | elevation(dp) | pressedScale |
|---|---|---|---|---|---|---|---|
| Control(0) | .04 | .06 | .30 | 15 | .018 | 3 | .975 |
| SettingsRow(1) | .025 | .035 | .12 | 15 | .012 | 0 | 1.0 |
| Dock(2) | .055 | .075 | .36 | 22 | .02 | 7 | .985 |
| Card(3) | .065 | .07 | .40 | 22 | .02 | 5 | .985 |
| Elevated(4) | .085 | .08 | .52 | 26 | .02 | 13 | .985 |
| Folder(5) | .025 | .035 | .30 | 16 | .012 | 6 | 1.0 |
| Screen(6) | .11 | .065 | .66 | 28 | .018 | 0 | 1.0 |
| Floating(7) | .08 | .085 | .56 | 20 | .02 | 11 | .98 |
| WidgetFrame(8) | .035 | .05 | .25 | 18 | .015 | 3 | 1.0 |

- 深色：基色换深色、tintα×0.65、fallbackα+0.12；按下：tintα+0.025、highlightα 0.4、1dp 边框 borderLight 渐变、pressedScale 生效。
- 应用层 `a()`：背景优先 **dev.chrisbanes.haze** 库做实时模糊（blurRadius/noise/tint），无 Haze 退化 fallback 纯色；阴影 ambient 0.12 / spot 0.18；圆角由调用方传入（文件夹图标 = 24%×尺寸）。

### 4. 应用目录扫描与图标缓存 `Z1/S2.java n()`（:120）

- LauncherApps 回调（含 density/locales/uiMode 变化 key）→ Dispatchers.IO → `getProfiles()` 去重 → 每个 unlocked profile `getActivityList(null, user)`，跳过自身包；工作资料 id 前缀 `duo-profile:v1:<serial>:`。
- 图标 `getBadgedIcon(0)` → `t0.k()` 转 144×144 圆角 34px Bitmap。
- **两级缓存**：内存 `U2.f10147t` LinkedHashMap（id→AppEntry，label 未变复用）；磁盘 `"app_catalog"` prefs 的 `apps` JSON {id,label,serial,profile,work,clone}——**磁盘缓存条目图标是占位（defaultActivityIcon）**，冷启动秒出列表，后台扫完换真图标。
- 排序：**Collator 本地化 label 排序**（中文按拼音序，`S2.java:949`）。

---

## 四、对本项目的参考价值（对照 HomeWidgets.java / FolderFan / HomeLayout / HomeStore）

1. **widget 绑定链路两边几乎一致**（allocate→bindIfAllowed→系统 BIND 弹窗→configure 701/702→孤儿 id 回收）；MiDuo 多出的值得抄：① pending widget 的**中断恢复持久化**（本项目 store 已有 pendingWidget，MiDuo 还存 placement/provider/profile/status 四元组）；② configure 是否需要用 `widgetFeatures & CONFIGURATION_OPTIONAL/RECONFIGURABLE` 判定而不是只看 configure.activity 非空。
2. **内置伪 widget（负 slot）**：本项目 PROJECT_STATE 已确认 MIUI 时钟/天气 provider 私有不可迁移——MiDuo 的方案正是"自绘时钟/天气条目 + 负数 slot"，与本项目的内置组件思路一致，可扩展成日历/天气等自绘 widget 家族。
3. **span 计算**：MiDuo 优先 `targetCellWidth/Height`（API 31+ provider 都有），本项目 defaultSpan 可补这一优先级；resize clamp 用 minResize/maxResize 与本项目 HomeWidgetResize 类似。
4. **HostView 缩放包装 + 可交互子 View 命中测试拦截**：如果本项目后续把 widget 放进可滚动的 pager/滚动容器，需要同款 `dispatchTouchEvent` 命中检测（本项目当前是 FrameLayout 直挂，暂时不需要）。
5. **文件夹**：MiDuo 图标预览确认也是 **2×2 取前 4 个成员**（与本项目 FolderFan 同构，成员 icon 38% 尺寸、背景圆角 24%）；**剩 1 个成员自动解散、最后一个 app 回填文件夹原格位**（本项目 HomeLayout.folder() 的 apps.size()>1 判定天然同语义，但需确认移出路径有同款回收逻辑）；格位移动"挤开而非交换"、widget 格不可推、桌面/dock 互斥，这三条移动语义可直接抄进本项目拖拽落点逻辑。
6. **拖拽落点优先级权重**（folder=3 > widget/library=2 > 普通格=1，删除区最先）+ 长按用系统超时 + "拖远超 view 尺寸才显示删除区"，交互细节完整可抄。
7. **DuoGlass 数值表**（第三节）：本项目 HomeControlPanel 的磨砂面板可对照调参——如 Folder 面板 blur 16dp/背景α.025，Screen blur 28dp/α.11，Dock blur 22dp；含 noise、elevation、pressedScale 与深色/按下态修正，等价于一份现成的玻璃质感设计规范。
8. **预置自动分类文件夹**（固定 UUID + 包名候选匹配 + ≥2 才建）可直接用于本项目 HomeMigrator 迁移后的缺省分类。
9. **图标两级缓存 + 占位图标冷启动**（磁盘只存元数据、图标用占位，扫完替换）与 **Collator 本地化排序**，适合本项目 HomeApps 大应用量时的加载体验。
