package com.lately.msgarchive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Avatars live in private storage, named by hash so display names never become file paths. */
object AvatarStore {
    private const val SIZE = 128
    private const val FACE_GRID = 16
    private val cache = LruCache<String, Bitmap>(64)
    private val palette = intArrayOf(0xFF0084FF.toInt(), 0xFF44BEC7.toInt(), 0xFFFA3C4C.toInt(), 0xFFD696BB.toInt(), 0xFF6699CC.toInt())

    private fun file(context: Context, name: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
        val id = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "avatars"), "$id.png")
    }

    fun has(context: Context, name: String) = file(context, name).exists()

    private fun sample(bitmap: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bitmap, FACE_GRID, FACE_GRID, true)
        val centre = FACE_GRID / 2
        // Corners are the chat background, which differs from one screen to the next.
        val inside = (0 until FACE_GRID).flatMap { y -> (0 until FACE_GRID).map { x -> x to y } }
            .filter { (x, y) -> (x - centre) * (x - centre) + (y - centre) * (y - centre) < centre * centre * 3 / 4 }
        return inside.map { (x, y) -> small.getPixel(x, y) }.toIntArray()
    }

    /** False when nothing is stored under this name, so an unknown chat never counts as a match. */
    fun matches(context: Context, name: String, picture: Bitmap): Boolean {
        if (!has(context, name)) return false
        val stored = load(context, name).copy(Bitmap.Config.ARGB_8888, false)
        return ChatParser.sameFace(sample(stored), sample(picture))
    }

    fun delete(context: Context, name: String) {
        file(context, name).delete()
        cache.remove(name)
    }

    fun deleteAll(context: Context) {
        File(context.filesDir, "avatars").deleteRecursively()
        forgetCache()
    }

    fun forgetCache() = cache.evictAll()

    fun save(context: Context, name: String, bitmap: Bitmap) {
        val target = file(context, name)
        target.parentFile?.mkdirs()
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        try {
            target.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            cache.remove(name)
        } catch (_: IOException) {
            target.delete()
        }
    }

    fun saveDrawable(context: Context, name: String, drawable: Drawable) {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, SIZE, SIZE)
        drawable.draw(Canvas(bitmap))
        save(context, name, bitmap)
    }

    /** Falls through the names in order, ending on a coloured initial like Messenger's own placeholder. */
    fun load(context: Context, vararg names: String?): Bitmap {
        val known = names.filterNotNull()
        for (name in known) {
            cache.get(name)?.let { return it }
            val path = file(context, name)
            if (path.exists()) {
                BitmapFactory.decodeFile(path.path)?.let {
                    cache.put(name, it)
                    return it
                }
            }
        }
        return placeholder(known.firstOrNull().orEmpty())
    }

    private fun placeholder(name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(palette[Math.floorMod(name.hashCode(), palette.size)])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = SIZE * 0.45f
            textAlign = Paint.Align.CENTER
        }
        val initial = name.trim().take(1).uppercase()
        canvas.drawText(initial, SIZE / 2f, SIZE / 2f - (paint.ascent() + paint.descent()) / 2, paint)
        return bitmap
    }
}
