package dev.creditwatch.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class WindowsCredentialIntegrationTest {
    @Test fun roundTripThroughRealCredentialManager() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows") &&
            System.getenv("CREDITWATCH_WINDOWS_CREDENTIAL_SMOKE") == "1")
        val store = WindowsCredentialSecretStore()
        val id = "smoke-${UUID.randomUUID()}"
        val key = "creditwatch-disposable-key".toCharArray()
        assertNull(store.get(id))
        try {
            store.put(id, key)
            assertContentEquals(key, store.get(id))
        } finally {
            store.delete(id)
            key.fill('\u0000')
        }
        assertNull(store.get(id))
    }
}
