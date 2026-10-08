package com.blueledger.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.app.ui.TAG_RECORD_BUTTON
import com.blueledger.app.feature.entry.TAG_AMOUNT_DISPLAY
import com.blueledger.app.feature.entry.TAG_ENTRY_BOTTOM
import com.blueledger.app.feature.entry.TAG_ENTRY_KEYBOARD
import com.blueledger.app.feature.entry.TAG_SAVE
import com.blueledger.app.feature.management.ManagementTags
import com.blueledger.app.feature.overview.TAG_OVERVIEW_SCREEN
import com.blueledger.app.feature.statistics.StatisticsTags
import com.blueledger.app.feature.transactions.TAG_TX_SCREEN
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

/** Catches crashes while constructing the real navigation graph on a device. */
@RunWith(AndroidJUnit4::class)
class AppLaunchInstrumentedTest {

    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesAndAmountKeyboardWorks() {
        composeRule.onNodeWithTag(TAG_OVERVIEW_SCREEN).assertIsDisplayed()
        // 浮动入口的坐标可能随系统配置重排；实际文字触点另外通过 ADB 复核。
        composeRule.onNodeWithTag(TAG_RECORD_BUTTON).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("category_cat_expense_food").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TAG_ENTRY_KEYBOARD).assertDoesNotExist()
        composeRule.onNodeWithTag("category_cat_expense_food").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(TAG_ENTRY_BOTTOM).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TAG_ENTRY_BOTTOM).assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TAG_AMOUNT_DISPLAY).assertTextEquals("0.00")
        listOf(
            "key_1" to "1",
            "key_2" to "12",
            "key_dot" to "12.",
            "key_5" to "12.5",
            "key_0" to "12.50",
        ).forEach { (key, amount) ->
            // The system IME may finish its exit animation while the keypad moves.
            // Invoke the key's accessibility click action to test its exact callback.
            composeRule.onNodeWithTag(key).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.OnClick)
            composeRule.onNodeWithTag(TAG_AMOUNT_DISPLAY).assertTextEquals(amount)
        }
        composeRule.onNodeWithTag("key_dot").assertIsDisplayed()
        composeRule.onNodeWithTag("key_0").assertIsDisplayed()
        composeRule.onNodeWithTag("key_delete").assertIsDisplayed()
        composeRule.onNodeWithTag("field_note").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        composeRule.onNodeWithTag(TAG_ENTRY_KEYBOARD).assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_SAVE).assertIsDisplayed()
        val decor = composeRule.activity.window.decorView
        val imeBottom = ViewCompat.getRootWindowInsets(decor)!!
            .getInsets(WindowInsetsCompat.Type.ime()).bottom
        val save = composeRule.onNodeWithTag(TAG_SAVE).fetchSemanticsNode().boundsInWindow
        assertTrue("保存按钮必须在真实输入法上方", save.bottom <= decor.height - imeBottom + 1)
        // 不提交账单，真机测试不修改用户记账数据。
    }

    @Test
    fun budgetKeypadIsFullyExpandedOnOpen() {
        composeRule.onNodeWithTag("home_预算").performClick()
        composeRule.onNodeWithText("编辑").performClick()
        // 弹层打开时控件仍在移动，调用已定位控件的点击动作。
        composeRule.onNodeWithText("编辑月度总预算").performSemanticsAction(SemanticsActions.OnClick)
        val minimum = 48 * composeRule.activity.resources.displayMetrics.density
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                val bounds = composeRule.onNodeWithTag("key_delete").assertIsDisplayed()
                    .fetchSemanticsNode().boundsInWindow
                bounds.width >= minimum && bounds.height >= minimum
            }.getOrDefault(false)
        }
        composeRule.waitForIdle()
        (listOf("key_dot", "key_delete") + (0..9).map { "key_$it" }).forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            assertTrue("$tag 必须完整显示且至少48dp", bounds.width >= minimum && bounds.height >= minimum)
        }
        // 不设置预算，只检查真实弹层与键盘的首次展开布局。
    }

    @Test
    fun primaryTabsOpenOnDevice() {
        composeRule.onNodeWithTag(TAG_OVERVIEW_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag("tab_transactions")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag(TAG_TX_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag("tab_statistics")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag(StatisticsTags.SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(StatisticsTags.TAB_YEAR)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(StatisticsTags.YEAR_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(StatisticsTags.YEAR_LABEL).assertIsDisplayed()
        composeRule.onNodeWithTag("tab_mine")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag(ManagementTags.MINE_SCREEN).assertIsDisplayed()
    }
}
