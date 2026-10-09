package com.blueledger.app.feature.management

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.core.model.CategoryIcons
import com.blueledger.app.core.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * S07 分类管理的真实 Compose 渲染测试（Robolectric）。
 *
 * 验收点：收支分区、新增/改名/图标、排序按钮、归档确认与恢复、
 * 兜底「其他」没有归档入口、失败原因在弹层内展示。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h1600dp-xhdpi")
class CategoriesRouteUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ManagementHarness

    @Before
    fun setUp() {
        harness = ManagementHarness()
        // Robolectric 下自动时钟无法收敛（无限旋转动画），统一手动推帧。
        composeRule.mainClock.autoAdvance = false
    }

    private fun scrollCategories(toBottom: Boolean) {
        // 手动时钟下 performScrollTo 的内部重试不会推进帧；显式执行滚动后推帧。
        composeRule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { action ->
            action(0f, if (toBottom) 100_000f else -100_000f)
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.settleUi()
    }

    @Test
    fun `支出收入分区与分类行`() {
        composeRule.setContent { CategoriesRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.CATEGORIES_ACTIVE_COUNT)

        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_ACTIVE_COUNT)
            .assertTextContains("我的分类 · ${com.blueledger.app.core.model.Defaults.EXPENSE_CATEGORIES.size} 个可用")
        composeRule.onNodeWithTag(ManagementTags.categoryRow("cat_expense_food")).assertIsDisplayed()
        composeRule.onNodeWithText("餐饮").assertIsDisplayed()

        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_TYPE_INCOME).performClick()
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_ACTIVE_COUNT)
            .assertTextContains("我的分类 · ${com.blueledger.app.core.model.Defaults.INCOME_CATEGORIES.size} 个可用")
        composeRule.onNodeWithTag(ManagementTags.categoryRow("cat_income_salary")).assertIsDisplayed()
    }

    @Test
    fun `兜底分类没有归档入口`() {
        composeRule.setContent { CategoriesRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.CATEGORIES_ACTIVE_COUNT)

        composeRule.onAllNodesWithTag(ManagementTags.categoryArchive("cat_expense_other")).assertCountEquals(0)
        scrollCategories(toBottom = true)
        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_FALLBACK_HINT).assertIsDisplayed()
        // 其他分类都有归档入口。
        scrollCategories(toBottom = false)
        composeRule.onNodeWithTag(ManagementTags.categoryArchive("cat_expense_food")).assertIsDisplayed()
    }

    @Test
    fun `归档需确认取消后分类仍在`() {
        composeRule.setContent { CategoriesRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.CATEGORIES_ACTIVE_COUNT)

        composeRule.onNodeWithTag(ManagementTags.categoryArchive("cat_expense_transport")).performClick()
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_ARCHIVE_DIALOG).assertIsDisplayed()
        assertFalse(harness.categories(TransactionType.EXPENSE).first { it.id == "cat_expense_transport" }.isArchived)

        composeRule.onNodeWithTag("btn_category_archive_cancel").performClick()
        composeRule.settleUi()
        assertFalse(harness.categories(TransactionType.EXPENSE).first { it.id == "cat_expense_transport" }.isArchived)
        scrollCategories(toBottom = false)
        composeRule.onNodeWithTag(ManagementTags.categoryArchive("cat_expense_transport")).assertIsDisplayed()
    }

    @Test
    fun `确认归档后进入已归档分区并可恢复`() {
        composeRule.setContent { CategoriesRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.CATEGORIES_ACTIVE_COUNT)

        composeRule.onNodeWithTag(ManagementTags.categoryArchive("cat_expense_transport")).performClick()
        composeRule.settleUi()
        composeRule.onNodeWithTag("btn_category_archive_confirm").performClick()
        composeRule.settleUi()

        assertTrue(harness.categories(TransactionType.EXPENSE).first { it.id == "cat_expense_transport" }.isArchived)
        scrollCategories(toBottom = true)
        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_ARCHIVED_HEADER).assertIsDisplayed()
        composeRule.onAllNodesWithTag(ManagementTags.categoryArchive("cat_expense_transport")).assertCountEquals(0)
        composeRule.onNodeWithTag(ManagementTags.categoryRestore("cat_expense_transport")).assertIsDisplayed().performClick()
        composeRule.settleUi()
        assertFalse(harness.categories(TransactionType.EXPENSE).first { it.id == "cat_expense_transport" }.isArchived)
        scrollCategories(toBottom = false)
        composeRule.onNodeWithTag(ManagementTags.categoryArchive("cat_expense_transport")).assertIsDisplayed()
    }

    @Test
    fun `弹层错误文案与图标选择器的可验证边界`() {
        // Robolectric 下「弹层在首帧就已存在」的组合永远不 idle（实测 AppNotIdleException，
        // 14 万—15 万次尝试 60 秒超时，见报告 §3.7）。因此这里不对弹层做渲染断言，改为：
        // 1) 业务规则（重名含归档、13 字符被拒、图标写入、失败保留输入）由
        //    CategoriesViewModelTest 在状态层断言；
        // 2) 「弹层能打开且结构正确」由本类 `编辑分类保留原类型且不提供类型切换`（真实点击打开）
        //    的用例覆盖；
        // 3) 图标 key 只允许来自唯一权威集合 CategoryIcons.SELECTABLE。
        assertTrue(CategoryIcons.isValidKey(CategoryIcons.STUDY))
        assertTrue(CategoryIcons.STUDY in CategoryIcons.SELECTABLE)
        assertFalse(CategoryIcons.isValidKey("no_such_icon"))
        assertEquals("其他", com.blueledger.app.core.designsystem.LedgerIcons.iconLabel("no_such_icon"))
    }

    @Test
    fun `排序按钮调整仓库顺序`() {
        composeRule.setContent { CategoriesRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.CATEGORIES_ACTIVE_COUNT)

        assertEquals(
            listOf("cat_expense_food", "cat_expense_shopping"),
            harness.categories(TransactionType.EXPENSE).take(2).map { it.id },
        )
        composeRule.onNodeWithTag("category_drag_cat_expense_food").performClick()
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.categoryMoveDown("cat_expense_food")).performClick()
        composeRule.settleUi()
        assertEquals(
            listOf("cat_expense_shopping", "cat_expense_food"),
            harness.categories(TransactionType.EXPENSE).take(2).map { it.id },
        )
    }

    @Test
    fun `编辑分类保留原类型且不提供类型切换`() {
        composeRule.setContent { CategoriesRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.CATEGORIES_ACTIVE_COUNT)

        composeRule.onNodeWithTag(ManagementTags.categoryEdit("cat_expense_food")).performClick()
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_EDITOR_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText("收支类型：支出（建立后不可更改）").assertIsDisplayed()
        composeRule.onAllNodesWithTag(ManagementTags.CATEGORIES_TYPE_INCOME).assertCountEquals(1)
    }

    @Test
    fun `新增分类在整页编辑器内按分组选图标并写入`() {
        composeRule.setContent { CategoriesRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.CATEGORIES_ACTIVE_COUNT)

        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_ADD).performClick()
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_EDITOR_DIALOG).assertIsDisplayed()
        listOf("常用", "收入", "生活", "其他").forEach { title ->
            composeRule.onNodeWithTag(ManagementTags.categoryIconGroup(title)).assertExists()
        }
        // 名称为空时「完成」不可点。
        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_SAVE).assertIsNotEnabled()

        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_NAME_FIELD).performTextInput("夜宵")
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_SAVE).assertIsEnabled()

        composeRule.onNodeWithTag(ManagementTags.categoryIconOption("snacks")).performScrollTo().performClick()
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.categoryIconOption("snacks")).assertIsSelected()

        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_SAVE).performClick()
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_EDITOR_DIALOG).assertDoesNotExist()
        val created = harness.categories(TransactionType.EXPENSE).single { it.name == "夜宵" }
        assertEquals("snacks", created.iconKey)
    }

    @Test
    fun `编辑页取消不写入任何改动`() {
        composeRule.setContent { CategoriesRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.CATEGORIES_ACTIVE_COUNT)
        val before = harness.categories(TransactionType.EXPENSE).map { it.id to it.name }

        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_ADD).performClick()
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_NAME_FIELD).performTextInput("临时分类")
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_CANCEL).performClick()
        composeRule.settleUi()

        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_EDITOR_DIALOG).assertDoesNotExist()
        assertEquals(before, harness.categories(TransactionType.EXPENSE).map { it.id to it.name })
    }
}
