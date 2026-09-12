package pl.tanitagarmin.sync.garmin

import android.content.Context
import pl.tanitagarmin.sync.tanita.Measurement
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

class GarminClient(private val context: Context) {
    private val auth = GarminAuthClient(context)

    fun uploadBodyComposition(measurement: Measurement): UploadResult {
        val fit = GarminFitEncoder.encode(measurement)
        var token = auth.validAccessToken()
        var response = upload(token, fit)
        if (response.status == 401) {
            token = auth.forceRefresh()
            response = upload(token, fit)
        }
        check(response.status in 200..299) {
            "Garmin upload: HTTP ${response.status}: ${response.body.take(300)}"
        }
        return UploadResult(response.status, response.body)
    }

    data class UploadResult(val httpStatus: Int, val responseBody: String)
    private data class HttpResponse(val status: Int, val body: String)

    private fun upload(accessToken: String, fit: ByteArray): HttpResponse {
        val boundary = "----TanitaGarminSync${UUID.randomUUID().toString().replace("-", "")}" 
        val out = ByteArrayOutputStream()
        fun text(s: String) = out.write(s.toByteArray(StandardCharsets.UTF_8))
        text("--$boundary\r\n")
        text("Content-Disposition: form-data; name=\"file\"; filename=\"body_composition.fit\"\r\n")
        text("Content-Type: application/octet-stream\r\n\r\n")
        out.write(fit)
        text("\r\n--$boundary--\r\n")
        val body = out.toByteArray()

        val conn = (URL("https://connectapi.garmin.com/upload-service/upload").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("User-Agent", GarminAuthClient.NATIVE_API_USER_AGENT)
            setRequestProperty("X-Garmin-User-Agent", GarminAuthClient.NATIVE_X_GARMIN_USER_AGENT)
            setRequestProperty("X-Garmin-Paired-App-Version", "10861")
            setRequestProperty("X-Garmin-Client-Platform", "Android")
            setRequestProperty("X-App-Ver", "10861")
            setRequestProperty("X-GCExperience", "GC5")
            setFixedLengthStreamingMode(body.size)
        }
        conn.outputStream.use { it.write(body) }
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return HttpResponse(status, response)
    }
}
