package dev.creditwatch.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SearchCatalogTest {
    private val providers = listOf(
        ProviderSearchItem("vast", "Vast.ai", "cloud gpu credits"),
        ProviderSearchItem("another", "Another Cloud", "future compute"),
    )

    @Test fun `search matches provider and action terms`() {
        val catalog = searchCatalog(providers, setOf("vast"))
        assertEquals(SearchAction.OpenProvider("vast"), searchEntries(catalog, "vast credits").single().action)
        assertEquals(SearchAction.OpenProvider("another"), searchEntries(catalog, "another compute").single().action)
        assertEquals(SearchAction.Refresh, searchEntries(catalog, "sync").single().action)
        assertEquals(SearchAction.OpenDashboard, searchEntries(catalog, "monitor").single().action)
    }

    @Test fun `refresh is absent before connection`() {
        assertFalse(searchCatalog(providers, emptySet()).any { it.action == SearchAction.Refresh })
    }
}
