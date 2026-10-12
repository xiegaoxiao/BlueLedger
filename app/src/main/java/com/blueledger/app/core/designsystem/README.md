# 公共组件入口

页面直接组合本目录的 `Ledger*` 组件。布局顺序、页面外边距、数据订阅、草稿、导航与保存操作由页面负责；组件接收展示值和事件回调，不访问 Repository 或页面 ViewModel。

| 用途 | 组件 | 文件 |
| --- | --- | --- |
| 顶栏、卡片、区块标题 | `LedgerTopBar`、`LedgerSectionCard`、`LedgerSectionHeader` | `LedgerLayout.kt` |
| 居中顶栏、弹层标题、下划线标签、下拉按钮与箭头、导航行 | `LedgerCenteredTopBar`、`LedgerSheetHeader`、`LedgerUnderlineTabs`、`LedgerDropdownButton`、`LedgerDropdownArrow`、`LedgerNavigationRow` | `LedgerPageComponents.kt` |
| 图标菜单、菜单分隔线、设置分组、开关、单选行 | `LedgerMenuRow`、`LedgerMenuDivider`、`LedgerSectionLabel`、`LedgerSwitchRow`、`LedgerChoiceRow` | `LedgerSettingsComponents.kt` |
| 选择器容器、原生数字滚轮 | `LedgerPickerSheet`、`LedgerWheelColumn` | `LedgerPickerComponents.kt` |
| 页面主操作、次操作、分类与账户图标 | `LedgerPrimaryButton`、`LedgerSecondaryButton`、`LedgerCategoryCell`、`LedgerAccountIcon` | `LedgerControls.kt` |
| 弹窗与行内按钮、危险确认 | `LedgerActionButton`、`LedgerConfirmDialog` | `LedgerActionButton.kt`、`LedgerFeedback.kt` |
| 键值行、账单行、汇总卡 | `LedgerKeyValueRow`、`LedgerTransactionRow`、`LedgerSummaryCard` | `LedgerDataViews.kt` |
| 可适配宽度的金额 | `LedgerFittedAmountText` | `LedgerFittedAmountText.kt` |
| 金额数字键盘 | `LedgerAmountKeyboard` | `LedgerAmountKeyboard.kt` |
| 折线图 | `LedgerLineChart` | `LedgerLineChart.kt` |
| 分段框、环形图、进度摘要、占比图、排行 | `LedgerSegmentedControl`、`LedgerDonutChart`、`LedgerProgressSummary`、`LedgerCompositionChart`、`LedgerRankingRow` | `LedgerChartComponents.kt` |
| 余额摘要、年份切换、分组与账户行 | `LedgerBalanceSummaryCard`、`LedgerYearNavigation`、`LedgerExpandableGroupHeader`、`LedgerBalanceAccountRow` | `LedgerBalanceComponents.kt` |
| 总预算与分类预算平铺摘要、预算空态图 | `LedgerBudgetSummary`、`LedgerBudgetEmptyIllustration` | `LedgerBudgetComponents.kt` |
| 资产选择行、平铺表单、总览与详情卡、账户行 | `LedgerAssetSelectionRow`、`LedgerInlineField`、`LedgerOverviewCard`、`LedgerAssetDetailCard`、`LedgerAssetListRow` | `LedgerAssetComponents.kt` |
| 银行图形标记 | `LedgerBankMark` | `LedgerBankMark.kt` |
| 账单年份与周期工具栏、金额表格行 | `LedgerBillToolbar`、`LedgerBillTableRow` | `LedgerBillComponents.kt` |
| 月账单头部、双期结余卡、成就卡及内容区块 | `LedgerMonthlyReportHeader`、`LedgerMonthlyBalanceCard`、`LedgerMetricsCard`、`LedgerReportSection` | `LedgerMonthlyReportComponents.kt` |

## 组合规则

- 先查组件目录，再扩展已有组件。业务页面不要再创建仅转发参数的 `ReferenceTitle`、`ReferenceMoney` 等别名包装。
- 用 `Modifier` 设置页面的排布、宽度、边距和测试定位；用组件参数选择已有外观。菜单卡直接使用 `LedgerSectionCard`，不再另建卡片实现。
- 选择组件接收当前值并通过回调上报选择。日期合法性、未来日期限制、账户规则和金额计算留在 feature 层。
- `ReferenceDatePicker`、`ReferenceMonthPicker` 保留日期草稿与可选范围；底部容器、数字滚轮和无障碍动作由公共组件实现。
- `ReferenceCategoryRing` 保留分类数据到图表切片的映射；`ReferenceLineChart` 保留该页面的金额格式策略。图形绘制交给公共图表组件。
- 扩展组件后验证使用它的实际 Route，覆盖真实交互、取消路径与 320dp 普通字号小屏；大字号仅按用户要求核验，截图使用虚构账本。
- 未设置预算时组合 `LedgerEmptyState(subtle = true)` 与“添加预算”主操作；不显示零预算和空环形图。键盘的金额解析与声音／震动偏好由页面处理，公共键盘只负责按键布局、语义、反馈事件和禁用状态。

```kotlin
LedgerSectionCard(
    modifier = Modifier.padding(horizontal = T.SpaceL, vertical = T.SpaceM),
    contentPadding = PaddingValues(0.dp),
    elevation = 0.dp,
) {
    LedgerMenuRow("设置", Icons.Outlined.Settings, onClick = onSettings)
    LedgerMenuDivider()
    LedgerMenuRow("数据管理", Icons.Outlined.Backup, onClick = onData)
}
```
- 图表页使用 LedgerSegmentedControl 的可选紧凑高度及 LedgerLineChart 的 compact／empty 外观。空态通过 contentPadding 调整布局；数据为空的判断、周期范围和金额计算由页面提供，其他调用保留默认外观。
- LedgerTopDropdown 将全宽单选面板锚定到指定控件下沿，并遮罩底栏；支持遮罩／返回键关闭。LedgerChoiceRow 的 leadingIcon、horizontalPadding、dividerStart 用于组合图标选择行，现有调用默认外观不变。
- 资产公共组件只接收展示值与事件；账户种类、银行选择、后四位保存、余额校准、转账及移除规则由页面与仓库处理。参考比例按 361dp 基准缩放；紧凑月份选择使用 LedgerDropdownButton 的 height／textStyle 参数，详情明细使用 LedgerTransactionRow 的 compactScale／leadingIcon 参数。金额继续复用统一隐藏与完整金额适配。
- 账单与资产使用 LedgerOverviewCard，通过标签及金额参数组合。账单工具栏复用 LedgerSegmentedControl 的紧凑高度、字号与圆角参数，真实选中语义保留；年度与账期计算在页面处理，LedgerBillTableRow 只展示列值与钻取事件。
- 月账单使用公共双期结余卡与指标卡，最长连续记账天数在页面按实际日期计算，视觉组件只接收标签、数值及单位。页头不包含用户系统或分享入口；LedgerCenteredTopBar 可选择透明背景以融入页头，其他页面保留原背景。
- 月份选择复用 LedgerPickerSheet 的 compact 外观：32dp 文字操作栏、蓝色文字确认、244dp 内容高度。LedgerWheelColumn 支持字号、浅选中线和相邻预览范围；预览范围不扩大可选范围，原生滑动与无障碍动作均按页面提供的有效范围限制。其他选择器保留默认外观。
