# 维护交接

实测环境为魅族 21 Pro、Flyme 12.7.0.0A（Android 16）、MediaProvider 12.1.11 和 LSPosed IT 2.2.1。其他系统版本需要单独验证。欢迎提交实测结果和修复。

## 代码入口

[`FolderAlbums.java`](src/dev/codex/flymepicker/FolderAlbums.java) 只在 `com.android.providers.media.module` 主进程加载。相册查询从系统允许展示的 MediaStore 记录中按卷和 `relative_path` 分组。打开新增相册时，媒体查询按同一个卷和路径筛选，并重新检查目录及其上级的 `.nomedia`。选图结果和 URI 授权仍由系统生成。

查询、过滤和卷路径方法通过类型签名定位。编译器生成的事务方法还要求是合成实例方法，且只允许唯一匹配。同签名的 Provider 回调，以及 `runWithTransaction` 等需要区分具体行为的入口，按名称匹配。新增相册依赖一次实际的目录过滤验证。遇到方法签名变动时，先核对新的调用链和查询结果，再调整匹配条件。

## 验证与排查

1. 用两份自己生成的测试图，在共享存储下新建两个同名子目录，分别放在不同的父目录下。打开调用系统 Photo Picker 的 App，确认两个相册各自只显示本目录的图。
2. 用无存储权限的测试 App 选择由另一应用或 ADB 写入的样本。确认返回的 `content://media/picker/…` URI 可读，未选测试图的访问被拒绝。测试多选时，检查调用方设置的选择上限。
3. 在一个测试相册的父目录加入 `.nomedia`，确认此前显示的相册打开后为空。重新打开 Picker，检查该卡片已隐藏，另一个测试相册仍可用。验证旧索引时，同时确认对应的图片记录仍在 MediaStore 中。
4. 在 LSPosed 日志里查找 `FlymeFolderAlbums`。`MediaProvider hooks installed` 表示方法已定位，`directory filter verified=true` 表示运行时过滤验证通过。报告问题时附上系统、MediaProvider 和 LSPosed 版本及合成样本的复现步骤。

需要恢复系统默认相册列表时，在 LSPosed 管理器停用模块并重启手机。若要安装旧版，须使用与当前版本相同的签名，并遵循设备的应用降级规则。

## 构建与发布

安装 Docker 和 PowerShell 7，在仓库根目录运行以下命令。

```powershell
pwsh -NoProfile -File ./build.ps1
```

脚本校验 `dependencies.lock` 中的 SHA-256，使用 Docker 内的 JDK 8 和 Java 21 构建，产物为 `build/flymepicker.apk`。本地测试可以使用脚本生成的签名。发布覆盖安装的更新时，使用与上一版相同的签名，将密钥放在 `build/flymepicker.p12`，通过 `FLYME_PICKER_KEY_PASSWORD` 提供密码。签名材料放在仓库忽略的构建目录里。新签名的构建只能按设备的重新安装流程部署。
