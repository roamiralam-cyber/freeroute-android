
package io.nekohasekai.sfa.freeroute

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.net.Socket
import java.util.Base64

data class FreeRouteNode(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val link: String,
    val latencyMs: Long? = null
)

object FreeRouteCatalog {

    private const val SOURCE =
        "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/lite.txt"

    private val supported = listOf(
        "vless://",
        "vmess://",
        "trojan://",
        "ss://",
        "hysteria2://",
        "hy2://",
        "tuic://"
    )

    suspend fun discover(): List<FreeRouteNode> =
        withContext(Dispatchers.IO) {
            val content = download(SOURCE)
            val links = decodeSubscription(content)

            links.mapNotNull { parseNode(it) }
                .distinctBy { it.link.substringBefore("#") }
        }

    private fun download(address: String): String {
        val connection = URL(address).openConnection()
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty(
            "User-Agent",
            "FreeRoute-Android/1.0"
        )

        return connection.getInputStream()
            .bufferedReader()
            .use { it.readText() }
    }

    private fun decodeSubscription(content: String): List<String> {
        val lines = content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

        val direct = lines.filter { line ->
            supported.any { line.startsWith(it, true) }
        }

        if (direct.isNotEmpty()) return direct

        return try {
            val decoded = String(
                Base64.getMimeDecoder().decode(content.trim())
            )

            decoded.lines()
                .map { it.trim() }
                .filter { line ->
                    supported.any { line.startsWith(it, true) }
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseNode(link: String): FreeRouteNode? {
        return try {
            val protocol = link.substringBefore("://").lowercase()

            var host = ""
            var port = 0
            var name = protocol.uppercase()

            if (protocol == "vmess") {
                val encoded = link.substringAfter("://")
                    .substringBefore("#")

                val json = JSONObject(
                    String(Base64.getMimeDecoder().decode(encoded))
                )

                host = json.optString("add")
                port = json.optInt("port")
                name = json.optString("ps", "VMess")
            } else {
                val uri = URI(link.substringBefore("#"))

                host = uri.host ?: ""
                port = uri.port

                if (host.isBlank()) return null

                name = link.substringAfter("#", protocol)
                    .let { java.net.URLDecoder.decode(it, "UTF-8") }
            }

            if (host.isBlank() || port !in 1..65535) {
                return null
            }

            FreeRouteNode(
                id = "${protocol}_${host}_$port",
                name = name,
                protocol = protocol.uppercase(),
                host = host,
                port = port,
                link = link
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun testLatency(
        node: FreeRouteNode
    ): FreeRouteNode = withContext(Dispatchers.IO) {

        val start = System.nanoTime()

        val result = try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(node.host, node.port),
                    5000
                )
            }

            (System.nanoTime() - start) / 1_000_000
        } catch (_: Exception) {
            null
        }

        node.copy(latencyMs = result)
    }

    suspend fun testAll(
        nodes: List<FreeRouteNode>
    ): List<FreeRouteNode> = withContext(Dispatchers.IO) {

        nodes.map { node ->
            testLatency(node)
        }.sortedBy { it.latencyMs ?: Long.MAX_VALUE }
    }
}
