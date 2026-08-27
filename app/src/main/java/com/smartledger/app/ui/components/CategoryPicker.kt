package com.smartledger.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartledger.app.data.Categories
import com.smartledger.app.data.CategoryIcons
import com.smartledger.app.data.TxType

/**
 * 分类选择器（内置分类 + 自定义分类），供单笔记账与编辑共用。
 * [names] 为完整显示顺序（内置 + 自定义，用户排序后），自定义分类通过「＋自定义」对话框新增/删除并可选图标。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategorySelector(
    type: String,
    selected: String,
    names: List<String>,
    customCategories: List<String>,
    onSelect: (String) -> Unit,
    onAddCustom: (String, String) -> Unit,
    onRemoveCustom: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        names.forEach { name ->
            FilterChip(
                selected = selected == name,
                onClick = { onSelect(name) },
                label = { Text(name) },
                leadingIcon = {
                    Icon(
                        imageVector = Categories.of(name).icon,
                        contentDescription = null,
                        tint = Categories.of(name).color,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        FilterChip(
            selected = false,
            onClick = { showAddDialog = true },
            label = { Text("＋自定义") },
            leadingIcon = {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            },
        )
    }

    if (showAddDialog) {
        AddCustomCategoryDialog(
            type = type,
            customCategories = customCategories,
            onAdd = onAddCustom,
            onRemove = onRemoveCustom,
            onDismiss = { showAddDialog = false },
        )
    }
}

/** 紧凑的下拉式分类选择器（多笔明细中每条使用） */
@Composable
fun CategoryDropdown(
    category: String,
    type: String,
    names: List<String>,
    customCategories: List<String>,
    onSelect: (String) -> Unit,
    onAddCustom: (String, String) -> Unit,
    onRemoveCustom: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            val cat = Categories.of(category)
            Icon(
                imageVector = cat.icon,
                contentDescription = null,
                tint = cat.color,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(category, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            names.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(name)
                        expanded = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Categories.of(name).icon,
                            contentDescription = null,
                            tint = Categories.of(name).color,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("＋自定义") },
                onClick = {
                    expanded = false
                    showAddDialog = true
                },
                leadingIcon = {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
        }
    }

    if (showAddDialog) {
        AddCustomCategoryDialog(
            type = type,
            customCategories = customCategories,
            onAdd = onAddCustom,
            onRemove = onRemoveCustom,
            onDismiss = { showAddDialog = false },
        )
    }
}

/** 新增 / 删除自定义分类的对话框（含预设图标选择） */
@Composable
private fun AddCustomCategoryDialog(
    type: String,
    customCategories: List<String>,
    onAdd: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var iconKey by remember { mutableStateOf(CategoryIcons.DEFAULT_KEY) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义${TxType.label(type)}分类") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("输入分类名称") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Text("选择图标", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                IconPickerGrid(selectedKey = iconKey, onSelect = { iconKey = it }, heightDp = 160)
                if (customCategories.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "已添加的自定义分类",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    customCategories.forEach { name ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(name, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onRemove(name) }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "删除 $name",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (input.isNotBlank()) {
                    onAdd(input.trim(), iconKey)
                    input = ""
                }
            }) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
