# HighRefreshRunner · 高刷奔跑

让屏幕以**最高刷新率**运行，并展示一个奔跑小人动画的 Android 工具 App（手机 / 平板通用）。

## 功能

- 强制本应用窗口使用设备支持的最高刷新率（`preferredDisplayModeId` + API 34+ `preferredRefreshRate`，watchdog 每 2 秒重断言，防省电降频）
- 持续渲染的奔跑小人动画（Canvas 逐帧绘制），维持峰值刷新率
- 实时显示当前刷新率（Hz）与 FPS
- 刷新率信息页：列出设备支持的全部显示模式，标注峰值模式
- 手机底部横排导航 / 平板（≥600dp）左侧竖排悬浮胶囊导航，GlassButtons 毛玻璃风格

## 安装

从 [Releases](https://github.com/GJR787878/HighRefreshRunner/releases) 下载最新 APK 安装。

> 提示：部分设备需先在系统设置中开启高刷新率（如 120Hz）后本应用才能请求到峰值模式。

## 原理

1. `WindowManager.LayoutParams.preferredDisplayModeId` 请求峰值显示模式（API 23+）
2. API 34+ 同时设置 `preferredRefreshRate` 精确指定刷新率
3. 每 2 秒 watchdog 重断言，防止系统省电策略把刷新率降回 60Hz
4. 动画逐帧 `invalidate()` 保持持续渲染，维持峰值刷新率

## 构建

GitHub Actions 自动构建：push main 触发编译；tag `v*` 触发自动发布 Release。

- 工具链：AGP 8.5.2 / Gradle 8.9 / JDK 17 / minSdk 31 / compileSdk 34
- UI：GlassButtons v1.0.5（[仓库](https://github.com/GJR787878/GlassButtons)）

## 版本

- 1.0.0 初版：强制最高刷新率 + 奔跑小人动画（手机/平板通用）
