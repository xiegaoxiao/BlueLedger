# 页面修改约定

本项目的 Android 页面可能同时存在历史实现与当前实现。处理界面反馈时，先从 `app/src/main/java/com/blueledger/app/app/ui/BlueLedgerApp.kt` 的导航注册确认实际使用的 Route，再追踪对应组件；不能仅凭页面名称选择文件。

## 统一控件

- 页面主要保存操作复用 `LedgerPrimaryButton`；弹窗与行内操作复用 `LedgerActionButton`。
- 主操作使用蓝色实心，次操作使用蓝色描边，取消／关闭使用中性色；危险入口使用红色描边，最终危险确认使用红色实心。
- 删除、清除等确认优先复用 `LedgerConfirmDialog`，保留操作对象摘要与取消路径。
- 下拉箭头复用 `LedgerDropdownArrow`；前后跳转使用矢量图标。不要用字体字符替代交互图标。
- 收支、周期等单选控件应提供真实的选中语义，不能只通过文字中的勾号表达选中状态。

## 页面核验

- 编辑内容可滚动，保存／取消操作区应稳定可达；标题、按钮与长取值分别留出空间。
- 核对空态、有数据、编辑、确认、禁用与保存中状态。普通字号之外，还需检查 320dp 小屏及两倍字号；不能让不同筛选项被截成同样的文本，也不能截断金额。
- 交互修复先复现，再验证真实保存结果与取消行为。纯样式调整使用实际 Compose 页面截图核验，避免源码字符串断言。
- 截图和自动测试使用虚构账本。报告应区分离线渲染结果与真机／输入法／TalkBack 的核验结果。
- 运行 Gradle 使用 PowerShell 7 与 `tools/gradle-run.ps1`，由该脚本串行化共享构建；离线 Release／Lint 使用 `tools/offline-compose-mapping.init.gradle`。

本轮实例与截图记录：`docs/其他页面UI打磨2026-10-10.md`。
