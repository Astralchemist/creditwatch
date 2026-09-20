package dev.creditwatch.app

import dev.creditwatch.provider.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class MacKeychainSecretStore : SecretStore {
    init {
        require(System.getProperty("os.name").startsWith("Mac")) { "macOS Keychain is required on this platform" }
    }

    override suspend fun put(id: String, value: CharArray): Unit = withContext(Dispatchers.IO) {
        require(value.isNotEmpty())
        val result = run(listOf("add-generic-password", "-a", id, "-s", SERVICE, "-U", "-w"), value)
        require(result.exitCode == 0) { "Could not save the API key in Keychain" }
    }

    override suspend fun get(id: String): CharArray? = withContext(Dispatchers.IO) {
        val result = run(listOf("find-generic-password", "-a", id, "-s", SERVICE, "-w"))
        when (result.exitCode) {
            0 -> result.output.trimEnd('\r', '\n').toCharArray()
            44 -> null
            else -> error("Could not read the API key from Keychain")
        }
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        val result = run(listOf("delete-generic-password", "-a", id, "-s", SERVICE))
        require(result.exitCode == 0 || result.exitCode == 44) { "Could not delete the API key from Keychain" }
    }

    private fun run(args: List<String>, input: CharArray? = null): Result {
        val process = ProcessBuilder(listOf("/usr/bin/security") + args)
            .redirectErrorStream(true)
            .start()
        process.outputStream.use { stream ->
            if (input != null) {
                val bytes = String(input).toByteArray(StandardCharsets.UTF_8)
                try {
                    stream.write(bytes)
                    stream.write('\n'.code)
                    stream.write(bytes)
                    stream.write('\n'.code)
                } finally { bytes.fill(0) }
            }
        }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Keychain operation timed out")
        }
        return Result(process.exitValue(), process.inputStream.bufferedReader().readText())
    }

    private data class Result(val exitCode: Int, val output: String)

    companion object {
        private const val SERVICE = "dev.creditwatch.vast.api-key"
    }
}
