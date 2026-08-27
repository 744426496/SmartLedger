package com.smartledger.app.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** 消费分类定义（名称 + 图标 + 主题色） */
data class Category(
    val name: String,
    val icon: ImageVector,
    val color: Color,
)

object Categories {
    val EXPENSE = listOf(
        Category("餐饮", Icons.Filled.Restaurant, Color(0xFFFF7043)),
        Category("交通", Icons.Filled.DirectionsBus, Color(0xFF42A5F5)),
        Category("购物", Icons.Filled.ShoppingCart, Color(0xFFAB47BC)),
        Category("娱乐", Icons.Filled.Movie, Color(0xFFFFA726)),
        Category("医疗", Icons.Filled.LocalHospital, Color(0xFFEF5350)),
        Category("住房", Icons.Filled.Home, Color(0xFF8D6E63)),
        Category("日用", Icons.Filled.LocalGroceryStore, Color(0xFF26A69A)),
        Category("教育", Icons.Filled.School, Color(0xFF5C6BC0)),
        Category("其他支出", Icons.Filled.MoreHoriz, Color(0xFF78909C)),
    )

    val INCOME = listOf(
        Category("工资", Icons.Filled.Payments, Color(0xFF66BB6A)),
        Category("奖金", Icons.Filled.Star, Color(0xFFFFCA28)),
        Category("理财", Icons.AutoMirrored.Filled.TrendingUp, Color(0xFF29B6F6)),
        Category("红包", Icons.Filled.CardGiftcard, Color(0xFFEF5350)),
        Category("报销", Icons.AutoMirrored.Filled.ReceiptLong, Color(0xFF9CCC65)),
        Category("其他收入", Icons.Filled.MoreHoriz, Color(0xFF90A4AE)),
    )

    /** 内置分类的默认图标 key（与 CategoryIcons 对齐） */
    private val DEFAULT_ICON_KEYS = mapOf(
        "餐饮" to "restaurant",
        "交通" to "bus",
        "购物" to "shopping",
        "娱乐" to "movie",
        "医疗" to "hospital",
        "住房" to "home",
        "日用" to "grocery",
        "教育" to "school",
        "其他支出" to "more",
        "工资" to "salary",
        "奖金" to "bonus",
        "理财" to "invest",
        "红包" to "redpacket",
        "报销" to "reimburse",
        "其他收入" to "more",
    )

    /** 按名称取分类定义：优先用户自定义图标，其次内置默认，未知回退"其他" */
    fun of(name: String): Category {
        val builtIn = (EXPENSE + INCOME).firstOrNull { it.name == name }
        val iconKey = iconKeyOf(name)
        return Category(
            name = name,
            icon = CategoryIcons.of(iconKey),
            color = builtIn?.color ?: CategoryIcons.colorOf(iconKey),
        )
    }

    /** 解析某分类当前使用的图标 key（用户覆盖 > 内置默认 > 其他） */
    fun iconKeyOf(name: String): String =
        CategoryPrefs.iconOverrides[name] ?: DEFAULT_ICON_KEYS[name] ?: CategoryIcons.DEFAULT_KEY

    fun names(type: String): List<String> =
        if (type == TxType.INCOME) INCOME.map { it.name } else EXPENSE.map { it.name }

    /** 某名称是否为内置分类（内置分类不可改名/删除，仅可换图标） */
    fun isBuiltIn(name: String): Boolean =
        (EXPENSE + INCOME).any { it.name == name }
}
