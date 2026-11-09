package io.nekohasekai.sfa.freeroute

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64

data class FreeRouteNode(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val link: String,
    val latencyMs: Long? = null,
)

object FreeRouteCatalog {

    /*
     * This is a public community subscription feed, not an official or
     * guaranteed source. It may be unavailable, stale, or contain unsafe nodes.
     */
    private val sources = listOf(
        // Curated, periodically refreshed public community feeds.
        "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/lite.txt",
        "https://raw.githubusercontent.com/chaishpish/free_server_v2ray/main/@TFMTProto",
        "https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/v2ray-base64.txt",
    )

    private val supportedSchemes = setOf(
        "vless",
        "vmess",
        "trojan",
        "ss",
        "shadowsocks",
        "hysteria2",
        "hy2",
        "tuic",
    )

    suspend fun discover(): List<FreeRouteNode> = withContext(Dispatchers.IO) {
        val collected = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (source in sources) {
            try {
                val body = download(source)
                collected += decodeSubscription(body)
            } catch (error: Exception) {
                errors += (error.message ?: "source unavailable")
            }
        }

        val parsed = collected
            .mapNotNull(::parseNode)
            .distinctBy { it.link.substringBefore("#") }

        if (parsed.isEmpty() && errors.isNotEmpty()) {
            throw IllegalStateException(
                "دریافت منبع عمومی ناموفق بود: ${errors.first()}",
            )
        }

        parsed
    }

    suspend fun discoverFromSubscription(address: String): List<FreeRouteNode> =
        withContext(Dispatchers.IO) {
            decodeSubscription(download(address))
                .mapNotNull(::parseNode)
                .distinctBy { it.link.substringBefore("#") }
        }

    fun parseLink(link: String): FreeRouteNode? = parseNode(link.trim())

    /**
     * Convert share links into a local sing-box configuration.
     * This is intentionally local: raw V2Ray URI subscriptions are not
     * sing-box JSON URLs and cannot be stored as remote JSON profiles.
     */
    fun buildSingBoxConfig(nodes: List<FreeRouteNode>): String {
        require(nodes.isNotEmpty()) { "No valid proxy links were found." }

        val outbounds = JSONArray()
        val nodeTags = mutableListOf<String>()

        nodes.forEachIndexed { index, node ->
            val tag = "freeroute-${index + 1}"
            val outbound = buildOutbound(node, tag)
            outbounds.put(outbound)
            nodeTags += tag
        }

        outbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        val finalTag = if (nodeTags.size == 1) {
            nodeTags.first()
        } else {
            val selector = JSONObject()
                .put("type", "selector")
                .put("tag", "proxy")
                .put("outbounds", JSONArray(nodeTags))
                .put("default", nodeTags.first())
            outbounds.put(selector)
            "proxy"
        }

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("outbounds", outbounds)
            .put(
                "route",
                JSONObject()
                    .put("auto_detect_interface", true)
                    .put("final", finalTag),
            )
            .toString(2)
    }
    fun buildProbeConfig(node: FreeRouteNode): String {
    val tag = "freeroute-probe"

    val outbounds = JSONArray()
        .put(buildOutbound(node, tag))

    val inbound = JSONObject()
        .put("type", "mixed")
        .put("tag", "probe-in")
        .put("listen", "127.0.0.1")
        .put("listen_port", 20808)

    return JSONObject()
        .put("log", JSONObject().put("level", "error"))
        .put("inbounds", JSONArray().put(inbound))
        .put("outbounds", outbounds)
        .put(
            "route",
            JSONObject()
                .put("auto_detect_interface", true)
                .put("final", tag)
        )
        .toString(2)
}

