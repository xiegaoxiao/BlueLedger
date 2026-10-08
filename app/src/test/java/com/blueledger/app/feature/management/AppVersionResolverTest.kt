package com.blueledger.app.feature.management

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * S10 版本号必须来自真实构建产物：`BuildConfig.VERSION_NAME`（若生成）或 PackageManager。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppVersionResolverTest {

    @Test
    fun `版本号非空且形如语义版本`() {
        val version = AppVersionResolver.resolve(ApplicationProvider.getApplicationContext())
        assertFalse("不能返回空版本号", version.isBlank())
        if (version != AppVersionResolver.UNKNOWN) {
            assertTrue(
                "版本号应为 1.0.0 这类构建产物值，实际：$version",
                Regex("""^\d+(\.\d+){0,3}.*$""").matches(version),
            )
        }
    }

    @Test
    fun `版本号与构建配置一致`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fromPackageManager = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val resolved = AppVersionResolver.resolve(context)
        if (!fromPackageManager.isNullOrBlank()) {
            assertTrue(
                "解析结果 $resolved 必须与安装包版本 $fromPackageManager 一致",
                resolved == fromPackageManager || resolved.startsWith(fromPackageManager),
            )
        }
    }
}
