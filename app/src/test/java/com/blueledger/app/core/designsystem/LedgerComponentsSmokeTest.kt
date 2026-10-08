package com.blueledger.app.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.app.ui.BlueLedgerTheme
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.MoneySummary
import com.blueledger.app.core.model.TransactionType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 公共组件冒烟测试：A3/A4/A5/A6 要用的每个组件都能在**不依赖任何业务
 * ViewModel** 的情况下真实组合、渲染并暴露可定位的语义节点。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class LedgerComponentsSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `顶部栏与卡片组件`() {
        composeRule.setContent {
            BlueLedgerTheme {
                Column {
                    LedgerTopBar(title = "账单", onBack = {}, actions = {})
                    LedgerSectionCard {
                        LedgerSectionHeader(title = "最近账单", subtitle = "查看全部")
                        LedgerKeyValueRow(label = "账户", value = "默认账户")
                        LedgerDivider()
                    }
                }
            }
        }
        composeRule.onNodeWithTag("btn_back").assertIsDisplayed()
        composeRule.onNodeWithText("账单").assertIsDisplayed()
        composeRule.onNodeWithText("最近账单").assertIsDisplayed()
        composeRule.onNodeWithText("默认账户").assertIsDisplayed()
    }

    @Test
    fun `深蓝摘要卡展示准确金额`() {
        composeRule.setContent {
            BlueLedgerTheme {
                LedgerSummaryCard(
                    label = "本月结余",
                    summary = MoneySummary(incomeCent = 1_080_000L, expenseCent = 307_880L, count = 7),
                    incomeLabel = "本月收入",
                    expenseLabel = "本月支出",
                    testTag = "summary_card",
                )
            }
        }
        composeRule.onNodeWithTag("summary_card").assertIsDisplayed()
        composeRule.onNodeWithTag("summary_card").assertTextContains("本月结余")
        composeRule.onNodeWithTag("summary_card").assertTextContains("7,721.20")
        composeRule.onNodeWithTag("summary_card").assertTextContains("10,800.00")
        composeRule.onNodeWithTag("summary_card").assertTextContains("3,078.80")
    }

    @Test
    fun `分段按钮与分类项与账单行`() {
        composeRule.setContent {
            BlueLedgerTheme {
                Column {
                    LedgerSegmentedToggle(
                        options = listOf(TransactionType.EXPENSE, TransactionType.INCOME),
                        selected = TransactionType.EXPENSE,
                        label = { if (it == TransactionType.EXPENSE) "支出" else "收入" },
                        onSelect = {},
                        testTags = { if (it == TransactionType.EXPENSE) "type_expense" else "type_income" },
                    )
                    LedgerCategoryCell(
                        name = "餐饮",
                        iconKey = "restaurant",
                        selected = true,
                        onClick = {},
                        testTag = "category_food",
                    )
                    LedgerTransactionRow(
                        categoryName = "餐饮",
                        iconKey = "restaurant",
                        type = TransactionType.EXPENSE,
                        amountCent = 2850L,
                        title = "晚餐",
                        subtitle = "餐饮 · 默认账户 · 2026 / 10 / 07",
                        testTag = "row_food",
                    )
                    LedgerFormRow(
                        label = "日期",
                        value = "2026 / 10 / 07",
                        onClick = {},
                        testTag = "field_date",
                    )
                }
            }
        }
        composeRule.onNodeWithTag("type_expense").assertIsDisplayed()
        composeRule.onNodeWithTag("category_food").assertIsDisplayed()
        composeRule.onNodeWithTag("row_food").assertIsDisplayed()
        composeRule.onNodeWithTag("row_food").assertTextContains("−28.50")
        composeRule.onNodeWithTag("field_date").assertIsDisplayed()
        composeRule.onNodeWithTag("field_date").assertTextContains("2026 / 10 / 07")
    }

    @Test
    fun `主次按钮与反馈横幅`() {
        composeRule.setContent {
            BlueLedgerTheme {
                Column {
                    LedgerPrimaryButton(text = "保存账单", onClick = {}, testTag = "btn_primary")
                    LedgerSecondaryButton(text = "保存并再记", onClick = {}, testTag = "btn_secondary")
                    LedgerErrorBanner(
                        message = "未能保存，请重试",
                        actionText = "重试",
                        onAction = {},
                        testTag = "banner_error",
                    )
                    LedgerSuccessBanner(message = "已记下 ¥36.00", testTag = "banner_success")
                    LedgerInfoBanner(message = "本地账本 · 无需登录")
                    LedgerProgressBar(fraction = 0.7697f)
                    LedgerProgressBar(fraction = null)
                }
            }
        }
        composeRule.onNodeWithTag("btn_primary").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_primary").assertTextContains("保存账单")
        composeRule.onNodeWithTag("btn_secondary").assertIsDisplayed()
        composeRule.onNodeWithTag("banner_error").assertIsDisplayed()
        composeRule.onNodeWithTag("banner_error_action").assertIsDisplayed()
        composeRule.onNodeWithTag("banner_success").assertIsDisplayed()
        composeRule.onNodeWithText("本地账本 · 无需登录").assertIsDisplayed()
    }

    @Test
    fun `空态加载态错误态互相区分`() {
        composeRule.setContent {
            BlueLedgerTheme {
                Column {
                    LedgerEmptyState(
                        title = "这个月还没有账单",
                        description = "记下第一笔，慢慢看清每月收支",
                        actionText = "记一笔",
                        onAction = {},
                        testTag = "empty",
                    )
                }
            }
        }
        composeRule.onNodeWithTag("empty").assertIsDisplayed()
        composeRule.onNodeWithTag("empty_action").assertIsDisplayed()
        composeRule.onNodeWithText("这个月还没有账单").assertIsDisplayed()
    }

    @Test
    fun `加载态与错误态`() {
        composeRule.setContent {
            BlueLedgerTheme {
                Column {
                    LedgerLoadingState(message = "正在读取…")
                    LedgerErrorState(message = "读取失败，请重试", onRetry = {}, testTag = "error_state")
                }
            }
        }
        composeRule.onNodeWithTag("state_loading").assertIsDisplayed()
        composeRule.onNodeWithTag("error_state").assertIsDisplayed()
        composeRule.onNodeWithTag("error_state_retry").assertIsDisplayed()
    }

    @Test
    fun `金额隐藏同时作用于显示文本与无障碍描述`() {
        composeRule.setContent {
            BlueLedgerTheme(hideAmounts = true) {
                Column {
                    LedgerSummaryCard(
                        label = "本月结余",
                        summary = MoneySummary(incomeCent = 1_080_000L, expenseCent = 307_880L, count = 7),
                        testTag = "summary_card",
                    )
                    LedgerTransactionRow(
                        categoryName = "餐饮",
                        iconKey = "restaurant",
                        type = TransactionType.EXPENSE,
                        amountCent = 2850L,
                        title = "晚餐",
                        subtitle = "餐饮 · 默认账户",
                        testTag = "row_food",
                    )
                }
            }
        }
        // 隐藏后不能读出真实金额（不是透明文字），显示值与语义都不泄露
        composeRule.onAllNodesWithText("••••", substring = true).assertCountEquals(2)
        composeRule.onAllNodesWithText("7,721", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("28.50", substring = true).assertCountEquals(0)

        val descriptions = composeRule.onNodeWithTag("row_food")
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
        org.junit.Assert.assertTrue(
            "隐藏金额后账单行语义必须说明金额已隐藏：$descriptions",
            descriptions.any { it.contains("金额已隐藏") },
        )
        org.junit.Assert.assertTrue(
            "隐藏金额后语义不得泄露真实金额：$descriptions",
            descriptions.none { it.contains("28.50") },
        )
        val summaryDescription = composeRule.onNodeWithTag("summary_card")
            .fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription]
        org.junit.Assert.assertTrue(
            "隐藏金额后摘要卡语义必须说明金额已隐藏：$summaryDescription",
            summaryDescription.any { it.contains("金额已隐藏") },
        )
    }

    @Test
    fun `分类图标圆底与账户类型图标`() {
        composeRule.setContent {
            BlueLedgerTheme {
                Column {
                    LedgerCategoryIcon(iconKey = "restaurant", selected = true)
                    LedgerCategoryIcon(iconKey = "restaurant", selected = false, archived = true)
                    AccountKind.entries.forEach { _ ->
                        LedgerCategoryIcon(iconKey = "不存在的图标", selected = false)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
