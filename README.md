# 蓝记 BlueLedger

一个使用 Kotlin、Jetpack Compose 和 Room 实现的离线 Android 记账应用。蓝色主题，账本保存在本机，无广告、无会员功能。

**源码版本：1.2.0 · 最新发布 APK：1.2.0 · Android 8.0（API 26）及以上 · Apache-2.0**

[下载 APK](https://github.com/xiegaoxiao/BlueLedger/releases/latest) · [构建说明](docs/开源构建说明.md) · [代码结构](docs/架构说明.md) · [许可证](LICENSE)

## 功能

- 收入与支出记账，分类、备注、账户、日期滚轮及加减金额键盘。
- 按天查看明细，编辑账单、删除与撤销、隐藏金额。
- 周／月／年图表、分类占比、月／年账单和月报告图片分享。
- 月度预算、资产与负债管理，按支付宝、微信、银行卡分组；每个小荷包独立记录，可连续添加账户和核对余额。
- 分类新增、编辑、归档及排序，长按手柄拖动，跨屏移动可使用上移／下移菜单。
- 完整 JSON 备份、校验与恢复、CSV 导出，保存目录选择一次后自动记住。
- 默认记账类型、图表周期、收支账户设置及本地记账提醒。
- 分类预算、标签、日历、自定义记账月起始日，以及删除账单的回收站。
- 按日／周／月／年自动记账，支持暂停规则和启动时补记；省电限制或强制停止可能延后后台执行。

应用没有网络权限。启用提醒时请求通知权限；导入导出使用 Android 系统文件访问框架，不申请存储权限。首次使用创建默认分类和账户，账本从空记录开始。

本地高级功能入口为「我的 → 设置 → 高级功能」。当前备份格式为 schema 4，支持导入 schema 1、2、3；恢复仍会替换现有账本，先校验和预览，再确认。周期规则的开始日期在过去时会补记，保存前请核对日期和金额。

## 资产管家

在「我的 → 资产管家」添加支付宝余额、微信零钱、每个小荷包和每张银行卡，输入当前余额即可。平台和小荷包标题显示汇总，只有实际账户参与总额计算。使用「保存并继续添加」连续录入；「更新余额」根据最新账单核对余额，记录核对日期，不增加收入或支出。现有账户会按名称与类型识别分组，可在编辑账户中更改位置。余额趋势从首次余额记录开始展示。

「图表」按年度走势、当前占比、账户排行榜排列。资产、负债与净资产切换栏固定在上方；净资产保留欠款符号，占比按金额绝对值计算。折线、环形图、排行与切换控件和收支图表、月报告、预算共用组件；缺失月份不补零。

<p>
  <img src="docs/images/asset-accounts.png" width="240" alt="虚构四个小荷包与支付宝汇总">
  <img src="docs/images/asset-editor-large-font.png" width="240" alt="320dp 两倍字号连续录入账户">
  <img src="docs/images/asset-charts.png" width="240" alt="虚构资产趋势、占比和账户排行榜">
</p>

## 界面预览

以下图片由 Robolectric 使用虚构账本生成，展示 1.2.0 的首页、周图表和我的页面。主要界面使用统一的品牌蓝、卡片边距与图标尺寸；周图表完整显示七天刻度，切换历史周时保持绘图区位置。

<p>
  <img src="docs/images/home-1.2.0.png" width="240" alt="首页与快捷卡片">
  <img src="docs/images/chart-1.2.0.png" width="240" alt="周图表与七天日期">
  <img src="docs/images/mine-1.2.0.png" width="240" alt="我的与功能菜单">
</p>

日期滚轮、分类设置和默认周期选择页：

<p>
  <img src="docs/images/date-picker.png" width="240" alt="记账日期滚轮">
  <img src="docs/images/category-settings.png" width="240" alt="分类设置">
  <img src="docs/images/settings-period.png" width="240" alt="默认图表周期">
</p>

## 开发

安装 JDK 21 与 Android SDK Platform 37，使用项目自带的 Gradle Wrapper：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintRelease :app:assembleRelease
```

Windows 使用 `gradlew.bat`。Release 构建启用代码和资源压缩，源码没有内置签名密钥；自行发布需要自己的签名配置。详细环境、输出路径和离线构建方法见[构建说明](docs/开源构建说明.md)。

项目包含业务、Room、备份与 Compose UI 测试。2026-10-12 发布回归为 **28 类、307 项全部通过**，覆盖数据库迁移、账本读写、备份恢复、公共组件、资产、预算、月年账单、月报告、月份选择与重复切页；Release 构建和 Lint 通过（0 errors / 48 warnings / 1 hint）。当前页面检查采用普通字号和 320dp 小屏，Compose 结果来自离线渲染，不作为真机输入法或 TalkBack 验收。1.2.0 原有版本另有 56 类、660 项完整回归记录。自动构建模板在 [docs/ci/android.yml](docs/ci/android.yml)，当前尚未启用在线 CI。

## 参与贡献

欢迎提交 Issue 和 Pull Request。复现问题时说明应用版本、Android 版本及操作步骤；截图和备份请使用虚构账本，移除个人信息。文档、预览和发布说明应描述蓝记自身的功能，避免使用第三方产品名称作对标、宣传或关联暗示；第三方依赖的许可与署名信息应保留。涉及金额、数据库迁移、备份恢复或排序的修改，请附相应测试。

## 许可证与依赖

项目源码采用 [Apache License 2.0](LICENSE)。版权与第三方说明见 [NOTICE](NOTICE) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

蓝记使用自身的项目名称、蓝色主题与项目图标。公开界面预览由虚构账本生成；仓库不分发第三方记账产品的代码、标志、品牌素材、界面截图或用户账本。本项目说明不表示与任何第三方产品存在合作、授权或背书关系。使用的开源依赖与图标遵循各自许可和署名要求。
