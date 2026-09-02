package io.github.miniaudiobookshelf.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

@Serializable
data class LocalDeviceInfo(
    val clientName: String = "Mini Audiobookshelf",
    val deviceId: String,
)

@Serializable
data class LocalMediaMetadata(
    val title: String = "",
)

@Serializable
data class LocalPlaybackSession(
    val id: String,
    val libraryItemId: String,
    val episodeId: String? = null,
    val mediaType: String = "book",
    val playMethod: Int = PLAY_METHOD_LOCAL,
    val duration: Double = 0.0,
    val currentTime: Double = 0.0,
    val timeListening: Double = 0.0,
    val startTime: Double = 0.0,
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
    val displayTitle: String = "",
    val displayAuthor: String = "",
    val mediaMetadata: LocalMediaMetadata = LocalMediaMetadata(),
    val deviceInfo: LocalDeviceInfo,
) {
    companion object {
        const val PLAY_METHOD_LOCAL = 3

        fun newId(): String = UUID.randomUUID().toString()

        fun isUuidV4(id: String): Boolean {
            return try {
                val uuid = UUID.fromString(id)
                uuid.version() == 4
            } catch (_: Exception) {
                false
            }
        }
    }
}

data class LocalSyncResult(
    val id: String,
    val success: Boolean,
    val error: String = "",
)

object LocalSessionCodec {
    fun requestBody(sessions: List<LocalPlaybackSession>): String {
        val payload = LocalAllRequest(sessions = sessions)
        return ApiClient.json.encodeToString(LocalAllRequest.serializer(), payload)
    }

    fun parseResults(json: String): List<LocalSyncResult> {
        val root = try {
            ApiClient.json.parseToJsonElement(json) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val array = root["results"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val success = obj["success"]?.jsonPrimitive?.booleanOrNull ?: false
            val error = obj["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
            LocalSyncResult(id = id, success = success, error = error)
        }
    }
}

@Serializable
private data class LocalAllRequest(
    val sessions: List<LocalPlaybackSession>,
)
