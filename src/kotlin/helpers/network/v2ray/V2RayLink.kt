package desu.inugram.helpers.network.v2ray

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

class V2RayServer(
    val link: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    private val outbound: JSONObject,
) {
    @Volatile var pingMs: Int = -1

    fun outboundJson(): JSONObject = JSONObject(outbound.toString())

    fun key(): String = "$protocol:$host:$port:${outbound.toString().hashCode()}"
}

object V2RayLink {
    private val PREFIXES = arrayOf("vless://", "vmess://", "trojan://", "ss://")

    fun looksLikeLink(s: String): Boolean = PREFIXES.any { s.startsWith(it) }

    fun parse(raw: String): V2RayServer? = try {
        val s = raw.trim()
        when {
            s.startsWith("vless://") -> vless(s)
            s.startsWith("vmess://") -> vmess(s)
            s.startsWith("trojan://") -> trojan(s)
            s.startsWith("ss://") -> shadowsocks(s)
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private class Parts(val user: String, val host: String, val port: Int, val query: Map<String, String>, val name: String)

    private fun split(raw: String): Parts {
        var rest = raw.substring(raw.indexOf("://") + 3)
        val hashAt = rest.indexOf('#')
        val name = if (hashAt >= 0) Uri.decode(rest.substring(hashAt + 1)) else ""
        if (hashAt >= 0) rest = rest.substring(0, hashAt)
        val qAt = rest.indexOf('?')
        val query = HashMap<String, String>()
        if (qAt >= 0) {
            for (pair in rest.substring(qAt + 1).split('&')) {
                val i = pair.indexOf('=')
                if (i > 0) query[pair.substring(0, i)] = Uri.decode(pair.substring(i + 1))
            }
            rest = rest.substring(0, qAt)
        }
        rest = rest.trimEnd('/')
        val at = rest.lastIndexOf('@')
        val user = if (at >= 0) Uri.decode(rest.substring(0, at)) else ""
        val hostPort = if (at >= 0) rest.substring(at + 1) else rest
        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']')
            host = hostPort.substring(1, end)
            port = hostPort.substring(end + 2).toInt()
        } else {
            val c = hostPort.lastIndexOf(':')
            host = hostPort.substring(0, c)
            port = hostPort.substring(c + 1).toInt()
        }
        require(host.isNotBlank() && port in 1..65535)
        return Parts(user, host, port, query, name)
    }

    private fun stream(
        host: String,
        net: String,
        security: String,
        sni: String,
        fp: String,
        alpn: String,
        path: String,
        hostHeader: String,
        serviceName: String,
        mode: String,
        pbk: String,
        sid: String,
        spx: String,
    ): JSONObject? {
        val network = when (net.ifEmpty { "tcp" }) {
            "tcp" -> "tcp"
            "ws" -> "ws"
            "grpc" -> "grpc"
            "httpupgrade" -> "httpupgrade"
            "xhttp", "splithttp" -> "xhttp"
            else -> return null
        }
        val s = JSONObject().put("network", network)
        when (network) {
            "ws" -> s.put(
                "wsSettings",
                JSONObject().put("path", path.ifEmpty { "/" }).also {
                    if (hostHeader.isNotEmpty()) it.put("headers", JSONObject().put("Host", hostHeader))
                },
            )
            "grpc" -> s.put("grpcSettings", JSONObject().put("serviceName", serviceName).put("multiMode", mode == "multi"))
            "httpupgrade" -> s.put("httpupgradeSettings", JSONObject().put("path", path.ifEmpty { "/" }).put("host", hostHeader))
            "xhttp" -> s.put(
                "xhttpSettings",
                JSONObject().put("path", path.ifEmpty { "/" }).put("host", hostHeader).put("mode", mode.ifEmpty { "auto" }),
            )
        }
        when (security) {
            "tls" -> {
                s.put("security", "tls")
                val tls = JSONObject()
                    .put("serverName", sni.ifEmpty { hostHeader.ifEmpty { host } })
                    .put("fingerprint", fp.ifEmpty { "chrome" })
                if (alpn.isNotEmpty()) {
                    tls.put("alpn", JSONArray(alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
                }
                s.put("tlsSettings", tls)
            }
            "reality" -> {
                if (pbk.isEmpty()) return null
                s.put("security", "reality")
                s.put(
                    "realitySettings",
                    JSONObject()
                        .put("serverName", sni.ifEmpty { host })
                        .put("fingerprint", fp.ifEmpty { "chrome" })
                        .put("publicKey", pbk)
                        .put("shortId", sid)
                        .put("spiderX", spx),
                )
            }
            else -> s.put("security", "none")
        }
        return s
    }

    private fun vless(raw: String): V2RayServer? {
        val p = split(raw)
        if (p.user.isEmpty()) return null
        val q = p.query
        val st = stream(
            p.host, q["type"].orEmpty(), q["security"].orEmpty(), q["sni"].orEmpty(), q["fp"].orEmpty(),
            q["alpn"].orEmpty(), q["path"].orEmpty(), q["host"].orEmpty(), q["serviceName"].orEmpty(),
            q["mode"].orEmpty(),
            q["pbk"].orEmpty(), q["sid"].orEmpty(), q["spx"].orEmpty(),
        ) ?: return null
        if (q["headerType"] == "http") return null
        val user = JSONObject().put("id", p.user).put("encryption", q["encryption"].orEmpty().ifEmpty { "none" })
        q["flow"]?.takeIf { it.isNotEmpty() }?.let { user.put("flow", it) }
        val ob = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(JSONObject().put("address", p.host).put("port", p.port).put("users", JSONArray().put(user))),
                ),
            )
            .put("streamSettings", st)
        return V2RayServer(raw, p.name.ifEmpty { "${p.host}:${p.port}" }, "vless", p.host, p.port, ob)
    }

