# 刻记（MomentLedger）

刻记 v2.5.5 是一款完全离线的 Android 本地记账应用，支持 Android 8.0 及以上版本。

- 手动记录收支，可自定义分类、支付渠道，支持通知栏快捷入口和系统快捷磁贴。
- 导入微信、支付宝及通用 CSV/XLSX 账单、银行 PDF 流水，支持截图离线 OCR、导入核对和跨渠道去重。截图识别可区分支付渠道与付款方式。
- 日历提供支出、收入、净支出和净收入视图。时刻统计支持按日、周、月查看、自定义全天时段、切换图表和查看时段明细。
- 按日、月、年查看收支和预算。日预算可多选日期批量设置，覆盖前需确认。月、年预算可自定义账期，超支时可展开逐笔支出。
- 支持本地 JSON 账本备份与恢复，以及账单 CSV 导出。

## 构建

需要 JDK 17、Android SDK 35 和 Android Build Tools 35.0.0。设置 `ANDROID_HOME`，或在项目根目录的本地 `local.properties` 中配置 SDK 路径。在 Windows PowerShell 中运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

调试 APK 在 `app/build/outputs/apk/debug/`。正式发布请配置自己的签名，签名文件和密码应仅保存在本地。自行签名的 APK 与原签名的 APK 使用不同签名，Android 会限制覆盖安装。

本仓库发布 v2.5.5 应用源码和经过筛选的合成测试。
