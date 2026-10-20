package io.github.jgfyyvzss.gliderpilotlog.data.weglide

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** The result of one WeGlide request. Failures carry a message ready to show. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Failed(val message: String) : ApiResult<Nothing>
}

/** Every WeGlide call goes through this interface, so tests use a fake or a local mock server, never WeGlide. */
interface WeGlideApi {
    /** `GET /v1/aircraft`, returned as the raw response body. */
    suspend fun fetchAircraftJson(): ApiResult<String>
}

/** WeGlide over HTTPS with OkHttp. The pilot supplied the paths; the shared host is to be checked with a first real call. */
class OkHttpWeGlideApi(
    private val userAgent: String,
    private val baseUrl: String = BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) : WeGlideApi {
    override suspend fun fetchAircraftJson(): ApiResult<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/aircraft")
            .header("User-Agent", userAgent)
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.code == 200) ApiResult.Ok(body)
                else ApiResult.Failed("WeGlide answered ${response.code}: ${body.take(300)}")
            }
        } catch (e: IOException) {
            ApiResult.Failed("Could not reach WeGlide: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        const val BASE_URL = "https://api.weglide.org"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /** For example `soarlogs/1.0.0 (Android)`. */
        fun userAgent(appVersion: String) = "soarlogs/$appVersion (Android)"
    }
}