    private fun buildOutbound(node: FreeRouteNode, tag: String): JSONObject {
        val scheme = node.protocol.lowercase()
        val link = node.link
        val uri = if (scheme == "vmess" || scheme == "ss" || scheme == "shadowsocks") {
            null
        } else {
            URI(link.substringBefore("#"))
        }
        val query = uri?.rawQuery.orEmpty()
            .split("&")
            .filter { it.contains("=") }
            .associate { pair ->
                val key = URLDecoder.decode(pair.substringBefore("="), "UTF-8")
                val value = URLDecoder.decode(pair.substringAfter("="), "UTF-8")
                key to value
            }

        val outbound = JSONObject()
            .put("type", if (scheme == "ss" || scheme == "shadowsocks") "shadowsocks" else scheme)
            .put("tag", tag)
            .put("server", node.host)
            .put("server_port", node.port)

        when (scheme) {
            "vless" -> {
                outbound.put("uuid", decodeUriUserInfo(uri?.rawUserInfo.orEmpty()))
                query["flow"]?.takeIf { it.isNotBlank() }?.let { outbound.put("flow", it) }
                addTls(outbound, query, query["security"] != "none")
                addTransport(outbound, query)
            }
            "vmess" -> {
                val encoded = link.substringAfter("://").substringBefore("#")
                val json = JSONObject(String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8))
                outbound.put("uuid", json.optString("id"))
                outbound.put("security", json.optString("scy").ifBlank { "auto" })
                outbound.put("alter_id", json.optInt("aid", 0))
                val vmessQuery = mutableMapOf<String, String>()
                val vmessTls = json.optString("tls")
                vmessQuery["security"] =
                    if (vmessTls.isBlank() || vmessTls.equals("none", ignoreCase = true)) "none" else "tls"
                vmessQuery["sni"] = json.optString("sni").ifBlank { json.optString("host") }
                vmessQuery["type"] = json.optString("net").ifBlank { "tcp" }
                vmessQuery["path"] = json.optString("path")
                vmessQuery["host"] = json.optString("host")
                addTls(
                    outbound,
                    vmessQuery,
                    vmessTls.isNotBlank() && !vmessTls.equals("none", ignoreCase = true),
                )
                addTransport(outbound, vmessQuery)
            }
            "trojan" -> {
                outbound.put("password", decodeUriUserInfo(uri?.rawUserInfo.orEmpty()))
                addTls(outbound, query, query["security"] != "none")
                addTransport(outbound, query)
            }
            "ss", "shadowsocks" -> {
                val credentials = decodeShadowsocksCredentials(link)
                outbound.put("method", credentials.first)
                outbound.put("password", credentials.second)
            }
            "hysteria2", "hy2" -> {
                outbound.put("type", "hysteria2")
                outbound.put("password", decodeUriUserInfo(uri?.rawUserInfo.orEmpty()))
                addTls(outbound, query, true)
                val obfsType = query["obfs"]
                val obfsPassword = query["obfs-password"]
                if (!obfsType.isNullOrBlank() && !obfsPassword.isNullOrBlank()) {
                    outbound.put(
                        "obfs",
                        JSONObject().put("type", obfsType).put("password", obfsPassword),
                    )
                }
            }
            "tuic" -> {
                val userInfo = decodeUriUserInfo(uri?.rawUserInfo.orEmpty())
                val parts = userInfo.split(":", limit = 2)
                require(parts.size == 2) { "Invalid TUIC user info" }
                outbound.put("uuid", parts[0])
                outbound.put("password", parts[1])
                query["congestion_control"]?.let { outbound.put("congestion_control", it) }
                addTls(outbound, query, true)
            }
            else -> throw IllegalArgumentException("Unsupported protocol: $scheme")
        }

