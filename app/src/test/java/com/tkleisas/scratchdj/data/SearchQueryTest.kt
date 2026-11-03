package com.tkleisas.scratchdj.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchQueryTest {

    @Test
    fun `builds contains pattern`() {
        assertEquals("%night%", SearchQuery.likePattern("night"))
    }

    @Test
    fun `trims whitespace`() {
        assertEquals("%night drive%", SearchQuery.likePattern("  night drive  "))
    }

    @Test
    fun `escapes sql wildcards`() {
        assertEquals("%100\\%%", SearchQuery.likePattern("100%"))
        assertEquals("%a\\_b%", SearchQuery.likePattern("a_b"))
        assertEquals("%a\\\\b%", SearchQuery.likePattern("a\\b"))
    }

    @Test
    fun `blank search is blank`() {
        assertEquals(true, SearchQuery.isBlank("   "))
        assertEquals(false, SearchQuery.isBlank("a"))
    }
}
