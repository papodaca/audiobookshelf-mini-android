package io.github.miniaudiobookshelf.android.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request

class CoverArtLoader(
    private val clientProvider: () -> OkHttpClient,
) {
    fun load(url: String, maxPx: Int): ByteArray? {
        if (url.isEmpty()) return null
        val bytes = if (url.startsWith("file:", ignoreCase = true)) {
            val path = Uri.parse(url).path ?: return null
            val file = File(path)
            if (!file.isFile) return null
            file.readBytes()
        } else {
            val request = Request.Builder().url(url).build()
            clientProvider().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.bytes() ?: return null
            }
        }
        return downsample(bytes, maxPx.coerceAtLeast(64))
    }

    private fun downsample(bytes: ByteArray, maxPx: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return bytes
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    companion object {
        fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
            var sample = 1
            var w = width
            var h = height
            while (w / 2 >= maxPx && h / 2 >= maxPx) {
                w /= 2
                h /= 2
                sample *= 2
            }
            return sample
        }
    }
}
