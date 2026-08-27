# 慧记账 (SmartLedger)

一个开箱即用的 **Android 智能记账 App**：拍照/上传小票即可自动记账，并给出日/周/月的消费总览。

![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-purple) ![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-green) ![ML Kit](https://img.shields.io/badge/ML%20Kit-OCR%20on--device-blue)

## 功能特性

### 📷 图片上传与 OCR 识别
- 支持**单张 / 多张（最多 9 张）**图片上传，使用系统 Photo Picker，**无需申请存储权限**
- 使用 **ML Kit 中文识别模型在设备端 OCR**（无需联网、无需 API Key、无费用）
- 从图片中自动提取：
  - **支付 / 收入金额**（优先匹配「合计 / 实收 / 实付 / 金额」等字段，自动避开年份等干扰数字）
  - **商家 / 收款方名称**（识别「收款方 / 商户名称 / 店名」等字段，失败时取首行店名）
  - **支付时间**（解析 `2024年12月31日 18:30` 等常见日期格式）
- 多张图片自动合并最可信的结果

### 🧾 一张图多笔记账
- 自动识别账单/小票中的**多行明细**（品名 + 金额），逐条列出供核对
- 支持**正负号区分收支**：适配微信/支付宝账单截图（`美团 -7.01`=支出、`转账 +50.00`=收入）
- 每条明细可**勾选是否入账**，并单独修改名称、金额、分类
- 底部实时汇总已选金额，一键**批量保存**多笔记录

### 🏷️ 自动归类
- **支出**：餐饮、交通、购物、娱乐、医疗、住房、日用、教育、其他支出
- **收入**：工资、奖金、理财、红包、报销、其他收入
- 基于关键词规则引擎（如「美团 / 海底捞 / 外卖」→ 餐饮，「工资 / 代发」→ 工资），商家名也会参与判断
- 识别结果自动填入表单，**用户可复核修正后再保存**

### 📊 消费总览
- **日 / 周 / 月** 三个维度切换
- 自绘柱状图展示消费趋势（近 14 天 / 近 8 周 / 近 12 个月），点击柱子联动查看明细
- 分类环形图 + 分类占比条
- 支出 / 收入 / 结余汇总卡片

### 其他
- Room 本地数据库，数据完全离线存储
- 深色模式适配
- 纯手动记账也支持（不传图片直接填表单）

## 技术栈

| 组件 | 选型 |
| --- | --- |
| 语言 / UI | Kotlin 2.0.20 + Jetpack Compose (Material 3) |
| 数据库 | Room 2.6.1（KSP 编译） |
| OCR | ML Kit 文本识别（中文 16.0.0 + 拉丁 16.0.1） |
| 图片选择 | Photo Picker（`PickMultipleVisualMedia`，免权限） |
| 图片加载 | Coil |
| 图表 | 自绘 Canvas（零第三方图表依赖） |
| 构建 | Gradle 8.9 / AGP 8.5.2 |

## 如何构建运行

### 📦 已构建好的安装包

最新安装包通过 **GitHub Releases** 发布（见页面右侧 Releases / Tags）：

- 最新版本：**`鲸鱼记账-v1.0.41-debug-arm64.apk`**（约 61 MB，已签名，可直接安装）

安装方式：
- 从 Releases 下载 APK，传到 Android 8.0+ 手机，点击安装（需允许"安装未知来源应用"）；或
- 电脑连接手机后执行 `adb install 鲸鱼记账-v1.0.41-debug-arm64.apk`

> 这是 debug 签名包，仅用于测试体验；正式发布请配置自己的 release 签名。

### 前置要求
- **Android Studio**（Ladybug 或更新版本，内置 JDK 17+）
- Android SDK（Android Studio 首次打开时会提示自动下载）

### 步骤
1. 用 Android Studio 打开本项目根目录（`SmartLedger` 文件夹）
2. 等待 Gradle 同步完成（首次会下载依赖，需要网络）
3. 连接 Android 真机（Android 8.0 / API 26 及以上）或启动模拟器
4. 点击 ▶ Run

> 💡 **如果 `gradlew` 提示缺少 `gradle-wrapper.jar`**：本仓库已配置 `gradle-wrapper.properties`
> 指向腾讯云 Gradle 镜像；若 wrapper 文件缺失，在 Android Studio 的 **Terminal** 里执行一次
> `gradle wrapper --gradle-version 8.9`（Android Studio 自带的 Gradle 即可），或在
> **File → Settings → Build, Execution, Deployment → Build Tools → Gradle** 中把 Gradle
> 发行版切换为「本地安装目录」后重新同步。

### 运行单元测试
```bash
./gradlew testDebugUnitTest
```

### 命令行构建（无需 Android Studio）
若本机有 JDK 17 与 Android SDK，可配置好 `local.properties` 后执行：
```bash
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```
SmartLedger/
├── app/src/main/java/com/smartledger/app/
│   ├── MainActivity.kt              # 入口 + 底部导航
│   ├── data/                        # Room 数据层（实体/DAO/数据库/仓库/分类定义）
│   ├── ocr/                         # OCR 引擎 + 票据解析器 + 自动归类器
│   │   ├── OcrEngine.kt             #   ML Kit 封装（中文优先，拉丁回退）
│   │   ├── ReceiptParser.kt         #   金额/商家/时间/收支类型提取
│   │   └── CategoryClassifier.kt    #   关键词自动归类
│   ├── ui/
│   │   ├── AppViewModel.kt          # 状态管理（OCR 状态机 + 增删改查）
│   │   ├── theme/                   # Material3 主题
│   │   ├── components/              # 图表、汇总卡片、流水行
│   │   └── screens/
│   │       ├── HomeScreen.kt        # 首页：本月收支 + 最近记录
│   │       ├── AddScreen.kt         # 记一笔：选图 → OCR → 复核 → 保存
│   │       └── OverviewScreen.kt    # 总览：日/周/月趋势 + 分类占比 + 明细
│   └── util/Format.kt               # 金额/日期格式化
└── app/src/test/                    # 解析器与归类器的单元测试
```

## 识别规则说明（可自行扩展）

- **金额**：优先匹配含「合计/总计/实收/实付/金额/小计/入账/工资」等关键词的行；
  其次找带 `¥/￥` 的数字；兜底时优先取**带小数**的最大金额（避免把年份当金额）。
- **收支类型**：命中「工资/收入/入账/到账/存入/收到」→ 收入，否则默认支出。
- **归类**：规则见 `CategoryClassifier.kt`，新增关键词即可扩充识别范围。

## 已知说明

- ML Kit 中文模型在**首次识别时**会下载约 20MB 的模型文件（Google Play 服务自动处理），
  之后完全离线运行。
- OCR 是启发式解析，复杂小票可能识别不准 —— 表单内可一键修正，这正是"识别 + 人工复核"流程的意义。
