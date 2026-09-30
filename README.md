# ColorOS Blur Enhance

[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPLv3--or--later-blue.svg)](LICENSE) [![Platform](https://img.shields.io/badge/Android-16%20(API%2036)-green.svg)](#-支持环境)

为 **ColorOS 16 桌面、多任务与时钟组件**提供动态模糊增强的 [LSPosed](https://github.com/LSPosed/LSPosed) 模块。

> 本项目由 `wisely-leo/Color-os-shortcut-enhance`（原 ShortcutBlur）与桌面时钟字形模糊实验合并重构而来。

---

## ✨ 功能

### 桌面模糊
- **图标模糊**：长按 / 拖拽图标时，按需为图标叠加动态模糊
- **文件夹模糊**：打开文件夹时内部图标模糊并带渐进动画，关闭时平滑还原
- **壁纸深度模糊**：接入桌面深度控制器，随桌面状态联动，模糊层次更自然
- **后处理采样适配**：提升后处理模糊采样率，改善模糊边缘的颗粒感

### 多任务（Recents）桌面模糊
- **进入多任务**时为桌面图标层叠加模糊，退出时平滑还原
- **入场时机**取自桌面自身的「进入概览」动画入口，手势一抬即开始
- **退场判据**以桌面缩放回弹为主，由桌面状态机兜底与校正
- **桌面图标层不淡出**：进入多任务时保持桌面不透明，只叠加模糊
- **渐进过渡**：入场由浅入深，退场快速回落
- **不干预原生行为**：不改动桌面自带的图标模糊开关

### 时钟组件字形模糊
- 对桌面时钟的**时间 / 日期 / 天气文字**与**天气图标**施加贴合字形轮廓的动态模糊
- 时钟文字通过颜色透明度叠加，形成字形模糊背景；天气图标由位图设置，改用整体透明度处理
- 时钟文字不触发文本变化回调，模块以轮询方式感知刷新

### 性能
- 反射结果缓存（带容量上限的 LRU），避免热点路径反复解析
- 帧内临时对象复用，减少每帧内存分配
- 发布版日志在**编译期**彻底关闭，零运行时开销

---

## 📱 支持环境

| 项目 | 要求 |
|---|---|
| 系统 | **Android 16（API 36）** |
| 框架 | LSPosed（libxposed API 102） |
| 桌面 | **仅 ColorOS / OPPO 系统桌面** |

模块共声明 **5 个作用域包**，分三类：

- **桌面进程**（`com.android.launcher` / `com.oplus.launcher` / `com.coloros.launcher`）
  图标 / 文件夹 / 壁纸深度模糊、多任务模糊、时钟组件字形模糊
- **时钟进程**（`com.coloros.alarmclock`）
  时钟文字透明度处理
- **后处理进程**（`com.oplus.blur`）
  后处理模糊采样率适配

> ⚠️ 本模块**并非只作用于桌面**：必须同时覆盖后处理与时钟进程，否则对应能力不会生效。

已在 OnePlus / OPPO PLC110（ColorOS 16.1，Android 16 / API 36）实机验证。

---

## 🚀 安装

1. 确保设备已安装 **LSPosed** 框架
2. 从 [Releases](../../releases) 下载并安装模块 APK
3. 在 LSPosed 管理器中启用本模块
4. **作用域**保持默认（模块已声明，全选即可）
5. 重启相应作用域（桌面进程、后处理进程、时钟进程）后生效

---

## 🧠 工作原理

- **桌面 / 文件夹 / 壁纸模糊**
  挂接到桌面弹窗的入场 / 退场动画入口，把模糊动画并入原生动画集合，与缩放、透明度同步；按场景分流为「文件夹内图标模糊」与「壁纸深度模糊」两条路径，两者严格隔离，避免互相干扰。

- **多任务模糊**
  由三类信号共同判断进 / 出时机：桌面自身的「进入概览」动画、桌面状态机、桌面缩放回弹。三类信号先到先得，进入时挂载模糊并保持桌面不透明，退出时平滑还原。

- **时钟组件字形模糊**
  在组件视图更新后定位到时钟视图，按其文字基线在视图本地坐标系内构建字形轮廓，再对轮廓施加动态模糊；多个时钟组件之间状态相互隔离，避免串扰。

- **后处理采样**
  提升后处理进程的模糊采样率，改善模糊边缘的马赛克 / 颗粒感。

---

## ⚙️ 运行期调参（免 root）

无需重装：向模块发送广播即可实时调整。所有参数均为**字符串**附加项（`--es`），并需带 `--user 0`。

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

## 🔇 日志与发布

- 日志由**编译期开关**控制：发布版关闭。开关是编译期常量，关闭后日志代码被完全消除 —— **不产生任何日志文件，也没有运行时开销**。
- 调试时把开关改为开启、重新构建即可；另有「每帧日志」二级开关，默认关闭，以避免过渡动画期间频繁写文件造成的卡顿。
- 日志按进程分流写入设备 `Download` 目录：主进程与后处理进程各写一个文件；运行时 logcat 使用统一 TAG。

> ⚠️ 这是一个刻意的发布安全设计：开关在编译期决定，不受运行时因素隐式改变。

---

## 📦 模块信息

| 项 | 值 |
|---|---|
| 模块 ID（applicationId） | `com.wiselyleo.blurenhance` |
| 模块入口 | `com.shortcutblur.BlurEnhanceModule` |
| Java 包名（namespace） | `com.shortcutblur` |
| 作用域 | 5 个（见「支持环境」） |
| 最低 / 目标 SDK | 36 / 36 |
| APK 清单版本 | `v41`（versionCode 401） |

> 📌 当前构建流程只替换 `classes.dex`，APK 清单（版本号 / 版本名）沿用打包模板。
> 因此**清单中的版本不会随代码版本自动变化** —— 发布新版本前，需先更新模板清单里的 `versionCode` / `versionName`。

---

## 📁 目录结构

```
ColorOS_Blur_Enhance/
├── assets/icons/                       # 应用图标（各密度）
├── libs/
│   └── libxposed-api-102.jar           # 编译依赖（LSPosed API 102）
└── src/main/java/com/shortcutblur/
    ├── BlurEnhanceModule.java          # 模块主入口（安装各进程 Hook）
    ├── RecentsBlur.java                # 多任务模糊控制器（与框架解耦）
    ├── BlurLib.java                    # 通用库（常量 + 反射 / 视图 / 状态 / 渲染效果工具）
    ├── GlyphBlurRenderer.java          # 字形贴合模糊渲染
    ├── WidgetBlurAttacher.java         # 桌面组件（Widget）模糊挂载
    ├── ClockTextAlphaHook.java         # 时钟文字透明度 Hook
    ├── ClockIds.java                   # 时钟文字 / 天气图标 id 常量
    ├── Reflect.java                    # 反射工具（带容量上限的 LRU 缓存）
    ├── ViewUtils.java                  # 视图工具
    └── ModuleLog.java                  # 可选文件日志（编译期开关）
```

---

## 🔧 构建

- 依赖：JDK 8、Android SDK（API 36）、`libs/libxposed-api-102.jar`
- 纯 Java 工程（无 Gradle 脚本）：以 `javac` 编译源码，用 `d8` 生成 `classes.dex`，再与模块清单、资源一起打包并签名。

---

## 🔖 版本历史（近期）

| 版本 | 说明 |
|---|---|
| **v42-dev**（进行中） | **多任务模糊重做**：入场改取桌面自身的「进入概览」动画入口；退场以缩放回弹为主、状态机兜底；进入多任务时保持桌面不淡出；恢复渐进入场；不再干预桌面自带的图标模糊开关。**结构调整**：抽出通用库与独立的多任务控制器，主入口大幅精简 |
| **v41** | 多任务入场 / 退场双阈值重构 + 性能与清理 |
| **v40** | 快捷方式吞暂停保活 + 图标模糊独立挂点 + 进入渐进 / 退出渐降；性能优化：反射结果缓存、缓存 LRU 化、帧内对象复用、视图矩形局部化 |
| v38.6 | 重构：抽出时钟 id 常量、命名魔法数、补类文档 |
| v24 | 多任务桌面图标模糊（方向穿越判据；v42-dev 已重做，见上） |

> 注：v38.7 为**内部测试版本，未发布到 git**，其内容已随 v40 一并发布，故不单列。

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

- **源文件版权头**：每个源文件头部均带有 `SPDX-License-Identifier: GPL-3.0-or-later` 与上述版权声明。
- **源码可得性（GPL-3.0 §6）**：本仓库即对应源码。对外分发编译产物（模块 APK）时，须同时提供或明确指明获取本源码的方式。
- **派生作品**：基于本项目的修改与再分发，必须同样以 GPL-3.0（或更新版本）开放源码，并保留原有版权与许可声明。
- **第三方组件**：`libs/libxposed-api-102.jar`（libxposed API）版权归其作者所有，遵循其自身许可；本项目的 GPL 仅覆盖本项目自身代码。
- **商标与隶属**：本项目为第三方开源项目，与 OPPO / ColorOS / LSPosed 官方无隶属关系，相关商标归各自所有者所有。
