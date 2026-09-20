package dev.creditwatch.app

import java.util.Locale

internal sealed interface SearchAction {
    data object OpenDashboard : SearchAction
    data class OpenProvider(val providerId: String) : SearchAction
    data object Refresh : SearchAction
}

internal data class ProviderSearchItem(
    val id: String,
    val name: String,
    val keywords: String,
)

internal data class SearchEntry(
    val title: String,
    val description: String,
    val category: String,
    val action: SearchAction,
    val keywords: String = "",
)

/** The palette receives the providers the application actually supports. */
internal fun searchCatalog(providers: List<ProviderSearchItem>, connectedIds: Set<String>): List<SearchEntry> = buildList {
    add(SearchEntry("Open dashboard", "Balance, burn, and runway", "ACTION", SearchAction.OpenDashboard,
        "window details monitor"))
    providers.forEach { provider ->
        add(SearchEntry(provider.name,
            if (provider.id in connectedIds) "Connected provider" else "Connect a provider",
            "PROVIDER", SearchAction.OpenProvider(provider.id), provider.keywords))
    }
    if (connectedIds.isNotEmpty()) add(SearchEntry("Refresh now", "Fetch current provider data", "ACTION", SearchAction.Refresh,
        "sync update reload"))
}

internal fun searchEntries(entries: List<SearchEntry>, query: String): List<SearchEntry> {
    val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
    if (terms.isEmpty()) return entries
    return entries.filter { entry ->
        val haystack = "${entry.title} ${entry.description} ${entry.category} ${entry.keywords}".lowercase(Locale.ROOT)
        terms.all(haystack::contains)
    }.sortedWith(compareByDescending<SearchEntry> { it.title.lowercase(Locale.ROOT).startsWith(terms.first()) }
        .thenBy { it.title })
}
