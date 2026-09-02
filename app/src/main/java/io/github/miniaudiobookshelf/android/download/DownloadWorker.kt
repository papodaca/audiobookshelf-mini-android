package io.github.miniaudiobookshelf.android.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.miniaudiobookshelf.android.MiniAbsApplication
import io.github.miniaudiobookshelf.android.R
import io.github.miniaudiobookshelf.core.ApiClient
import io.github.miniaudiobookshelf.core.ApiError
import io.github.miniaudiobookshelf.core.DownloadFiles
import io.github.miniaudiobookshelf.core.DownloadPolicy
import io.github.miniaudiobookshelf.core.DownloadRecord
import io.github.miniaudiobookshelf.core.DownloadSidecar
import io.github.miniaudiobookshelf.core.DownloadStatus
import io.github.miniaudiobookshelf.core.SidecarChapter
import io.github.miniaudiobookshelf.core.SidecarTrack
import io.github.miniaudiobookshelf.core.DownloadSidecarJson
import android.os.SystemClock
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private var channelReady = false

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID).orEmpty()
        val episodeId = inputData.getString(KEY_EPISODE_ID).orEmpty()
        if (itemId.isEmpty()) return Result.failure()
        val app = applicationContext as MiniAbsApplication
        val repo = app.downloads
        val api = app.session.apiClient() ?: return fail(repo, itemId, episodeId, "Not signed in")
        val tokens = app.session.tokenProvider()
        setForeground(foregroundInfo(applicationContext.getString(R.string.download_starting), 0, 0))
        return try {
            download(app, repo, api, tokens?.accessToken(), itemId, episodeId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ApiError.Unauthorized) {
            fail(repo, itemId, episodeId, "Sign-in expired")
        } catch (_: Exception) {
            fail(repo, itemId, episodeId, "Download failed")
        }
    }

    private suspend fun download(
        app: MiniAbsApplication,
        repo: DownloadRepository,
        api: ApiClient,
        token: String?,
        itemId: String,
        episodeId: String,
    ): Result {
        val item = withContext(Dispatchers.IO) { api.fetchItem(itemId, episodeId.ifEmpty { null }) }
        val target = if (episodeId.isEmpty()) item else item.episodeById(episodeId) ?: item.copy(episodeId = episodeId)
        val tracks = repo.downloadableTracks(target)
        if (tracks.isEmpty()) {
            return fail(repo, itemId, episodeId, "This title has no downloadable audio files")
        }
        val dir = repo.dirFor(itemId, episodeId)
        dir.mkdirs()
        repo.fetchCover(app.session.coverOkHttpClient(), api.coverUrl(itemId), itemId, episodeId)
        val total = tracks.sumOf { it.byteSize.coerceAtLeast(0L) }
        var downloaded = 0L
        val client = app.session.downloadOkHttpClient()
        upsert(
            repo,
            itemId,
            episodeId,
            target.title.ifEmpty { item.title },
            if (episodeId.isEmpty()) item.author else item.title,
            DownloadStatus.RUNNING,
            downloaded,
            total,
            waiting = false,
            error = "",
        )
        for (track in tracks) {
            if (isStopped) {
                upsert(repo, itemId, episodeId, target.title, item.author, DownloadStatus.PAUSED, downloaded, total, false, "")
                return Result.success()
            }
            val dest = repo.audioFile(itemId, episodeId, track.index)
            val part = repo.partFile(itemId, episodeId, track.index)
            val url = api.fileDownloadUrl(itemId, track.ino)
            if (url.contains("token=")) {
                return fail(repo, itemId, episodeId, "Download URL included a query token")
            }
            if (url.contains("/public/session/")) {
                return fail(repo, itemId, episodeId, "Refusing to download a session track URL")
            }
            val existing = if (dest.isFile) dest.length() else 0L
            if (track.byteSize > 0L && existing == track.byteSize) {
                downloaded += existing
                continue
            }
            val startAt = if (part.isFile) part.length() else 0L
            val request = Request.Builder().url(url).get().apply {
                if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token")
                if (startAt > 0L) header("Range", "bytes=$startAt-")
            }.build()
            val title = target.title.ifEmpty { item.title }
            val author = if (episodeId.isEmpty()) item.author else item.title
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw ApiError.Server("download http ${response.code}")
                    }
                    val body = response.body ?: throw ApiError.Server("empty download")
                    val append = response.code == 206 && startAt > 0L
                    val prior = tracks.takeWhile { it.index < track.index }.sumOf { it.byteSize.coerceAtLeast(0L) }
                    var copied = if (append) startAt else 0L
                    var lastNotify = 0L
                    FileOutputStream(part, append).buffered(BUFFER_BYTES).use { out ->
                        body.byteStream().buffered(BUFFER_BYTES).use { input ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                                copied += read
                                downloaded = prior + copied
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastNotify >= NOTIFY_EVERY_MS) {
                                    lastNotify = now
                                    setForeground(foregroundInfo(title, downloaded, total))
                                    upsert(repo, itemId, episodeId, title, author, DownloadStatus.RUNNING, downloaded, total, false, "")
                                }
                            }
                            out.flush()
                        }
                    }
                    downloaded = prior + copied
                    setForeground(foregroundInfo(title, downloaded, total))
                }
            }
            if (part.exists()) {
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) {
                    return fail(repo, itemId, episodeId, "Could not finalize audio file")
                }
            }
            downloaded = tracks.filter { it.index <= track.index }.sumOf { file ->
                val done = repo.audioFile(itemId, episodeId, file.index)
                if (done.isFile) done.length() else 0L
            }
            upsert(
                repo,
                itemId,
                episodeId,
                target.title.ifEmpty { item.title },
                if (episodeId.isEmpty()) item.author else item.title,
                DownloadStatus.RUNNING,
                downloaded,
                total,
                false,
                "",
            )
        }
        repo.fetchCover(app.session.coverOkHttpClient(), api.coverUrl(itemId), itemId, episodeId)
        val sidecar = DownloadSidecar(
            itemId = itemId,
            episodeId = episodeId,
            title = target.title.ifEmpty { item.title },
            author = if (episodeId.isEmpty()) item.author else item.title,
            tracks = tracks.map { track ->
                SidecarTrack(
                    index = track.index,
                    startOffset = track.startOffset,
                    duration = track.duration,
                    title = track.title,
                    mimeType = track.mimeType,
                    fileName = DownloadFiles.audioFileName(track.index),
                    byteSize = repo.audioFile(itemId, episodeId, track.index).length(),
                    ino = track.ino,
                )
            },
            chapters = target.chapters.ifEmpty { item.chapters }.map { chapter ->
                SidecarChapter(id = chapter.id, start = chapter.start, end = chapter.end, title = chapter.title)
            },
            coverFileName = DownloadRepository.COVER_NAME,
        )
        val sidecarFile = repo.sidecarFile(itemId, episodeId)
        sidecarFile.writeText(DownloadSidecarJson.encode(sidecar))
        val sizes = sidecar.tracks.associate { it.fileName to repo.audioFile(itemId, episodeId, it.index).length() }
        if (!DownloadPolicy.canPlayOffline(sidecar, sizes)) {
            return fail(repo, itemId, episodeId, "Downloaded files did not match")
        }
        upsert(
            repo,
            itemId,
            episodeId,
            sidecar.title,
            sidecar.author,
            DownloadStatus.COMPLETE,
            sidecar.tracks.sumOf { it.byteSize },
            sidecar.tracks.sumOf { it.byteSize },
            false,
            "",
            sidecarFile.absolutePath,
        )
        return Result.success()
    }

    private suspend fun upsert(
        repo: DownloadRepository,
        itemId: String,
        episodeId: String,
        title: String,
        author: String,
        status: DownloadStatus,
        downloaded: Long,
        total: Long,
        waiting: Boolean,
        error: String,
        sidecarPath: String = repo.sidecarFile(itemId, episodeId).absolutePath,
    ) {
        repo.upsert(
            DownloadRecord(
                itemId = itemId,
                episodeId = episodeId,
                title = title,
                author = author,
                status = status,
                bytesDownloaded = downloaded,
                bytesTotal = total,
                sidecarPath = sidecarPath,
                waitingUnmetered = waiting,
                error = error,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun fail(
        repo: DownloadRepository,
        itemId: String,
        episodeId: String,
        error: String,
    ): Result {
        val existing = repo.record(itemId, episodeId)
        upsert(
            repo,
            itemId,
            episodeId,
            existing?.title.orEmpty(),
            existing?.author.orEmpty(),
            DownloadStatus.FAILED,
            existing?.bytesDownloaded ?: 0L,
            existing?.bytesTotal ?: 0L,
            false,
            error,
        )
        return Result.failure()
    }

    private fun foregroundInfo(title: String, downloaded: Long, total: Long): ForegroundInfo {
        ensureChannel()
        val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.download_notification_title))
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, total <= 0)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (channelReady) return
        val manager = NotificationManagerCompat.from(applicationContext)
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(applicationContext.getString(R.string.download_channel))
            .build()
        manager.createNotificationChannel(channel)
        channelReady = true
    }

    companion object {
        const val KEY_ITEM_ID = "item_id"
        const val KEY_EPISODE_ID = "episode_id"
        private const val CHANNEL_ID = "mabs_download"
        private const val NOTIFICATION_ID = 42
        private const val BUFFER_BYTES = 256 * 1024
        private const val NOTIFY_EVERY_MS = 500L

        fun uniqueName(itemId: String, episodeId: String): String = "download:$itemId:$episodeId"

        fun enqueue(context: Context, itemId: String, episodeId: String, allowMetered: Boolean, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                .build()
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_ITEM_ID to itemId, KEY_EPISODE_ID to episodeId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(itemId, episodeId), policy, request)
        }

        fun cancel(context: Context, itemId: String, episodeId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(itemId, episodeId))
        }
    }
}
