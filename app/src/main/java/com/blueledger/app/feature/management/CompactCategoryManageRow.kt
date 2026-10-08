package com.blueledger.app.feature.management

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.core.designsystem.LedgerCategoryIcon
import com.blueledger.app.core.model.LedgerCategory
import kotlin.math.roundToInt

@Composable
internal fun CompactCategoryManageRow(category: LedgerCategory, canMoveUp: Boolean, canMoveDown: Boolean,
    onEdit: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit, onArchive: () -> Unit, onDrop: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }
    val drop by rememberUpdatedState(onDrop)
    Column(Modifier.fillMaxWidth().zIndex(if (drag != 0f) 1f else 0f)
        .graphicsLayer { translationY = drag }.background(T.Surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).onSizeChanged { rowHeight = it.height }
            .testTag(ManagementTags.categoryRow(category.id)), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onArchive, enabled = !category.isFallback,
                modifier = Modifier.size(48.dp).testTag(if (category.isFallback) ManagementTags.CATEGORIES_FALLBACK_HINT else ManagementTags.categoryArchive(category.id))) {
                Icon(if (category.isFallback) Icons.Outlined.Lock else Icons.Outlined.RemoveCircle,
                    if (category.isFallback) "兜底分类不可归档" else "归档 ${category.name}",
                    tint = if (category.isFallback) T.TextSecondary else T.Risk, modifier = Modifier.size(24.dp))
            }
            Row(Modifier.weight(1f).clickable(onClick = onEdit).testTag(ManagementTags.categoryEdit(category.id))
                .heightIn(min = 48.dp).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LedgerCategoryIcon(category.iconKey, selected = false, modifier = Modifier.size(32.dp), diameter = 32.dp)
                Text(category.name, color = T.TextPrimary, fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag("category_drag_${category.id}")
                    .pointerInput(category.id) {
                        detectDragGesturesAfterLongPress(
                            onDrag = { change, amount -> change.consume(); drag += amount.y },
                            onDragEnd = { val distance = (drag / (rowHeight + 1)).roundToInt(); drag = 0f; if (distance != 0) drop(distance) },
                            onDragCancel = { drag = 0f },
                        )
                    }) { Icon(Icons.Outlined.DragHandle, "排序 ${category.name}", tint = T.TextSecondary, modifier = Modifier.size(24.dp)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("上移") }, enabled = canMoveUp, modifier = Modifier.testTag(ManagementTags.categoryMoveUp(category.id)),
                        onClick = { menu = false; onMoveUp() })
                    DropdownMenuItem(text = { Text("下移") }, enabled = canMoveDown, modifier = Modifier.testTag(ManagementTags.categoryMoveDown(category.id)),
                        onClick = { menu = false; onMoveDown() })
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = T.Border.copy(alpha = .45f), thickness = .5.dp)
    }
}
