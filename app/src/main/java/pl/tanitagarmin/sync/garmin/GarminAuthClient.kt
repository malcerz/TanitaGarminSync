package pl.tanitagarmin.sync.garmin

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Garmin authentication for the post-March-2026 DI OAuth2 flow.
 *
 * The user signs in on Garmin's own SSO page in WebView. We only receive the
 * one-time CAS service ticket (ST-...), then exchange it for DI Bearer tokens.
 * The Garmin password is never read or stored by this class.
 */
class GarminAuthClient(context: Context) {
    private val store = GarminTokenStore(context)

    fun exchangeServiceTicket(ticket: String): GarminTokenStore.Tokens {
        require(ticket.startsWith("ST-")) { "Nieprawidłowy Garmin service ticket" }

        var lastError = "Brak odpowiedzi Garmin"
        for (clientId in DI_CLIENT_IDS) {
            val form = linkedMapOf(
                "client_id" to clientId,
                "service_ticket" to ticket,
                "grant_type" to DI_GRANT_TYPE,
                // This must match the `service` used when obtaining the ticket.
                "service_url" to SSO_LOGIN_BASE
            )
            val response = tokenRequest(clientId, form)
            if (response.status in 200..299) {
                val tokens = parseTokens(
                    body = response.body,
                    clientId = clientId,
                    previousRefreshToken = null,
                    previousRefreshExpiry = null
                )
                store.save(tokens)
                return tokens
            }
            lastError = "HTTP ${response.status}: ${response.body.take(220)}"
        }
        error("Garmin DI OAuth2: $lastError")
    }

    fun validAccessToken(): String {
        val current = store.load() ?: error("Garmin nie jest zalogowany")
        val now = System.currentTimeMillis() / 1000
        if (current.expiresAtEpochSeconds > now + 90) return current.accessToken
        return refresh(current).accessToken
    }

    fun forceRefresh(): String {
        val current = store.load() ?: error("Garmin nie jest zalogowany")
        return refresh(current).accessToken
    }

    fun isAuthenticated(): Boolean = store.isAuthenticated()

    fun logout() = store.clear()

    private fun refresh(current: GarminTokenStore.Tokens): GarminTokenStore.Tokens {
        val now = System.currentTimeMillis() / 1000
        current.refreshExpiresAtEpochSeconds?.let {
            if (it <= now + 30) {
                store.clear()
                error("Sesja Garmin wygasła. Zaloguj Garmin Connect ponownie.")
            }
        }

        val form = linkedMapOf(
            "grant_type" to "refresh_token",
            "client_id" to current.clientId,
            "refresh_token" to current.refreshToken
        )
        val response = tokenRequest(current.clientId, form)
        if (response.status !in 200..299) {
            if (response.status == 400 || response.status == 401) store.clear()
            error("Garmin odświeżanie tokenu: HTTP ${response.status}: ${response.body.take(220)}")
        }
        val refreshed = parseTokens(
            body = response.body,
            clientId = current.clientId,
            previousRefreshToken = current.refreshToken,
            previousRefreshExpiry = current.refreshExpiresAtEpochSeconds
        )
        store.save(refreshed)
        return refreshed
    }

    private fun parseTokens(
        body: String,
        clientId: String,
        previousRefreshToken: String?,
        previousRefreshExpiry: Long?
    ): GarminTokenStore.Tokens {
        val j = JSONObject(body)
        val access = j.optString("access_token").takeIf { it.isNotBlank() && it != "null" }
            ?: error("Garmin nie zwrócił access_token")
        val refresh = j.optString("refresh_token").takeIf { it.isNotBlank() && it != "null" }
            ?: previousRefreshToken
            ?: error("Garmin nie zwrócił refresh_token")

        val now = System.currentTimeMillis() / 1000
        val expiresIn = j.optLong("expires_in", 3600L).coerceAtLeast(60L)
        val refreshExpiresIn = j.optLong("refresh_token_expires_in", 0L)
        val refreshExpiry = if (refreshExpiresIn > 0) now + refreshExpiresIn else previousRefreshExpiry

        return GarminTokenStore.Tokens(
            clientId = clientId,
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochSeconds = now + expiresIn,
            refreshExpiresAtEpochSeconds = refreshExpiry
        )
    }

    private fun tokenRequest(clientId: String, form: Map<String, String>): HttpResponse {
        val body = form.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
            .toByteArray(StandardCharsets.UTF_8)
        val basic = Base64.getEncoder().encodeToString("$clientId:".toByteArray(StandardCharsets.UTF_8))
        return http(
            url = DI_TOKEN_URL,
            method = "POST",
            body = body,
            contentType = "application/x-www-form-urlencoded",
            headers = mapOf("Authorization" to "Basic $basic")
        )
    }

    private data class HttpResponse(val status: Int, val body: String)

    private fun http(
        url: String,
        method: String,
        body: ByteArray? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 40_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", NATIVE_API_USER_AGENT)
            setRequestProperty("X-Garmin-User-Agent", NATIVE_X_GARMIN_USER_AGENT)
            setRequestProperty("X-Garmin-Paired-App-Version", "10861")
            setRequestProperty("X-Garmin-Client-Platform", "Android")
            setRequestProperty("X-App-Ver", "10861")
            setRequestProperty("X-Lang", "pl")
            setRequestProperty("X-GCExperience", "GC5")
            setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.7")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                contentType?.let { setRequestProperty("Content-Type", it) }
                setFixedLengthStreamingMode(body.size)
            }
        }
        if (body != null) conn.outputStream.use { it.write(body) }
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val bytes = stream?.use {
            val out = ByteArrayOutputStream()
            it.copyTo(out)
            out.toByteArray()
        } ?: ByteArray(0)
        conn.disconnect()
        return HttpResponse(status, bytes.toString(StandardCharsets.UTF_8))
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        const val SSO_LOGIN_BASE = "https://sso.garmin.com/sso/embed"
        const val SSO_LOGIN_URL =
            "https://sso.garmin.com/sso/embed?id=gauth-widget&embedWidget=true" +
                "&gauthHost=https%3A%2F%2Fsso.garmin.com%2Fsso" +
                "&clientId=GarminConnect&locale=en_US" +
                "&redirectAfterAccountLoginUrl=https%3A%2F%2Fsso.garmin.com%2Fsso%2Fembed" +
                "&service=https%3A%2F%2Fsso.garmin.com%2Fsso%2Fembed"

        private const val DI_TOKEN_URL = "https://diauth.garmin.com/di-oauth2-service/oauth/token"
        private const val DI_GRANT_TYPE =
            "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"
        private val DI_CLIENT_IDS = listOf(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI",
            "GARMIN_CONNECT_MOBILE_IOS_DI"
        )

        const val NATIVE_API_USER_AGENT = "GCM-Android-5.23"
        const val NATIVE_X_GARMIN_USER_AGENT =
            "com.garmin.android.apps.connectmobile/5.23; ; Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0"
    }
}
