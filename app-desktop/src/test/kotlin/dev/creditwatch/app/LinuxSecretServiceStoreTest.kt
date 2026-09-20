package dev.creditwatch.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxSecretServiceStoreTest {
    @Test fun storeLookupAndClearUseAttributesAndPasswordInput() = runBlocking {
        var saved: CharArray? = null
        val calls = mutableListOf<List<String>>()
        val store = LinuxSecretServiceStore { args, input ->
            calls += args
            when (args.first()) {
                "store" -> { saved = input?.copyOf(); LinuxSecretServiceStore.Result(0, "") }
                "lookup" -> if (saved == null) LinuxSecretServiceStore.Result(1, "")
                    else LinuxSecretServiceStore.Result(0, saved!!.concatToString())
                "clear" -> { saved = null; LinuxSecretServiceStore.Result(0, "") }
                else -> error("Unexpected command")
            }
        }
        store.put("vast-default", "test-secret".toCharArray())
        assertContentEquals("test-secret".toCharArray(), store.get("vast-default"))
        store.delete("vast-default")
        assertNull(store.get("vast-default"))
        assertEquals(listOf("application", "dev.creditwatch", "account", "vast-default"), calls[0].takeLast(4))
        assertTrue(calls.none { it.contains("test-secret") })
    }

    @Test fun unavailableServiceBlocksSaving() = runBlocking {
        val store = LinuxSecretServiceStore { _, _ -> LinuxSecretServiceStore.Result(1, "No keyring") }
        val failure = assertFailsWith<SecureStorageUnavailableException> { store.put("vast-default", "key".toCharArray()) }
        assertTrue(failure.message!!.contains("Secret Service is unavailable"))
    }
}
