package io.github.miniaudiobookshelf.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

data class UserAccess(
    val userId: String = "",
    val canDownload: Boolean = true,
)

object UserAccessParser {
    fun parse(json: String): UserAccess {
        val root = try {
            ApiClient.json.parseToJsonElement(json) as? JsonObject
        } catch (_: Exception) {
            return UserAccess()
        } ?: return UserAccess()
        val user = root["user"] as? JsonObject ?: return UserAccess()
        val id = (user["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val permissions = user["permissions"] as? JsonObject
        val download = permissions?.get("download") as? JsonPrimitive
        val canDownload = download?.booleanOrNull
            ?: download?.contentOrNull?.toBooleanStrictOrNull()
            ?: true
        return UserAccess(userId = id, canDownload = canDownload)
    }
}
