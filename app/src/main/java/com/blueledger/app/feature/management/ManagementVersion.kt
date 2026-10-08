package com.blueledger.app.feature.management

import android.content.Context
import android.content.pm.PackageManager

/**
 * S10「关于」里的版本号必须来自真实构建产物，**不得写死**。
 *
 * 解析顺序：
 * 1. 反射读取 `com.blueledger.app.BuildConfig.VERSION_NAME`（模块生成了 BuildConfig 时的精确值）。
 *    用反射而不是直接引用，是为了在 `buildFeatures.buildConfig` 未开启时也不会编译失败。
 * 2. 回退到 [PackageManager] 的 `packageInfo.versionName`（安装包真实版本）。
 * 3. 两者都拿不到时返回「未知」——不编造版本号。
 */
object AppVersionResolver {

    const val UNKNOWN: String = "未知"

    fun resolve(context: Context): String {
        buildConfigVersionName()?.let { return it }
        return packageManagerVersionName(context) ?: UNKNOWN
    }

    private fun buildConfigVersionName(): String? = runCatching {
        val field = Class.forName("com.blueledger.app.BuildConfig").getField("VERSION_NAME")
        (field.get(null) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun packageManagerVersionName(context: Context): String? = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
