package com.blueledger.app.acceptance

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.util.Locale
import kotlin.math.abs

/**
 * 键盘硬性布局的**验收框架**（docs/AI开发提示词.md §9「键盘硬性布局要求」、§13）。
 *
 * 这一层与具体实现无关：阶段一用它自检框架本身（参考布局 + 故意做坏的布局），
 * 阶段二原样作用于 A2 的真实记账页 —— 断言代码只有一份，不允许针对真实页面放宽。
 *
 * 判定标准（不可放宽）：
 * 1. 12 个按键与保存/连续记按钮必须存在且真实显示（`assertIsDisplayed`）。
 * 2. 每个按键与按钮的**实际节点 bounds** 必须完整落在「扣除 system bars 与 IME 后的可用区域」内。
 * 3. 触控目标高度与宽度都 ≥ 48dp。
 * 4. 键盘四行必须在保存区之上、排成 3 列，不与上方表单互相覆盖。
 * 5. 点击必须真的落到按键上（命中测试）：逐键输入 12.50 后保存，仓库里必须是 1250 分。
 */
object KeypadContract {

    /** 12 个金额键盘按键的测试标识（冻结契约：A2 必须使用这些 tag）。 */
    const val KEY_1 = "key_1"
    const val KEY_2 = "key_2"
    const val KEY_3 = "key_3"
    const val KEY_4 = "key_4"
    const val KEY_5 = "key_5"
    const val KEY_6 = "key_6"
    const val KEY_7 = "key_7"
    const val KEY_8 = "key_8"
    const val KEY_9 = "key_9"
    const val KEY_DOT = "key_dot"
    const val KEY_0 = "key_0"
    const val KEY_DELETE = "key_delete"

    val digitKeys: List<String> = (1..9).map { "key_$it" } + KEY_0

    /** 四行键盘的阅读顺序：1 2 3 / 4 5 6 / 7 8 9 / . 0 删除。 */
    val allKeys: List<String> = listOf(
        KEY_1, KEY_2, KEY_3,
        KEY_4, KEY_5, KEY_6,
        KEY_7, KEY_8, KEY_9,
        KEY_DOT, KEY_0, KEY_DELETE,
    )

    const val BTN_SAVE = "btn_save"
    const val BTN_SAVE_AND_NEW = "btn_save_and_new"

    /** 记账页金额显示节点。 */
    const val AMOUNT_DISPLAY = "entry_amount_value"

    const val MIN_TOUCH_TARGET_DP = 48f

    /** 浮点容差（dp）。 */
    const val EDGE_TOLERANCE_DP = 0.5f
}

/**
 * 一次布局探测的观测值（由被测内容在组合期写回，测试线程读取）。
 * 阶段一与阶段二共用；阶段二由真实记账页的包裹层写入同样的字段。
 */
class AcceptanceProbe {
    var rootWidthDp: Float = 0f
    var rootHeightDp: Float = 0f

    /** 状态栏/导航栏/刘海等安全区内边距（dp）。 */
    var safeDrawingTopDp: Float = 0f
    var safeDrawingBottomDp: Float = 0f

    /** 输入法内边距（dp）。Robolectric 下通常为 0：真实 IME 行为仍需设备验证。 */
    var imeBottomDp: Float = 0f

    /** 组合期真实观测到的字体缩放（证明布局确实在目标字倍下测量）。 */
    var observedFontScale: Float = 0f

    /** 逐键输入后界面上读到的金额文本（由实现写回，用于核对显示与落库一致）。 */
    var observedAmountText: String = ""

    /** 保存动作真正发生时写回的金额（分）。 */
    var savedAmountCent: Long? = null

    /** 「保存并再记」被点击的次数。 */
    var saveAndNewCount: Int = 0

    fun usableTopDp(): Float = safeDrawingTopDp

    fun usableBottomDp(): Float = rootHeightDp - safeDrawingBottomDp - imeBottomDp

    fun resetTransient() {
        observedAmountText = ""
        savedAmountCent = null
        saveAndNewCount = 0
    }
}

/**
 * 把内容包在探测层里：通过 [WindowInsets] 读取真实安全区与 IME 内边距，
 * 供断言换算「扣除 system bars / IME 后的可用区域」。
 *
 * 阶段二对真实记账页使用同一个包裹层，避免出现两套口径。
 */