    private fun vmess(raw: String): V2RayServer? {
        val j = JSONObject(String(decodeBase64(raw.removePrefix("vmess://").substringBefore('#')), Charsets.UTF_8))
        val host = j.optString("add")
        val port = j.optString("port").toInt()
        val id = j.optString("id")
        if (host.isBlank() || id.isBlank() || port !in 1..65535) return null
        val security = if (j.optString("tls") == "tls") "tls" else "none"
        val st = stream(
            host, j.optString("net"), security, j.optString("sni"), j.optString("fp"), j.optString("alpn"),
            j.optString("path"), j.optString("host"), j.optString("path"), "", "", "", "",
        ) ?: return null
        if (j.optString("type") == "http") return null
        val user = JSONObject()
            .put("id", id)
            .put("alterId", j.optString("aid", "0").toIntOrNull() ?: 0)
            .put("security", j.optString("scy").ifEmpty { "auto" })
        val ob = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vmess")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user))),
                ),
            )
            .put("streamSettings", st)
        return V2RayServer(raw, j.optString("ps").ifEmpty { "$host:$port" }, "vmess", host, port, ob)
    }

    private fun trojan(raw: String): V2RayServer? {
        val p = split(raw)
        if (p.user.isEmpty()) return null
        val q = p.query
        val st = stream(
            p.host, q["type"].orEmpty(), q["security"].orEmpty().ifEmpty { "tls" }, q["sni"].orEmpty(), q["fp"].orEmpty(),
            q["alpn"].orEmpty(), q["path"].orEmpty(), q["host"].orEmpty(), q["serviceName"].orEmpty(),
            q["mode"].orEmpty(),
            q["pbk"].orEmpty(), q["sid"].orEmpty(), q["spx"].orEmpty(),
        ) ?: return null
        val ob = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "trojan")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(JSONObject().put("address", p.host).put("port", p.port).put("password", p.user)),
                ),
            )
            .put("streamSettings", st)
        return V2RayServer(raw, p.name.ifEmpty { "${p.host}:${p.port}" }, "trojan", p.host, p.port, ob)
    }

    private fun shadowsocks(raw: String): V2RayServer? {
        var rest = raw.removePrefix("ss://")
        val hashAt = rest.indexOf('#')
        val name = if (hashAt >= 0) Uri.decode(rest.substring(hashAt + 1)) else ""
        if (hashAt >= 0) rest = rest.substring(0, hashAt)
        if (rest.contains("plugin=")) return null
        rest = rest.substringBefore('?').trimEnd('/')
        val at = rest.lastIndexOf('@')
        val creds: String
        val hostPort: String
        if (at >= 0) {
            val userInfo = Uri.decode(rest.substring(0, at))
            creds = if (userInfo.contains(':')) userInfo else String(decodeBase64(userInfo), Charsets.UTF_8)
            hostPort = rest.substring(at + 1)
        } else {
            val whole = String(decodeBase64(rest), Charsets.UTF_8)
            val i = whole.lastIndexOf('@')
            creds = whole.substring(0, i)
            hostPort = whole.substring(i + 1)
        }
        val c = creds.indexOf(':')
        val method = creds.substring(0, c)
        val password = creds.substring(c + 1)
        val colon = hostPort.lastIndexOf(':')
        val host = hostPort.substring(0, colon).trim('[', ']')
        val port = hostPort.substring(colon + 1).toInt()
        if (host.isBlank() || method.isBlank() || port !in 1..65535) return null
        val ob = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "shadowsocks")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject().put("address", host).put("port", port).put("method", method).put("password", password),
                    ),
                ),
            )
        return V2RayServer(raw, name.ifEmpty { "$host:$port" }, "ss", host, port, ob)
    }

    fun decodeBase64(s: String): ByteArray {
        var t = s.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return Base64.decode(t, Base64.DEFAULT)
    }
}
