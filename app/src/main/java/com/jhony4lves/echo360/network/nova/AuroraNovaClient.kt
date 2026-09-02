package com.jhony4lves.echo360.network.nova

import android.util.Base64
import com.jhony4lves.echo360.domain.doctor.DashLaunchOption
import com.jhony4lves.echo360.domain.doctor.DashLaunchSnapshot
import com.jhony4lves.echo360.domain.doctor.DashLaunchVersion
import com.jhony4lves.echo360.domain.library.NowPlaying
import com.jhony4lves.echo360.domain.xbox.XboxDevicePath
import com.jhony4lves.echo360.domain.xbox.XboxEndpoint
import com.jhony4lves.echo360.domain.xbox.XboxProfile
import com.jhony4lves.echo360.network.TcpPortProbe
import com.jhony4lves.echo360.network.TcpProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Safe authenticated subset of Aurora NOVA.
 *
 * This client exposes runtime title/launch operations plus the non-identity
 * DashLaunch settings snapshot used by EchoDoctor. It intentionally does not
 * request /system, CPU/DVD keys, serials, console IDs, or other identity
 * material. JWTs live only in process memory.
 */
class AuroraNovaClient(
    private val tcpProbe: TcpPortProbe = TcpPortProbe(),
) {
    @Volatile
    private var cachedToken: CachedToken? = null

    suspend fun probe(endpoint: XboxEndpoint): TcpProbeResult =
        tcpProbe.probe(endpoint.host, endpoint.novaPort)

    suspend fun nowPlaying(profile: XboxProfile): NowPlaying = withContext(Dispatchers.IO) {
        val payload = authenticatedJsonGet(profile, "/title")
        parseNowPlaying(payload)
    }

    suspend fun dashLaunch(profile: XboxProfile): DashLaunchSnapshot = withContext(Dispatchers.IO) {
        val payload = authenticatedJsonGet(profile, "/dashlaunch")
        parseDashLaunch(payload)
    }

    suspend fun launch(
        profile: XboxProfile,
        canonicalDirectory: String,
        executable: String,
        type: Int,
    ) = withContext(Dispatchers.IO) {
        require(type in -1..4) { "Tipo NOVA inválido: $type" }
        require(executable.isNotBlank()) { "Executável não pode estar vazio." }

        val deviceDirectory = XboxDevicePath.toDevicePath(canonicalDirectory)
        val response = authenticatedMultipartPost(
            profile = profile,
            path = "/title/launch",
            fields = linkedMapOf(
                "exec" to executable,
                "path" to deviceDirectory,
                "type" to type.toString(),
            ),
            retryAuth = true,
        )
        if (response.code != 202) {
            throw NovaHttpException(response.code, response.body.ifBlank { "NOVA recusou o launch." })
        }
    }

    private fun authenticatedJsonGet(profile: XboxProfile, path: String): JSONObject {
        var token = tokenFor(profile, forceRefresh = false)
        var response = request(
            profile = profile,
            method = "GET",
            path = path,
            bearerToken = token,
        )

        if (response.code == 401) {
            token = tokenFor(profile, forceRefresh = true)
            response = request(
                profile = profile,
                method = "GET",
                path = path,
                bearerToken = token,
            )
        }

        if (response.code !in 200..299) {
            throw NovaHttpException(response.code, response.body.ifBlank { "NOVA retornou erro." })
        }
        return JSONObject(response.body)
    }

    private fun authenticatedMultipartPost(
        profile: XboxProfile,
        path: String,
        fields: Map<String, String>,
        retryAuth: Boolean,
    ): NovaResponse {
        var token = tokenFor(profile, forceRefresh = false)
        var response = multipartPost(profile, path, fields, token)
        if (response.code == 401 && retryAuth) {
            token = tokenFor(profile, forceRefresh = true)
            response = multipartPost(profile, path, fields, token)
        }
        return response
    }

    private fun tokenFor(profile: XboxProfile, forceRefresh: Boolean): String {
        val endpoint = profile.endpoint.validated()
        val credentials = profile.credentials
        require(credentials.novaUsername.isNotBlank() && credentials.novaPassword.isNotBlank()) {
            "Configure usuário e senha NOVA na aba Xbox."
        }

        val key = "${endpoint.host}:${endpoint.novaPort}:${credentials.novaUsername}"
        val now = System.currentTimeMillis() / 1000L
        val current = cachedToken
        if (!forceRefresh && current != null && current.key == key && current.expiresAtEpochSeconds > now + 15L) {
            return current.token
        }

        val response = multipartPost(
            profile = profile,
            path = "/authenticate",
            fields = linkedMapOf(
                "username" to credentials.novaUsername,
                "password" to credentials.novaPassword,
            ),
            bearerToken = null,
        )
        if (response.code != 200) {
            throw NovaHttpException(response.code, "NOVA recusou autenticação.")
        }

        val token = JSONObject(response.body).optString("token").trim()
        require(token.isNotBlank()) { "NOVA autenticou sem retornar JWT." }
        cachedToken = CachedToken(
            key = key,
            token = token,
            expiresAtEpochSeconds = jwtExpiry(token) ?: (now + 20 * 60L),
        )
        return token
    }

    private fun multipartPost(
        profile: XboxProfile,
        path: String,
        fields: Map<String, String>,
        bearerToken: String?,
    ): NovaResponse {
        val boundary = "Echo360-${UUID.randomUUID()}"
        val endpoint = profile.endpoint.validated()
        val connection = URL("http://${endpoint.host}:${endpoint.novaPort}$path")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        connection.doOutput = true
        connection.setRequestProperty("Accept", "application/json, text/html")
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }

        connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            fields.forEach { (name, value) ->
                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"")
                writer.append(name)
                writer.append("\"\r\n\r\n")
                writer.append(value)
                writer.append("\r\n")
            }
            writer.append("--$boundary--\r\n")
        }

        return connection.readResponse()
    }

    private fun request(
        profile: XboxProfile,
        method: String,
        path: String,
        bearerToken: String?,
    ): NovaResponse {
        val endpoint = profile.endpoint.validated()
        val connection = URL("http://${endpoint.host}:${endpoint.novaPort}$path")
            .openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        return connection.readResponse()
    }
}

