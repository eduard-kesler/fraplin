package default_code.service

import default_code.util.getJsonIfSuccessfulOrThrow
import default_code.util.getValueForKey
import default_code.util.toRequestBody
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.datetime.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

open class FrappeCloudBaseService(
    private val token: String,
    private val baseClient: OkHttpClient = OkHttpClient().newBuilder().addInterceptor {
        val request = it.request()
        val headers = request.headers.newBuilder().add("Authorization", "Token $token").build()
        it.proceed(request.newBuilder().headers(headers).build())
    }.build(),
    private val frappeCloudUrl: HttpUrl = "https://frappecloud.com/api/method/press.api.site.login".toHttpUrl()
) {
    private val siteTokens = ConcurrentHashMap<HttpUrl, CompletableFuture<SiteToken>>()

    val client: OkHttpClient
        get() = baseClient

    suspend fun getSiteToken(siteUrl: HttpUrl): SiteToken {
        val siteToken = siteTokens.getValueForKey(siteUrl) { url ->
            val body = JsonObject(mapOf("name" to JsonPrimitive(url.host))).toRequestBody()
            val token = Request.Builder()
                .post(body)
                .url(frappeCloudUrl)
                .send {
                    getJsonIfSuccessfulOrThrow<JsonObject>()["message"]!!.jsonObject["sid"]!!.jsonPrimitive.content
                }
            SiteToken(
                token = token,
                expiresIn = now.plusHours(3 * 24 - 1),
            )
        }
        return if (siteToken.isExpired()) {
            siteTokens.remove(siteUrl)
            getSiteToken(siteUrl)
        } else
            siteToken
    }

    data class SiteToken(
        val token: String,
        val expiresIn: LocalDateTime,
    ) {
        fun isExpired() = expiresIn < now
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    protected suspend fun <T> Request.Builder.send(responseHandler: Response.() -> T): T =
        baseClient.newCall(this.build()).executeAsync().use { responseHandler(it) }

    companion object {
        private val now get() = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        private fun LocalDateTime.plusHours(hours: Long) =
            toJavaLocalDateTime().plusHours(hours).toKotlinLocalDateTime()
    }

}