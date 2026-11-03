package com.lately.msgarchive

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Zip copies in Download/LuuTinBackup. Files made through MediaStore outlive an uninstall, which is
 * what a backup is for, and the app needs neither a storage permission nor Internet access.
 */
object Backup {
    private const val FOLDER = "LuuTinBackup"
    private const val PREFIX = "luutin-"
    private const val KEEP = 5
    private const val BUFFER = 64 * 1024
    private const val MAX_RESTORE_BYTES = 8L * 1024 * 1024 * 1024
    private const val DB_FOLDER = "db"
    private const val FILES_FOLDER = "files"
    private const val AVATARS = "avatars"
    private val lock = Any()

    private fun folders() = Source.entries.map { it.mediaFolder } + AVATARS

    /** Returns null when nothing changed since the last copy, unless [force] is set. */
    fun run(context: Context, force: Boolean = false): Uri? = synchronized(lock) {
        val state = Source.entries.joinToString("|") { MessageStore.get(context, it).fingerprint() }
        if (!force && state == Settings.backupState(context)) return null
        val resolver = context.contentResolver
        val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("không tạo được file sao lưu")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("không mở được file sao lưu")
            stream.use { write(context, it) }
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        prune(context)
        Settings.noteBackup(context, state)
        Diagnostics.trace(context, "Đã sao lưu: $name")
        uri
    }

    private fun write(context: Context, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            for (source in Source.entries) {
                val store = MessageStore.get(context, source)
                // A plain file copy could catch the database halfway through a write.
                val snapshot = File(context.cacheDir, "snapshot-${source.database}")
                snapshot.delete()
                try {
                    store.snapshot(snapshot)
                    addFile(zip, "$DB_FOLDER/${source.database}", snapshot)
                } finally {
                    snapshot.delete()
                }
                zip.putNextEntry(ZipEntry("${source.name.lowercase(Locale.US)}.json"))
                val writer = zip.writer()
                store.exportJson(NoClose(writer))
                writer.flush()
                zip.closeEntry()
            }
            for (folder in folders()) {
                File(context.filesDir, folder).listFiles()?.forEach { addFile(zip, "$FILES_FOLDER/$folder/${it.name}", it) }
            }
        }
    }

    /** exportJson closes its writer, which would close the whole zip. */
    private class NoClose(private val inner: java.io.Writer) : java.io.Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = inner.write(cbuf, off, len)
        override fun flush() = inner.flush()
        override fun close() = inner.flush()
    }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip, BUFFER) }
        zip.closeEntry()
    }

    private fun prune(context: Context) {
        val resolver = context.contentResolver
        val old = ArrayList<Long>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("$PREFIX%.zip", "%$FOLDER%"),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) if (c.position >= KEEP) old.add(c.getLong(0))
        }
        for (id in old) {
            resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads._ID}=?", arrayOf(id.toString()))
        }
    }

    /** Unpacks fully before touching anything, so a damaged file cannot leave the archive half replaced. */
    fun restore(context: Context, uri: Uri) = synchronized(lock) {
        val stage = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("không mở được file")
            ZipInputStream(stream).use { unpack(it, stage) }
            val main = File(stage, "$DB_FOLDER/${Source.MESSENGER.database}")
            if (!isDatabase(main)) throw IOException("đây không phải file sao lưu của app")

            val wasPaused = Settings.isPaused(context)
            Settings.setPaused(context, true)
            MessageStore.closeAll()
            for (source in Source.entries) {
                val target = context.getDatabasePath(source.database)
                val incoming = File(stage, "$DB_FOLDER/${source.database}")
                if (!incoming.exists()) continue
                File(target.path + "-journal").delete()
                incoming.copyTo(target, overwrite = true)
            }
            for (folder in folders()) {
                val target = File(context.filesDir, folder)
                val incoming = File(stage, "$FILES_FOLDER/$folder")
                target.deleteRecursively()
                if (incoming.exists()) incoming.copyRecursively(target, overwrite = true)
            }
            AvatarStore.forgetCache()
            Settings.setPaused(context, wasPaused)
            Diagnostics.trace(context, "Đã khôi phục từ bản sao lưu")
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun isDatabase(file: File): Boolean {
        if (!file.exists()) return false
        val head = ByteArray(15)
        file.inputStream().use { it.read(head) }
        return String(head, Charsets.US_ASCII) == "SQLite format 3"
    }

    /** Only names this app writes are accepted; anything else in the file is ignored, never followed. */
    private fun unpack(zip: ZipInputStream, stage: File) {
        val databases = Source.entries.map { "$DB_FOLDER/${it.database}" }
        val allowed = folders()
        var total = 0L
        while (true) {
            val entry = zip.nextEntry ?: return
            val parts = entry.name.split('/')
            val attachment = parts.size == 3 && parts[0] == FILES_FOLDER && parts[1] in allowed &&
                parts[2].isNotEmpty() && parts[2] != "." && parts[2] != ".."
            if (!entry.isDirectory && (entry.name in databases || attachment)) {
                val target = File(stage, entry.name)
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_RESTORE_BYTES) throw IOException("file sao lưu quá lớn")
                        output.write(buffer, 0, read)
                    }
                }
            }
            zip.closeEntry()
        }
    }

    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, BackupJob::class.java))
                .setPeriodic(TimeUnit.HOURS.toMillis(INTERVAL_HOURS))
                .setPersisted(true)
                .setRequiresStorageNotLow(true)
                .build(),
        )
    }

    private const val JOB_ID = 1
    private const val INTERVAL_HOURS = 6L
}

class BackupJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                Backup.run(this)
            } catch (e: IOException) {
                Diagnostics.record(this, e)
            } catch (e: RuntimeException) {
                Diagnostics.record(this, e)
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
