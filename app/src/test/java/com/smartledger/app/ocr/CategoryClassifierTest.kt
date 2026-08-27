package com.smartledger.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryClassifierTest {

    @Test
    fun classifyExpenseCategories() {
        assertEquals("餐饮", CategoryClassifier.classify("海底捞火锅 合计68.5", "expense", "海底捞"))
        assertEquals("餐饮", CategoryClassifier.classify("美团外卖订单", "expense", null))
        assertEquals("交通", CategoryClassifier.classify("地铁 3元", "expense", null))
        assertEquals("交通", CategoryClassifier.classify("滴滴出行", "expense", null))
        assertEquals("购物", CategoryClassifier.classify("京东商城 订单", "expense", null))
        assertEquals("购物", CategoryClassifier.classify("拼多多 包裹", "expense", null))
        assertEquals("医疗", CategoryClassifier.classify("医院 挂号费 50", "expense", null))
        assertEquals("娱乐", CategoryClassifier.classify("万达影城 电影票", "expense", null))
        assertEquals("住房", CategoryClassifier.classify("房租 2800", "expense", null))
        assertEquals("日用", CategoryClassifier.classify("沃尔玛超市 购物", "expense", null))
        assertEquals("教育", CategoryClassifier.classify("新东方课程 学费", "expense", null))
        assertEquals("其他支出", CategoryClassifier.classify("没有特征的一行字", "expense", null))
    }

    @Test
    fun classifyIncomeCategories() {
        assertEquals("工资", CategoryClassifier.classify("工资入账 8500", "income", null))
        assertEquals("奖金", CategoryClassifier.classify("绩效奖金到账", "income", null))
        assertEquals("红包", CategoryClassifier.classify("微信红包 200", "income", null))
        assertEquals("理财", CategoryClassifier.classify("余额宝收益 3.2", "income", null))
        assertEquals("报销", CategoryClassifier.classify("差旅报销 500", "income", null))
        assertEquals("其他收入", CategoryClassifier.classify("一笔莫名的款项", "income", null))
    }

    @Test
    fun merchantHelpsClassification() {
        // 商家名也能帮助归类
        assertEquals("餐饮", CategoryClassifier.classify("合计 68", "expense", "肯德基"))
    }

    @Test
    fun classifyRealBillMerchants() {
        // 常见商户归类验证（使用通用名）
        assertEquals("餐饮", CategoryClassifier.classify("美团", "expense", null))
        assertEquals("餐饮", CategoryClassifier.classify("家常餐厅", "expense", null))
        assertEquals("餐饮", CategoryClassifier.classify("牛杂", "expense", null))
        assertEquals("餐饮", CategoryClassifier.classify("螺蛳粉(大学城店)", "expense", null))
        assertEquals("日用", CategoryClassifier.classify("中国电信", "expense", null))
        assertEquals("日用", CategoryClassifier.classify("手机充值", "expense", null))
        assertEquals("日用", CategoryClassifier.classify("超市", "expense", null))
        assertEquals("日用", CategoryClassifier.classify("超市", "expense", null))
        assertEquals("日用", CategoryClassifier.classify("零食铺", "expense", null))
        assertEquals("购物", CategoryClassifier.classify("抖音电商", "expense", null))
        assertEquals("购物", CategoryClassifier.classify("百货", "expense", null))
        assertEquals("娱乐", CategoryClassifier.classify("猫眼", "expense", null))
        assertEquals("娱乐", CategoryClassifier.classify("bilibili", "expense", null))
        assertEquals("医疗", CategoryClassifier.classify("健康", "expense", null))
        assertEquals("医疗", CategoryClassifier.classify("医院", "expense", null))
        assertEquals("交通", CategoryClassifier.classify("一卡通", "expense", null))
        // 收入
        assertEquals("报销", CategoryClassifier.classify("退款", "income", null))
        assertEquals("红包", CategoryClassifier.classify("转账", "income", null))
        assertEquals("红包", CategoryClassifier.classify("转账", "income", null))
    }

    @Test
    fun classifyExtendedBrands() {
        assertEquals("餐饮", CategoryClassifier.classify("瑞幸咖啡", "expense", null))
        assertEquals("餐饮", CategoryClassifier.classify("茶百道", "expense", null))
        assertEquals("交通", CategoryClassifier.classify("中国石油加油站", "expense", null))
        assertEquals("交通", CategoryClassifier.classify("哈啰单车", "expense", null))
        assertEquals("购物", CategoryClassifier.classify("优衣库", "expense", null))
        assertEquals("购物", CategoryClassifier.classify("小米之家", "expense", null))
        assertEquals("娱乐", CategoryClassifier.classify("腾讯视频", "expense", null))
        assertEquals("娱乐", CategoryClassifier.classify("bilibili", "expense", null))
        assertEquals("医疗", CategoryClassifier.classify("同仁堂药店", "expense", null))
        assertEquals("住房", CategoryClassifier.classify("链家", "expense", null))
        assertEquals("住房", CategoryClassifier.classify("自如", "expense", null))
        assertEquals("日用", CategoryClassifier.classify("盒马鲜生", "expense", null))
        assertEquals("日用", CategoryClassifier.classify("罗森便利店", "expense", null))
        assertEquals("教育", CategoryClassifier.classify("学而思", "expense", null))
    }
}
