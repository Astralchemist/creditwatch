package dev.creditwatch.provider

interface SecretStore {
    suspend fun put(id: String, value: CharArray)
    suspend fun get(id: String): CharArray?
    suspend fun delete(id: String)
}
