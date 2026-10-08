package com.blueledger.app.app.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.blueledger.app.app.navigation.Routes
import com.blueledger.app.core.model.DeleteReceipt
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.StatisticsTab
import com.blueledger.app.core.model.TransactionFilterSeed
import com.blueledger.app.di.AppContainer
import com.blueledger.app.feature.backup.DataManagementRoute
import com.blueledger.app.feature.entry.EntryRoute
import com.blueledger.app.feature.management.AccountsRoute
import com.blueledger.app.feature.management.BudgetRoute
import com.blueledger.app.feature.management.CategoriesRoute
import com.blueledger.app.feature.management.MineRoute
import com.blueledger.app.feature.overview.OverviewRoute
import com.blueledger.app.feature.reference.*
import java.time.LocalDate
import java.time.YearMonth
import com.blueledger.app.feature.statistics.StatisticsRoute
import com.blueledger.app.feature.transactions.DetailRoute
import com.blueledger.app.feature.transactions.TransactionsRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 应用根节点：主题 + 一级导航骨架 + 原生导航图。
 *
 * 布局归属说明（重要的是别改坏）：
 * - 底部栏只在四个一级页面显示：它由「＋ 记一笔」主按钮 + 四项导航组成，
 *   固定在内容下方并自带系统导航栏安全区。
 * - 记账页(S02) 是二级任务页，**不显示底部栏**，它拥有自己的底部数字键盘与保存区。
 * - NavHost 内容统一拿 Top + Horizontal 的安全区 padding；底部由各页面自行负责，
 *   避免记账页被多加一层底部内边距而顶掉键盘。
 */
@Composable
fun BlueLedgerApp(container: AppContainer) {
    val navController = rememberNavController()
    val repository = container.ledgerRepository

    val settingsFlow = remember(repository) { repository.observeSettings() }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
    val hideAmounts = settings?.hideAmounts == true

    // 幂等初始化：首次启动写入默认分类/默认账户/设置，账单始终为零。
    LaunchedEffect(Unit) {
        repository.initializeIfNeeded()
    }

    BlueLedgerTheme(hideAmounts = hideAmounts) {
        val backStackEntry by navController.currentBackStackEntryAsState()
        // Navigation returns the destination pattern for optional query arguments, even
        // when a top-level tab is opened without any arguments. Resolve the tab from
        // the actual arguments so filtered drill-down pages remain secondary screens.
        val topLevelRoute = backStackEntry?.primaryTabRoute()
        val showBottomBar = topLevelRoute != null

        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        // 删除撤销：详情页删除成功后把凭据交回这里，由本层统一提供「已删除 · 撤销」。
        // 用 NavHost 这一唯一位置持有，而不是 feature 里的进程内单例——任务书 §10 要求
        // 显式传递、不依赖全局变量碰巧保留。
        var pendingUndo by remember { mutableStateOf<DeleteReceipt?>(null) }

        Scaffold(
            containerColor = BlueLedgerTokens.Background,
            contentWindowInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
            ),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (showBottomBar) {
                    BlueLedgerBottomBar(
                        currentRoute = topLevelRoute,
                        onSelectTopLevel = { route -> navController.switchTopLevel(route) },
                        onRecord = { navController.navigate(Routes.entry()) },
                        modifier = Modifier.testTag(TAG_BOTTOM_BAR),
                    )
                }
            },
        ) { innerPadding ->
            BlueLedgerNavHost(
                navController = navController,
                container = container,
                onTransactionDeleted = { receipt -> pendingUndo = receipt },
                modifier = Modifier.padding(innerPadding),
            )
        }

        LaunchedEffect(pendingUndo) {
            val receipt = pendingUndo ?: return@LaunchedEffect
            val dismissJob = scope.launch {
                delay(UNDO_AFFORDANCE_MILLIS)
                snackbarHostState.currentSnackbarData?.dismiss()
            }
            val result = snackbarHostState.showSnackbar(
                message = "已删除这笔账单",
                actionLabel = "撤销",
                duration = SnackbarDuration.Indefinite,
                withDismissAction = false,
            )
            dismissJob.cancel()
            if (result == SnackbarResult.ActionPerformed) {
                val undoResult = container.ledgerRepository.undoDelete(receipt)
                if (undoResult is com.blueledger.app.core.model.MutationResult.Failure) {
                    snackbarHostState.showSnackbar(
                        message = undoResult.error.message,
                        duration = SnackbarDuration.Short,
                    )
                }
            }
            pendingUndo = null
        }
    }
}

