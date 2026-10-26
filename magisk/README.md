# Tubing App 自动执行模块

本模块通过 Magisk `service.sh` 启动前台 App 监测，配合此仓库的新版本 APK 使用。模块按 [Magisk 官方模块规范](https://topjohnwu.github.io/Magisk/guides.html)打包，无需开启 Zygisk。

## 安装与绑定

1. 安装本次构建的 `tubing-v1.4.2.apk`（versionCode 17，覆盖原 Tubing，保留数据）。旧版 APK 没有自动执行接口。
2. 可在 App「设置 → 面具模块 · App 自动执行 → 安装 / 更新配套模块」将内置 `tubing-app-trigger-v1.1.1.zip` 交给 Magisk / KernelSU 安装；不支持直接接收 ZIP 的管理器版本会打开首页，手动从模块页选择 `Download/Tubing` 中的 ZIP。安装后重启。也可以使用独立 ZIP 从本地安装。
3. 在 Tubing 创建快捷方式，选择脚本及所需输入方案。
4. 打开「设置 → 面具模块 · App 自动执行 → 添加 / 修改」，搜索并选择已安装的 App，再选择快捷方式，并授予 Tubing Root 权限。未列出的 App 仍可手动填包名。每个包名绑定一个快捷方式，多 App 可共用。
5. 切到桌面，再打开目标 App，通常约 2～6 秒后确认进入前台；Tubing 会在实际启动前再次复核前台与锁屏状态，再按当前 Pipe / PTY 设置运行脚本。输入方案默认按旧版延时发送，无需提示词；也可在方案里选择等待提示词。执行通知可停止任务。

若模块派发结果显示 `Blocked by AutoLaunch`，需要在手机系统中允许 Tubing 自启动。红魔 NX769J 的入口为「掌心管家 → 工具 → 应用管控 → 自启动 → Tubing → 允许」；修改后离开目标 App，再重新进入。模块已检测到 App 时也可能被这项系统策略拦截。

修改脚本、方案内容会在下次触发时生效。快捷方式重命名或删除后，对应绑定不会再执行，需要重新绑定。删除绑定立即在 App 内失效；Root 同步失败可点「重新同步」。删除所有绑定可暂停全部自动执行；也可在 Magisk 禁用模块。

## 触发规则与限制

- 普通前台每 4 秒采样，命中目标后每 2 秒确认；熄屏每 5 秒、无绑定每 10 秒；查询错误退避，最大 15 秒。快速打开又离开的 App 可能不触发。刚同步、刚解锁或故障恢复时可能需要更久。
- 已有脚本运行、准备启动或处于启动冷却期时跳过，不排队、不重试；请等待脚本结束后离开并重新进入 App。
- 仅支持机主环境（当前完整用户为 user 0）。同包名系统分身共用方案和策略，前台身份按用户标识区分；完整切换到另一 Android 用户不触发。锁屏和熄屏暂停检测，解锁后重新确认。
- 无法识别的前台记录不触发。Android 厂商可能修改 `dumpsys` 输出，兼容性需要真机验证。分屏按系统的 top resumed activity 判断。
- 不拉起 Tubing 界面；App 没有任何绑定时不执行。桥接服务受 `android.permission.DUMP` 保护，Root / shell 以及持有此权限的系统特权应用可调用，普通第三方 App 无法调用。
- 默认每绑定冷却 60 秒、任务超时 120 秒，可点击绑定修改并设置单次启用；输入默认就绪后等待 1.5 秒、后续间隔 0.9 秒发送，选择等待提示词时每项等待上限为 30 秒。输入加密、敏感方案和日志策略详见[改进清单](../AUTOMATION_SAFETY.md)。`DUMP` 也可能授予部分系统特权应用，因此不应将此权限描述为模块专属认证。
- 绑定文件 `/data/adb/tubing-automation/packages` 仅保存包名，方案和输入保存在 App 私有数据中。卸载模块保留此文件供重装使用。
- 在模块管理器点击模块“操作”按钮可查看运行状态、最近派发结果和已同步包名；`status.log` 记录等待配置、锁屏暂停或前台识别状态，`monitor-output.log` 记录启动错误，`last-trigger.log` 记录系统派发结果。查看 `adb logcat -s TubingAutomation` 检查快捷方式解析错误。派发成功不代表脚本执行成功，执行结果请查看 Tubing 终端与历史。

## 构建

在项目根目录运行 `powershell -File magisk/build.ps1`。ZIP 根目录直接包含 `module.prop`、`customize.sh` 和监测脚本，供 Magisk App 安装；不支持 Recovery 刷入。
