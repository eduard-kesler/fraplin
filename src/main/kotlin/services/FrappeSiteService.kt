package com.fraplin.services

import com.fraplin.models.*
import com.fraplin.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class FrappeSiteService(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient,
) {
    private val json: Json = Json { ignoreUnknownKeys = true }

    constructor(
        siteUrl: HttpUrl,
        userApiToken: String,
        httpClient: OkHttpClient = OkHttpClient(),
    ) : this(
        baseUrl = siteUrl,
        client = httpClient.newBuilder().addInterceptor {
            it.proceed(it.request().newBuilder().header("Authorization", "token $userApiToken").build())
        }.build()
    )

    suspend fun getDocTypes(additionalInfo: Set<DocTypeInfo>): Flow<DocType> =
        coroutineScope {
            withContext(Dispatchers.IO) {
                val docTypes = async {
                    loadBatches(docType = "DocType", batchSize = 1000) {
                        addQueryParameter("fields", getFilterList<DocTypeRaw>())
                    }.map { json.decodeFromJsonElement<DocTypeRaw>(it) }
                }
                val docFields = async {
                    loadBatches(docType = "DocField", batchSize = 1000) {
                        addQueryParameter("parent", "DocType")
                        addQueryParameter("fields", getFilterList<DocFieldRaw>())
                    }.map { json.decodeFromJsonElement<DocFieldRaw>(it) }
                }
                val docCustomFields = async {
                    loadBatches(docType = "Custom Field", batchSize = 1000) {
                        addQueryParameter("fields", getFilterList<DocCustomFieldRaw>())
                    }.map { json.decodeFromJsonElement<DocCustomFieldRaw>(it) }
                }
                val additionalInfoMap = additionalInfo.associateBy { it.name }
                val allFields = (docFields.await() + docCustomFields.await()).groupBy { it.parent }
                docTypes.await().map { docType ->
                    val fields = allFields[docType.name] ?: emptyList()
                    val info = additionalInfoMap[docType.name]
                    docType.toDocType(fields = fields, additionalInfo = info)
                }.asFlow()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun loadBatches(docType: String, batchSize: Int, block: HttpUrl.Builder.() -> Unit) =
        flow {
            for (idx in range()) {
                val url = baseUrl.newBuilder {
                    addPathSegments("api/resource/$docType")
                    addQueryParameter("limit_start", (idx * batchSize).toString())
                    addQueryParameter("limit", batchSize.toString())
                    block()
                }
                val names = Request.Builder().get().url(url).send(client) {
                    getJsonIfSuccessfulOrThrow<JsonObject>(json)["data"]!!.jsonArray.map { it.jsonObject }
                }
                emit(names)
                if (names.size != batchSize) break
            }
        }.flatMapConcat { it.asFlow() }.toSet()

    companion object {
        private fun Iterable<String>.toFilterList() =
            toSet().joinToString(separator = ",", prefix = "[", postfix = "]") { "\"$it\"" }

        private inline fun <reified T : Any> getFilterList() = getAllSerialNames<T>().toFilterList()
    }
}