@Composable
fun AcceptanceProbeHost(probe: AcceptanceProbe, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val safeDrawing = WindowInsets.safeDrawing
    val ime = WindowInsets.ime
    SideEffect {
        probe.safeDrawingTopDp = safeDrawing.getTop(density).toDpValue(density)
        probe.safeDrawingBottomDp = safeDrawing.getBottom(density).toDpValue(density)
        probe.imeBottomDp = ime.getBottom(density).toDpValue(density)
        probe.observedFontScale = density.fontScale
    }
    Box(modifier = Modifier.fillMaxSize()) { content() }
}

private fun Int.toDpValue(density: androidx.compose.ui.unit.Density): Float =
    with(density) { this@toDpValue.toDp().value }

/** 窗口配置：宽度、高度（dp）、字体缩放与 Robolectric 限定符。 */
data class WindowConfig(
    val widthDp: Int,
    val heightDp: Int,
    val fontScale: Float,
    val robolectricQualifiers: String,
) {
    val label: String get() = "${widthDp}x${heightDp}dp@font${fontScale}"
}

/** §13 要求的适配矩阵。 */
object WindowMatrix {
    val WIDTHS: List<Int> = listOf(320, 360, 390, 430)
    val HEIGHTS: List<Int> = listOf(700, 844)
    val FONT_SCALES: List<Float> = listOf(1.0f, 1.3f, 2.0f)

    fun qualifiers(widthDp: Int, heightDp: Int): String = "w${widthDp}dp-h${heightDp}dp-xxhdpi"

    /** 每个宽度/高度组合作为一个测试方法（Robolectric 窗口尺寸只能在方法级指定）。 */
    val baselineWindows: List<WindowConfig> = WIDTHS.flatMap { w ->
        HEIGHTS.map { h -> WindowConfig(w, h, 1.0f, qualifiers(w, h)) }
    }

    /**
     * IME 弹起 / 三键导航挤压的**代理场景**：可用高度被压缩后，
     * 底部键盘与保存区仍必须完整可点。
     * 这是代理，不等于真实 IME 验证（真实 IME 需要设备，见测试报告「未验证」）。
     */
    val compressedHeightProxies: List<WindowConfig> = listOf(
        WindowConfig(360, 560, 2.0f, qualifiers(360, 560)),
        WindowConfig(390, 600, 1.3f, qualifiers(390, 600)),
        WindowConfig(320, 520, 1.0f, qualifiers(320, 520)),
    )
}

/** 单个节点在一次探测中的几何信息。 */
data class NodeGeometry(
    val tag: String,
    val bounds: DpRect,
) {
    val widthDp: Float get() = bounds.right.value - bounds.left.value
    val heightDp: Float get() = bounds.bottom.value - bounds.top.value
}

/**
 * 把每次布局探测的**真实 bounds 数字**落盘，作为可引用的原始证据
 * （Gradle 默认不把测试 stdout 留在 XML 报告里，所以这里直接写文件）。
 *
 * 位置：环境变量 `BLUELEDGER_A7_EVIDENCE_DIR`，默认 `build/a7-evidence`（相对模块目录）。
 * 每次运行前由调用方删除旧文件，避免新旧证据混在一起。
 */
object A7Evidence {
    private val dir: java.io.File =
        System.getenv("BLUELEDGER_A7_EVIDENCE_DIR")?.let { java.io.File(it) }
            ?: java.io.File("build/a7-evidence")

    val file: java.io.File get() = java.io.File(dir, "keypad-bounds.txt")

    fun record(line: String) {
        runCatching {
            dir.mkdirs()
            file.appendText(line + System.lineSeparator(), Charsets.UTF_8)
        }
    }
}

