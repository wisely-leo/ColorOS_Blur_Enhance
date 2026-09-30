# ColorOS Blur Enhance

[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPLv3--or--later-blue.svg)](LICENSE) [![Platform](https://img.shields.io/badge/Android-16%20(API%2036)-green.svg)](#-支持环境)

为 **ColorOS 16 桌面、多任务、时钟组件**提供动态模糊增强的 [LSPosed](https://github.com/LSPosed/LSPosed) 模块。

> 本模块由 **wisely-leo/Color-os-shortcut-enhance** 与桌面时钟字形模糊实验合并重构而来：以原 ShortcutBlur 为主体，并入时钟文字 / 天气图标的字形贴合动态高斯模糊能力，以及多任务（Recents）桌面图标模糊。

---

## ✨ 功能

### 桌面模糊（原 ShortcutBlur 能力）
- **图标模糊**：长按 / 拖拽图标时，按需为图标叠加动态模糊
- **文件夹模糊**：打开文件夹时，内部图标模糊并带渐进动画；关闭时平滑还原
- **壁纸深度模糊**：接入桌面 depth controller，随桌面状态联动，让模糊层次更自然
- **后处理采样适配**：统一后处理采样率，改善模糊边缘的马赛克 / 颗粒感

### 多任务图标模糊（Recents）
- **入场时机**：hook launcher 自己的「进入概览动画」入口 `SwipeToRecentAnimationHelper.goOverviewAnimation()`（实测比状态机 / 可见性更早，且每个手势都有）
- **退场判据**：`LauncherState` 状态机（`onStateTransitionStart(NORMAL)`）+ scale 阈值「**先跌破 0.96、再回升过 0.988**」（先跌破再回升，避免下降途中经过 0.988 造成误退）
- **桌面不淡出**：拦截 `OplusDragLayer.setAlpha`，`a < 0.999` 时钳回 `1.0`，保持桌面图标层不淡出
- **渐进动画**：入场 `0 → 64` / 180ms / Decelerate；退场 120ms
- **不动原生模糊**：`supportIconBlur()` 原样传递（pass-through），不强制打开 launcher 自带图标模糊
- **代码结构**：控制器 `RecentsBlur.java`（经 `HookApi` 与 XposedModule 解耦）；通用工具 `BlurLib.java`
- **调参**：免 root，广播 `com.wiselyleo.blurenhance.SETCONF`（`scale_min` / `blur_max` / `scaleexit` / `gts` / `tint` / `diag` / `verbose` 等）

### 时钟组件字形模糊
- **字形贴合模糊**：对 ColorOS 桌面时钟组件的**时间 / 日期 / 天气文字**以及**天气图标**，生成与字形轮廓贴合的 Path，通过 setPathProvider + invalidatePath 施加动态高斯模糊
- **时钟文字**：Hook 时钟进程 RemoteViews.setTextColor，对时钟文字颜色叠加 alpha（70% 透明度）
- **天气图标**：因天气图标由 RemoteViews.setImageViewBitmap() 设置，改用 View.setAlpha() 处理
- **刷新轮询**：TextClock 不触发 TextWatcher，模块以 500ms 轮询感知文字变化并重建快照

---

## 📱 支持环境

| 项目 | 要求 |
|---|---|
| 系统 | **Android 16（API 36）** |
| 框架 | LSPosed（libxposed API 102） |
| 桌面 | **仅 ColorOS / OPPO 系统桌面** |

本模块声明 **5 个作用域包**，分三类：

- **桌面进程**：com.android.launcher、com.oplus.launcher、com.coloros.launcher（代码中以 isTargetLauncher 统一匹配）。ColorOS 桌面内部复用 AOSP launcher3 的类路径，模块对 PopupBlurView、ArrowPopup、OplusPopupContainerWithArrow 等挂载 Hook，实现图标 / 文件夹 / 壁纸深度模糊与多任务图标模糊；并对 RemoteViews.apply / AppWidgetHostView.updateAppWidget 挂载 Hook，驱动时钟组件字形模糊。
- **时钟进程**：com.coloros.alarmclock。Hook 时钟文字颜色，实现字形模糊背景与文字透明。
- **后处理进程**：com.oplus.blur（独立进程，非桌面本身）。模块对类 e.a 的 c / e / d / f 四个方法挂载 Hook，将后处理模糊采样率由系统原生 0.25 提升至 0.5。

> ⚠️ 因此本模块**并非只作用于桌面**：必须同时覆盖 com.oplus.blur 与 com.coloros.alarmclock 进程，否则后处理采样适配与时钟字形模糊不会生效。

已在 OnePlus / OPPO PLC110（ColorOS 16.1，Android 16 / API 36）实机验证。

---

## 🚀 安装

1. 确保设备已安装 **LSPosed** 框架
2. 从 [Releases](../../releases) 下载并安装模块 APK
3. 在 LSPosed 管理器中启用本模块
4. **作用域**保持默认（模块已声明，全选即可）
5. 重启相应作用域（桌面进程、com.oplus.blur 与 com.coloros.alarmclock 进程）生效

---

## 📦 模块信息

| 项 | 值 |
|---|---|
| 模块 ID（applicationId） | com.wiselyleo.blurenhance |
| 模块入口 | com.shortcutblur.BlurEnhanceModule |
| Java 包名（namespace） | com.shortcutblur |
| 版本 | **v40（versionCode 400）** |
| 最低 / 目标 SDK | 36 / 36 |

---

## 📁 目录结构

```
ColorOS_Blur_Enhance/
├── assets/icons/                       # 应用图标（各密度）
├── libs/
│   └── libxposed-api-102.jar           # 编译依赖（LSPosed API 102）
└── src/main/java/com/shortcutblur/
    ├── BlurEnhanceModule.java          # 模块主入口（XposedModule；安装桌面 / 时钟 / 组件 / 弹窗 Hook）
    ├── RecentsBlur.java                # 多任务（Recents / Overview）模糊控制器（经 HookApi 与框架解耦）
    ├── BlurLib.java                    # 通用库（launcher 类名常量 + 反射 / View / 状态 / 渲染效果工具）
    ├── GlyphBlurRenderer.java          # 字形贴合模糊渲染（Path 构建 / 轮询刷新）
    ├── WidgetBlurAttacher.java         # 桌面组件（Widget）模糊挂载
    ├── ClockTextAlphaHook.java         # 时钟文字 alpha Hook
    ├── ClockIds.java                   # 时钟文字 / 天气图标 id 常量集中定义
    ├── Reflect.java                    # 反射工具（带容量上限的 LRU 缓存）
    ├── ViewUtils.java                  # 视图工具（视图树遍历 / 可见性判定）
    └── ModuleLog.java                  # 可选文件日志（RELEASE 编译期开关，发布版即关闭）
```

---

## 🧠 实现原理

### 桌面模糊
- Hook 桌面弹窗容器的入场 / 退场动画创建入口（onCreateOpenAnimation / onCreateCloseAnimation），把「模糊 0→1 / 1→0」的动画直接 set.play(...) 并进原生 AnimatorSet，与原生 alpha / scale 动画同步。
- 按视图所处状态分流处理：在文件夹内时走图标模糊路径，其余走壁纸深度模糊路径；判定结果在单次弹窗流程内缓存，流程结束时失效。
- 模糊由 RenderEffect.createBlurEffect(64f, ...) 实现：优先调用 com.oplus.view.OplusViewBackgroundRenderEffect.setBackgroundRenderEffect(effect, view)，失败则回退标准 View.setRenderEffect(effect)。
- 图标模糊动画在收尾与逐帧更新时校验有效性，中途状态变化时立即取消，避免闪回。

### 多任务图标模糊（Recents）
- 通过拦截桌面的动画 / 透明度回调采样容器缩放值，以 `down-cross 0.96` / `up-cross 0.96` 的**方向穿越**判定进入 / 退出 Recents。
- 用 `sRecentsPhase` 维护单次流程阶段；`isInsideOpenFolder` 用于排除打开文件夹时的误判，保证 **Recents 与文件夹两条链严格隔离**。
- 进入时为桌面图标挂载模糊，退出时按同一判据平滑还原。

### 时钟字形模糊
- 桌面进程 Hook RemoteViews.apply / AppWidgetHostView.updateAppWidget，在组件视图更新后定位到 provider 根视图，交给 GlyphBlurRenderer。
- GlyphBlurRenderer 遍历时间 / 日期 / 天气文字与天气图标，按 getTotalPaddingTop() + getLayout().getLineBaseline(0) 计算基线，在载体 View 的**本地坐标系**内构建字形 Path，再通过 setPathProvider + invalidatePath() 施加动态模糊；矩形遮罩取模糊层 bounds（对齐 provider 根）。
- 时钟进程 Hook RemoteViews.setTextColor，对目标文字 id 叠加 alpha。
- 任何 static 全局状态均按 **per-container** 维护，避免多组件串扰。

### 性能优化（v40）
- **OPlus API 反射结果缓存**：`sOplusEffectResolved` / `sSetBgRenderEffect` 等缓存反射结果，避免热点路径反复 `Class.forName` / 查方法。
- **反射缓存 LRU 化**：`Reflect` 的 method / field / ctor / class 缓存改为带容量上限的 LRU（access-order），避免「超限即全清」导致的周期性缓存失效与反复解析。
- **帧内对象复用**：`GlyphBlurRenderer` 用 ThreadLocal 复用 scratch `Path` / `Matrix`，消除每帧对象分配。
- **视图矩形局部化**：`ViewUtils` 使用局部 `Rect` 而非静态共享实例，避免多容器互相覆盖（正确性 / 线程安全）。

---

## 🔇 日志

`ModuleLog` 是**编译期开关**的调试文件日志，**发布版本固定关闭**：

```java
public static final boolean RELEASE = true;      // 发布：true = 无日志
public static final boolean ENABLED = !RELEASE;
```

- `RELEASE` / `ENABLED` 均为 `final` 常量 → javac 常量折叠 + 死代码消除，**关闭时零运行时开销**，且**不产生任何日志文件**。（已验证：release 版 dex 内 `FileOutputStream` / `OutputStreamWriter` / 日志格式串全部消失）
- **调试**：把 `RELEASE` 改为 `false` 重新构建即可，所有日志与调参开关照常工作。
- **二级开关** `ModuleLog.VERBOSE`（默认 `false`）：控制动画期「每帧」日志（`DRAGALPHA` 逐帧 / `SCALECLAMP` / `BLURAPPLY`）。默认关闭，避免过渡动画期间「每行一次 open+flush+close」的文件 I/O 抖动。
- 日志按进程分流写入：
  - 主进程：`/storage/emulated/0/Download/ColorOSBlurEnhance.log`
  - 后处理进程：`/storage/emulated/0/Download/PostEffectBlur.log`
- 运行时 logcat 统一 TAG 为 `ColorOSBlurEnhance`。

> ⚠️ 这是一个**刻意的发布安全设计**：开关在编译期决定，不由 `BuildConfig` 等运行时因素隐式改变。

---

## 🔖 版本历史（近期）

| 版本 | 说明 |
|---|---|
| **v42-dev**（进行中） | **Recents 解冻重做**：入场改 hook `SwipeToRecentAnimationHelper.goOverviewAnimation()`；退场走 `LauncherState` 状态机 + scale「先跌破 0.96、再回升过 0.988」；`OplusDragLayer.setAlpha` 钳回 1.0（桌面图标层不淡出）；恢复 `0→64` / 180ms 渐进入场；不再强开原生 `supportIconBlur`。**结构拆分**：抽出 `BlurLib`（通用库）与 `RecentsBlur`（多任务控制器，经 `HookApi` 解耦），`BlurEnhanceModule` 2758 → 1302 行 |
| **v41** | Recents 入场 / 退场双阈值重构 + 性能与清理 |
| **v40** | 快捷方式吞暂停保活 + 图标模糊独立挂点 + 进入渐进 / 退出渐降（与菜单展开同步）；性能优化：OPlus API 反射缓存、反射缓存 LRU 化、帧内 scratch 复用、视图矩形局部化修复 |
| v38.6 | 重构：抽出 ClockIds、命名魔法数、补类文档 |
| v24 | Recents 桌面图标模糊（方向穿越判据；v42-dev 已重做，见上） |

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

- **源文件版权头**：`src/main/java/com/shortcutblur/` 下每个源文件头部均带有
  `SPDX-License-Identifier: GPL-3.0-or-later` 与上述版权声明。
- **源码可得性（GPL-3.0 §6）**：本仓库即对应源码。对外分发编译产物（模块 APK）时，
  须同时提供或明确指明获取本源码的方式。
- **派生作品**：基于本项目的修改与再分发，必须同样以 GPL-3.0（或更新版本）开放源码，
  并保留原有版权与许可声明。
- **第三方组件**：`libs/libxposed-api-102.jar`（libxposed API）版权归其作者所有，
  遵循其自身许可；本项目的 GPL 仅覆盖本项目自身代码。
- **商标与隶属**：本项目为第三方开源项目，与 OPPO / ColorOS / LSPosed 官方无隶属关系，
  相关商标归各自所有者所有。
