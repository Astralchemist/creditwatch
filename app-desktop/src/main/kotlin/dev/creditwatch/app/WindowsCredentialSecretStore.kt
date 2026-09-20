package dev.creditwatch.app

import dev.creditwatch.provider.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

internal class WindowsCredentialSecretStore(
    private val credentials: WindowsCredentials = WindowsCredentials(),
) : SecretStore {
    override suspend fun put(id: String, value: CharArray) = withContext(Dispatchers.IO) {
        val bytes = String(value).toByteArray(StandardCharsets.UTF_8)
        try { credentials.put(target(id), bytes) } finally { bytes.fill(0) }
    }

    override suspend fun get(id: String): CharArray? = withContext(Dispatchers.IO) {
        val bytes = credentials.get(target(id)) ?: return@withContext null
        try { String(bytes, StandardCharsets.UTF_8).toCharArray() } finally { bytes.fill(0) }
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        credentials.delete(target(id))
    }

    private fun target(id: String): String {
        require(id.isNotBlank() && !id.contains('\u0000'))
        return "dev.creditwatch.vast.api-key/$id"
    }
}