/** 一次布局探测的完整报告，会写进测试报告作为证据。 */
class KeypadLayoutReport(
    val config: WindowConfig,
    val root: DpRect,
    val usableTopDp: Float,
    val usableBottomDp: Float,
    val safeDrawingBottomDp: Float,
    val imeBottomDp: Float,
    val observedFontScale: Float,
    val nodes: List<NodeGeometry>,
) {
    val keys: List<NodeGeometry> get() = nodes.filter { it.tag in KeypadContract.allKeys }
    val minKeyHeightDp: Float get() = keys.minOf { it.heightDp }
    val minKeyWidthDp: Float get() = keys.minOf { it.widthDp }
    val lowestKeyBottomDp: Float get() = keys.maxOf { it.bounds.bottom.value }

    fun describe(): String = buildString {
        appendLine("配置=${config.label} 窗口=${fmt(root.right.value)}x${fmt(root.bottom.value)}dp fontScale=$observedFontScale")
        appendLine(
            "可用区 top=${fmt(usableTopDp)}dp bottom=${fmt(usableBottomDp)}dp " +
                "(安全区底=${fmt(safeDrawingBottomDp)}dp IME=${fmt(imeBottomDp)}dp)",
        )
        appendLine("最小键 ${fmt(minKeyWidthDp)}x${fmt(minKeyHeightDp)}dp 键盘最低沿=${fmt(lowestKeyBottomDp)}dp")
        nodes.forEach { node ->
            appendLine(
                "  ${node.tag}: left=${fmt(node.bounds.left.value)} top=${fmt(node.bounds.top.value)} " +
                    "right=${fmt(node.bounds.right.value)} bottom=${fmt(node.bounds.bottom.value)} " +
                    "(${fmt(node.widthDp)}x${fmt(node.heightDp)}dp)",
            )
        }
    }

    private fun fmt(value: Float): String = String.format(Locale.ROOT, "%.1f", value)
}

object KeypadHarness {

    /** 逐键点击（真实命中测试：被遮挡时状态不会变化，测试会失败）。 */
    fun pressKeys(rule: ComposeContentTestRule, tags: List<String>) {
        tags.forEach { tag ->
            rule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
            rule.waitForIdle()
        }
    }

    /** 读取金额显示节点的文本。 */
    fun amountText(rule: ComposeContentTestRule): String =
        rule.onNodeWithTag(KeypadContract.AMOUNT_DISPLAY, useUnmergedTree = true)
            .fetchSemanticsNode(errorMessageOnFail = "找不到金额显示节点 ${KeypadContract.AMOUNT_DISPLAY}")
            .config
            .getOrNull(SemanticsProperties.Text)
            ?.joinToString(" ") { it.text }
            .orEmpty()

    fun hasNode(rule: ComposeContentTestRule, tag: String): Boolean =
        rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun node(rule: ComposeContentTestRule, tag: String): SemanticsNodeInteraction =
        rule.onNodeWithTag(tag, useUnmergedTree = true)

