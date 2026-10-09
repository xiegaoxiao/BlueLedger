package com.blueledger.app.feature.management

import com.blueledger.app.core.model.CategoryIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类编辑器图标分组只影响展示顺序：既不能漏图标，也不能把同一个 key 放进两组。
 *
 * 分组表写在界面文件里，因此这里逐项核对它与唯一权威集合 [CategoryIcons.SELECTABLE] 的关系。
 * 纯 JVM 断言，不依赖 Robolectric。
 */
class CategoryEditorIconGroupsTest {

    @Test
    fun `分组覆盖全部可选图标且互不重复`() {
        val keys = editorIconSections().flatMap { it.second }
        assertEquals(CategoryIcons.SELECTABLE.size, keys.size)
        assertEquals(CategoryIcons.SELECTABLE.toSet(), keys.toSet())
    }

    @Test
    fun `分组标题唯一且顺序稳定`() {
        val titles = editorIconSections().map { it.first }
        assertEquals(titles.distinct(), titles)
        assertTrue(titles.first() == "常用")
        assertTrue(titles.last() == "其他")
    }

    @Test
    fun `每个分组都非空`() {
        editorIconSections().forEach { (title, keys) ->
            assertTrue("分组「$title」不应为空", keys.isNotEmpty())
        }
    }
}
