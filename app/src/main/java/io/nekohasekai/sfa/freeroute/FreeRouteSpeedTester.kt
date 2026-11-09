package io.nekohasekai.sfa.freeroute

import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.OverrideOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

data class FreeRouteSpeedResult(
    val node: FreeRouteNode,
    val success: Boolean,
    val bytesDownloaded: Long,
    val durationMs: Long,
    val firstByteMs: Long?,
    val speedKbps: Double?,
    val error: String? = null
)

object FreeRouteSpeedTester {

    private const val TEST_URL = "https://speed.cloudflare.com/__down?bytes=102400"
    private const val TARGET_BYTES = 100L * 1024L
    private const val PROXY_PORT = 20808
    private const val CONNECT_TIMEOUT = 12_000
    private const val READ_TIMEOUT = 20_000

    /**
     * configContent must contain:
     * 1. The outbound for this node.
     * 2. A local HTTP or mixed inbound on 127.0.0.1:20808.
     * 3. A route that sends the test traffic through this node.
     *
     * serverFactory must return a fresh CommandServer.
     * CommandServer.start() is intentionally not called here: it starts the
     * command gRPC listener. StartOrReloadService starts the probe service itself.
     */
    suspend fun test(
        node: FreeRouteNode,
        configContent: String,
        serverFactory: () -> CommandServer
    ): FreeRouteSpeedResult = withContext(Dispatchers.IO) {

        var probeServer: CommandServer? = null
        var downloaded = 0L
        var firstByteMs: Long? = null
        var measurementStartedAt = 0L

        try {
            require(configContent.isNotBlank()) {
                "Probe configuration is empty"
            }

            probeServer = serverFactory()
            probeServer.startOrReloadService(
                configContent,
                OverrideOptions()
            )

            // Exclude sing-box engine startup time from the measured speed.
            measurementStartedAt = System.nanoTime()

            val proxy = Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress("127.0.0.1", PROXY_PORT)
            )

            val connection =
                (URL(TEST_URL).openConnection(proxy) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    useCaches = false
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "FreeRoute-Android/1.0")
                    setRequestProperty("Accept", "*/*")
                }

            try {
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw IllegalStateException("HTTP error: $responseCode")
                }

                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)

                    while (downloaded < TARGET_BYTES) {
                        val remaining =
                            (TARGET_BYTES - downloaded)
                                .coerceAtMost(buffer.size.toLong())
                                .toInt()
                        val count = input.read(buffer, 0, remaining)
                        if (count <= 0) break

                        downloaded += count
                        if (firstByteMs == null) {
                            firstByteMs =
                                (System.nanoTime() - measurementStartedAt) / 1_000_000
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            val durationMs =
                ((System.nanoTime() - measurementStartedAt) / 1_000_000)
                    .coerceAtLeast(1)

            if (downloaded < TARGET_BYTES) {
                throw IllegalStateException(
                    "Incomplete download: $downloaded/$TARGET_BYTES bytes"
                )
            }

            // Decimal kilobits per second (bytes * 8 / milliseconds).
            val speedKbps = downloaded * 8.0 / durationMs

            FreeRouteSpeedResult(
                node = node,
                success = true,
                bytesDownloaded = downloaded,
                durationMs = durationMs,
                firstByteMs = firstByteMs,
                speedKbps = speedKbps
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val durationMs =
                if (measurementStartedAt == 0L) {
                    0L
                } else {
                    ((System.nanoTime() - measurementStartedAt) / 1_000_000)
                        .coerceAtLeast(1)
                }

            FreeRouteSpeedResult(
                node = node,
                success = false,
                bytesDownloaded = downloaded,
                durationMs = durationMs,
                firstByteMs = firstByteMs,
                speedKbps = null,
                error = e.message ?: "Speed test failed"
            )
        } finally {
            probeServer?.let { server ->
                runCatching { server.closeService() }
                runCatching { server.close() }
            }
        }
    }

    suspend fun testAll(
        nodes: List<FreeRouteNode>,
        configBuilder: suspend (FreeRouteNode) -> String,
        serverFactory: () -> CommandServer
    ): List<FreeRouteSpeedResult> {

        val results = mutableListOf<FreeRouteSpeedResult>()

        // Sequential testing avoids concurrent probes sharing the same local port.
        for (node in nodes) {
            val result = try {
                val config = configBuilder(node)
                test(
                    node = node,
                    configContent = config,
                    serverFactory = serverFactory
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FreeRouteSpeedResult(
                    node = node,
                    success = false,
                    bytesDownloaded = 0,
                    durationMs = 0,
                    firstByteMs = null,
                    speedKbps = null,
                    error = e.message ?: "Configuration failed"
                )
            }
            results.add(result)
        }

        return results.sortedWith(
            compareByDescending<FreeRouteSpeedResult> { it.success }
                .thenByDescending { it.speedKbps ?: 0.0 }
        )
    }
}
