# Flyme 相册目录

让 Flyme 系统照片选择器的「相册」页按本地目录显示 MediaStore 已索引、原本可选的图片和视频。ChatGPT、Gemini 等 App 可以通过原有的选图入口浏览这些目录。系统继续处理图片选择和 URI 授权。

有 `.nomedia` 的目录及其子目录会被过滤。列表和打开自定义相册时都会检查标记，因此媒体索引尚未更新时也可以生效。系统自带的「照片」页和内置相册按 MediaStore 的扫描结果刷新。

模块在运行时反射查找 MediaProvider 的查询方法。编译器生成的事务方法按类型签名匹配。只有匹配结果唯一且通过目录过滤验证时，模块才会添加自定义相册。

## 安装

已验证环境为魅族 21 Pro、Flyme 12.7.0.0A（Android 16）、MediaProvider 12.1.11 和 LSPosed IT 2.2.1。

1. 从 GitHub Releases 下载 APK 并安装。
2. 在 LSPosed 管理器启用「Flyme 相册目录」，将作用域设为 `com.android.providers.media.module`（媒体存储设备）。
3. 重启手机，再从 App 的照片入口切到「相册」。

恢复系统默认列表时，在 LSPosed 管理器关闭模块并重启手机。

## 构建

在项目目录用 PowerShell 7 运行以下命令。脚本使用 Docker 中的 JDK 8 和 Java 21，并按锁定的 SHA-256 下载和校验构建依赖。

```powershell
pwsh -File ./build.ps1
```

产物位于 `build/flymepicker.apk`。首次构建会生成本地签名密钥。同包名升级时，请复用原签名密钥和密码。将密钥放在 `build/flymepicker.p12`，在构建前通过 `FLYME_PICKER_KEY_PASSWORD` 提供密码。请保留自己生成的密钥和 `build/signing-password`，供后续构建使用。

## 实机验证

- ChatGPT 和 Gemini 的原生照片选择器都显示了新增目录。
- 验证了单选、多选上限和原生 Picker URI。测试 App 无法读取另一张未选中的图片。
- 在测试目录的父目录添加 `.nomedia` 后，媒体索引还保留图片记录时，打开已有相册卡片已返回空列表。重新打开 Picker 后，相册卡片消失，普通目录仍显示。
