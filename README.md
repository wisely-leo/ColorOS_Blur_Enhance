# ColorOS Blur Enhance
[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPLv3--or--later-blue.svg)](LICENSE) [![Platform](https://img.shields.io/badge/Android-16%20(API%2036)-green.svg)](#-支持环境) [![LSPosed](https://img.shields.io/badge/LSPosed-libxposed%20API%20102-orange.svg)](#-支持环境)

为 **ColorOS 16 桌面、多任务、时钟组件、相册、全局搜索与输入法**提供动态模糊 / 视觉增强的 [LSPosed](https://github.com/LSPosed/LSPosed) 模块，附带一个可实时调参的 **图形化设置界面**。

> 本项目由 `wisely-leo/Color-os-shortcut-enhance`（原 ShortcutBlur）与桌面时钟字形模糊实验合并重构而来。

---

## ✨ 功能

### 🎨 图形化设置界面（GUI）
模块自带独立界面，**无需重启、无需改代码**即可实时调整各项能力：
- **双页结构**：「模块设置」（功能开关）+「更多」（检查更新 / 关于 / 权限说明 / 开源致谢）
- **可折叠选项**：带子选项的功能使用可折叠控件，收起 / 展开子参数
- **实时生效**：改动通过广播 + `blur.conf` 同步给宿主进程，无需重装
- **检查更新**：查询 GitHub Releases，按 `versionCode` 语义比较，**仅当远端版本更高时**才提示更新
- **作用域重启**：借助 Shizuku 逐包 `force-stop`，也可一键重启全部；应用名以中文显示
- **自定义背景**：可设置背景图（≤15MB），卡片与顶栏自动叠加真实模糊，带平滑淡入 / 收回动画
- **ADB 权限检测**：打开界面时自动检测 Shizuku 权限；未授权会弹窗引导，拒绝授权或获取失败则退出界面

### 🖥️ 桌面模糊
- **图标模糊**：长按 / 拖拽图标时，按需为图标叠加动态模糊
- **文件夹模糊**：打开文件夹时内部图标模糊并带渐进动画，关闭时平滑还原
- **壁纸深度模糊**：接入桌面深度控制器，随桌面状态联动，模糊层次更自然
- **桌面卡片模糊**：桌面上的 OPPO 卡片（耗电看板等）**出现即自动挂上高斯模糊**，无需任何手动操作（开关：GUI「模块设置」内的「卡片模糊（耗电看板等）」）
- **桌面应用名阴影**：应用名常驻一层柔和阴影，亮 / 暗壁纸下都清晰；卡片内标题同样适配
- **后处理采样适配**：提升后处理模糊采样率，改善模糊边缘的颗粒感

### 📱 多任务（Recents）桌面模糊 ⚠️ 实验性
> ⚠️ **实验性功能（默认关闭）**：该能力经多次版本更迭验证为**不稳定，部分 bug 难以修复**（如快速上滑时模糊突变为衰减、偶发不生效等）。现独立为「实验性功能」分区，**默认不启用**；需在 GUI 中手动开启，开启前会弹出风险提示，确认后方可启用。
- **进入多任务**时为桌面图标层叠加模糊，退出时平滑还原；入场时机取自桌面自身的「进入概览」动画入口
- **桌面图标层不淡出**：进入多任务时保持桌面不透明，只叠加模糊
- **退场判据**以桌面缩放回弹为主，由桌面状态机兜底与校正；不改动桌面自带的图标模糊开关

### 🕐 时钟组件字形模糊
- 对桌面时钟的**时间 / 日期 / 天气文字**与**天气图标**施加贴合字形轮廓的动态模糊
- **自定义混色**：可调色相 + 手工输入色号（双向联动），向时钟混入自定义颜色；启用后时钟透明度 / 提亮由混色接管
- **真我（realme）时钟适配**：适配 `local_*` 系列组件与「日历 / 天气」横向小组件（`weather_widget_layout`）；真我文字透明度改用**视图级不透明度（`View.setAlpha`）**单一来源，消除「实心白 / 模糊」来回闪烁

### 🔍 全局搜索（下拉搜索实时模糊）
- **背景透明化**：全局搜索页顶层背景置为透明，**透出桌面侧已做好的实时模糊**（含图标、小组件、壁纸）
- **精确来源判断**：仅在「下层是桌面」时透明；负一屏等来源保持搜索默认背景
- **零额外开销**：不在搜索页侧重复做模糊，完全复用桌面侧实时模糊

### 🖼️ 相册强制白色主题
- 强制相册（`com.coloros.gallery3d`）照片页使用浅色背景，不受系统深色模式影响
- **原理**：hook 相册配置读取的统一入口 `com.oplus.aiunit.vision.rda` 的 `Boolean M6(String, boolean)`，按配置键覆盖返回值

### ⌨️ 输入法键盘模糊（仅适配语燕输入法）
- **键盘背景高斯模糊**，与系统其他模糊区域观感统一；可调模糊程度 / 键盘圆角 / 白灰蒙版强度
- **适配识别去硬编码**：由「写死混淆类名」改为「候选名 + 特征校验」运行时识别；宿主列表读取模块作用域自动同步

### ⚡ 性能与开销
- 反射结果缓存（上限 512，超过后整体清空重建），避免热点路径反复解析
- 绘制路径上的 `Rect` / `RectF` 以字段复用，避免每帧新建对象；同帧内多次施模糊去抖合并
- 桌面 / 卡片模糊的重试采用指数退避（窗口约 18 秒），已有结果的卡片不再重复扫描
- **发布版日志默认关闭**：不产生日志文件，也没有运行时开销

---

## 📱 支持环境
| 项目 | 要求 |
|---|---|
| 系统 | **Android 16（API 36）** |
| 框架 | LSPosed（libxposed API 102） |
| 桌面 | **仅 ColorOS / OPPO 系统桌面** |

模块共声明 **6 个作用域包**（即 `scope.list` 全文）：
| 作用域包 | 用途 |
|---|---|
| `com.android.launcher` | 图标 / 文件夹 / 壁纸深度模糊、多任务模糊、桌面卡片模糊、应用名阴影、时钟组件字形模糊 |
| `com.coloros.alarmclock` | 时钟文字透明度 / 混色（兼容 OnePlus、OPPO 与真我 realme 两套时钟布局） |
| `com.oplus.blur` | 后处理模糊采样率适配 |
| `com.heytap.quicksearchbox` | 下拉搜索页背景透明化（透出桌面实时模糊） |
| `com.coloros.gallery3d` | 相册照片页强制浅色主题 |
| `com.yuyan.pinyin.offline.release` | 输入法键盘背景高斯模糊（语燕输入法） |

> ⚠️ 本模块**并非只作用于桌面**：桌面模糊必须同时覆盖后处理与时钟进程才会生效；相册、输入法等能力需**单独勾选对应作用域**（升级安装时 LSPosed 会保留旧作用域，可能需手动勾选）。

已在 OnePlus / OPPO PLC110（ColorOS 16.1，Android 16 / API 36）实机验证。

---

## 🚀 安装
1. 确保设备已安装 **LSPosed** 框架
2. 从 [Releases](../../releases) 下载并安装模块 APK
3. 在 LSPosed 管理器中启用本模块
4. **作用域**：建议全选（模块已声明 6 个；升级安装时 LSPosed 可能保留旧作用域，必要时手动勾选「相册」「语燕输入法」等）
5. 打开模块自带界面，按需调整功能开关
6. 若开关不立即生效，可在「模块设置」中重启相应作用域（需 Shizuku）

### 可选：Shizuku（用于「作用域重启」）
- 安装 [Shizuku](https://shizuku.rikka.app/) 并在模块界面中授权
- 授权后即可在界面上**逐个应用单独重启**，无需手动去系统里找
- 不授权也不影响模糊功能，只是少了便捷重启

---

## 🧠 工作原理
- **桌面 / 文件夹 / 壁纸模糊**：挂接到桌面弹窗的入场 / 退场动画入口，把模糊动画并入原生动画集合，与缩放、透明度同步；按场景分流为「文件夹内图标模糊」与「壁纸深度模糊」两条路径，两者严格隔离。
- **桌面卡片模糊**：hook 卡片类（`TitleCardView` 等）的 `onAttachedToWindow` / `initCardAfterInflate`，在卡片挂到窗口、内容加载完成后自动定位卡片内「不透明 CSS 底色层」，通过后处理管理器为其创建卡片类型模糊；卡片内容变化时重新处理，未就绪则按指数退避重试（约 18 秒窗口），已有结果的卡片直接短路、不再重复遍历。
- **应用名阴影**：原生阴影只在「默认文字色 + 壁纸不亮 + 标题阴影开关开启」时才有，且亮壁纸下会被直接清空。模块拦截文字控件的 `setShadowLayer`（连宿主的「清除阴影」调用也替换为阴影），并在桌面应用名的阴影刷新方法之后补一次，使阴影常驻、不被主题 / 壁纸 / 透明度动画清掉；卡片内标题一并适配。
- **多任务模糊**：由三类信号共同判断进 / 出时机——桌面自身的「进入概览」动画、桌面状态机、桌面缩放回弹；三类信号先到先得，进入时挂载模糊并保持桌面不透明，退出时平滑还原。
- **时钟组件字形模糊**：在组件视图更新后定位时钟视图，按其文字基线在视图本地坐标系内构建字形轮廓，再对轮廓施加动态模糊；多组件状态相互隔离。真我文字改为**只通过视图级 `View.setAlpha` 控制透明度**，从而稳定露出容器上的模糊层。
- **后处理采样**：提升后处理进程的模糊采样率，改善模糊边缘的马赛克 / 颗粒感。
- **相册强制浅色**：hook 配置读取的统一入口，按配置键强制「非深色 / 浅色可用」的相关返回值为浅色。
- **输入法键盘模糊**：hook 输入法视图基类的 `onAttachedToWindow` / `onVisibilityChanged` 与主题背景刷新方法，反射构造 OPLUS `ViewRootManager`（`com.oplus.view.ViewRootManager`），取其 `getBackgroundBlurDrawable` 并设置半径 / 颜色 / 圆角后作为键盘背景。

---

## ⚙️ 运行期调参
### 方式一：图形界面（推荐）
打开模块自带界面即可调整全部开关，改动**立即同步**到各宿主进程。界面用到两条同步通道：
1. **广播**：向全部作用域宿主包（默认 6 个，读取模块自身 `scope.list`）发送 `com.wiselyleo.blurenhance.SETCONF`
2. **配置文件**：借助 Shizuku 写入 `/data/local/tmp/ColorOSBlurEnhance/blur.conf`（模块读不到广播时也能从这里取到最新配置）

**配置文件示例（节选）：**
```ini
# ColorOS Blur Enhance - runtime feature flags (written by GUI)
shortcut_blur=1
recents_blur=1
widget_blur=1
launcher_card_blur=1
posteffect=1
quicksearch_blur=1
gallery_light=1
launcher_label_shadow=1
ime_blur=0
sample_scale=0.5
log_enabled=0
```

### 方式二：广播（免 root、免界面）
所有参数均为**字符串**附加项（`--es`），并需带 `--user 0`。
| 参数 | 作用 |
|---|---|
| `blur_max` | 多任务模糊的最大半径 |
| `scale_min` | 桌面缩放钳制下限 |
| `scaleexit off` | 关闭「缩放回弹」退场判据（退场仅由状态机负责） |
| `scaleenter off` | 关闭「缩放跌落」补入场信号 |
| `gts off` | 关闭桌面状态机源头信号 |
| `visenter on` / `visexit on` | 启用基于可见性的进 / 退场信号（默认关闭） |
| `tint on` | 用红色滤镜替代模糊，仅用于肉眼确认生效时机 |
| `mark <文本>` | 在日志中打一个标记，便于对照某次操作 |

```bash
am broadcast --user 0 -a com.wiselyleo.blurenhance.SETCONF --es blur_max 48
am broadcast --user 0 -a com.wiselyleo.blurenhance.SETCONF --es mark "enter-recents"
```

---

## 🔇 日志与文件位置
- 日志**默认关闭**：不产生日志文件，也没有运行时开销；可在界面「模块设置」中临时开启用于排查。
- 日志按进程分流，文件名带进程 PID，避免多进程互相覆盖；运行期 logcat 使用统一 TAG。

| 文件 | 路径 |
|---|---|
| 运行时配置 | `/data/local/tmp/ColorOSBlurEnhance/blur.conf` |
| 模块日志 | `/storage/emulated/0/Download/ColorOSBlurEnhance_p<pid>.log`（按进程分文件） |
| 界面日志 | `/storage/emulated/0/Download/UiStartup.log` |
| 自定义背景图 | 应用私有目录 `files/bg.jpg` |

---

## 📦 模块信息
| 项 | 值 |
|---|---|
| 模块 ID（applicationId） | `com.wiselyleo.blurenhance` |
| 模块入口 | `com.shortcutblur.BlurEnhanceModule` |
| Java 包名（namespace） | `com.shortcutblur` |
| 作用域 | 6 个（见「支持环境」） |
| 最低 / 目标 SDK | 36 / 36 |
| APK 清单版本 | `v46.3`（versionCode 463） |

---

## 📁 目录结构
```
ColorOS_Blur_Enhance/
├── assets/icons/                  # 应用图标（各密度）
├── libs/                          # libxposed API / Shizuku API（依赖 jar）
├── res/                           # 界面资源（图标 / 颜色 / 文字）
└── src/main/java/com/shortcutblur/
    ├── BlurEnhanceModule.java     # 模块主入口（按包分发安装各进程 Hook）
    ├── FeatureFlags.java          # 运行期功能开关 + 配置读取
    ├── ModuleLog.java             # 可选文件日志（按进程分文件，带上限轮转）
    ├── Reflect.java / ViewUtils.java / Symbols.java / BlurLib.java   # 反射 / 视图 / 常量工具
    ├── RecentsBlur.java / IconBlurOverlayView.java                   # 多任务模糊
    ├── ShortcutBlurFeature.java / OplusCardBlur.java                 # 桌面图标 / 文件夹模糊、快捷菜单模糊
    ├── CardBlurAttacher.java / CardBlurHook.java                     # 桌面卡片模糊
    ├── LauncherLabelShadowHook.java                                  # 桌面应用名阴影
    ├── WidgetBlurAttacher.java / GlyphBlurRenderer.java              # 桌面组件与字形贴合模糊
    ├── GlassColorHook.java / ClockTextAlphaHook.java / ClockIds.java # 时钟组件混色与文字透明度
    ├── QuickSearchBlur.java / GalleryLightHook.java                  # 全局搜索、相册
    ├── ImeBlurHook.java / ImeGlowStrokeDrawable.java                 # 输入法键盘模糊与内发光
    └── ui/                        # 图形化设置界面
        ├── SettingsActivity.java / SettingsStore.java   # 主界面与配置存储 / 同步
        ├── SoftUi.java / UiLog.java                     # 轻量 UI 组件库与界面日志
        ├── Adb.java / ScopeService.java                 # Shizuku 通道（作用域重启）
        └── App.java                                     # Application 与日志出口
```
> 📌 本仓库为**发布源码**：不含构建定义文件（`AndroidManifest.xml` / 模块元数据），无法直接构建——仅提供源码阅读与许可合规用途。

---

## 🔖 版本历史（近期）
| 版本 | 说明 |
|---|---|
| **v46.3** | **新增「桌面卡片模糊」+ 卡片标题阴影适配**：桌面上的 OPPO 卡片（耗电看板等）出现即自动挂上高斯模糊（hook 卡片生命周期，无需手动触发）。仅处理已知卡片类并校验网格跨度；内容变化时自动重新处理，未就绪则指数退避重试（约 18 秒窗口）；已有结果的卡片直接短路，不再重复扫描。新增热更参数 `launcher_card_blur`。「桌面应用名阴影」适配卡片内标题，卡片基类改为按需解析。桌面卡片日志拆出独立标签。 |
| **v46.2** | **新增「桌面应用名阴影」**：应用名常驻柔和阴影（原生仅暗壁纸下才有，亮壁纸 / 主题切换 / 透明度动画都会清掉）；阴影恒为纯黑、只有浓度可调。同时修复键盘内发光观感（按到圆角矩形边界的距离重做，圆角不再熄灭）。 |
| **v46.1** | **发热与耗电优化**：日志改为批量写入、不再写入系统日志；时钟组件遮挡时探测频率下调（4Hz → 1.25Hz）；最近任务滑动期配置读取频率下调；键盘内发光按边界距离分块绘制，绘制层数由 12 层降至 6 层以内；多处缓存增加上限保护。 |
| **v46** | **快捷菜单模糊修复与增强**：修复一级菜单关闭时「闪白 / 闪实心」、修复二级菜单弹出时桌面图标模糊被误清除、二级菜单模糊半径独立可调（默认 96）；清理调试探针与失效 Hook，模糊动画缓存改用弱引用。 |
| **v45.5** | 日志开关三处写入点统一（关闭后无任何后台写文件）；色相滑块拖动不再每帧分配数组。 |
| v45.4 及更早 | 真我（realme）时钟适配与文字闪烁修复、ADB 权限检测、键盘深色适配、最近任务模糊判据重构（改由系统壁纸缩放信号驱动）、输入法键盘模糊、下拉搜索实时模糊、相册强制浅色、图形化设置界面等 —— 详见 GUI 内「更新日志」。 |

> 注：v38.7 为内部测试版本，未单独发布，其内容已随 v40 一并发布。

---

## 📄 许可证
本项目以 **GNU 通用公共许可证第 3 版或更新版本（GPL-3.0-or-later）** 发布，完整条款见 [LICENSE](LICENSE)。

```
ColorOS Blur Enhance —— ColorOS 16 桌面 / 多任务 / 时钟组件的动态模糊增强（LSPosed 模块）
Copyright (C) 2026 wisely-leo

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```
- **源码可得性（GPL-3.0 §6）**：本仓库即对应源码。对外分发模块 APK 时，须同时提供或明确指明获取本源码的方式。
- **派生作品**：基于本项目的修改与再分发，必须同样以 GPL-3.0（或更新版本）开放源码，并保留原有版权与许可声明。
- **第三方组件**：`libs/` 下的 `libxposed-api-102.jar`（libxposed API）与 `shizuku-*.jar`（Shizuku）版权归其各自作者所有，遵循其自身许可；本项目的 GPL 仅覆盖本项目自身代码。
- **商标与隶属**：本项目为第三方开源项目，与 OPPO / ColorOS / LSPosed / Shizuku 官方无隶属关系，相关商标归各自所有者所有。