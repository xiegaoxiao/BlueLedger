package com.blueledger.app.acceptance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal

/**
 * ⚠️ 仅供**验证验收框架本身**使用的参考布局，不是 App 实现，也不代表功能已验证。
 *
 * 它做两件事：
 * 1. 按 §9 的硬性布局要求摆一个"正确答案"（键盘固定底部、四行三列、每键 ≥48dp、上方表单滚动），
 *    用来证明 [KeypadHarness.assertLayout] 的断言在合格布局上确实能通过（不是永远失败的废断言）。
 * 2. 提供几个**故意做坏**的变体（键盘被顶出可用区、按键只有 36dp、小数点尺寸为 0、保存按钮被遮挡），
 *    用来证明同一套断言确实能抓到这些失败（不是永远通过的假断言）。
 *
 * 阶段二对 A2 真实记账页运行同一套断言；本文件届时只保留负例用途。
 */
class HarnessAmountBuffer(initial: String = "") {
    var text by mutableStateOf(initial)
        private set

    val isEmpty: Boolean get() = text.isEmpty()

    fun reset() {
        text = ""
    }

    /** 是否已经可以保存（金额 > 0）。 */
    val hasPositiveAmount: Boolean get() = runCatching { cent }.getOrNull()?.let { it > 0L } == true

    val cent: Long get() = BigDecimal(text.ifEmpty { "0" }).movePointRight(2).toBigIntegerExact().longValueExact()

    /** $ 或 ¥ 前缀只用于展示，这里返回纯数字文本。 */
    fun press(key: String) {
        when (key) {
            "." -> {
                if (text.contains('.')) return
                text = if (text.isEmpty()) "0." else "$text."
            }

            "删除" -> text = text.dropLast(1)

            else -> {
                val digit = key.singleOrNull()?.takeIf { it.isDigit() } ?: return
                val dotIndex = text.indexOf('.')
                val decimals = if (dotIndex >= 0) text.length - dotIndex - 1 else 0
                if (decimals >= com.blueledger.app.core.model.Limits.MAX_AMOUNT_DECIMALS) return
                val integerDigits = (if (dotIndex >= 0) text.substring(0, dotIndex) else text).trimStart('0').length
                if (dotIndex < 0 && integerDigits >= 7 && text != "0") return // 上限 9,999,999.99
                text = when {
                    text == "0" -> digit.toString()
                    else -> text + digit
                }
            }
        }
    }
}

