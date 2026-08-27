package com.smartledger.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import com.smartledger.app.data.Categories
import com.smartledger.app.data.CategoryIcons
import com.smartledger.app.data.TxType
import com.smartledger.app.ui.AppViewModel
import com.smartledger.app.ui.theme.Dimens
import com.smartledger.app.ui.theme.ThemeMode
import com.smartledger.app.widget.WidgetPeriod
import kotlinx.coroutines.launch
import java.time.LocalDate

private data class EditingCategory(
    val name: String,
    val type: String,
    val isBuiltIn: Boolean,
    val isNew: Boolean = false,
)

/** 设置：分类管理（改名 / 换图标 / 新增 / 删除自定义分类） + 主题 + 备份恢复 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val customCategories by viewModel.customCategories.collectAsState()
    val categoryOrder by viewModel.categoryOrder.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val widgetPeriod by viewModel.widgetPeriod.collectAsState()

    var editing by remember { mutableStateOf<EditingCategory?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 恢复前待确认的 uri
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    // 备份：让用户选择保存位置
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val count = viewModel.backupTo(it)
                    snackbarHostState.showSnackbar("已备份 $count 笔账目")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("备份失败：${e.message}")
                }
            }
        }
    }

    // 恢复：让用户选择备份文件
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) pendingRestoreUri = uri
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.Close, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = Dimens.pagePadding,
                end = Dimens.pagePadding,
                top = Dimens.lg,
                bottom = Dimens.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.sectionSpacing),
        ) {
            item {
                Text("分类管理", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "点击分类可改名、更换图标；用 ↑↓ 调整顺序；底部可新增自定义分类",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                CategorySection(
                    title = "支出分类",
                    names = categoryOrder[TxType.EXPENSE].orEmpty(),
                    type = TxType.EXPENSE,
                    onEdit = { name -> editing = EditingCategory(name, TxType.EXPENSE, Categories.isBuiltIn(name)) },
                    onAdd = { editing = EditingCategory("", TxType.EXPENSE, isBuiltIn = false, isNew = true) },
                    onMove = { from, to -> viewModel.moveCategory(TxType.EXPENSE, from, to) },
                )
            }

            item {
                CategorySection(
                    title = "收入分类",
                    names = categoryOrder[TxType.INCOME].orEmpty(),
                    type = TxType.INCOME,
                    onEdit = { name -> editing = EditingCategory(name, TxType.INCOME, Categories.isBuiltIn(name)) },
                    onAdd = { editing = EditingCategory("", TxType.INCOME, isBuiltIn = false, isNew = true) },
                    onMove = { from, to -> viewModel.moveCategory(TxType.INCOME, from, to) },
                )
            }

            item {
                Text("主题", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "选择应用主题色，或跟随系统壁纸取色（莫奈）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(mode.label) },
                            leadingIcon = {
                                mode.previewColor?.let { color ->
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .background(color, CircleShape),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            item {
                Text("桌面小组件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "选择小组件展示的总览周期（日/周/月）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WidgetPeriod.entries.forEach { period ->
                        FilterChip(
                            selected = widgetPeriod == period,
                            onClick = { viewModel.setWidgetPeriod(period) },
                            label = { Text("${period.label}总览") },
                        )
                    }
                }
            }

            item {
                Text("数据备份与恢复", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "备份为 JSON 文件；恢复会覆盖当前全部账目",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { backupLauncher.launch("慧记账备份-${LocalDate.now()}.json") },
                        modifier = Modifier.weight(1f),
                    ) { Text("备份到文件") }
                    OutlinedButton(
                        onClick = { restoreLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier.weight(1f),
                    ) { Text("从文件恢复") }
                }
            }
        }
    }

    editing?.let { editingCat ->
        CategoryEditDialog(
            editing = editingCat,
            onDismiss = { editing = null },
            onSave = { newName, iconKey ->
                val finalName = newName.trim().ifBlank { editingCat.name }
                if (editingCat.isNew) {
                    viewModel.addCustomCategory(editingCat.type, finalName)
                } else if (!editingCat.isBuiltIn && finalName != editingCat.name) {
                    viewModel.renameCustomCategory(editingCat.type, editingCat.name, finalName)
                }
                viewModel.setCategoryIcon(finalName, iconKey)
                editing = null
            },
            onDelete = {
                viewModel.removeCustomCategory(editingCat.type, editingCat.name)
                editing = null
            },
        )
    }

    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("恢复备份") },
            text = { Text("恢复将覆盖当前全部账目，且不可撤销。确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestoreUri = null
                    scope.launch {
                        try {
                            val count = viewModel.restoreFrom(uri)
                            snackbarHostState.showSnackbar("已恢复 $count 笔账目")
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("恢复失败：${e.message}")
                        }
                    }
                }) { Text("确定恢复") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun CategorySection(
    title: String,
    names: List<String>,
    type: String,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            names.forEachIndexed { index, name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(name) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val cat = Categories.of(name)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(cat.color.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = cat.icon,
                            contentDescription = cat.name,
                            tint = cat.color,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(
                        onClick = { onMove(index, index - 1) },
                        enabled = index > 0,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowUp,
                            contentDescription = "上移",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onMove(index, index + 1) },
                        enabled = index < names.lastIndex,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "下移",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新增${TxType.label(type)}分类")
            }
        }
    }
}

@Composable
private fun CategoryEditDialog(
    editing: EditingCategory,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(editing.name) { mutableStateOf(editing.name) }
    var iconKey by remember(editing.name) { mutableStateOf(Categories.iconKeyOf(editing.name)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing.isNew) "新增分类" else if (editing.isBuiltIn) "更换图标" else "编辑分类") },
        text = {
            Column {
                if (!editing.isBuiltIn) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("分类名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        "「${editing.name}」为内置分类，仅可更换图标",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("选择图标", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.height(280.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(CategoryIcons.ALL) { option ->
                        val selected = option.key == iconKey
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable { iconKey = option.key },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = option.icon,
                                contentDescription = option.label,
                                tint = if (selected) MaterialTheme.colorScheme.primary else option.color,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, iconKey) }, enabled = name.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                if (!editing.isBuiltIn && !editing.isNew) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
