package com.blueledger.app.feature.entry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.app.ui.BlueLedgerTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 键盘硬性布局验证（真实 Compose 布局 + 真实节点 bounds，非像素对照）。
 *
 * 覆盖 320/360/390/430dp 宽 × 520/560/600/640/700/844dp 高。
 * 字体放大场景见 [EntryKeyboardLargeFontTest]（Robolectric 的文本度量不能按字体
 * 比例放大，故两件事分开验证，见报告「已知限制」）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EntryKeyboardLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: EntryTestHarness

    @Before
    fun setUp() {
        harness = EntryTestHarness()
    }

    private fun openEntry() {
        composeRule.setEntryContent(harness)
        composeRule.openAmountKeyboard()
        composeRule.waitUntil(10_000L) {
            composeRule.onAllNodesWithText("默认账户").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.settle()
    }

    private fun verify(label: String) {
        openEntry()
        composeRule.assertKeypadUsable(label)
    }

    // ── 320dp 压力宽度 ──

    @Test
    @Config(qualifiers = "w320dp-h700dp-xhdpi")
    fun keypadAvailableOn320x700() = verify("320x700@1.0")

    @Test
    @Config(qualifiers = "w320dp-h844dp-xhdpi")
    fun keypadAvailableOn320x844() = verify("320x844@1.0")

    /** 总控要求的最坏用例（可用高度 640dp）。 */
    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun keypadAvailableOn320x640ShortWindow() = verify("320x640@1.0")

    /** IME 压缩代理场景。 */
    @Test
    @Config(qualifiers = "w320dp-h600dp-xhdpi")
    fun keypadAvailableOn320x600ShortWindow() = verify("320x600@1.0")

    @Test
    @Config(qualifiers = "w360dp-h560dp-xhdpi")
    fun keypadAvailableOn360x560ShortWindow() = verify("360x560@1.0")

    @Test
    @Config(qualifiers = "w320dp-h520dp-xhdpi")
    fun keypadAvailableOn320x520ShortWindow() = verify("320x520@1.0")

    // ── 常见手机宽度 ──

    @Test
    @Config(qualifiers = "w360dp-h700dp-xhdpi")
    fun keypadAvailableOn360x700() = verify("360x700@1.0")

    @Test
    @Config(qualifiers = "w360dp-h844dp-xhdpi")
    fun keypadAvailableOn360x844() = verify("360x844@1.0")

    @Test
    @Config(qualifiers = "w390dp-h700dp-xhdpi")
    fun keypadAvailableOn390x700() = verify("390x700@1.0")

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun keypadAvailableOn390x844() = verify("390x844@1.0")

    @Test
    @Config(qualifiers = "w430dp-h700dp-xhdpi")
    fun keypadAvailableOn430x700() = verify("430x700@1.0")

    @Test
    @Config(qualifiers = "w430dp-h844dp-xhdpi")
    fun keypadAvailableOn430x844() = verify("430x844@1.0")

    /**
     * 顶栏不得自带状态栏内边距：总控 Scaffold 已经通过 innerPadding 提供顶部安全区。
     * 这里在页面外包一层 24dp 的「模拟 Scaffold 内边距」，顶栏顶边必须正好落在 24dp 处，
     * 多一个状态栏高度会立刻变红（防止以后有人再加一层 statusBarsPadding/insetsPadding）。
     */
    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun topBarStartsWithScaffoldInsetOnly() {
        val scaffoldTopInset = 24.dp
        composeRule.setContent {
            BlueLedgerTheme {
                Column {
                    Spacer(Modifier.height(scaffoldTopInset))
                    EntryRoute(
                        repository = harness.repository,
                        clock = harness.clock,
                        editTransactionId = null,
                        initialType = null,
                        initialCategoryId = null,
                        onExit = {},
                    )
                }
            }
        }
        composeRule.awaitTag(TAG_ENTRY_TOP_BAR)
        composeRule.settle()

        val density = composeRule.density
        val expectedTopPx = with(density) { scaffoldTopInset.toPx() }
        val topBarTop = composeRule.onNodeWithTag(TAG_ENTRY_TOP_BAR).fetchSemanticsNode().boundsInRoot.top
        val rootTop = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.top

        assertEquals(
            "顶栏顶部应恰好等于 Scaffold 内边距（不允许叠加状态栏内边距）：" +
                "topBar.top=$topBarTop root.top=$rootTop expectedInset=$expectedTopPx",
            expectedTopPx.toDouble(),
            (topBarTop - rootTop).toDouble(),
            2.0,
        )
    }
}