/** 参考布局里的键盘按钮：点击必须走真实命中测试。 */
@Composable
private fun KeyButton(
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .testTag(tag)
            .semantics { contentDescription = if (label == "删除") "删除" else label }
            .background(Color(0xFFEFF5FF), RoundedCornerShape(14.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun KeypadPanel(
    buffer: HarnessAmountBuffer,
    rowHeight: androidx.compose.ui.unit.Dp = 56.dp,
    dotKeySize: androidx.compose.ui.unit.Dp? = null,
) {
    val rows = listOf(
        listOf("1" to KeypadContract.KEY_1, "2" to KeypadContract.KEY_2, "3" to KeypadContract.KEY_3),
        listOf("4" to KeypadContract.KEY_4, "5" to KeypadContract.KEY_5, "6" to KeypadContract.KEY_6),
        listOf("7" to KeypadContract.KEY_7, "8" to KeypadContract.KEY_8, "9" to KeypadContract.KEY_9),
        listOf("." to KeypadContract.KEY_DOT, "0" to KeypadContract.KEY_0, "删除" to KeypadContract.KEY_DELETE),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().height(rowHeight)) {
                row.forEach { (label, tag) ->
                    val sizeModifier = if (tag == KeypadContract.KEY_DOT && dotKeySize != null) {
                        Modifier.size(dotKeySize)
                    } else {
                        Modifier.weight(1f).fillMaxSize()
                    }
                    KeyButton(
                        label = label,
                        tag = tag,
                        modifier = sizeModifier,
                        onClick = { buffer.press(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveRow(buffer: HarnessAmountBuffer, probe: AcceptanceProbe) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(horizontal = 4.dp)
                .testTag(KeypadContract.BTN_SAVE_AND_NEW)
                .background(Color(0xFFEFF5FF), RoundedCornerShape(16.dp))
                .clickable(enabled = buffer.hasPositiveAmount) {
                    probe.saveAndNewCount += 1
                    buffer.reset()
                },
            contentAlignment = Alignment.Center,
        ) { Text("保存并再记", fontSize = 16.sp, maxLines = 1) }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .padding(horizontal = 4.dp)
                .testTag(KeypadContract.BTN_SAVE)
                .background(Color(0xFF2563EB), RoundedCornerShape(16.dp))
                .clickable(enabled = buffer.hasPositiveAmount) {
                    probe.savedAmountCent = buffer.cent
                    probe.observedAmountText = buffer.text
                },
            contentAlignment = Alignment.Center,
        ) { Text("保存账单", fontSize = 16.sp, color = Color.White, maxLines = 1) }
    }
}

/**
 * 参考「正确」布局：上方表单独立滚动，键盘与保存区固定在底部。
 * 注意：这里不实现任何业务逻辑，只在按键回调里更新测试用缓冲。
 */
@Composable
fun ReferenceEntryScreen(
    probe: AcceptanceProbe,
    buffer: HarnessAmountBuffer = remember { HarnessAmountBuffer() },
) {    SideEffect { probe.observedAmountText = buffer.text }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text("记一笔", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("¥ ${buffer.text}", modifier = Modifier.testTag(KeypadContract.AMOUNT_DISPLAY), fontSize = 36.sp)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("餐饮", "交通", "居住").forEach { name ->
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(72.dp)
                            .background(Color(0xFFEFF5FF), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text(name, fontSize = 14.sp, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("日期：2026-10-07", fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text("账户：银行卡", fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text("备注（最多 200 字）", fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
        }
        KeypadPanel(buffer)
        SaveRow(buffer, probe)
    }
}

// ───────────────────── 故意做坏的变体：只用于证明断言能抓到失败 ─────────────────────

/** 键盘整体被顶到窗口下方（模拟"小数点/保存被裁掉"的原始缺陷）。 */
@Composable
fun ClippedKeypadScreen(probe: AcceptanceProbe) {
    Box(modifier = Modifier.fillMaxSize().offset(y = 260.dp)) {
        ReferenceEntryScreen(probe)
    }
}

/** 键盘行高只有 36dp（低于 48dp 触控目标）。 */
@Composable
fun UndersizedKeysScreen(probe: AcceptanceProbe) {
    val buffer = HarnessAmountBuffer()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Spacer(Modifier.weight(1f))
        KeypadPanel(buffer, rowHeight = 36.dp)
        SaveRow(buffer, probe)
    }
}

/** 小数点被做成 0dp 的"不可点击小装饰"。 */
@Composable
fun HiddenDotKeyScreen(probe: AcceptanceProbe) {
    val buffer = HarnessAmountBuffer()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Spacer(Modifier.weight(1f))
        KeypadPanel(buffer, dotKeySize = 0.dp)
        SaveRow(buffer, probe)
    }
}

/** 底部保存区被透明遮罩压住：布局尺寸合格，但点击落不到保存按钮上。 */
@Composable
fun SaveButtonCoveredScreen(probe: AcceptanceProbe) {
    Box(modifier = Modifier.fillMaxSize()) {
        ReferenceEntryScreen(probe)
        // 只压住底部保存区（约 64dp），键盘上四行仍可点击，便于区分"整页不可点"与"命中被吞"。
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(64.dp)
                .clickable { /* 吞掉保存区点击 */ },
        )
    }
}

/** 四行键盘被折行成多列（横向空间不足时按窄列强行排列的典型错误）。 */
@Composable
fun OffScreenColumnScreen(probe: AcceptanceProbe) {
    val buffer = HarnessAmountBuffer()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Spacer(Modifier.weight(1f))
        // 每行只留 60dp 宽但塞三个键：第三个键会被挤出窗口右侧。
        Row {
            KeypadContract.allKeys.take(3).forEach { tag ->
                KeyButton(
                    label = tag.removePrefix("key_"),
                    tag = tag,
                    modifier = Modifier.width(60.dp).height(56.dp),
                    onClick = { buffer.press(tag.removePrefix("key_")) },
                )
            }
        }
        Row {
            KeypadContract.allKeys.drop(3).forEach { tag ->
                KeyButton(
                    label = tag.removePrefix("key_"),
                    tag = tag,
                    modifier = Modifier.width(60.dp).height(56.dp),
                    onClick = { buffer.press(tag.removePrefix("key_")) },
                )
            }
        }
        SaveRow(buffer, probe)
    }
}
