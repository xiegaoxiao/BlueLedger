package com.blueledger.app.feature.entry

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * S02 关键交互的真实验证（Robolectric 驱动真实 Compose 组合与真实点击）。
 *
 * 核心验收：逐键点击 key_1 / key_2 / key_dot / key_5 / key_0 → 显示 12.50
 * → 保存后从仓库读回 amountCent == 1250（Long 整数分）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class EntryInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: EntryTestHarness

    @Before
    fun setUp() {
        harness = EntryTestHarness()
    }

    private fun openEntry(
        editTransactionId: String? = null,
        initialType: TransactionType? = null,
        initialCategoryId: String? = null,
    ) {
        composeRule.setEntryContent(
            harness = harness,
            editTransactionId = editTransactionId,
            initialType = initialType,
            initialCategoryId = initialCategoryId,
        )
        composeRule.openAmountKeyboard()
        // 等到账户被解析出来（默认账户），保证「未修改」基线已建立。
        composeRule.waitUntil(10_000L) {
            composeRule.onAllNodesWithText("默认账户").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.settle()
    }

    private fun typeKeys(vararg keys: String) {
        keys.forEach { key -> composeRule.onNodeWithTag(key).performClick() }
        composeRule.settle()
    }

    private fun pickCategory(categoryId: String = "cat_expense_food") {
        composeRule.onNodeWithTag("category_" + categoryId).performClick()
        composeRule.settle()
    }

    // ───────────────────────── 核心验收 ─────────────────────────

    @Test
    fun `先选择分类自动展开键盘且金额备注固定在分类下方`() {
        composeRule.setEntryContent(harness)
        composeRule.awaitTag("category_cat_expense_food")
        composeRule.onNodeWithTag(TAG_ENTRY_KEYBOARD).assertDoesNotExist()
        pickCategory()
        composeRule.onNodeWithTag(TAG_ENTRY_KEYBOARD).assertIsDisplayed()
        typeKeys("key_1", "key_2")
        composeRule.onNodeWithTag(TAG_AMOUNT_DISPLAY).assertTextEquals("12")
        composeRule.onNodeWithTag("field_note").assertIsDisplayed()
    }

    @Test
    fun `逐键点击 1 2 点 5 0 得到 12 点 50 并保存为 1250 分`() {
        openEntry()
        typeKeys("key_1", "key_2", "key_dot", "key_5", "key_0")

        composeRule.onNodeWithTag(TAG_AMOUNT_DISPLAY).assertTextEquals("12.50")

        pickCategory()
        composeRule.onNodeWithTag(TAG_SAVE).performClick()
        composeRule.settle()

        val items = harness.transactions()
        assertEquals(1, items.size)
        assertEquals(1250L, items.first().amountCent)
        assertEquals(TransactionType.EXPENSE, items.first().type)
        assertEquals("cat_expense_food", items.first().transaction.categoryId)
        assertEquals("acc_default", items.first().transaction.accountId)
        assertEquals(1, harness.exitCount)
    }

    @Test
    fun `输入 12 保存为 1200 分`() {
        openEntry()
        typeKeys("key_1", "key_2")
        pickCategory()
        composeRule.onNodeWithTag(TAG_SAVE).performClick()
        composeRule.settle()

        assertEquals(1200L, harness.transactions().first().amountCent)
    }

    @Test
    fun `输入 0 点 01 保存为 1 分`() {
        openEntry()
        typeKeys("key_dot", "key_0", "key_1")
        composeRule.onNodeWithTag(TAG_AMOUNT_DISPLAY).assertTextEquals("0.01")
        pickCategory()
        composeRule.onNodeWithTag(TAG_SAVE).performClick()
        composeRule.settle()

        assertEquals(1L, harness.transactions().first().amountCent)
    }

    @Test
    fun `删除键逐位退格并恢复键盘可用`() {
        openEntry()
        typeKeys("key_1", "key_2", "key_dot", "key_5", "key_0")
        composeRule.onNodeWithTag(TAG_AMOUNT_DISPLAY).assertTextEquals("12.50")

        composeRule.onNodeWithTag("key_delete").performClick()
        composeRule.settle()
        composeRule.onNodeWithTag(TAG_AMOUNT_DISPLAY).assertTextEquals("12.5")

        composeRule.onNodeWithTag("key_delete").performClick()
        composeRule.settle()
        composeRule.onNodeWithTag(TAG_AMOUNT_DISPLAY).assertTextEquals("12.")

        repeat(3) {
            composeRule.onNodeWithTag("key_delete").performClick()
            composeRule.settle()
        }
        composeRule.onNodeWithTag(TAG_AMOUNT_DISPLAY).assertTextEquals("0.00")
        composeRule.onNodeWithTag("key_dot").assertIsDisplayed()
    }

    // ───────────────────────── 校验与错误 ─────────────────────────

    @Test
    fun `零金额保存被拒并保留已选分类`() {
        openEntry()
        pickCategory()
        composeRule.onNodeWithTag(TAG_SAVE).performClick()
        composeRule.settle()

        composeRule.onNodeWithTag("error_amount").assertIsDisplayed()
        assertTrue(harness.transactions().isEmpty())
        assertEquals(0, harness.exitCount)
    }

    @Test
    fun `未选分类保存被拒且提示分类必填`() {
        openEntry()
        typeKeys("key_1", "key_2", "key_dot", "key_5")
        composeRule.onNodeWithTag(TAG_SAVE).performClick()
        composeRule.settle()

        composeRule.onNodeWithTag("error_category").assertIsDisplayed()
        assertTrue(harness.transactions().isEmpty())
    }

    @Test
    fun `保存失败时保留输入并可重试成功`() {
        openEntry()
        typeKeys("key_1", "key_2", "key_dot", "key_5", "key_0")
        pickCategory()

        harness.repository.failNextWrite = LedgerError.Storage("保存失败，请重试")
        composeRule.onNodeWithTag(TAG_SAVE).performClick()
        composeRule.settle()

        composeRule.onNodeWithTag("error_banner").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_AMOUNT_DISPLAY).assertTextEquals("12.50")
        assertEquals(0, harness.exitCount)
        assertTrue(harness.transactions().isEmpty())

        composeRule.onNodeWithTag("error_banner_action").performClick()
        composeRule.settle()

        assertEquals(1250L, harness.transactions().first().amountCent)
        assertEquals(1, harness.exitCount)
    }

    @Test
    fun `快速连点保存只记一笔`() {
        openEntry()
        typeKeys("key_1", "key_2", "key_dot", "key_5", "key_0")
        pickCategory()

        composeRule.onNodeWithTag(TAG_SAVE).performClick()
        composeRule.onNodeWithTag(TAG_SAVE).performClick()
        composeRule.onNodeWithTag(TAG_SAVE).performClick()
        composeRule.settle()

        assertEquals(1, harness.transactions().size)
    }

    // ───────────────────────── 连续记账 ─────────────────────────

    @Test
    fun `保存并再记保留类型日期账户并清空金额分类备注`() {
        openEntry()
        typeKeys("key_1", "key_2", "key_dot", "key_5", "key_0")
        pickCategory()

        composeRule.onNodeWithTag(TAG_SAVE_AND_NEW).performClick()
        composeRule.settle()

        assertEquals(1, harness.transactions().size)
        assertEquals(1250L, harness.transactions().first().amountCent)
        assertEquals(0, harness.exitCount)

        composeRule.onNodeWithTag(TAG_AMOUNT_DISPLAY).assertTextEquals("0.00")
        composeRule.onNodeWithTag("field_date").assertTextContains("2026 / 10 / 07")
        composeRule.onNodeWithTag("field_account").assertTextContains("默认账户")
        composeRule.onNodeWithTag("key_dot").assertIsDisplayed()
        composeRule.onNodeWithTag("success_banner").assertIsDisplayed()

        // 再记一笔：类型/日期/账户保留
        typeKeys("key_3", "key_dot", "key_5", "key_0")
        pickCategory("cat_expense_transport")
        composeRule.onNodeWithTag(TAG_SAVE_AND_NEW).performClick()
        composeRule.settle()

        val all = harness.transactions()
        assertEquals(2, all.size)
        assertEquals(setOf(1250L, 350L), all.map { it.amountCent }.toSet())
    }

    // ───────────────────────── 类型切换 ─────────────────────────

    @Test
    fun `切换到收入清空已选分类但保留金额`() {
        openEntry()
        typeKeys("key_1", "key_2")
        pickCategory()

        composeRule.onNodeWithTag("type_income").performClick()
        composeRule.settle()

        composeRule.onNodeWithTag(TAG_AMOUNT_DISPLAY).assertTextEquals("12")
        composeRule.onNodeWithTag("category_cat_income_salary").assertIsDisplayed()
        composeRule.onNodeWithTag("category_cat_expense_food").assertDoesNotExist()

        pickCategory("cat_income_salary")
        composeRule.onNodeWithTag(TAG_SAVE).performClick()
        composeRule.settle()

        val tx = harness.transactions().first()
        assertEquals(TransactionType.INCOME, tx.type)
        assertEquals(1200L, tx.amountCent)
    }

    // ───────────────────────── 编辑模式 ─────────────────────────

    @Test
    fun `编辑模式预填原值保留 id 与创建时间且无保存并再记`() {
        val id = harness.seed(amountCent = 1250L, note = "旧备注")
        val createdBefore = harness.transaction(id)!!.createdAt

        openEntry(editTransactionId = id)
        composeRule.onNodeWithTag(TAG_AMOUNT_DISPLAY).assertTextEquals("12.50")
        composeRule.onNodeWithTag("field_note").assertTextContains("旧备注")
        composeRule.onNodeWithTag(TAG_SAVE_AND_NEW).assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_SAVE).assertTextContains("保存修改")

        repeat(5) {
            composeRule.onNodeWithTag("key_delete").performClick()
        }
        composeRule.settle()
        typeKeys("key_2", "key_0", "key_dot", "key_0", "key_0")
        composeRule.onNodeWithTag(TAG_SAVE).performClick()
        composeRule.settle()

        val updated = harness.transaction(id)!!
        assertEquals(2000L, updated.amountCent)
        assertEquals(id, updated.id)
        assertEquals(createdBefore, updated.createdAt)
        assertEquals(1, harness.transactions().size)
        assertEquals(1, harness.exitCount)
    }

    // ───────────────────────── 退出确认 ─────────────────────────

    @Test
    fun `有未保存修改时返回需要确认`() {
        openEntry()
        composeRule.onNodeWithTag("key_1").performClick()
        composeRule.settle()

        composeRule.onNodeWithTag("btn_back").performClick()
        composeRule.settle()
        composeRule.onNodeWithTag("dialog_discard").assertIsDisplayed()

        composeRule.onNodeWithTag("btn_keep_editing").performClick()
        composeRule.settle()
        composeRule.onNodeWithTag("dialog_discard").assertDoesNotExist()
        assertEquals(0, harness.exitCount)

        composeRule.onNodeWithTag("btn_back").performClick()
        composeRule.settle()
        composeRule.onNodeWithTag("btn_discard").performClick()
        composeRule.settle()
        assertEquals(1, harness.exitCount)
        assertTrue(harness.transactions().isEmpty())
    }

    @Test
    fun `没有修改时直接返回不弹确认`() {
        openEntry()
        composeRule.onNodeWithTag("btn_back").performClick()
        composeRule.settle()

        composeRule.onNodeWithTag("dialog_discard").assertDoesNotExist()
        assertEquals(1, harness.exitCount)
    }

    // ───────────────────────── 键盘与 IME 不叠加 ─────────────────────────

    @Test
    fun `备注聚焦时隐藏数字键盘但保存仍可见可达`() {
        openEntry()
        typeKeys("key_1", "key_2")
        pickCategory()

        // 语义聚焦动作等价于无障碍/IME 聚焦；Robolectric 的触摸注入在文本字段上不稳定。
        composeRule.onNodeWithTag("field_note").performClick()
        composeRule.settle()
        if (!isFocused("field_note")) {
            composeRule.onNodeWithTag("field_note")
                .performSemanticsAction(SemanticsActions.RequestFocus)
            composeRule.settle()
        }
        assertTrue("备注必须能获得焦点", isFocused("field_note"))

        // 两套键盘绝不叠加：金额键盘隐藏，保存仍然可见可点
        composeRule.onNodeWithTag(TAG_ENTRY_KEYBOARD).assertDoesNotExist()
        composeRule.onNodeWithTag("key_dot").assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_SAVE).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_SAVE).assertTextContains("保存账单")
    }

    private fun isFocused(tag: String): Boolean = try {
        composeRule.onNodeWithTag(tag).assertIsFocused()
        true
    } catch (_: AssertionError) {
        false
    }

    // ───────────────────────── TalkBack 语义 ─────────────────────────

    @Test
    fun `键盘按键具备可读语义`() {
        openEntry()
        composeRule.onNodeWithContentDescription("小数点").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("删除").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("数字 1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("数字 0").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("金额数字键盘").assertIsDisplayed()
    }

    @Test
    fun `备注超过 200 字被截断并显示剩余字数`() {
        openEntry()
        composeRule.onNodeWithTag("field_note").performClick()
        composeRule.settle()

        composeRule.onNodeWithTag("field_note").performTextInput("账".repeat(210))
        composeRule.settle()

        composeRule.onNodeWithTag("note_counter").assertIsDisplayed()
        composeRule.onNodeWithTag("note_counter").assertTextContains("还可输入 0 字", substring = true)
    }
}
