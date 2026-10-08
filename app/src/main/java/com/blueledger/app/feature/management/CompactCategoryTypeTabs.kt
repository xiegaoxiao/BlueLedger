package com.blueledger.app.feature.management

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.core.model.TransactionType

@Composable
internal fun CompactCategoryTypeTabs(selected: TransactionType, onSelect: (TransactionType) -> Unit) {
    val shape = RoundedCornerShape(3.dp)
    Row(Modifier.fillMaxWidth().clip(shape).border(1.dp, T.TextPrimary, shape)) {
        listOf(TransactionType.EXPENSE, TransactionType.INCOME).forEach { type ->
            Box(Modifier.weight(1f).background(if (selected == type) T.TextPrimary else T.PrimarySoft)
                .selectable(selected == type, role = Role.Tab, onClick = { onSelect(type) })
                .heightIn(min = 40.dp).padding(vertical = 6.dp)
                .testTag(if (type == TransactionType.EXPENSE) ManagementTags.CATEGORIES_TYPE_EXPENSE else ManagementTags.CATEGORIES_TYPE_INCOME),
                contentAlignment = Alignment.Center) {
                Text(if (type == TransactionType.EXPENSE) "支出" else "收入", fontSize = 16.sp,
                    color = if (selected == type) T.Surface else T.TextPrimary)
            }
        }
    }
}
