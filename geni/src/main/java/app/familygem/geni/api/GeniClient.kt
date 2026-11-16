package app.familygem.geni.api

import app.familygem.geni.auth.TokenStore
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** The user must log in to Geni (again). */
class GeniAuthRequiredException : Exception("Geni login required")

/** Geni answered with an error. */
class GeniApiException(val code: Int, message: String) : Exception(message)

/**
 * Client of the Geni API.
 * @param baseUrl API base, like https://www.geni.com/api/
 * @param now Current time in milliseconds, to check token expiry
 */
/** Profile fields requested for mapping. */
const val PROFILE_FIELDS = "id,guid,first_name,middle_name,last_name,maiden_name,suffix,display_name,name,gender,is_alive," +
        "occupation,about_me,cause_of_death,nicknames,birth,death,baptism,burial,updated_at,deleted,merged_into,mugshot_urls"

/** IDs per batch request. */
private const val BATCH_SIZE = 50

class GeniClient(
    private val baseUrl: HttpUrl,
    private val tokens: TokenStore,
    private val limiter: RateLimiter,
    private val http: OkHttpClient = OkHttpClient(),
    private val now: () -> Long = System::currentTimeMillis
) {
    private val gson = Gson()

    /** Profile of the logged-in user. */
    suspend fun currentProfile(): GeniProfile = gson.fromJson(getJson("profile"), GeniProfile::class.java)

    /** IDs in the immediate family of a profile. */
    suspend fun immediateFamily(profileId: String): ImmediateFamily = ImmediateFamily.parse(getJson("$profileId/immediate-family"))

    /** Profiles with the mapped fields, each with its raw JSON, by Geni ID. */
    suspend fun profiles(ids: List<String>): Map<String, Pair<GeniProfile, String>> =
        batch(ids, GeniProfile::class.java, { it.id }) { "profile?ids=$it&fields=$PROFILE_FIELDS" }

    /** Unions, each with its raw JSON, by Geni ID. */
    suspend fun unions(ids: List<String>): Map<String, Pair<GeniUnion, String>> =
        batch(ids, GeniUnion::class.java, { it.id }) { "union?ids=$it" }

    /** Requests the IDs in chunks of [BATCH_SIZE] and parses each result. */
    private suspend fun <T> batch(ids: List<String>, type: Class<T>, idOf: (T) -> String, path: (String) -> String): Map<String, Pair<T, String>> {
        val result = linkedMapOf<String, Pair<T, String>>()
        for (chunk in ids.distinct().chunked(BATCH_SIZE)) {
            for (raw in rawResults(getJson(path(chunk.joinToString(","))))) {
                val item = gson.fromJson(raw, type)
                result[idOf(item)] = item to raw
            }
        }
        return result
    }

    /** Raw JSON response of a GET request to an API path relative to the base, like "profile-1/immediate-family". */
    suspend fun getJson(path: String): String {
        val token = tokens.get()?.takeIf { it.expiresAtMillis > now() } ?: throw GeniAuthRequiredException()
        val request = Request.Builder()
            .url(baseUrl.resolve(path)!!)
            .header("Authorization", "Bearer ${token.token}")
            .build()
        var retried = false
        while (true) {
            limiter.acquire()
            val (code, body) = withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { response ->
                    updateLimiter(response)
                    response.code to response.body.string()
                }
            }
            when {
                code in 200..299 -> return body
                code == 401 -> {
                    tokens.clear()
                    throw GeniAuthRequiredException()
                }
                code == 429 && !retried -> {
                    retried = true
                    limiter.update(limit = null, remaining = 0, windowSeconds = null)
                }
                else -> throw GeniApiException(code, body)
            }
        }
    }

    private fun updateLimiter(response: Response) = limiter.update(
        limit = response.header("X-API-Rate-Limit")?.toIntOrNull(),
        remaining = response.header("X-API-Rate-Remaining")?.toIntOrNull(),
        windowSeconds = response.header("X-API-Rate-Window")?.toIntOrNull()
    )
}
