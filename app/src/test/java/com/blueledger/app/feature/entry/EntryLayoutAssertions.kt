package com.blueledger.app.feature.entry

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * S02 键盘与保存区的共享布局断言（A2 的多个布局测试类复用）。
 *
 * 判据（docs/AI开发提示词.md §9「键盘硬性布局要求」+ 用户验收点）：
 * 1. 12 个按键全部存在，且**未裁切 bounds == 裁切 bounds**（没被遮挡/裁掉）；
 * 2. 每个按键与保存按钮都完整落在窗口可用区域内；
 * 3. 每个触控目标高度与宽度 ≥48dp；
 * 4. 四行三列结构正确，键盘整体在保存区之上；
 * 5. 底部区贴住窗口底部，可滚动表单在它上方；
 * 6. 表单滚动时键盘位置不变（键盘固定在底部，不在长列表末尾）。
 */
internal fun ComposeContentTestRule.assertKeypadUsable(
    label: String,
    evidenceFile: String = "keypad-bounds.txt",
) {
    val density = this.density
    val minTouchPx = with(density) { 48.dp.toPx() }
    val root = onRoot().fetchSemanticsNode().boundsInRoot

    fun bounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    fun unclippedPx(tag: String): Rect {
        val dpRect = onNodeWithTag(tag).getUnclippedBoundsInRoot()
        return with(density) {
            Rect(dpRect.left.toPx(), dpRect.top.toPx(), dpRect.right.toPx(), dpRect.bottom.toPx())
        }
    }

    // 1) 按键齐全 + 在窗口内 + 未裁切 + 触控尺寸达标
    REGISTER_KEYS.forEach { tag ->
        val box = bounds(tag)
        val raw = unclippedPx(tag)
        assertTrue(
            "$label：$tag 应完整位于可用区域内，实际 $box（root=$root）",
            box.left >= -1f && box.top >= -1f &&
                box.right <= root.width + 1f && box.bottom <= root.height + 1f,
        )
        assertTrue(
            "$label：$tag 被裁切/遮挡（裁切 $box vs 未裁切 $raw）",
            kotlin.math.abs(box.width - raw.width) <= 2f && kotlin.math.abs(box.height - raw.height) <= 2f,
        )
        assertTrue(
            "$label：$tag 触控高度应 ≥48dp，实际 ${box.height}px（${box.height / density.density}dp）",
            box.height >= minTouchPx - 1f,
        )
        assertTrue(
            "$label：$tag 触控宽度应 ≥48dp，实际 ${box.width}px",
            box.width >= minTouchPx - 1f,
        )
    }

    // 2) 四行结构
    assertTrue("$label：第 2 行应在第 1 行下方", bounds("key_4").top > bounds("key_7").top)
    assertTrue("$label：第 3 行应在第 2 行下方", bounds("key_1").top > bounds("key_4").top)
    assertTrue("$label：第 4 行应在第 3 行下方", bounds("key_dot").top > bounds("key_1").top)
    assertEquals(
        "$label：小数点 / 0 / 删除必须同一行",
        bounds("key_dot").top.toDouble(),
        bounds("key_delete").top.toDouble(),
        0.5,
    )
    assertEquals(
        "$label：小数点 / 0 / 删除必须同一行",
        bounds("key_0").top.toDouble(),
        bounds("key_delete").top.toDouble(),
        0.5,
    )

    // 3) 保存区在键盘下方且完整可见
    val save = bounds(TAG_SAVE)
    assertTrue(
        "$label：保存按钮应完整在可用区域内，实际 $save（root=$root）",
        save.bottom <= root.height + 1f && save.top >= -1f,
    )
    assertTrue("$label：保存按钮触控高度应 ≥48dp，实际 ${save.height}px", save.height >= minTouchPx - 1f)
    assertEquals("$label：完成键应位于键盘第四行", bounds("key_dot").top.toDouble(), save.top.toDouble(), 1.0)
    assertTrue("$label：完成键应位于删除键右侧", save.left >= bounds("key_delete").right - 1f)

    val saveAndNew = bounds(TAG_SAVE_AND_NEW)
    assertTrue("$label：保存并再记应完整可见", saveAndNew.bottom <= root.height + 1f)
    assertTrue(
        "$label：保存并再记触控高度应 ≥48dp，实际 ${saveAndNew.height}px",
        saveAndNew.height >= minTouchPx - 1f,
    )

    // 4) 底部固定：贴住窗口底部，表单在它上方
    val bottom = bounds(TAG_ENTRY_BOTTOM)
    val form = bounds(TAG_ENTRY_FORM)
    assertTrue("$label：底部操作区应贴住窗口底部，实际 $bottom（root=$root）", bottom.bottom >= root.height - 2f)
    assertTrue("$label：可滚动表单必须在底部操作区上方", form.bottom <= bottom.top + 1f)

    val keyboard = bounds(TAG_ENTRY_KEYBOARD)
    assertTrue("$label：键盘整体可见", keyboard.top >= -1f && keyboard.bottom <= bottom.bottom + 1f)

    // 5) 滚动表单不会移动键盘
    val before = bounds("key_1")
    onNodeWithTag(TAG_ENTRY_FORM).performTouchInput { swipeUp() }
    waitForIdle()
    val after = bounds("key_1")
    assertEquals("$label：滚动表单后键盘不应移动", before.top.toDouble(), after.top.toDouble(), 1.0)

    recordEvidence(
        evidenceFile,
        label.padEnd(30) +
            " root=${root.width.toInt()}x${root.height.toInt()}px" +
            " key_dot=[t=${bounds("key_dot").top.toInt()} b=${bounds("key_dot").bottom.toInt()} h=${bounds("key_dot").height.toInt()}]" +
            " key_0=[t=${bounds("key_0").top.toInt()} b=${bounds("key_0").bottom.toInt()} h=${bounds("key_0").height.toInt()}]" +
            " key_delete=[t=${bounds("key_delete").top.toInt()} b=${bounds("key_delete").bottom.toInt()} h=${bounds("key_delete").height.toInt()}]" +
            " btn_save=[t=${save.top.toInt()} b=${save.bottom.toInt()} h=${save.height.toInt()}]" +
            " btn_save_and_new=[t=${saveAndNew.top.toInt()} b=${saveAndNew.bottom.toInt()} h=${saveAndNew.height.toInt()}]" +
            " minTouch=${minTouchPx.toInt()}px",
    )
}

/** 12 个按键的 testTag，顺序即 1—9 / . 0 删除。 */
internal val REGISTER_KEYS: List<String> = listOf(
    "key_1", "key_2", "key_3",
    "key_4", "key_5", "key_6",
    "key_7", "key_8", "key_9",
    "key_dot", "key_0", "key_delete", "key_date", "key_plus", "key_minus",
)

/** 把真实 bounds 追加写入 app/build/a2-evidence/<file>，供交接报告引用。 */
internal fun recordEvidence(fileName: String, line: String) {
    runCatching {
        val dir = java.io.File(System.getProperty("user.dir"), "build/a2-evidence")
        dir.mkdirs()
        java.io.File(dir, fileName).appendText(line + "\n")
    }
}