/**
 * 撤销入口的可见时长。
 *
 * 凭据本身的有效期是 [Limits.UNDO_WINDOW_MILLIS]（5 秒，从 deletedAt 起算，由数据层强制）。
 * 入口比它早 200ms 消失，确保用户点得到的那一次一定落在有效期内——
 * 否则边界点击会因为「弹出消失到调用 undoDelete 之间的调度延迟」被判 UNDO_TOKEN_EXPIRED，
 * 那属于实现缺陷而不是用户操作错误。
 */
private const val UNDO_AFFORDANCE_MILLIS: Long = Limits.UNDO_WINDOW_MILLIS - 200L

@Composable
private fun BlueLedgerNavHost(
    navController: NavHostController,
    container: AppContainer,
    onTransactionDeleted: (DeleteReceipt) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.OVERVIEW,
        modifier = modifier,
        // 一级 tab 直接切换，不让两张完整页面在淡入淡出期间同时布局和绘制。
        // 详情、记账等二级页面仍保留短过渡。
        enterTransition = {
            if (initialState.primaryTabRoute() != null && targetState.primaryTabRoute() != null) {
                EnterTransition.None
            } else {
                fadeIn(tween(160))
            }
        },
        exitTransition = {
            if (initialState.primaryTabRoute() != null && targetState.primaryTabRoute() != null) {
                ExitTransition.None
            } else {
                fadeOut(tween(120))
            }
        },
    ) {
        // ── 一级：总览 ──
        composable(Routes.OVERVIEW) {
            ReferenceHomeRoute(container.ledgerRepository, container.clock,
                onDetail = { navController.navigate(Routes.detail(it)) },
                onReport = { navController.navigate("billReport") },
                onBudget = { navController.navigate(Routes.budget(it)) },
                onAssets = { navController.navigate("assets") },
                onMore = { navController.navigate("settings") },
            )
        }

        // ── 一级：账单 ──
        composable(
            route = Routes.TRANSACTIONS_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_YEAR_MONTH) { nullable = true; defaultValue = null; type = NavType.StringType },
                navArgument(Routes.ARG_TYPE) { nullable = true; defaultValue = null; type = NavType.StringType },
                navArgument(Routes.ARG_CATEGORY_ID) { nullable = true; defaultValue = null; type = NavType.StringType },
                navArgument(Routes.ARG_ACCOUNT_ID) { nullable = true; defaultValue = null; type = NavType.StringType },
                navArgument(Routes.ARG_QUERY) { nullable = true; defaultValue = null; type = NavType.StringType },
            ),
        ) { entry ->
            if (entry.primaryTabRoute() != null) {
                ReferenceBillsRoute(container.ledgerRepository, container.clock,
                    onMonth = { navController.navigate("monthReport/$it") })
            } else {
            TransactionsRoute(
                repository = container.ledgerRepository,
                clock = container.clock,
                initialSeed = Routes.parseSeed(
                    yearMonth = entry.arguments?.getString(Routes.ARG_YEAR_MONTH),
                    type = entry.arguments?.getString(Routes.ARG_TYPE),
                    categoryId = entry.arguments?.getString(Routes.ARG_CATEGORY_ID),
                    accountId = entry.arguments?.getString(Routes.ARG_ACCOUNT_ID),
                    query = entry.arguments?.getString(Routes.ARG_QUERY),
                ),
                onOpenDetail = { id -> navController.navigate(Routes.detail(id)) },
                onOpenEntry = { launch -> navController.navigate(Routes.entry(launch = launch)) },
            )
            }
        }

        // ── 一级：统计 ──
        composable(
            route = Routes.STATISTICS_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_TAB) { nullable = true; defaultValue = null; type = NavType.StringType },
                navArgument(Routes.ARG_YEAR_MONTH) { nullable = true; defaultValue = null; type = NavType.StringType },
                // NavType.IntType cannot be nullable. -1 represents an omitted query parameter.
                navArgument(Routes.ARG_YEAR) { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { entry ->
            ReferenceChartRoute(container.ledgerRepository, container.clock,
                initialMonth = Routes.parseYearMonth(entry.arguments?.getString(Routes.ARG_YEAR_MONTH)),
                initialYear = entry.arguments?.getInt(Routes.ARG_YEAR)?.takeIf { it > 0 },
                onCategory = { range, type, id -> navController.navigate("categoryBills/${range.from}/${range.to}/${type.name}/${Routes.encode(id)}") },
            )
        }

        // ── 一级：我的 ──
        composable(Routes.MINE) {
            ReferenceMineRoute(container.ledgerRepository, container.clock,
                onSettings = { navController.navigate("settings") },
                onAccounts = { navController.navigate("assets") },
                onData = { navController.navigate(Routes.DATA) },
            )
        }

        // ── 二级：记账 / 详情 / 管理 ──
        composable(
            route = Routes.ENTRY_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_EDIT_ID) { nullable = true; defaultValue = null; type = NavType.StringType },
                navArgument(Routes.ARG_TYPE) { nullable = true; defaultValue = null; type = NavType.StringType },
                navArgument(Routes.ARG_CATEGORY_ID) { nullable = true; defaultValue = null; type = NavType.StringType },
            ),
        ) { entry ->
            // A2 已交付真实 S02：记账/编辑共用同一状态模型，底部是完整数字键盘 + 保存区。
            EntryRoute(
                repository = container.ledgerRepository,
                clock = container.clock,
                editTransactionId = entry.arguments?.getString(Routes.ARG_EDIT_ID),
                initialType = Routes.parseType(entry.arguments?.getString(Routes.ARG_TYPE)),
                initialCategoryId = entry.arguments?.getString(Routes.ARG_CATEGORY_ID),
                onExit = { navController.popBackStack() },
                categoryFirst = true,
                onOpenCategories = { navController.navigate("${Routes.CATEGORIES}?type=${it.name}") },
            )
        }

        composable(
            route = Routes.DETAIL_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_TRANSACTION_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            DetailRoute(
                repository = container.ledgerRepository,
                clock = container.clock,
                transactionId = entry.arguments?.getString(Routes.ARG_TRANSACTION_ID).orEmpty(),
                onEdit = { id -> navController.navigate(Routes.entry(editTransactionId = id)) },
                onDeleted = { receipt ->
                    onTransactionDeleted(receipt)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable("${Routes.CATEGORIES}?type={type}", arguments = listOf(navArgument("type") { nullable = true; defaultValue = null; type = NavType.StringType })) { entry ->
            CategoriesRoute(
                repository = container.ledgerRepository,
                clock = container.clock,
                onBack = { navController.popBackStack() },
                initialType = Routes.parseType(entry.arguments?.getString("type")),
            )
        }

        composable(
            route = Routes.BUDGET_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_YEAR_MONTH) { nullable = true; defaultValue = null; type = NavType.StringType },
            ),
        ) { entry ->
            ReferenceBudgetRoute(container.ledgerRepository, container.clock,
                initialMonth = Routes.parseYearMonth(entry.arguments?.getString(Routes.ARG_YEAR_MONTH)),
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.ACCOUNTS) {
            AccountsRoute(
                repository = container.ledgerRepository,
                clock = container.clock,
                onBack = { navController.popBackStack() },
                onOpenTransactions = { seed -> navController.navigate(Routes.transactions(seed)) },
            )
        }

        composable("assets") {
            ReferenceAssetsRoute(container.ledgerRepository, container.clock,
                onBack = { navController.popBackStack() },
                onAccountBills = { navController.navigate(Routes.transactions(TransactionFilterSeed(accountId = it))) })
        }
        composable("settings") {
            ReferenceSettingsRoute(container.ledgerRepository, container.clock,
                onBack = { navController.popBackStack() },
                onCategories = { navController.navigate("${Routes.CATEGORIES}?type=${it.name}") },
                onAccounts = { navController.navigate(Routes.ACCOUNTS) },
                onData = { navController.navigate(Routes.DATA) },
                onMigrate = { navController.navigate("migrate") })
        }
        composable("migrate") {
            ReferenceMigrateRoute(container.ledgerRepository, onBack = { navController.popBackStack() })
        }
        composable("billReport") {
            ReferenceBillsRoute(container.ledgerRepository, container.clock,
                onBack = { navController.popBackStack() }, onMonth = { navController.navigate("monthReport/$it") })
        }
        composable("monthReport/{month}") { entry ->
            ReferenceMonthReportRoute(container.ledgerRepository, container.clock,
                month = YearMonth.parse(entry.arguments!!.getString("month")), onBack = { navController.popBackStack() },
                onDetail = { navController.navigate(Routes.detail(it)) },
                onAll = { navController.navigate(Routes.transactions(TransactionFilterSeed(yearMonth = it))) },
                onCategory = { selected, category -> navController.navigate(Routes.transactions(TransactionFilterSeed(yearMonth = selected, type = com.blueledger.app.core.model.TransactionType.EXPENSE, categoryId = category))) })
        }
        composable("categoryBills/{from}/{to}/{type}/{id}") { entry ->
            val args = entry.arguments!!
            ReferenceCategoryBillsRoute(container.ledgerRepository,
                LocalDate.parse(args.getString("from")), LocalDate.parse(args.getString("to")),
                com.blueledger.app.core.model.TransactionType.valueOf(args.getString("type")!!), args.getString("id")!!,
                onBack = { navController.popBackStack() }, onDetail = { navController.navigate(Routes.detail(it)) })
        }

        composable(Routes.DATA) {
            DataManagementRoute(
                repository = container.ledgerRepository,
                clock = container.clock,
                backupCodec = container.backupCodec,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** 一级导航：切换 tab 时保存/恢复各自的状态（筛选条件与滚动位置不丢）。 */
private fun NavHostController.switchTopLevel(route: String) {
    if (currentBackStackEntry?.primaryTabRoute() == route) return
    navigate(route) {
        popUpTo(Routes.OVERVIEW) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

/** 带钻取参数的账单/统计仍是二级页面，不能被当作底部 tab。 */
private fun NavBackStackEntry.primaryTabRoute(): String? = when (val route = destination.route) {
    Routes.OVERVIEW, Routes.MINE -> route
    Routes.TRANSACTIONS_PATTERN -> Routes.TRANSACTIONS.takeIf {
        listOf(
            Routes.ARG_YEAR_MONTH, Routes.ARG_TYPE, Routes.ARG_CATEGORY_ID,
            Routes.ARG_ACCOUNT_ID, Routes.ARG_QUERY,
        ).all { arguments?.getString(it) == null }
    }
    Routes.STATISTICS_PATTERN -> Routes.STATISTICS.takeIf {
        arguments?.getString(Routes.ARG_TAB) == null &&
            arguments?.getString(Routes.ARG_YEAR_MONTH) == null &&
            (arguments?.getInt(Routes.ARG_YEAR) ?: -1) <= 0
    }
    else -> null
}

private data class TopLevelTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val TOP_LEVEL_TABS = listOf(
    TopLevelTab(Routes.OVERVIEW, "明细", Icons.Outlined.Receipt),
    TopLevelTab(Routes.STATISTICS, "图表", Icons.Outlined.BarChart),
    TopLevelTab(Routes.TRANSACTIONS, "账单", Icons.Outlined.Receipt),
    TopLevelTab(Routes.MINE, "我的", Icons.Outlined.Person),
)

const val TAG_RECORD_BUTTON = "global_record_button"
const val TAG_BOTTOM_BAR = "bottom_bar"

/**
 * 底部操作区：四个一级导航围绕一个清晰的记账动作。
 * 记账是动作而不是第五个页面，因此它不参与选中态。
 */
@Composable
private fun BlueLedgerBottomBar(
    currentRoute: String?,
    onSelectTopLevel: (String) -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val largeFont = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.4f
    val baseHeight = if (largeFont) 76.dp else 64.dp
    Box(modifier.fillMaxWidth().navigationBarsPadding().height(baseHeight + if (largeFont) 28.dp else 24.dp)) {
        Surface(Modifier.fillMaxWidth().height(baseHeight).align(Alignment.BottomCenter),
            color = BlueLedgerTokens.Surface, shadowElevation = BlueLedgerTokens.CardElevation) {}
        Row(
            Modifier.fillMaxWidth().height(baseHeight).align(Alignment.BottomCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TOP_LEVEL_TABS.forEachIndexed { index, tab ->
                if (index == 2) {
                    Box(Modifier.weight(1f))
                }
                val selected = currentRoute == tab.route
                val tint = if (selected) BlueLedgerTokens.Primary else BlueLedgerTokens.TextSecondary
                Column(Modifier.weight(1f).fillMaxHeight().testTag("tab_${tab.route}")
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelectTopLevel(tab.route) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Icon(tab.icon, null, tint = tint, modifier = Modifier.size(27.dp))
                    Text(tab.label, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1,
                        color = tint, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp)
            .testTag(TAG_RECORD_BUTTON).clickable(role = Role.Button, onClickLabel = "记一笔", onClick = onRecord),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(BlueLedgerTokens.Primary),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, null, tint = BlueLedgerTokens.Surface, modifier = Modifier.size(30.dp))
            }
            Text("记账", fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, color = BlueLedgerTokens.TextSecondary)
        }
    }
}
