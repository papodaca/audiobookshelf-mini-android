package io.github.miniaudiobookshelf.core

import kotlinx.serialization.Serializable

data class AuthTokens(
    val access: String,
    val refresh: String = "",
    val expiresIn: Long = -1,
) {
    companion object {
        fun parse(json: String): AuthTokens {
            val root = try {
                ApiClient.json.decodeFromString(AuthJson.serializer(), json)
            } catch (e: Exception) {
                throw ApiError.Server(e.message ?: "invalid auth json")
            }
            val expires = when {
                root.expiresIn != null && root.expiresIn >= 0 -> root.expiresIn
                root.user.expiresIn != null && root.user.expiresIn >= 0 -> root.user.expiresIn
                else -> -1L
            }
            val access = root.user.accessToken.ifBlank { root.user.token }
            if (access.isBlank()) {
                throw ApiError.Server("invalid auth json")
            }
            return AuthTokens(
                access = access,
                refresh = root.user.refreshToken,
                expiresIn = expires,
            )
        }
    }
}

@Serializable
private data class AuthJson(
    val user: AuthUserJson,
    val expiresIn: Long? = null,
)

@Serializable
private data class AuthUserJson(
    val token: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresIn: Long? = null,
)
