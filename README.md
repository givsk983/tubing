<p align="center">
  <img src="./assets/readme/hero.svg" width="100%" alt="tubing：面向已 Root Android 设备的脚本、ELF 与交互终端执行控制台">
</p>

<p align="center">
  <a href="https://github.com/yunhe4550-design/Tubing/releases/tag/v1.3.0"><img alt="Release v1.3.0" src="https://img.shields.io/badge/release-v1.3.0-28B9CF?style=flat-square"></a>
  <img alt="Android 8.0 or newer" src="https://img.shields.io/badge/Android-8.0%2B-28B9CF?style=flat-square&logo=android&logoColor=white">
  <img alt="Root required" src="https://img.shields.io/badge/Root-required-101820?style=flat-square">
  <img alt="Kotlin 2.0.21" src="https://img.shields.io/badge/Kotlin-2.0.21-101820?style=flat-square&logo=kotlin&logoColor=white">
</p>

<p align="center">
  在 Android 上导入、管理并以 Root 权限运行 SH 脚本或 ELF 文件。<br>
  需要逐行输入时使用 Pipe，需要完整终端语义时切换 PTY。
</p>

<p align="center">
  <a href="https://github.com/yunhe4550-design/Tubing/releases/tag/v1.3.0">下载 APK</a>
  · <a href="#快速开始">快速开始</a>
  · <a href="./ARCHITECTURE.md">架构说明</a>
  · <a href="./TESTING.md">测试清单</a>
  · <a href="https://t.me/mbazhuan">Telegram 反馈</a>
</p>

> [!WARNING]
> tubing 会在设备上执行 Root 命令。请只导入你已阅读并信任的文件。当前 `v1.3.0` 是 Debug 签名测试版，不同 Root 管理器、输入法与 OEM 后台策略仍需真机验证。

## 它解决什么问题

很多 Root 脚本不只是“一次点击”：它们需要交互输入、持续输出、后台存活、可追踪日志，偶尔还要完整的终端能力。tubing 把这些环节放在一个本地 Android 应用里，并让每次执行都经过明确的授权、校验与生命周期管理。

| 能力 | 你会得到什么 |
| --- | --- |
| 双执行后端 | Pipe 适合普通脚本与逐行输入；PTY 适合密码隐藏、单键读取、方向键菜单与全屏终端程序 |
| Root 会话 | 每次运行前重新确认 Root、权限、SELinux 状态与 `noexec` 挂载限制 |
| 交互终端 | 内置离线 xterm.js，流式显示 ANSI 输出并接收键盘与输入法内容 |
| 快捷启动 | 为脚本绑定输入方案，使用简洁首页一键启动；也可随时切回完整界面 |
| 文件管理 | 双栏浏览已导入目录与设备存储，支持复制、重命名、删除、新建目录和路径收藏 |
| 会话与记录 | 前台服务维持运行，可保存日志；任务被划走时尝试终止对应进程组 |

## 执行路径

<p align="center">
  <img src="./assets/readme/execution-flow.svg" width="100%" alt="tubing 从文件导入、安全校验、Root 会话到终端输出的四步执行流程">
</p>

所有托管文件位于：

```text
/data/adb/root_executor/
├── scripts/      # 导入的 SH 脚本
├── binaries/     # 导入的 ELF 文件
├── logs/         # 可选执行日志
└── tmp/          # 会话 PID、取消标记与临时数据
```

## 快速开始

### 1. 准备设备

- Android 8.0（API 26）或更高版本
- 可用的 `su` 实现与 Root 管理器
- 支持 `setsid` 的 Android 环境

### 2. 安装测试版

从 [GitHub Releases](https://github.com/yunhe4550-design/Tubing/releases/tag/v1.3.0) 下载 `tubing-v1.3.0.apk`，或通过 ADB 安装：

```bash
adb install -r tubing-v1.3.0.apk
```

本地仓库中 APK 的 SHA-256：

```text
b40e769d181f2ac8844b3ada0841fd1c97bebe4099e59f7e041022c7b376c7a7
```

### 3. 完成第一次执行

1. 打开 tubing，在 Root 管理器中允许授权。
2. 导入 `.sh` 脚本或 Android 可执行 ELF 文件。
3. 选择 **Pipe** 或 **PTY** 执行方式。
4. 选中文件并启动，在终端中查看输出、提交输入与确认退出码。

## Pipe 还是 PTY？

| 场景 | Pipe | PTY |
| --- | :---: | :---: |
| 普通命令、逐行文本输入 | ✅ 推荐 | ✅ |
| 无换行提示的即时输出 | ✅ | ✅ |
| 隐藏密码输入（`stty -echo`） | — | ✅ 推荐 |
| 单键读取、方向键菜单、全屏 TUI | — | ✅ 推荐 |
| 最少依赖，不加载 JNA | ✅ | — |

Pipe 模式会在按下回车后提交一行输入；程序自身的缓冲仍可能让提示延迟。需要真实终端尺寸、回显控制或原始按键时，请使用 PTY。

## 从源码构建

需要 JDK 17 与 Android SDK 35。Windows 下可直接运行：

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
```

Debug APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

> `local.properties` 与仓库中的本地 SDK 目录均为机器专属配置，不应提交。

## 安全边界

tubing 尽量缩小高权限操作的意外范围，但它不是 Root 沙箱：

- Root 文件操作集中在 `RootExecutor`，每次操作只调用一次 `su -c`，不嵌套提权。
- 导入、修改与执行前检查目录、常规文件、符号链接、文件权限及名称。
- Shell 参数使用单引号转义；导入名称经过保守字符过滤并添加随机后缀。
- ELF 文件会检查魔数；运行前会重新应用并验证权限，同时检查 `noexec`。
- 强行停止、断电、OEM 进程清理或脚本主动守护化，无法保证触发正常的会话清理。
- Pipe 模式不能遵守终端密码回显控制；敏感输入请使用 PTY，并谨慎启用输入记忆。

更完整的实现与限制见 [ARCHITECTURE.md](./ARCHITECTURE.md)。

## 项目结构

```text
app/src/main/java/com/stealth/rootexecutor/
├── MainActivity.kt       # 授权、终端、文件管理与设置界面
├── ExecutionService.kt   # 前台服务、会话状态与生命周期
├── RootExecutor.kt       # Root 文件操作、校验与启动命令
├── PipeSession.kt        # 标准输入输出管道后端
├── PtySession.kt         # 基于 Bionic/JNA 的 PTY 后端
└── PipeInput.kt          # Pipe 行编辑、控制键与本地回显
```

## 当前状态

`v1.3.0` 已包含快捷/完整界面、输入历史与方案、快捷方式、双栏文件管理、路径收藏，以及 Pipe/PTY 两种执行后端。发布包仍标记为测试版；Root、SELinux、锁屏、任务划走与输入法兼容性请按 [TESTING.md](./TESTING.md) 在真实设备上验证。

## 第三方组件与许可

- [xterm.js 5.5.0](https://github.com/xtermjs/xterm.js) — MIT License
- [JNA 5.15.0](https://github.com/java-native-access/jna) — Apache License 2.0 选项

详情见 [THIRD_PARTY.md](./THIRD_PARTY.md)。当前仓库尚未提供项目级 `LICENSE` 文件；在许可证明确前，请勿假定代码可被复制、修改或再分发。

## 反馈

发现兼容性问题时，请附上 Android 版本、Root 方案、所选执行模式与可脱敏的终端输出：

- [GitHub Issues](https://github.com/yunhe4550-design/Tubing/issues)
- [Telegram 反馈频道](https://t.me/mbazhuan)
