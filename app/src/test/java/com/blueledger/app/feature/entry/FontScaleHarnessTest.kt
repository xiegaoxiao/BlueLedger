package com.blueledger.app.feature.entry

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 字体放大测试手段的有效性验证（先证明手段，再谈结论）。
 *
 * 三轮真实失败后的定位：
 * 1. `CompositionLocalProvider(LocalDensity provides Density(density, fontScale))` **无效**：
 *    1.0 与 2.0 下 38sp 文本都测得 35px（Robolectric 默认 LEGACY 图形模式文本度量是假的）。
 * 2. 只改 Application 的 Configuration 也无效（Compose 视图用的是 Activity 的 Resources）。
 * 3. 有效组合：`@GraphicsMode(NATIVE)` + 在 `setContent` **之前**改写 Activity 的
 *    `Configuration.fontScale` → `LocalDensity.fontScale` 真的是 2.0，sp→dp 尺寸随之增大。
 *
 * **仍存在的限制**：即使 NATIVE 模式，Compose 文本度量也不按字体比例成倍放大
 * （实测 38sp 文本 1.0→2.0 仅 90px→106px；sp 派生 dp 盒 40px→68px）。
 * 因此「真机 2.0 倍字体下文本高度成倍增长」这一最坏情况在 JVM 上无法忠实复现；
 * 本类只证明「字体比例确实到达 Compose 且布局随之重排」，真机确认交给 A7 设备验收。
 * 实测数字写入 app/build/a2-evidence/font-scale.txt。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class FontScaleHarnessTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `1 倍字体基线：fontScale 为 1 且 20sp 盒为 40px`() {
        val probe = measure("scale1.0", 1.0f)
        assertEquals("1.0 倍时 Compose 看到的 fontScale 应为 1.0", 1.0f, probe.seenFontScale, 0.001f)
        assertEquals("xhdpi 下 20sp(fontScale=1) 应为 20dp = 40px", 40f, probe.spBox, 1f)
    }

    @Test
    fun `2 倍字体真的到达 Compose 且 sp 派生尺寸变大`() {
        val probe = measure("scale2.0", 2.0f)
        assertEquals("2.0 倍时 Compose 看到的 fontScale 必须是 2.0", 2.0f, probe.seenFontScale, 0.001f)
        assertTrue(
            "sp 派生的 dp 尺寸必须随 fontScale 变大：实测 ${probe.spBox}px（1.0 基线 40px）",
            probe.spBox > 50f,
        )
        assertTrue("38sp 文本应有真实高度，实测 ${probe.textHeight}px", probe.textHeight > 0f)
    }

    private data class Probe(
        val textWidth: Float,
        val textHeight: Float,
        val spBox: Float,
        val seenFontScale: Float,
    )

    private fun measure(label: String, scale: Float): Probe {
        applyFontScaleToActivity(scale)
        composeRule.setContent { ProbeContent() }
        composeRule.waitForIdle()

        val textBounds = composeRule.onNodeWithTag("probe").fetchSemanticsNode().boundsInRoot
        val boxBounds = composeRule.onNodeWithTag("probe_sp_box").fetchSemanticsNode().boundsInRoot
        val seen = composeRule
            .onNodeWithTag("probe_density")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString()
            .substringAfter("seenFontScale=")
            .trim()
            .toFloat()

        recordEvidence(
            "font-scale.txt",
            "FontScaleHarnessTest $label text=${textBounds.width}x${textBounds.height}px " +
                "spBox=${boxBounds.width}px seenFontScale=$seen",
        )
        return Probe(textBounds.width, textBounds.height, boxBounds.width, seen)
    }

    @Composable
    private fun ProbeContent() {
        val density = LocalDensity.current
        Column {
            Text(text = "12.50", fontSize = 38.sp, modifier = Modifier.testTag("probe"))
            Box(
                modifier = Modifier
                    .testTag("probe_sp_box")
                    .size(with(density) { 20.sp.toDp() }),
            )
            Text(text = "seenFontScale=${density.fontScale}", modifier = Modifier.testTag("probe_density"))
        }
    }

    private fun applyFontScaleToActivity(scale: Float) {
        val context: Context = composeRule.activity
        val configuration = Configuration(context.resources.configuration)
        configuration.fontScale = scale
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
    }
}
