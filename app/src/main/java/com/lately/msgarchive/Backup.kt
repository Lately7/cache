package com.lately.msgarchive

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
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
    private const val ZALO_AVATARS = "avatars-zalo"
    private val lock = Any()

    private fun folders() = Source.entries.map { it.mediaFolder } + AVATARS + ZALO_AVATARS

    /** Returns null when nothing changed since the last copy, unless [force] is set. */
    fun run(context: Context, force: Boolean = false): Uri? = synchronized(lock) {
        // Avatars are in the backup but change no message row, so they need their own part of the check.
        val pictures = listOf(AVATARS, ZALO_AVATARS).flatMap { File(context.filesDir, it).listFiles().orEmpty().toList() }
        val state = Source.entries.joinToString("|") { MessageStore.get(context, it).fingerprint() } +
            "|a:${pictures.size}:${pictures.maxOfOrNull { it.lastModified() } ?: 0L}"
        if (!force && state == Settings.backupState(context)) return null
        // A fresh install starts empty; backing that up would push the real backups out of the kept five.
        if (!force && Source.entries.all { MessageStore.get(context, it).isEmpty() }) return null
        val resolver = context.contentResolver
        removeUnfinished(context)
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
        } catch (e: Exception) {
            // Database and provider failures must not leave a hidden half-written file behind either.
            resolver.delete(uri, null, null)
            throw if (e is IOException) e else IOException(e.message, e)
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

    /** A killed process leaves its pending file hidden in Download; nothing else would ever remove it. */
    private fun removeUnfinished(context: Context) {
        val args = android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$PREFIX%.zip"))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
        }
        val stale = ArrayList<Long>()
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID), args, null)?.use { c ->
            while (c.moveToNext()) stale.add(c.getLong(0))
        }
        for (id in stale) {
            context.contentResolver.delete(
                android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                null,
                null,
            )
        }
    }

    private fun prune(context: Context) {
        val resolver = context.contentResolver
        val all = ArrayList<Pair<Long, Long>>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("$PREFIX%.zip", "%$FOLDER%"),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) all.add(c.getLong(0) to c.getLong(1))
        }
        // The largest backup is kept whatever its age: after a lost or wiped archive it may be the only full one.
        val largest = all.maxByOrNull { it.second }?.first
        val old = all.drop(KEEP).map { it.first }.filter { it != largest }
        for (id in old) {
            resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads._ID}=?", arrayOf(id.toString()))
        }
    }

    private fun staged(context: Context) = File(context.filesDir, "restore-ready")

    /**
     * Unpacks and checks a backup without touching the archive. Open databases cannot be swapped safely,
     * so the files only replace the archive when the process next starts, before anything opens them.
     */
    fun prepareRestore(context: Context, uri: Uri) = synchronized(lock) {
        val work = File(context.filesDir, "restore-work").apply { deleteRecursively(); mkdirs() }
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("không mở được file")
            ZipInputStream(stream).use { unpack(it, work) }
            for (source in Source.entries) checkDatabase(File(work, "$DB_FOLDER/${source.database}"))
            // Every folder is present in the stage, so applying it twice after a crash changes nothing.
            for (folder in folders()) File(work, "$FILES_FOLDER/$folder").mkdirs()
            val ready = staged(context)
            ready.deleteRecursively()
            if (!work.renameTo(ready)) throw IOException("không chuẩn bị được bản khôi phục")
        } finally {
            work.deleteRecursively()
        }
    }

    private fun checkDatabase(file: File) {
        if (!file.exists()) throw IOException("file sao lưu thiếu ${file.name}")
        try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val ok = db.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
                if (!ok) throw IOException("file sao lưu bị hỏng")
                if (db.version > MessageStore.VERSION) throw IOException("file sao lưu của bản app mới hơn, hãy cập nhật app trước")
            }
        } catch (e: SQLiteException) {
            throw IOException("đây không phải file sao lưu của app", e)
        }
    }

    /** Called from Application.onCreate, which runs before any service or screen can open a database. */
    fun applyPendingRestore(context: Context) {
        // Left by a restore that was killed while unpacking; nothing can be using it at process start.
        File(context.filesDir, "restore-work").deleteRecursively()
        val ready = staged(context)
        if (!ready.exists()) return
        // Each part is moved out of the stage only once it is in place, so a run killed halfway is finished
        // by the next one without redoing, or undoing, what the first already moved.
        for (source in Source.entries) {
            val incoming = File(ready, "$DB_FOLDER/${source.database}")
            if (!incoming.exists()) continue
            val target = context.getDatabasePath(source.database)
            target.parentFile?.mkdirs()
            for (suffix in listOf("-journal", "-wal", "-shm")) File(target.path + suffix).delete()
            target.delete()
            if (!incoming.renameTo(target)) return
        }
        for (folder in folders()) {
            val incoming = File(ready, "$FILES_FOLDER/$folder")
            if (!incoming.exists()) continue
            val target = File(context.filesDir, folder)
            target.deleteRecursively()
            if (!incoming.renameTo(target)) return
        }
        ready.deleteRecursively()
        Diagnostics.trace(context, "Đã khôi phục từ bản sao lưu")
    }

    fun restorePending(context: Context) = staged(context).exists()

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
