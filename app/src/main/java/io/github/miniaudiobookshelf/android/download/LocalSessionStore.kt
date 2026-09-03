package io.github.miniaudiobookshelf.android.download

import android.content.Context
import io.github.miniaudiobookshelf.core.ApiClient
import io.github.miniaudiobookshelf.core.LocalPlaybackSession
import java.io.File
import kotlinx.serialization.Serializable

class LocalSessionStore(context: Context) {
    private val file = File(context.filesDir, "local-sessions.json")
    private val lock = Any()
    private val json = ApiClient.json

    fun unsynced(): List<LocalPlaybackSession> = synchronized(lock) {
        load().filter { !it.synced }.map { it.session }
    }

    fun get(itemId: String, episodeId: String): LocalPlaybackSession? = synchronized(lock) {
        load().map { it.session }.lastOrNull { row ->
            row.libraryItemId == itemId && (row.episodeId ?: "") == episodeId
        }
    }

    fun hasUnsynced(itemId: String, episodeId: String): Boolean = synchronized(lock) {
        load().any { row ->
            !row.synced && row.session.libraryItemId == itemId && (row.session.episodeId ?: "") == episodeId
        }
    }

    fun applyServerProgress(itemId: String, episodeId: String, currentTime: Double, duration: Double, now: Long) {
        synchronized(lock) {
            val rows = load().toMutableList()
            val index = rows.indexOfLast { row ->
                row.session.libraryItemId == itemId && (row.session.episodeId ?: "") == episodeId
            }
            if (index < 0 || !rows[index].synced) return
            val current = rows[index].session
            rows[index] = rows[index].copy(
                session = current.copy(
                    currentTime = currentTime,
                    duration = if (duration > 0) duration else current.duration,
                    updatedAt = now,
                ),
                synced = true,
            )
            save(rows)
        }
    }

    fun upsert(session: LocalPlaybackSession) {
        synchronized(lock) {
            val rows = load().toMutableList()
            val index = rows.indexOfFirst { it.session.id == session.id }
            val row = StoredSession(session = session, synced = false)
            if (index >= 0) rows[index] = row else rows.add(row)
            save(rows)
        }
    }

    fun addListened(id: String, position: Double, delta: Double, duration: Double, now: Long) {
        synchronized(lock) {
            val rows = load().toMutableList()
            val index = rows.indexOfFirst { it.session.id == id }
            if (index < 0) return
            val current = rows[index].session
            rows[index] = StoredSession(
                session = current.copy(
                    currentTime = position,
                    duration = duration,
                    timeListening = current.timeListening + delta.coerceAtLeast(0.0),
                    updatedAt = now,
                ),
                synced = false,
            )
            save(rows)
        }
    }

    fun markSynced(ids: Collection<String>) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            val rows = load().map { row ->
                if (row.session.id in ids) row.copy(synced = true) else row
            }
            save(rows)
        }
    }

    private fun load(): List<StoredSession> {
        if (!file.isFile) return emptyList()
        return try {
            json.decodeFromString(StoredFile.serializer(), file.readText()).sessions
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(rows: List<StoredSession>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(StoredFile.serializer(), StoredFile(rows)))
    }
}

@Serializable
private data class StoredFile(
    val sessions: List<StoredSession> = emptyList(),
)

@Serializable
private data class StoredSession(
    val session: LocalPlaybackSession,
    val synced: Boolean = false,
)
