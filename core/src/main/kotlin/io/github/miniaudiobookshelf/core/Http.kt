package io.github.miniaudiobookshelf.core

data class HttpResponse(
    val status: Int,
    val body: String = "",
    val location: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

interface HttpTransport {
    suspend fun send(
        method: String,
        uri: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpResponse
}

interface TokenProvider {
    fun accessToken(): String?
    suspend fun freshToken(): String? = accessToken()
}

interface AuthEndpoints {
    suspend fun login(username: String, password: String): String
    suspend fun refresh(refreshToken: String): String
    suspend fun logout()
}
