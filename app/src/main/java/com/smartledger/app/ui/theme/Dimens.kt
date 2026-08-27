package com.smartledger.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一设计令牌：间距 / 圆角 / 高度，避免各界面散落魔法数字，保证整体节奏一致。
 * 命名遵循 Material 间距语义：xs < sm < md < lg < xl。
 */
object Dimens {
    // 间距
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp

    // 页面内容左右留白
    val pagePadding: Dp = 20.dp
    // 卡片内边距
    val cardPadding: Dp = 18.dp
    // 卡片之间垂直间距
    val sectionSpacing: Dp = 16.dp

    // 圆角
    val cornerSm: Dp = 12.dp
    val cornerMd: Dp = 16.dp
    val cornerLg: Dp = 20.dp
    val cornerXl: Dp = 28.dp

    // 图标徽章（分类图标底色圆/圆角方块）直径
    val iconBadge: Dp = 44.dp
    val iconBadgeSmall: Dp = 36.dp
}
