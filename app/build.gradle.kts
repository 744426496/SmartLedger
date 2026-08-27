plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.ksp)
}

android {
    namespace = "com.smartledger.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.smartledger.app"
        minSdk = 26
        targetSdk = 34
        // 版本号规则：每完成一个任务 versionCode +1，versionName 自动按
        // 1.0.0 → 1.0.1 → … 递增，patch 满 99 后进位为 1.1.0。
        // 微信账单一键自动导入：versionCode 12→13，versionName "1.0.12"。
        // 截屏回退滑动（往回滑缩短）+ 过滤悬浮窗残留词：versionCode 13→14，versionName "1.0.13"。
        // 无障碍服务名加「鲸鱼记账-」前缀（设置列表里一眼可辨）：versionCode 14→15，versionName "1.0.14"。
        // 修复回退滑动误删帧：回退仅认「画面几乎完全回到历史帧」并需两次确认，正常下滑不再误删：15→16，"1.0.15"。
        // 修复识别数量与日期错误：①帧内缺日期不再 fallback 到识别时刻（幽灵条目）；
        // ②跨帧去重改为同一分钟判定并优先保留真实时间戳（重复/幽灵被吸收）；
        // ③带时刻日期行才回填最近一笔（帧首残留日期不再错配给下一笔，整体错位修复）；
        // ④括号续行合并（「某某螺蛳粉 (大学城店)」不再拆出幽灵「大学城店」）；
        // ⑤金额正则支持 4 位以上整数（1200.00 不再被截成 120.00）：16→17，"1.0.16"。
        // 修复「某某大学城店」跨帧变体导致重复识别：①括号续行识别加强（「分(大学城店)」也识别
        // 为续行并只取括号内店名）；②跨帧去重支持商家名互相包含合并（OCR 拆行/噪声字变体）：
        // 17→18，"1.0.17"。
        // 首页总金额拆为「总金额 + 预算」两个可修改分栏（逻辑相同：设置时刻前账单不影响，
        // 之后账单同时计入两者加减）；记一笔多笔模式新增筛选（搜索/分类/日期 + 重复状态）：
        // 18→19，"1.0.18"。
        // 修复「无时间幽灵记账」：OCR 把「+7.90」读成「90」且丢日期行时产生无时间戳残留，
        // 新增 dropUntimedGhosts（无时间戳且存在同名有时间戳条目 → 丢弃，须在退款成对前调用）：
        // 19→20，"1.0.19"。
        // 修复总金额/预算统计依据：改为「账单进入系统的时间 createdAt」而非交易时间戳——
        // 设置后导入的历史账单（交易时间早于设置）也计入；新增 createdAt 字段（DB v2 迁移）：
        // 20→21，"1.0.20"。
        // 安全：App 后台被清理（服务被解绑/销毁）时自动撤销无障碍授权（disableSelf），
        // 避免无障碍+截屏权限在后台长期挂起：21→22，"1.0.21"。
        // 修复划卡清除后台后授权仍开启：ColorOS 划卡保活进程、服务不解绑，onUnbind/onDestroy
        // 不触发；改为 onStop 后延时检测 ActivityManager 任务列表，任务消失（被划掉）即撤销：
        // 22→23，"1.0.22"。
        // 修复 v1.0.22 划卡后依然不撤销（根因）：onDestroy 里 removeCallbacks 把 onStop 挂起的
        // 任务检测取消了，划卡时 onStop→onDestroy 紧挨着发生，检测永远不执行；改用
        // Activity.onTaskRemoved 同步回调（裁剪 SDK 编译期无此方法，运行时按方法名+签名
        // 虚分派命中）+ 保留任务列表检测兜底（onDestroy 不再取消）：23→24，"1.0.23"。
        // 修复 v1.0.23 实测仍不撤销（真机结论）：ColorOS 划卡不派发 onTaskRemoved（vtable 无效），
        // 且打开最近任务时 App 已 onStop，onStop 检测只跑一次错过任务移除；新增无障碍服务
        // 常驻看门狗——App 打开过（MainActivity.onStart 注册）后每 1.5s 检查 appTasks，
        // 连续 2 次为空（任务全被划掉）即 disableSelf 撤销授权：24→25，"1.0.24"。
        // 修复支付宝账单页顶部截图识别全错：顶部「我的消费图鉴/收支分析/本月已省」汇总卡片
        // 的孤立金额（¥80/¥830.00/¥1,306.87）被列布局当成交易金额导致全部配对错位——
        // 新增 stripTopSummaryAmounts 剥离汇总区金额（遇第一个商家行停止）；平台标识行
        // （闲鱼/天猫/淘等整行）不当商家；补全支付宝分类标签词（日用百货/家居家装等）：
        // 25→26，"1.0.25"。
        // 余额宝/零钱通「自动转入/自动转出」等资金搬运条目标记为内部转移（总资产不变），
        // 保存时默认不勾选——「余额宝-自动转入」中间的「-自动」让 contains("余额宝转入")
        // 匹配不到，需补「自动转入/自动转出」词：26→27，"1.0.26"。
        // 与已导入账目判重简化：只需同年同月同日同分同秒（精确到秒）+ 金额相同即判重复，
        // 不再比较类型/商家名（OCR 商家名多/漏字符会漏判）；跨帧去重 dedupeOverlapping
        // 保留原名/类型比较（退款成对与真实同额转账靠它区分，只看金额+时间会误合并）：
        // 27→28，"1.0.27"。
        // 新增支付宝账单自动导入：记一笔页「支付宝」入口 → 自动打开支付宝 → 我的 → 账单
        // → 滚动截长图 → OCR 识别入账，与微信自动记账一致。复用同一无障碍服务（用户无需
        // 再开一个），新增 AlipayImportCoordinator 处理支付宝导航（我的→账单 两步，无需
        // 钱包/服务中转）；manifest 加支付宝包可见性：28→29，"1.0.28"。
        // 修复支付宝识别「87 笔且乱」（用户只截 8 月 34 笔）：根因是①某些帧被误判成列布局，
        // 用「金额[i]↔商家[i]」序号硬配对，OCR 漏读/多读一行即整体错位（实测分账-某平台=730）；
        // ②状态词被 OCR 误读成商家（「芝麻免押下单成功」→「之麻先种下半成功」）导致金额错配；
        // ③跨帧去重按「同分钟」过严，同一笔在相邻滚动帧日期分/秒略出入就漏合。
        // 修复：支付宝强制走交替布局（parseAlipayLayout：当前商家跟踪+金额配对，不做列配对），
        // 先滤除状态词行；跨帧去重对支付宝放宽到「同一自然日」：29→30，"1.0.29"。
        // 代码审查与等价优化：①支付宝 OCR 完成后通知正确协调器收尾，异常也 finally 清理截帧/悬浮窗；
        // ②OCR 整图异常路径确保回收；③RapidOCR 跨分块去重只扫纵向近邻，避免长图 O(n²) 比较；
        // ④ReceiptParser 逐行热路径复用正则；⑤小组件 7 日账目单次日期分桶；⑥多笔账目 Room 批量插入，
        // 并以受监督应用级协程替代 GlobalScope：30→31，"1.0.30"。
        // 修复支付宝自动导入漏记：①截帧底部裁剪从 14% 收窄至 6%，不再丢掉仍完整可见的末尾交易；
        // ②顶部「订单」页签只整行过滤，保留「订单XXXXXXXX」等真实交易；③先处理交易后的「退款」
        // 标记，再过滤同名页签，保留退款状态：31→32，"1.0.31"。
        // 代码审查与精简（行为等价）：①3 个已弃用图标改用官方 AutoMirrored 版本；②2 处 ONNX
        // 不可避免的 unchecked cast 加 @Suppress 说明；③删除无引用死代码——Repository 的
        // sumExpenseBetween/sumIncomeBetween 与 WechatImportService 三个空转发 companion 包装：
        // 32→33，"1.0.32"。
        // 修复支付宝「滑过头」滚进更早月份导致多识别：用户目标为当月（如 8 月），一不留神会滑过
        // 月界滚进上一自然月（如 7 月），那些月份更早的账目被误入（实测 74 笔被识别成 76 笔）。
        // 新增 dropOffTargetMonth：跨月批次只保留最新月（目标月）账目，丢弃滑过头的更早月份；
        // 强化「滑过头回退」——该过滤同时覆盖微信（alipay=false）与支付宝（alipay=true）两条导入
        // 链路（共用 runOcrBatch），并补微信/支付宝双路回归测试：34→35，"1.0.34"。
        // 通用化「滑过头回退」：原 dropOffTargetMonth 只处理「跨月滑过」（8 月滑进 7 月），
        // 无法覆盖「同月滑过」（本想在 8月6 停、却滑到 8月3）。改为按「目标停点日期」判定——
        // 停点 = 用户最后停住那一帧里最早的交易日期；丢弃任何早于该停点的条目（跨月、同月一并覆盖），
        // 不依赖脆弱的滑动方向判断（该识别曾在微信白底下误删 60 笔）：35→36，"1.0.35"。
        // 修复「更新后仍识别 76 条 + 带月日账单」：根因是停点日期取「最后一帧最早日期」，
        // 若最后一帧本身就是滑过头的 7 月，停点=7 月日期 → 7 月误入被保留。改为 dropOvershoot：
        // 目标月份永远取「最新账目所在月」（8 月），7 月整组剔除；同月停点仅在落在目标月内时采信
        // （最后一帧是 7 月则回退为目标月内最早一条）：36→37，"1.0.36"。
        // UI 重构（行为不变，仅视觉）：统一设计令牌（Dimens 间距/圆角 + 更清晰字体层级）；
        // 首页结余/预算合并为一张 hero 卡（大金额 + 预算一行，分别可点编辑）；汇总条弱化标签、
        // 加粗金额；交易行圆角徽章、行距统一；各页面边距/区块间距统一为 Dimens：37→38，"1.0.37"。
        // 修复 hero 卡「当前结余」的「当」字被切：内层行误用 clip(圆角) + clickable，圆角比
        // 左内边距大导致把最左字符切掉；去掉 clip（外层 Card 已圆角裁剪）。同时 hero 卡
        // 底色改为中性 surfaceVariant，深色模式下不再刺眼：38→39，"1.0.38"。
        // 修复微信账单两问题：①「识别少了」——dropOvershoot 的「同月日级停点」会误删合法的
        // 月末账目（8月1日 被删），改为只做「跨月过滤」（目标月=最新账目所在月，丢弃更早月份，
        // 同月全部保留）；②「月日为账单」——月界/年份标题行（2026年8月/2026年/8月，含 v/√/～ 等
        // OCR 尾巴）纳入噪声，不再被当商家/账单：39→40，"1.0.39"。
        // 修复「月日为账单」残留：OCR 偶发把帧首「年份+月度总额」错读成「0月0日10.10」，
        // 月=0/日=0 日期解析失败后退化成「商家(月 日)+金额(10.10)」假账。新增非法月日噪声拦截
        // （0月 / 月0日，负向前瞻避免误伤「10月」「8月05日」）：40→41，"1.0.40"。
        // 修复支付宝「37 笔只识别 33 笔（少 4 笔）」：dropUntimedGhosts 只按「名字」判幽灵，
        // 把「余额提现 XX.XX」这类同名不同金额、但日期行被截断的账单误删。改为「同名且
        // 金额差 <1 元」才判截断版（能吸收 OCR 拆分/舍入误差，又能区分相差数元
        // 的不同账单）：41→42，"1.0.41"。
        // 新增平台「商品订单详情页」截图识别场景（拼多多等电商 App 的订单详情）：以
        // 「实付 + 下单时间」为通用特征检测（不依赖具体平台/商品词——用户上传任意商品的
        // 订单详情界面都能识别），金额取「实付 ¥6·28」、日期取「下单时间 202X一XX一XX
        // 22:15:05」（OCR 把连字符读成「一」、小数点读成 中点·/全角点．，均归一化；
        // 不误用拼单时间/发货时间/快递动态时间）；仅单笔结果时用该条回填表单。
        // 「我的订单」列表页（无下单时间字段）不在此场景内，按原管线处理。新增 5 个回归测试：42→43，"1.0.42"。
        // 按用户要求改「识别商品名、不要店铺名」：详情页商家从「实付」行向上多行拼接商品标题
        // （OCR 拆行也能还原），店铺名行（旗舰店/专营店…）作边界跳过而非商家；测试断言同步改为
        // 「含商品关键词、不含旗舰店」：43→44，"1.0.43"。
        // 修复「换商品识别出拼多多订单」：RapidOCR 输出中「官方标配」是独立标签行，
        // 旧版把裸「官方」当店铺后缀遇它 break，商品名收集为空回落兜底名。改为 ①店铺后缀只认
        // 复合词（官方旗舰店/官方店），裸「官方/自营」移入详情噪声词表（官方标配/品牌/速发/现货
        // 等标签行不再切断扫描）；②商品名提取改「先找最近候选、再向上连拼」两阶段；③商品名
        // 保留数字/字母（智能插座 不丢型号）且去字间空白。新增 RapidOCR 回归
        // （智能插座，下单时间 202X-XX-XX 17:34:06 连写格式、实付￥45.9）：44→45，"1.0.44"。
        // 新功能「凭证图片入账单」：①记一笔「选图」识别后可勾选是否把凭证图片保存进账单
        // （默认勾选；不勾则只识别不入图）；②图片保存时复制进应用私有目录（不再依赖选图器
        // 临时授权，重启后仍可查看）；③首页点开账单（编辑详情页）可看到凭证图片缩略图、
        // 点开全屏大图，并可添加/更换/删除图片（更换/删除同步清理旧图文件）；④首页流水行
        // 有图账单金额旁显示小图标提示；⑤删除账单时同步删除其关联图片文件。复用既有
        // imageUri 字段（DB 结构不变，无需迁移）：45→46，"1.0.45"。
        // 修复「一图两单删其一后另一单图片看不了」：多笔导入共享同一凭证图片文件，
        // 删除单笔时无条件删文件导致共享方引用失效。改为引用计数式清理——删除图片文件
        // 前先检查是否还有其他账单仍在引用（单删/多选删/编辑页删图三处入口统一接入）：
        // 有引用保留文件、仅删除账单本身；最后一个引用移除才真正清理文件：46→47，"1.0.46"。
        // 代码精简与清理（功能不变）：①删除诊断性测试与临时调试辅助；②dropOvershoot 移除已弃用的 settleHintDate
        // 参数（早已不参与判定）；③删除无引用死代码 cleanDetailShopName；④详情页实付金额正则
        // 提取为类常量，消除热路径重复编译：47→48，"1.0.47"。
        // 按用户要求以 v1.0.41 为蓝本回退功能：①回退掉拼多多「商品订单详情页」识别
        // （v1.0.42~44 新增：ReceiptParser 的 isOrderDetailPage/parseOrderDetailLayout 整块、
        // runOcrBatch 的单笔回填、PddOrderDetailRegressionTest）——上传任意订单详情截图不再
        // 触发详情页单笔解析，恢复为原有 微信/支付宝账单 + 小票/凭证 多场景解析；
        // ②回退掉「凭证图片入账单」（v1.0.45~46 新增：persistImage/deleteImageFile/
        // deleteImageFileIfUnused、AddScreen 的 keepImages 勾选、EditScreen 的凭证图片区/大图、
        // TransactionRow 的图片图标）——保存与编辑不再复制/清理图片文件，恢复直接存原始 URI。
        // 保留既有 imageUri 字段与「选图」入口（记账时仍可带图，仅不复制进私有目录）。
        // 同时保留 v1.0.41 之后的全部记账核心能力（微信/支付宝自动导入、跨帧去重、
        // 退款成对、分类、统计、视图等），并叠加此前的行为等价精简（删死代码/弃用参数/诊断测试）：
        // 48→49，"1.0.48"。
        versionCode = 49
        versionName = versionNameFor(49)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 精简：默认只打包 arm64-v8a 原生库（覆盖近 10 年几乎所有新手机），
        // 大幅减小 onnxruntime 体积。需要全架构安装包时加 -PincludeAllAbis=true。
        ndk {
            if ((project.findProperty("includeAllAbis") as? String).toBoolean()) {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            } else {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // 显式指定 debug 签名密钥库（默认会写入 ~/.android，受限环境不可用）。
    // keystores/ 不入库：仓库无密钥时回退到 AGP 自动生成的默认 debug 签名（~/.android），
    // 保证任何人 clone 后无需额外配置即可构建 debug 包。
    signingConfigs {
        getByName("debug") {
            val ksFile = rootProject.file("keystores/debug.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.coil.compose)

    // 离线 PaddleOCR（PP-OCRv4）：ONNX Runtime 本地 AAR（含各 ABI 原生库）
    implementation(files("libs/onnxruntime-android-1.13.1.aar"))

    testImplementation(libs.junit)
    // JVM 单测里可用的真实 org.json（备份/恢复 JSON 往返测试）；离线环境本地 jar
    testImplementation(files("libs/org.json-20231013.jar"))

    debugImplementation(libs.androidx.ui.tooling)
}

/**
 * 版本号推导：code=1 → "1.0.0"，每 +1 递增 0.0.1，
 * patch 到 99 后再 +1 进位为 1.1.0（1.0.99 之后 → 1.1.0）。
 */
fun versionNameFor(code: Int): String {
    val steps = code - 1
    return "1.${steps / 100}.${steps % 100}"
}