internal fun parseNowPlaying(payload: JSONObject): NowPlaying {
    val disc = payload.optJSONObject("disc") ?: JSONObject()
    val resolution = payload.optJSONObject("resolution") ?: JSONObject()
    val version = payload.optJSONObject("version") ?: JSONObject()
    return NowPlaying(
        titleId = parseNovaHex(payload.optString("titleid")),
        mediaId = parseNovaHex(payload.optString("mediaid")),
        executableDevicePath = payload.optString("path"),
        titleUpdateVersion = payload.optInt("tuver", 0),
        discCurrent = disc.optInt("current", 0),
        discCount = disc.optInt("count", 0),
        resolutionWidth = resolution.optInt("width", 0),
        resolutionHeight = resolution.optInt("height", 0),
        baseVersion = version.optString("base").takeIf(String::isNotBlank),
        currentVersion = version.optString("current").takeIf(String::isNotBlank),
    )
}

internal fun parseDashLaunch(payload: JSONObject): DashLaunchSnapshot {
    val optionsArray = payload.optJSONArray("options")
    val options = buildList {
        if (optionsArray != null) {
            for (index in 0 until optionsArray.length()) {
                val item = optionsArray.optJSONObject(index) ?: continue
                add(
                    DashLaunchOption(
                        id = item.optLong("id", 0L),
                        category = item.optString("category", "").trim(),
                        name = item.optString("name", "").trim(),
                        value = item.optString("value", ""),
                    ),
                )
            }
        }
    }

    val version = payload.optJSONObject("version") ?: JSONObject()
    val number = version.optJSONObject("number") ?: JSONObject()
    return DashLaunchSnapshot(
        options = options,
        version = DashLaunchVersion(
            kernel = version.optLong("kernel", 0L),
            major = number.optLong("major", 0L),
            minor = number.optLong("minor", 0L),
            build = number.optLong("build", 0L),
        ),
    )
}

internal fun parseNovaHex(value: String): Long {
    val clean = value.trim().removePrefix("0x").removePrefix("0X")
    return clean.toLongOrNull(16) ?: 0L
}

private fun HttpURLConnection.readResponse(): NovaResponse = try {
    val code = responseCode
    val stream = if (code in 200..399) inputStream else errorStream
    val body = if (stream == null) "" else BufferedReader(InputStreamReader(stream)).use { it.readText() }
    NovaResponse(code, body)
} finally {
    disconnect()
}

private fun jwtExpiry(jwt: String): Long? = runCatching {
    val payload = jwt.split('.').getOrNull(1) ?: return@runCatching null
    val decoded = Base64.decode(
        payload,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )
    JSONObject(String(decoded, StandardCharsets.UTF_8)).optLong("exp").takeIf { it > 0L }
}.getOrNull()

private data class CachedToken(
    val key: String,
    val token: String,
    val expiresAtEpochSeconds: Long,
)

private data class NovaResponse(
    val code: Int,
    val body: String,
)

class NovaHttpException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)
