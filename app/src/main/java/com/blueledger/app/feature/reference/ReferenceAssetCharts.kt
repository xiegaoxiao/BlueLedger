package com.blueledger.app.feature.reference

import androidx.compose.foundation.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.core.contract.*
import com.blueledger.app.core.designsystem.*
import com.blueledger.app.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
internal fun ReferenceAssetCharts(repository: LedgerRepository, clock: Clock, assets: List<AccountWithBalance>, modifier: Modifier) {
    var type by rememberSaveable { mutableIntStateOf(0) }
    var year by rememberSaveable { mutableIntStateOf(clock.today().year) }
    val (state, _) = ledgerState(repository, "reference.assetHistory", TransactionFilter(limit = Int.MAX_VALUE))
    val series by produceState<List<Pair<Long, Long>>>(emptyList(), assets, state, year) {
        value = withContext(Dispatchers.Default) {
            assetMonthlyTotals(assets.map { it.account }, state.rows, year, clock.today())
        }
    }
    val firstRecorded = remember(assets, state.rows) {
        assets.map { account ->
            AssetHistory.parse(account.account.openingHistory).firstOrNull()?.first ?: clock.today()
        }.minOrNull() ?: clock.today()
    }
    val startMonth = if (year == firstRecorded.year) firstRecorded.monthValue else 1
    val endMonth = if (year == clock.today().year) clock.today().monthValue else 12
    val values = remember(series, type, startMonth, endMonth, year, firstRecorded) {
        if (assets.isEmpty() || year < firstRecorded.year) emptyList() else series.drop(startMonth - 1).take(endMonth - startMonth + 1)
            .map { totals -> when (type) { 0 -> totals.first; 1 -> totals.second; else -> totals.first - totals.second } }
    }
    val balances = assets.filter { when (type) { 0 -> it.balanceCent > 0; 1 -> it.balanceCent < 0; else -> it.balanceCent != 0L } }
        .let { rows -> if (type == 2) rows.sortedByDescending { it.balanceCent } else rows.sortedByDescending { abs(it.balanceCent) } }
    val totals = assetTotals(assets.map { it.balanceCent })
    val currentTotal = when (type) { 0 -> totals.first; 1 -> totals.second; else -> totals.first - totals.second }
    val composition = if (type == 2) listOf("资产" to totals.first, "负债" to totals.second).filter { it.second > 0 }
        else assetPlatformGroups(balances).map { (platform, group) -> platform.label to group.sumOf { abs(it.balanceCent) } }.sortedByDescending { it.second }
    val colors = if (type == 2) listOf(T.Primary, T.Risk) else listOf(T.Primary, Color(0xFF078B78), Color(0xFF7C4DC4), Color(0xFFD46A20))
    val title = listOf("资产", "负债", "净资产")[type]
    Column(modifier) {
        LedgerSegmentedControl(listOf(0, 1, 2), type, { listOf("资产", "负债", "净资产")[it] }, { type = it }, modifier = Modifier.padding(horizontal = T.SpaceL, vertical = T.SpaceS), tag = { "asset_chart_type_$it" })
        LazyColumn(Modifier.weight(1f).testTag("asset_charts_list"), contentPadding = PaddingValues(T.SpaceL), verticalArrangement = Arrangement.spacedBy(T.SpaceL)) {
        item {
            LedgerChartCard {
                    val yearControls: @Composable () -> Unit = {
                        LedgerYearNavigation(year, year > firstRecorded.year, year < clock.today().year, { year-- }, { year++ }, yearTag = "asset_chart_year")
                    }
                    if (LocalDensity.current.fontScale <= 1.4f) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${title}走势图", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = T.TextPrimary)
                            yearControls()
                        }
                    } else {
                        Text("${title}走势图", style = MaterialTheme.typography.titleMedium, color = T.TextPrimary)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { yearControls() }
                    }
                    LedgerLineChart((1..12).map { month -> values.getOrNull(month - startMonth) }, (1..12).map { "${it}月" }, description = "${title}年度趋势", chartTag = "asset_trend_chart", showAverage = false)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(T.SpaceM)) {
                Text("当前${title}状况", style = MaterialTheme.typography.titleMedium, color = T.TextPrimary)
                LedgerCompositionChart(composition.mapIndexed { index, item -> LedgerChartSlice(item.first, item.second, colors[index % colors.size]) }, currentTotal, if (type == 2) title else "总$title", amountTag = "asset_chart_current_total")
            }
        }
        item {
            HorizontalDivider(color = Color(0xFFCCD5E2))
            LedgerSectionHeader("${title}排行榜", Modifier.padding(top = T.SpaceL).testTag("asset_ranking_title"))
            if (balances.isEmpty()) Text(if (type == 1) "暂无负债账户" else "暂无账户数据", Modifier.padding(top = T.SpaceM), color = Color(0xFF42526B))
        }
        items(balances, key = { it.account.id }) { item ->
            val share = abs(item.balanceCent).toDouble() / balances.sumOf { abs(it.balanceCent) }.coerceAtLeast(1L)
            Column {
                LedgerRankingRow(item.account.name, if (type == 1) abs(item.balanceCent) else item.balanceCent, share,
                    modifier = Modifier.testTag("asset_rank_${item.account.id}"), subtitle = item.account.location.label,
                    amountTag = "asset_rank_balance_${item.account.id}", color = if (item.balanceCent < 0) T.Risk else T.Primary) {
                    Box(Modifier.size(40.dp).background(T.PrimarySoft, RoundedCornerShape(T.RadiusChip)), contentAlignment = Alignment.Center) {
                        Icon(LedgerIcons.accountKindIcon(item.account.kind), null, tint = if (item.balanceCent < 0) T.Risk else T.Primary)
                    }
                }
                HorizontalDivider(Modifier.padding(top = T.SpaceS), color = T.Border)
            }
        }
        }
    }
}