    /**
     * 核心布局断言：所有按键与保存按钮必须在可用区域内、≥48dp、键盘四行三列位于保存区之上。
     * 返回报告供测试报告记录证据。
     */
    fun assertLayout(
        rule: ComposeContentTestRule,
        probe: AcceptanceProbe,
        config: WindowConfig,
    ): KeypadLayoutReport {
        rule.waitForIdle()

        val rootBounds = rule.onRoot(useUnmergedTree = true).getUnclippedBoundsInRoot()
        probe.rootWidthDp = rootBounds.right.value
        probe.rootHeightDp = rootBounds.bottom.value
        val usableTop = probe.usableTopDp()
        val usableBottom = probe.usableBottomDp()

        assertTrue(
            "配置 ${config.label}：布局必须在 fontScale=${config.fontScale} 下测量，" +
                "实际组合期观测到 ${probe.observedFontScale}（包裹层必须提供目标字倍）",
            abs(probe.observedFontScale - config.fontScale) <= 0.01f,
        )

        val tags = KeypadContract.allKeys + listOf(KeypadContract.BTN_SAVE, KeypadContract.BTN_SAVE_AND_NEW)
        val missing = tags.filter { !hasNode(rule, it) }
        if (missing.isNotEmpty()) {
            fail("配置 ${config.label}：缺少关键节点 $missing（完整键盘与保存区必须存在且带契约 tag）")
        }

        val geometries = tags.map { tag -> NodeGeometry(tag, node(rule, tag).getUnclippedBoundsInRoot()) }
        val report = KeypadLayoutReport(
            config = config,
            root = rootBounds,
            usableTopDp = usableTop,
            usableBottomDp = usableBottom,
            safeDrawingBottomDp = probe.safeDrawingBottomDp,
            imeBottomDp = probe.imeBottomDp,
            observedFontScale = probe.observedFontScale,
            nodes = geometries,
        )

        // 1) 必须真实可见（被裁掉或零尺寸会在这里失败）
        KeypadContract.allKeys.forEach { tag -> node(rule, tag).assertIsDisplayed() }
        node(rule, KeypadContract.BTN_SAVE).assertIsDisplayed()

        // 2) 触控目标 ≥ 48dp
        geometries.forEach { geometry ->
            assertTrue(
                "配置 ${config.label}：${geometry.tag} 高度 ${geometry.heightDp}dp < ${KeypadContract.MIN_TOUCH_TARGET_DP}dp\n${report.describe()}",
                geometry.heightDp >= KeypadContract.MIN_TOUCH_TARGET_DP - KeypadContract.EDGE_TOLERANCE_DP,
            )
            assertTrue(
                "配置 ${config.label}：${geometry.tag} 宽度 ${geometry.widthDp}dp < ${KeypadContract.MIN_TOUCH_TARGET_DP}dp\n${report.describe()}",
                geometry.widthDp >= KeypadContract.MIN_TOUCH_TARGET_DP - KeypadContract.EDGE_TOLERANCE_DP,
            )
        }

        // 3) 完整落在可用区域内（"小数点/0/删除/保存没有被 system bars 或 IME 顶掉"的判定）
        geometries.forEach { geometry ->
            val b = geometry.bounds
            assertTrue(
                "配置 ${config.label}：${geometry.tag} 左边界 ${b.left.value}dp 越界\n${report.describe()}",
                b.left.value >= -KeypadContract.EDGE_TOLERANCE_DP,
            )
            assertTrue(
                "配置 ${config.label}：${geometry.tag} 右边界 ${b.right.value}dp 超出窗口宽 ${report.root.right.value}dp\n${report.describe()}",
                b.right.value <= report.root.right.value + KeypadContract.EDGE_TOLERANCE_DP,
            )
            assertTrue(
                "配置 ${config.label}：${geometry.tag} 上边界 ${b.top.value}dp 高于可用区顶部 ${fmt(usableTop)}dp\n${report.describe()}",
                b.top.value >= usableTop - KeypadContract.EDGE_TOLERANCE_DP,
            )
            assertTrue(
                "配置 ${config.label}：${geometry.tag} 下边界 ${b.bottom.value}dp 低于可用区底部 ${fmt(usableBottom)}dp" +
                    "（安全区 ${fmt(probe.safeDrawingBottomDp)}dp + IME ${fmt(probe.imeBottomDp)}dp 之后放不下）\n${report.describe()}",
                b.bottom.value <= usableBottom + KeypadContract.EDGE_TOLERANCE_DP,
            )
        }

        // 4) 键盘四行整体在保存区之上
        val highestSaveTop = listOf(KeypadContract.BTN_SAVE, KeypadContract.BTN_SAVE_AND_NEW)
            .map { tag -> geometries.first { it.tag == tag }.bounds.top.value }
            .min()
        assertTrue(
            "配置 ${config.label}：键盘最低沿 ${fmt(report.lowestKeyBottomDp)}dp 与保存区顶部 ${fmt(highestSaveTop)}dp " +
                "重叠（保存区必须固定在键盘下方）\n${report.describe()}",
            report.lowestKeyBottomDp <= highestSaveTop + KeypadContract.EDGE_TOLERANCE_DP,
        )

        // 5) 键盘必须排成 4 行 × 3 列（被折行/挤出窗口会在这里失败）
        val rows = clusterCount(report.keys.map { it.bounds.bottom.value })
        assertTrue(
            "配置 ${config.label}：键盘应为 4 行，实际聚类出 $rows 行\n${report.describe()}",
            rows == 4,
        )
        val columns = clusterCount(report.keys.map { it.bounds.left.value })
        assertTrue(
            "配置 ${config.label}：键盘应为 3 列，实际聚类出 $columns 列\n${report.describe()}",
            columns == 3,
        )

        A7Evidence.record("[PASS] 配置 ${config.label} 全部判据通过\n${report.describe()}")
        return report
    }

    /** 以 0.5dp 容差把坐标聚类成"行/列"个数。 */
    private fun clusterCount(values: List<Float>): Int {
        val sorted = values.sorted()
        var clusters = 0
        var last: Float? = null
        sorted.forEach { value ->
            val prev = last
            if (prev == null || value - prev > KeypadContract.EDGE_TOLERANCE_DP) clusters++
            last = value
        }
        return clusters
    }

    private fun fmt(value: Float): String = String.format(Locale.ROOT, "%.1f", value)
}