        return outbound
    }

    private fun addTls(
        outbound: JSONObject,
        query: Map<String, String>,
        enabled: Boolean,
    ) {
        if (!enabled) return
        val tls = JSONObject().put("enabled", true)
        val serverName = query["sni"] ?: query["serverName"] ?: query["host"]
        if (!serverName.isNullOrBlank()) tls.put("server_name", serverName)

        val fingerprint = query["fp"]
        if (!fingerprint.isNullOrBlank()) {
            tls.put(
                "utls",
                JSONObject().put("enabled", true).put("fingerprint", fingerprint),
            )
        }

        if (query["security"] == "reality" || !query["pbk"].isNullOrBlank()) {
            val reality = JSONObject().put("enabled", true)
            query["pbk"]?.let { reality.put("public_key", it) }
            query["sid"]?.let { reality.put("short_id", it) }
            tls.put("reality", reality)
        }

        query["alpn"]?.split(",")?.filter { it.isNotBlank() }?.let {
            tls.put("alpn", JSONArray(it))
        }
        outbound.put("tls", tls)
    }

    private fun addTransport(outbound: JSONObject, query: Map<String, String>) {
        val type = query["type"] ?: return
        when (type.lowercase()) {
            "ws" -> {
                val transport = JSONObject().put("type", "ws")
                query["path"]?.let { transport.put("path", it) }
                query["host"]?.let {
                    transport.put("headers", JSONObject().put("Host", it))
                }
                outbound.put("transport", transport)
            }
            "grpc" -> {
                val transport = JSONObject().put("type", "grpc")
                (query["serviceName"] ?: query["service_name"] ?: query["path"])
                    ?.let { transport.put("service_name", it.trimStart('/')) }
                outbound.put("transport", transport)
            }
            "httpupgrade" -> {
                val transport = JSONObject().put("type", "httpupgrade")
                query["path"]?.let { transport.put("path", it) }
                query["host"]?.let {
                    transport.put("host", JSONArray().put(it))
                }
                outbound.put("transport", transport)
            }
        }
    }

    private fun decodeUriUserInfo(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private fun decodeShadowsocksCredentials(link: String): Pair<String, String> {
        val payload = link.substringAfter("://").substringBefore("#").substringBefore("?")
        val decoded = if (payload.contains("@")) {
            payload
        } else {
            try {
                String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8)
            } catch (_: Exception) {
                String(Base64.getMimeDecoder().decode(payload), StandardCharsets.UTF_8)
            }
        }
        val userInfo = decoded.substringBeforeLast("@", decoded)
        val separator = userInfo.indexOf(':')
        require(separator > 0) { "Invalid Shadowsocks credentials" }
        return userInfo.substring(0, separator) to userInfo.substring(separator + 1)
    }

    private fun download(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 18_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "FreeRoute-Android/1.0")
            setRequestProperty("Accept", "text/plain,*/*")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status")
            }

            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_FEED_BYTES) {
                throw IllegalStateException("feed is too large")
            }

            return connection.inputStream.bufferedReader().use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(8192)
                var total = 0
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_FEED_BYTES) {
                        throw IllegalStateException("feed is too large")
                    }
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeSubscription(content: String): List<String> {
        fun supportedLines(value: String): List<String> =
            value.lineSequence()
                .map(String::trim)
                .filter { line ->
                    line.isNotEmpty() &&
                        !line.startsWith("#") &&
                        supportedSchemes.any { scheme ->
                            line.startsWith("$scheme://", ignoreCase = true)
                        }
                }
                .toList()

        val direct = supportedLines(content)
        if (direct.isNotEmpty()) return direct

        val compact = content.filterNot(Char::isWhitespace)
        val decoded = try {
            String(Base64.getMimeDecoder().decode(compact), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }
        return supportedLines(decoded)
    }

    private fun parseNode(originalLink: String): FreeRouteNode? {
        val link = originalLink.trim()
        return try {
            val scheme = link.substringBefore("://").lowercase()
            if (scheme !in supportedSchemes) return null

            var host: String
            var port: Int
            var name = scheme.uppercase()

            if (scheme == "vmess") {
                val encoded = link.substringAfter("://").substringBefore("#")
                val jsonText = String(
                    Base64.getMimeDecoder().decode(encoded),
                    StandardCharsets.UTF_8,
                )
                val json = JSONObject(jsonText)
                host = json.optString("add").trim()
                port = json.optString("port").toIntOrNull() ?: json.optInt("port")
                name = json.optString("ps").takeIf { it.isNotBlank() } ?: "VMess"
            } else if (scheme == "ss" || scheme == "shadowsocks") {
                val parsed = parseShadowsocks(link) ?: return null
                host = parsed.first
                port = parsed.second
                name = parsed.third
            } else {
                val uri = URI(link.substringBefore("#"))
                host = uri.host ?: return null
                port = uri.port
                val fragment = link.substringAfter("#", "")
                if (fragment.isNotBlank()) {
                    name = URLDecoder.decode(fragment, StandardCharsets.UTF_8.name())
                }
            }

            if (host.isBlank() || port !in 1..65535) return null

            FreeRouteNode(
                id = "${scheme}_${host}_$port",
                name = name,
                protocol = scheme.uppercase(),
                host = host,
                port = port,
                link = link,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseShadowsocks(link: String): Triple<String, Int, String>? {
        val withoutScheme = link.substringAfter("://")
        val fragmentSplit = withoutScheme.split("#", limit = 2)
        val payload = fragmentSplit[0].substringBefore("?")
        val displayName = fragmentSplit.getOrNull(1)
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            ?.takeIf { it.isNotBlank() } ?: "Shadowsocks"

        val authority = payload.substringAfterLast("@", "")
        val hostPort = if (authority.isNotBlank()) {
            authority
        } else {
            val decoded = try {
                String(
                    Base64.getUrlDecoder().decode(payload),
                    StandardCharsets.UTF_8,
                )
            } catch (_: Exception) {
                String(
                    Base64.getMimeDecoder().decode(payload),
                    StandardCharsets.UTF_8,
                )
            }
            decoded.substringAfterLast("@", "")
        }

        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close <= 0) return null
            host = hostPort.substring(1, close)
            port = hostPort.substring(close + 1).removePrefix(":").toIntOrNull() ?: return null
        } else {
            val lastColon = hostPort.lastIndexOf(':')
            if (lastColon <= 0) return null
            host = hostPort.substring(0, lastColon)
            port = hostPort.substring(lastColon + 1).toIntOrNull() ?: return null
        }

        return Triple(host, port, displayName)
    }

    suspend fun testLatency(node: FreeRouteNode): FreeRouteNode =
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            val latency = try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(node.host, node.port), CONNECT_TIMEOUT_MS)
                }
                (System.nanoTime() - start) / 1_000_000
            } catch (_: Exception) {
                null
            }
            node.copy(latencyMs = latency)
        }

    suspend fun testAll(nodes: List<FreeRouteNode>): List<FreeRouteNode> =
        coroutineScope {
            val semaphore = Semaphore(MAX_PARALLEL_TESTS)
            nodes.map { node ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { testLatency(node) }
                }
            }.awaitAll().sortedBy { it.latencyMs ?: Long.MAX_VALUE }
        }

    private const val MAX_FEED_BYTES = 3 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val MAX_PARALLEL_TESTS = 20
}
