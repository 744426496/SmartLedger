package com.smartledger.app.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalMall
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/** 分类图标选项：一个字符串 key 对应一个图标与主题色 */
data class IconOption(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val color: Color,
)

/**
 * 分类图标库（内置一套供用户挑选的图标，风格与内置分类一致）。
 * key 会被持久化到 SharedPreferences，运行时再映射回 ImageVector。
 */
object CategoryIcons {

    val ALL: List<IconOption> = listOf(
        IconOption("restaurant", "餐饮", Icons.Filled.Restaurant, Color(0xFFFF7043)),
        IconOption("cafe", "咖啡", Icons.Filled.LocalCafe, Color(0xFF8D6E63)),
        IconOption("fastfood", "快餐", Icons.Filled.Fastfood, Color(0xFFFFB74D)),
        IconOption("shopping", "购物", Icons.Filled.ShoppingCart, Color(0xFFAB47BC)),
        IconOption("bag", "购物袋", Icons.Filled.ShoppingBag, Color(0xFF7E57C2)),
        IconOption("mall", "商场", Icons.Filled.LocalMall, Color(0xFFEC407A)),
        IconOption("grocery", "超市", Icons.Filled.LocalGroceryStore, Color(0xFF26A69A)),
        IconOption("bus", "公交", Icons.Filled.DirectionsBus, Color(0xFF42A5F5)),
        IconOption("car", "汽车", Icons.Filled.DirectionsCar, Color(0xFF1E88E5)),
        IconOption("subway", "地铁", Icons.Filled.DirectionsSubway, Color(0xFF3949AB)),
        IconOption("train", "火车", Icons.Filled.Train, Color(0xFF5C6BC0)),
        IconOption("flight", "机票", Icons.Filled.Flight, Color(0xFF29B6F6)),
        IconOption("gas", "加油", Icons.Filled.LocalGasStation, Color(0xFF8D6E63)),
        IconOption("taxi", "打车", Icons.Filled.LocalTaxi, Color(0xFF6D4C41)),
        IconOption("hospital", "医疗", Icons.Filled.LocalHospital, Color(0xFFEF5350)),
        IconOption("medication", "药品", Icons.Filled.Medication, Color(0xFFE53935)),
        IconOption("pharmacy", "药店", Icons.Filled.LocalPharmacy, Color(0xFFD81B60)),
        IconOption("home", "住房", Icons.Filled.Home, Color(0xFF8D6E63)),
        IconOption("build", "装修", Icons.Filled.Build, Color(0xFF6D4C41)),
        IconOption("bolt", "电费", Icons.Filled.Bolt, Color(0xFFFFB300)),
        IconOption("water", "水费", Icons.Filled.WaterDrop, Color(0xFF039BE5)),
        IconOption("wifi", "宽带", Icons.Filled.Wifi, Color(0xFF26C6DA)),
        IconOption("school", "教育", Icons.Filled.School, Color(0xFF5C6BC0)),
        IconOption("book", "图书", Icons.AutoMirrored.Filled.MenuBook, Color(0xFF7986CB)),
        IconOption("movie", "电影", Icons.Filled.Movie, Color(0xFFFFA726)),
        IconOption("game", "游戏", Icons.Filled.SportsEsports, Color(0xFF7E57C2)),
        IconOption("fitness", "健身", Icons.Filled.FitnessCenter, Color(0xFF66BB6A)),
        IconOption("music", "音乐", Icons.Filled.MusicNote, Color(0xFF5C6BC0)),
        IconOption("pets", "宠物", Icons.Filled.Pets, Color(0xFF8D6E63)),
        IconOption("child", "育儿", Icons.Filled.ChildCare, Color(0xFFEC407A)),
        IconOption("phone", "话费", Icons.Filled.PhoneAndroid, Color(0xFF26A69A)),
        IconOption("devices", "数码", Icons.Filled.Devices, Color(0xFF78909C)),
        IconOption("clothes", "服装", Icons.Filled.Checkroom, Color(0xFF5C6BC0)),
        IconOption("gift", "礼物", Icons.Filled.CardGiftcard, Color(0xFFEF5350)),
        IconOption("party", "聚会", Icons.Filled.Celebration, Color(0xFFFF7043)),
        IconOption("flower", "鲜花", Icons.Filled.LocalFlorist, Color(0xFFF06292)),
        IconOption("diamond", "珠宝", Icons.Filled.Diamond, Color(0xFF00BCD4)),
        IconOption("salary", "工资", Icons.Filled.Payments, Color(0xFF66BB6A)),
        IconOption("bonus", "奖金", Icons.Filled.Star, Color(0xFFFFCA28)),
        IconOption("invest", "理财", Icons.AutoMirrored.Filled.TrendingUp, Color(0xFF29B6F6)),
        IconOption("bank", "银行", Icons.Filled.AccountBalance, Color(0xFF42A5F5)),
        IconOption("savings", "储蓄", Icons.Filled.Savings, Color(0xFF26A69A)),
        IconOption("redpacket", "红包", Icons.Filled.CardGiftcard, Color(0xFFEF5350)),
        IconOption("reimburse", "报销", Icons.AutoMirrored.Filled.ReceiptLong, Color(0xFF9CCC65)),
        IconOption("money", "入账", Icons.Filled.MonetizationOn, Color(0xFF66BB6A)),
        IconOption("award", "获奖", Icons.Filled.EmojiEvents, Color(0xFFFFCA28)),
        IconOption("sell", "出售", Icons.Filled.Sell, Color(0xFFAB47BC)),
        IconOption("receipt", "账单", Icons.Filled.Receipt, Color(0xFF90A4AE)),
        IconOption("label", "标签", Icons.AutoMirrored.Filled.Label, Color(0xFF90A4AE)),
        IconOption("more", "其他", Icons.Filled.MoreHoriz, Color(0xFF78909C)),
    )

    const val DEFAULT_KEY = "more"

    fun of(key: String): ImageVector =
        ALL.firstOrNull { it.key == key }?.icon ?: Icons.Filled.MoreHoriz

    fun colorOf(key: String): Color =
        ALL.firstOrNull { it.key == key }?.color ?: Color(0xFF78909C)
}
