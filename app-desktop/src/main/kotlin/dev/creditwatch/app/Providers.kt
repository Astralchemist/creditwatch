package dev.creditwatch.app

/**
 * The providers the settings pane offers.
 *
 * Listing one that has no adapter would be a promise the app cannot keep, so [adapter] says
 * plainly which can be connected today. The list is here, rather than inferred from the
 * modules on the classpath, so adding a provider is one entry plus its adapter.
 */
data class ProviderEntry(
    val id: String,
    val name: String,
    /** True once a [dev.creditwatch.provider.CloudProvider] exists for it. */
    val adapter: Boolean,
    val keyDocsUrl: String? = null,
    val note: String? = null,
)

val PROVIDER_CATALOGUE: List<ProviderEntry> = listOf(
    ProviderEntry("vast", "Vast.ai", adapter = true,
        keyDocsUrl = "https://docs.vast.ai/guides/reference/keys"),
    ProviderEntry("runpod", "RunPod", adapter = false,
        keyDocsUrl = "https://docs.runpod.io/get-started/api-keys",
        note = "Adapter not written yet"),
    ProviderEntry("lambda", "Lambda", adapter = false,
        note = "Adapter not written yet"),
    ProviderEntry("paperspace", "Paperspace", adapter = false,
        note = "Adapter not written yet"),
)
