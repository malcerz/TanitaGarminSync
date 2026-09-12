package pl.tanitagarmin.sync.tanita

import java.io.ByteArrayOutputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MyTanitaClient {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    data class DownloadResult(
        val csv: String,
        val parseResult: ParseResult
    )

    fun loginAndDownload(email: String, password: String): DownloadResult {
        require(email.isNotBlank()) { "Podaj e-mail MyTANITA" }
        require(password.isNotBlank()) { "Podaj hasło MyTANITA" }

        val loginPage = request("https://mytanita.eu/en/user/login")
        check(loginPage.status in 200..299) { "MyTANITA login page: HTTP ${loginPage.status}" }

        val token = extractToken(loginPage.body)
            ?: error("Nie znaleziono tokenu logowania na stronie MyTANITA. Serwis mógł zmienić formularz.")

        val form = buildString {
            append("mail=").append(enc(email))
            append("&password=").append(enc(password))
            append("&token=").append(enc(token))
            append("&login=Login")
        }

        val loginResponse = request(
            url = "https://mytanita.eu/en/user/processlogin",
            method = "POST",
            body = form.toByteArray(StandardCharsets.UTF_8),
            contentType = "application/x-www-form-urlencoded; charset=UTF-8",
            referer = "https://mytanita.eu/en/user/login"
        )
        check(loginResponse.status in 200..399) { "Logowanie MyTANITA: HTTP ${loginResponse.status}" }

        // Nie polegamy na tytule strony. Najpewniejszym testem udanej sesji jest
        // próba pobrania właściwego eksportu i walidacja jego nagłówka.
        val export = request(
            url = "https://mytanita.eu/en/user/export-csv",
            referer = "https://mytanita.eu/en/user/"
        )
        check(export.status in 200..299) { "Eksport MyTANITA: HTTP ${export.status}" }

        val csv = export.body.removePrefix("\uFEFF")
        if (!csv.lineSequence().firstOrNull().orEmpty().contains("Weight (kg)") ||
            !csv.lineSequence().firstOrNull().orEmpty().contains("Date")) {
            val hint = extractError(loginResponse.body)
            error(
                if (hint != null) "MyTANITA: $hint"
                else "Nie otrzymano CSV z MyTANITA. Sprawdź login/hasło albo zmianę serwisu."
            )
        }

        return DownloadResult(csv, TanitaCsvParser.parse(csv))
    }

    private data class Response(val status: Int, val body: String)

    private fun request(
        url: String,
        method: String = "GET",
        body: ByteArray? = null,
        contentType: String? = null,
        referer: String? = null,
        redirectsLeft: Int = 6
    ): Response {
        require(redirectsLeft >= 0) { "Za dużo przekierowań HTTP" }

        val uri = URI(url)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) TanitaGarminSync/0.1")
            setRequestProperty("Accept", "text/html,text/csv,application/xhtml+xml,*/*;q=0.8")
            setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")
            referer?.let { setRequestProperty("Referer", it) }

            cookies.get(uri, emptyMap()).forEach { (name, values) ->
                if (name.equals("Cookie", ignoreCase = true)) {
                    setRequestProperty("Cookie", values.joinToString("; "))
                }
            }

            if (body != null) {
                doOutput = true
                contentType?.let { setRequestProperty("Content-Type", it) }
                setFixedLengthStreamingMode(body.size)
            }
        }

        if (body != null) conn.outputStream.use { it.write(body) }

        val status = conn.responseCode
        cookies.put(uri, conn.headerFields)

        if (status in setOf(301, 302, 303, 307, 308)) {
            val location = conn.getHeaderField("Location")
                ?: error("HTTP $status bez nagłówka Location")
            conn.disconnect()
            val nextUrl = uri.resolve(location).toString()
            val keepMethod = status == 307 || status == 308
            return request(
                url = nextUrl,
                method = if (keepMethod) method else "GET",
                body = if (keepMethod) body else null,
                contentType = if (keepMethod) contentType else null,
                referer = url,
                redirectsLeft = redirectsLeft - 1
            )
        }

        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val bytes = stream?.use {
            val out = ByteArrayOutputStream()
            it.copyTo(out)
            out.toByteArray()
        } ?: ByteArray(0)
        conn.disconnect()

        return Response(status, bytes.toString(StandardCharsets.UTF_8))
    }

    private fun extractToken(html: String): String? {
        val patterns = listOf(
            Regex("""<input[^>]*name=[\"']token[\"'][^>]*value=[\"']([^\"']+)[\"'][^>]*>""", RegexOption.IGNORE_CASE),
            Regex("""<input[^>]*value=[\"']([^\"']+)[\"'][^>]*name=[\"']token[\"'][^>]*>""", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
    }

    private fun extractError(html: String): String? {
        return Regex("""<li[^>]*>(.*?)</li>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace(Regex("<[^>]+>"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
