package com.blueledger.app.feature.entry

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 「系统字体 2.0 倍 + 小屏」下的键盘与保存区验证。
 *
 * 字体比例必须在 `setContent` **之前**写进 Activity 的 Configuration，
 * 否则 Compose 视图已经用旧配置建好了密度，注入不会生效
 * （A2 实测：仅用 CompositionLocalProvider(LocalDensity) 无效，1.0/2.0 都是 35px）。
 *
 * 已知限制（见交接报告）：Robolectric 的文本度量不能按比例放大
 * （实测 38sp 文本 1.0→2.0 只从 90px 涨到 106px，sp→dp 盒 40px→68px），
 * 所以这里验证的是「fontScale 真的到达 Compose + 所有 dp 尺寸重排后按键与保存区仍然完整」，
 * 真机上文本成倍放大后的最终确认由 A7 的设备/模拟器验收完成。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EntryKeyboardLargeFontTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var harness: EntryTestHarness

    @Before
    fun setUp() {
        harness = EntryTestHarness()
        applyFontScaleToActivity(2.0f)
    }

    private fun applyFontScaleToActivity(scale: Float) {
        val context: Context = composeRule.activity
        val configuration = Configuration(context.resources.configuration)
        configuration.fontScale = scale
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
    }

    private fun openEntry() {
        composeRule.setEntryContent(harness)
        composeRule.openAmountKeyboard()
        composeRule.waitUntil(10_000L) {
            composeRule.onAllNodesWithText("默认账户").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.settle()
    }

    /** 先证明 2.0 倍字体真的到达了 Compose（否则后面的布局结论没有意义）。 */
    private fun assertFontScaleReached(): String {
        var seen = "未知"
        composeRule.setContent {
            val density = LocalDensity.current
            seen = density.fontScale.toString()
            androidx.compose.material3.Text("fontScale=${density.fontScale}")
        }
        composeRule.waitForIdle()
        return seen
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun fontScale2On320x640() {
        // 单独一次组合用于断言密度，随后才是真实页面布局
        assertTrue(
            "fontScale 必须真的为 2.0",
            isFontScaleTwo(),
        )
    }

    private fun isFontScaleTwo(): Boolean {
        // 用独立的一次组合读取真实 LocalDensity.fontScale
        var value = 0f
        composeRule.setContent {
            value = LocalDensity.current.fontScale
        }
        composeRule.waitForIdle()
        return value == 2.0f
    }

    @Test
    @Config(qualifiers = "w320dp-h700dp-xhdpi")
    fun keypadOn320x700FontScale2() {
        openEntry()
        composeRule.assertKeypadUsable("320x700@2.0", evidenceFile = "keypad-bounds-font2.txt")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun keypadOn320x640FontScale2() {
        openEntry()
        composeRule.assertKeypadUsable("320x640@2.0", evidenceFile = "keypad-bounds-font2.txt")
    }

    @Test
    @Config(qualifiers = "w360dp-h700dp-xhdpi")
    fun keypadOn360x700FontScale2() {
        openEntry()
        composeRule.assertKeypadUsable("360x700@2.0", evidenceFile = "keypad-bounds-font2.txt")
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-xhdpi")
    fun keypadOn390x844FontScale2() {
        openEntry()
        composeRule.assertKeypadUsable("390x844@2.0", evidenceFile = "keypad-bounds-font2.txt")
    }

    @Test
    @Config(qualifiers = "w320dp-h520dp-xhdpi")
    fun keypadOn320x520FontScale2() {
        openEntry()
        composeRule.assertKeypadUsable("320x520@2.0", evidenceFile = "keypad-bounds-font2.txt")
    }
}
