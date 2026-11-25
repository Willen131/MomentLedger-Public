# 刻记（MomentLedger）v2.6.0

完全离线、零网络权限的 Android 本地记账应用（原生主干版）。

- **当前分支**：`main`（原生极简主干版，无主题美化与桌宠）
- **当前版本**：`v2.6.0`（`versionCode = 25`）
- **最低系统**：Android 8.0（API 26），`compileSdk = 35` / `targetSdk = 35`
- **APK 直下**：[`release/刻记-原版主干-v2.6.0.apk`](./release/%E5%88%BB%E8%AE%B0-%E5%8E%9F%E7%89%88%E4%B8%BB%E5%B9%B2-v2.6.0.apk)
- **主题美化版**：如需孤独摇滚主题 + 伪 Live2D 桌宠，请切换至 [`skin-pack`](../../tree/skin-pack) 分支。

## 核心特性

- **严格离线**：在 `AndroidManifest.xml` 中显式移除 `INTERNET`、`ACCESS_NETWORK_STATE` 及 `datatransport` 上报组件，应用永远无法联网。
- **离线 PP-OCRv5 识账与一键识屏**：内置离线 PP-OCRv5 ONNX 模型，支持无障碍一键识屏、相册多选或系统分享截图识别；自动推理商户、支付渠道及默认/自定义分类。
- **原生快捷悬浮球**：支持在其他应用上层一键「记一笔」或「一键识屏」，支持悬浮大小、弹出卡片大小（50%–150% 实时预览）、不透明度调节与息屏解锁自动恢复。
- **多源账单导入与智能去重**：支持微信/支付宝 CSV/XLSX、主流银行 PDF 流水（含密码解锁与水印过滤）、跨渠道时间/单号/拆分扣款/提现手续费去重。
- **日历、统计与预算**：日历多视角、可切换柱状/折线图表、自定义全天时段下钻、日/月/年多周期预算管理，以及本地 JSON 备份恢复与 CSV 导出。

## 本地构建

需要 JDK 17、Android SDK 35 与 Android Build Tools 35.0.0。配置 `ANDROID_HOME` 或在项目根目录添加本地 `local.properties` 后运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

> 注：正式发布签名密钥请仅保存在本地，切勿提交到公开仓库。