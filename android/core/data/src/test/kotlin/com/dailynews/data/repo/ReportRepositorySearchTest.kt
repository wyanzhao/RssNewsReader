package com.dailynews.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals

class ReportRepositorySearchTest {
    @Test
    fun likeMetacharactersAreEscapedAsLiterals() {
        assertEquals("%100\\%\\_done\\\\ok%", reportSearchPattern(" 100%_done\\ok "))
        assertEquals("%%", reportSearchPattern("  "))
    }
}
