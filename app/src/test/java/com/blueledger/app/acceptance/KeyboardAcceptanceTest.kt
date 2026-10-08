package com.blueledger.app.acceptance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 键盘验收测试（§9「键盘硬性布局要求」、§13「Compose 与设备交互」）。
 *
 * **阶段一正在验证的是验收框架本身**：
 * - 参考布局（合格）必须通过全部断言 → 证明断言不是"永远失败"；
 * - 4 个故意做坏的布局必须被拒绝 → 证明断言不是"永远通过"；
 * - 真实命中测试：逐键点击 → 显示 12.50 → 保存动作拿到 1250 分。
 *
 * 阶段二会把同一套 [KeypadHarness.assertLayout] 与逐键序列跑在 A2 的真实记账页上，
 * 并从 Room 仓库读回 amountCent == 1250。**本文件当前的结果不能算作 App 已验收。**
 *
 * Robolectric 固定 API 34（本机缓存：android-all 34/35）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardAcceptanceTest {

    @get:Rule
    val rule = createComposeRule()

    /** 在一个方法内跑多种字体缩放：窗口尺寸由方法级 @Config 限定，字倍由 LocalDensity 注入。 */
    private fun runMatrix(configs: List<WindowConfig>, screen: @Composable (AcceptanceProbe) -> Unit) {
        val fontScale = mutableStateOf(configs.first().fontScale)
        val probe = AcceptanceProbe()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale.value)) {
                AcceptanceProbeHost(probe) { screen(probe) }
            }
        }
        configs.forEach { config ->
            fontScale.value = config.fontScale
            rule.waitForIdle()
            probe.resetTransient()
            val report = KeypadHarness.assertLayout(rule, probe, config)
            // 原始证据写进测试输出（阶段二会汇总进 docs/测试报告.md）。
            println("[A7-KEYPAD] ${report.describe()}")
        }
    }

    private fun setScreen(fontScaleValue: Float, screen: @Composable (AcceptanceProbe) -> Unit): AcceptanceProbe {
        val probe = AcceptanceProbe()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScaleValue)) {
                AcceptanceProbeHost(probe) { screen(probe) }
            }
        }
        rule.waitForIdle()
        return probe
    }

    // ───────────────── 基线矩阵：320/360/390/430dp × 700/844dp × 1.0/1.3/2.0 ─────────────────

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h700dp-xxhdpi")
    fun `320x700 在 1_0 与 1_3 与 2_0 字倍下键盘完整可点`() {
        runMatrix(
            WindowMatrix.FONT_SCALES.map { WindowConfig(320, 700, it, WindowMatrix.qualifiers(320, 700)) },
        ) { probe -> ReferenceEntryScreen(probe) }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h844dp-xxhdpi")
    fun `320x844 在 1_0 与 1_3 与 2_0 字倍下键盘完整可点`() {
        runMatrix(
            WindowMatrix.FONT_SCALES.map { WindowConfig(320, 844, it, WindowMatrix.qualifiers(320, 844)) },
        ) { probe -> ReferenceEntryScreen(probe) }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h700dp-xxhdpi")
    fun `360x700 在 1_0 与 1_3 与 2_0 字倍下键盘完整可点`() {
        runMatrix(
            WindowMatrix.FONT_SCALES.map { WindowConfig(360, 700, it, WindowMatrix.qualifiers(360, 700)) },
        ) { probe -> ReferenceEntryScreen(probe) }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h844dp-xxhdpi")
    fun `360x844 在 1_0 与 1_3 与 2_0 字倍下键盘完整可点`() {
        runMatrix(
            WindowMatrix.FONT_SCALES.map { WindowConfig(360, 844, it, WindowMatrix.qualifiers(360, 844)) },
        ) { probe -> ReferenceEntryScreen(probe) }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h700dp-xxhdpi")
    fun `390x700 在 1_0 与 1_3 与 2_0 字倍下键盘完整可点`() {
        runMatrix(
            WindowMatrix.FONT_SCALES.map { WindowConfig(390, 700, it, WindowMatrix.qualifiers(390, 700)) },
        ) { probe -> ReferenceEntryScreen(probe) }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
    fun `390x844 在 1_0 与 1_3 与 2_0 字倍下键盘完整可点`() {
        runMatrix(
            WindowMatrix.FONT_SCALES.map { WindowConfig(390, 844, it, WindowMatrix.qualifiers(390, 844)) },
        ) { probe -> ReferenceEntryScreen(probe) }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w430dp-h700dp-xxhdpi")
    fun `430x700 在 1_0 与 1_3 与 2_0 字倍下键盘完整可点`() {
        runMatrix(
            WindowMatrix.FONT_SCALES.map { WindowConfig(430, 700, it, WindowMatrix.qualifiers(430, 700)) },
        ) { probe -> ReferenceEntryScreen(probe) }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w430dp-h844dp-xxhdpi")
    fun `430x844 在 1_0 与 1_3 与 2_0 字倍下键盘完整可点`() {
        runMatrix(
            WindowMatrix.FONT_SCALES.map { WindowConfig(430, 844, it, WindowMatrix.qualifiers(430, 844)) },
        ) { probe -> ReferenceEntryScreen(probe) }
    }

    // ───────────────── 可用高度被压缩（IME / 三键导航代理场景） ─────────────────

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h560dp-xxhdpi")
    fun `360x560 压缩高度下 2_0 字倍键盘仍在可用区`() {
        runMatrix(listOf(WindowConfig(360, 560, 2.0f, WindowMatrix.qualifiers(360, 560)))) { probe ->
            ReferenceEntryScreen(probe)
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h600dp-xxhdpi")
    fun `390x600 压缩高度下 1_3 字倍键盘仍在可用区`() {
        runMatrix(listOf(WindowConfig(390, 600, 1.3f, WindowMatrix.qualifiers(390, 600)))) { probe ->
            ReferenceEntryScreen(probe)
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h520dp-xxhdpi")
    fun `320x520 极端高度下 1_0 字倍键盘仍在可用区`() {
        runMatrix(listOf(WindowConfig(320, 520, 1.0f, WindowMatrix.qualifiers(320, 520)))) { probe ->
            ReferenceEntryScreen(probe)
        }
    }

    // ───────────────── 真实命中测试：逐键输入与保存 ─────────────────

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
    fun `逐键输入 12_50 显示一致且保存动作得到 1250 分`() {
        val probe = setScreen(1.0f) { p -> ReferenceEntryScreen(p) }

        KeypadHarness.pressKeys(
            rule,
            listOf(
                KeypadContract.KEY_1,
                KeypadContract.KEY_2,
                KeypadContract.KEY_DOT,
                KeypadContract.KEY_5,
                KeypadContract.KEY_0,
            ),
        )

        val display = KeypadHarness.amountText(rule)
        assertTrue("金额显示应包含 12.50，实际为「$display」", display.contains(Expect.KEYPAD_INPUT_TEXT))
        val digits = display.filter { it.isDigit() || it == '.' }
        assertEquals(
            "界面显示的金额换算成整数分必须精确等于 ${Expect.KEYPAD_INPUT_CENT}",
            Expect.KEYPAD_INPUT_CENT,
            Cent.fromYuanTextExact(digits),
        )

        rule.onNodeWithTag(KeypadContract.BTN_SAVE, useUnmergedTree = true).performClick()
        rule.waitForIdle()

        assertEquals(
            "保存动作必须拿到 1250 分（不是 1250.0、不是 12.5 分）",
            Expect.KEYPAD_INPUT_CENT,
            probe.savedAmountCent,
        )
        A7Evidence.record(
            "[PASS] 逐键 key_1 key_2 key_dot key_5 key_0 → 界面显示「$display」→ 独立换算 ${Cent.fromYuanTextExact(digits)} 分" +
                " → 保存回调 ${probe.savedAmountCent} 分",
        )
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h700dp-xxhdpi")
    fun `删除键与零点键的真实点击行为正确`() {
        val probe = setScreen(1.0f) { p -> ReferenceEntryScreen(p) }

        // 1 2 . 5 → 12.5
        KeypadHarness.pressKeys(
            rule,
            listOf(KeypadContract.KEY_1, KeypadContract.KEY_2, KeypadContract.KEY_DOT, KeypadContract.KEY_5),
        )
        assertTrue(
            "输入 12.5 后显示应含 12.5，实际「${KeypadHarness.amountText(rule)}」",
            KeypadHarness.amountText(rule).contains("12.5"),
        )

        // 0 → 12.50
        KeypadHarness.pressKeys(rule, listOf(KeypadContract.KEY_0))
        assertTrue(
            "补 0 后应显示 12.50，实际「${KeypadHarness.amountText(rule)}」",
            KeypadHarness.amountText(rule).contains("12.50"),
        )

        // 删除 → 12.5
        KeypadHarness.pressKeys(rule, listOf(KeypadContract.KEY_DELETE))
        assertTrue(
            "删除一位后应显示 12.5，实际「${KeypadHarness.amountText(rule)}」",
            KeypadHarness.amountText(rule).contains("12.5") &&
                !KeypadHarness.amountText(rule).contains("12.50"),
        )

        // 再删两位 → 12，删除键必须真实生效（不是装饰）
        KeypadHarness.pressKeys(rule, listOf(KeypadContract.KEY_DELETE, KeypadContract.KEY_DELETE))
        assertEquals(
            "连续删除后应回到 12",
            "12",
            KeypadHarness.amountText(rule).filter { it.isDigit() || it == '.' },
        )

        rule.onNodeWithTag(KeypadContract.BTN_SAVE, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals("删除后保存必须是 1200 分", 1_200L, probe.savedAmountCent)
        A7Evidence.record("[PASS] key_delete 真实生效：12.50 → 12.5 → 12，保存回调 ${probe.savedAmountCent} 分")
    }

    // ───────────────── 断言本身的有效性：故意做坏的布局必须被拒绝 ─────────────────

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
    fun `键盘被顶出窗口时必须判定失败`() {
        assertDetectorRejects("键盘整体下移 260dp") { probe ->
            val report = KeypadHarness.assertLayout(
                rule,
                probe,
                WindowConfig(390, 844, 1.0f, WindowMatrix.qualifiers(390, 844)),
            )
            println(report.describe())
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
    fun `按键小于 48dp 时必须判定失败`() {
        assertDetectorRejects("键盘行高 36dp") { probe ->
            KeypadHarness.assertLayout(rule, probe, WindowConfig(390, 844, 1.0f, WindowMatrix.qualifiers(390, 844)))
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
    fun `小数点被做成 0dp 时必须判定失败`() {
        assertDetectorRejects("小数点尺寸 0dp") { probe ->
            KeypadHarness.assertLayout(rule, probe, WindowConfig(390, 844, 1.0f, WindowMatrix.qualifiers(390, 844)))
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
    fun `键盘被挤到窗口右侧时必须判定失败`() {
        assertDetectorRejects("键盘每行超出窗口右边界") { probe ->
            KeypadHarness.assertLayout(rule, probe, WindowConfig(390, 844, 1.0f, WindowMatrix.qualifiers(390, 844)))
        }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
    fun `保存区被遮挡时点击必须落不到按钮上`() {
        val probe = setScreen(1.0f) { p -> SaveButtonCoveredScreen(p) }

        // 键盘仍然可点（证明不是整页不可点）
        KeypadHarness.pressKeys(
            rule,
            listOf(KeypadContract.KEY_1, KeypadContract.KEY_2, KeypadContract.KEY_5, KeypadContract.KEY_0),
        )
        assertTrue(
            "被遮挡场景下键盘本身仍应可点，实际显示「${KeypadHarness.amountText(rule)}」",
            KeypadHarness.amountText(rule).contains("1250"),
        )

        rule.onNodeWithTag(KeypadContract.BTN_SAVE, useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertNull(
            "保存按钮被透明遮罩压住时，点击不得产生保存动作；" +
                "若这里拿到了金额，说明『命中测试』判据失效（警告：假通过）",
            probe.savedAmountCent,
        )
    }

    /** 与上一个用例配对的正向对照：同样输入在合格布局上必须真的保存成功。 */
    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
    fun `同样输入在合格布局上必须保存成功`() {
        val probe = setScreen(1.0f) { p -> ReferenceEntryScreen(p) }

        KeypadHarness.pressKeys(
            rule,
            listOf(KeypadContract.KEY_1, KeypadContract.KEY_2, KeypadContract.KEY_5, KeypadContract.KEY_0),
        )
        rule.onNodeWithTag(KeypadContract.BTN_SAVE, useUnmergedTree = true).performClick()
        rule.waitForIdle()

        assertEquals("1250 = 12.50 元", 125_000L, probe.savedAmountCent)
    }

    private fun assertDetectorRejects(layoutName: String, block: (AcceptanceProbe) -> Unit) {
        val probe = AcceptanceProbe()
        val screen: @Composable (AcceptanceProbe) -> Unit = when (layoutName) {
            "键盘整体下移 260dp" -> { p -> ClippedKeypadScreen(p) }
            "键盘行高 36dp" -> { p -> UndersizedKeysScreen(p) }
            "小数点尺寸 0dp" -> { p -> HiddenDotKeyScreen(p) }
            else -> { p -> OffScreenColumnScreen(p) }
        }
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.0f)) {
                AcceptanceProbeHost(probe) { screen(probe) }
            }
        }
        rule.waitForIdle()

        val error = runCatching { block(probe) }.exceptionOrNull()
        assertTrue(
            "布局「$layoutName」明显不符合 §9，但验收断言放过了它（说明断言是假通过）。" +
                "实际异常：${error?.javaClass?.name}: ${error?.message?.take(400)}",
            error is AssertionError,
        )
        A7Evidence.record(
            "[REJECTED] $layoutName 被断言判失败 -> ${error!!.javaClass.name}: ${error.message?.lineSequence()?.first()?.take(160)}",
        )
    }
}
