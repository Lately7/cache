package com.lately.msgarchive

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Attachments live in private storage and are keyed by message id. */
object MediaStoreFiles {
    private const val QUALITY = 90
    private const val BUFFER = 64 * 1024
    private const val MAX_COPY_BYTES = 50L * 1024 * 1024
    private const val MAX_SIDE = 1600

    fun folder(context: Context, source: Source) = File(context.filesDir, source.mediaFolder)

    private fun target(context: Context, id: Long, source: Source, extension: String = "jpg"): File {
        val dir = folder(context, source).apply { mkdirs() }
        return File(dir, "$id.$extension")
    }

    fun isPicture(name: String) = name.endsWith(".jpg")

    fun save(context: Context, id: Long, bitmap: Bitmap, source: Source) {
        val file = target(context, id, source)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            MessageStore.get(context, source).setMedia(id, file.name)
        } catch (_: IOException) {
            file.delete()
        }
    }

    /** Full-size copy from a notification, when the chat app shares one with listeners. */
    fun copy(context: Context, id: Long, uri: Uri, source: Source, type: String?) {
        // Any other scheme could make this app read its own private files on a notification's say-so.
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        val picture = type?.startsWith("image/") == true
        // The extension comes from a known type table, so a sender cannot choose a file name here.
        val extension = if (picture) "jpg" else MimeTypeMap.getSingleton().getExtensionFromMimeType(type) ?: "bin"
        val file = target(context, id, source, extension)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> copyLimited(input, output) }
                MessageStore.get(context, source).setMedia(id, file.name)
            }
        } catch (_: IOException) {
            file.delete()
        } catch (_: RuntimeException) {
            // Providers rethrow their own failures here, typically SecurityException or IllegalArgumentException.
            file.delete()
        }
    }

    private fun copyLimited(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            total += read
            if (total > MAX_COPY_BYTES) throw IOException("attachment too large")
            output.write(buffer, 0, read)
        }
    }

    /** Decoded for a phone screen; a full-size copy would exhaust memory while scrolling a chat. */
    fun load(context: Context, name: String, source: Source, maxSide: Int = MAX_SIDE): Bitmap? {
        val path = File(folder(context, source), name).path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    fun delete(context: Context, names: List<String>, source: Source) {
        names.forEach { File(folder(context, source), it).delete() }
    }

    fun deleteAll(context: Context, source: Source) {
        folder(context, source).deleteRecursively()
    }
}
