package com.smartledger.app.data

/**
 * 进程内缓存的自定义分类图标覆盖（name -> iconKey）。
 * 由 AppViewModel 在初始化时从 SharedPreferences 载入，并在用户修改时同步更新；
 * Categories.of() 据此解析出分类最终显示的图标与颜色。
 */
object CategoryPrefs {
    @Volatile
    var iconOverrides: Map<String, String> = emptyMap()
}
