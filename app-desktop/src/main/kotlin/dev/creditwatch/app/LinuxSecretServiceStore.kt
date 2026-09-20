package dev.creditwatch.app

import dev.creditwatch.provider.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Uses libsecret's secret-tool and the user's unlocked Secret Service collection. */
internal class LinuxSecretServiceStore(private val run: (List<String>, CharArray?) -> Result) : SecretStore {
    override suspend fun put(id: String, value: CharArray): Unit = withContext(Dispatchers.IO) {
        require(value.isNotEmpty())
        val result = run(listOf("store", "--label=CreditWatch Vast.ai API key") + attributes(id), value)
        if (result.exitCode != 0) unavailable()
    }

    override suspend fun get(id: String): CharArray? = withContext(Dispatchers.IO) {
        val result = run(listOf("lookup") + attributes(id), null)
        // secret-tool returns 1 with no output when there is no matching item.
        if (result.exitCode != 0 && result.output.isNotEmpty()) unavailable()
        if (result.exitCode != 0) return@withContext null
        result.output.trimEnd('\r', '\n').takeIf(String::isNotEmpty)?.toCharArray()
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        val result = run(listOf("clear") + attributes(id), null)
        if (result.exitCode != 0) unavailable()
    }

    private fun attributes(id: String): List<String> {
        require(id.isNotBlank() && !id.contains('\u0000'))
        return listOf("application", "dev.creditwatch", "account", id)
    }

    internal data class Result(val exitCode: Int, val output: String)

    companion object {
        fun create(): LinuxSecretServiceStore {
            val executable = System.getenv("PATH")?.split(':')?.asSequence()?.map { Path.of(it, "secret-tool") }
                ?.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            return LinuxSecretServiceStore { args, input ->
                if (executable == null) throw SecureStorageUnavailableException(UNAVAILABLE_MESSAGE)
                runProcess(executable.toString(), args, input)
            }
        }

        private fun runProcess(executable: String, args: List<String>, input: CharArray?): Result {
            val process = ProcessBuilder(listOf(executable) + args).redirectErrorStream(true).start()
            process.outputStream.use { stream ->
                if (input != null) {
                    val bytes = String(input).toByteArray(StandardCharsets.UTF_8)
                    try { stream.write(bytes) } finally { bytes.fill(0) }
                }
            }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw SecureStorageUnavailableException(UNAVAILABLE_MESSAGE)
            }
            return Result(process.exitValue(), process.inputStream.bufferedReader().readText())
        }

        private const val UNAVAILABLE_MESSAGE = "Linux Secret Service is unavailable. Install libsecret tools and unlock a keyring in your desktop session."
    }

    private fun unavailable(): Nothing = throw SecureStorageUnavailableException(
        "Linux Secret Service is unavailable. Install libsecret tools and unlock a keyring in your desktop session.")

}
