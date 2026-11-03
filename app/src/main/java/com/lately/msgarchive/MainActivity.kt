package com.lately.msgarchive

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var store: MessageStore
    private lateinit var colors: Palette
    private lateinit var banner: TextView
    private lateinit var panel: LinearLayout
    private lateinit var panelStatus: TextView
    private lateinit var search: EditText
    private lateinit var empty: TextView
    private val adapter = RowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        store = MessageStore.get(this)
        colors = Palette(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(colors.background)
        }
        root.addView(header())
        banner = TextView(this).apply {
            textSize = 14f
            setTextColor(colors.onBubbleOut)
            background = rounded(colors.bubbleOut, 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { togglePanel(true) }
        }
        root.addView(banner, margins(dp(16), 0, dp(16), dp(10)))
        panel = settingsPanel()
        root.addView(panel, margins(dp(16), 0, dp(16), dp(10)))

        search = EditText(this).apply {
            hint = "Tìm trong tin nhắn"
            isSingleLine = true
            textSize = 16f
            setTextColor(colors.text)
            setHintTextColor(colors.muted)
            background = rounded(colors.field, 20)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = refresh()
            })
        }
        root.addView(search, margins(dp(16), 0, dp(16), dp(10)))

        root.addView(divider(colors))

        empty = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(colors.muted)
            setPadding(dp(32), dp(48), dp(32), 0)
        }
        root.addView(empty)

        root.addView(
            ListView(this).apply {
                adapter = this@MainActivity.adapter
                divider = null
                setOnItemLongClickListener { _, _, position, _ ->
                    confirmDeleteChat(this@MainActivity.adapter.getItem(position).conversation)
                    true
                }
                setOnItemClickListener { _, _, position, _ ->
                    val row = this@MainActivity.adapter.getItem(position)
                    startActivity(
                        Intent(this@MainActivity, ConversationActivity::class.java)
                            .putExtra(ConversationActivity.EXTRA_NAME, row.conversation),
                    )
                }
            },
            LinearLayout.LayoutParams(-1, 0, 1f),
        )
        setContentView(root)
        matchSystemBars()
        // Without this the "still saving" notification stays hidden, though capture works either way.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    @Deprecated("Activity result API from the framework; AndroidX is deliberately not used")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST_EXPORT && resultCode == RESULT_OK && uri != null) export(uri)
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(8), dp(10))
        addView(
            TextView(context).apply {
                text = "Đoạn chat"
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colors.text)
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        addView(
            TextView(context).apply {
                text = "⚙"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(colors.text)
                contentDescription = "Cài đặt"
                setOnClickListener { togglePanel(panel.visibility != View.VISIBLE) }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
    }

    private fun settingsPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        background = rounded(colors.surface, 14)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        panelStatus = TextView(context).apply {
            textSize = 13f
            setTextColor(colors.muted)
        }
        addView(panelStatus)
        addView(action("Bật đọc thông báo") {
            startActivity(Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        addView(action("Bật Trợ năng") {
            startActivity(Intent(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        addView(action("Xuất JSON") { startExport() })
        addView(action("Xoá tất cả") { confirmDelete() })
        addView(action("Xem lỗi gần nhất") { showText("Lỗi gần nhất", Diagnostics.lastError(context)) })
        addView(action("Xem nhật ký lưu tin") { showText("Nhật ký lưu tin", Diagnostics.traceLog(context)) })
        addView(action("Ghi cấu trúc màn hình Messenger") {
            Settings.requestDump(context)
            Toast.makeText(context, "Mở một đoạn chat có ảnh trong Messenger, rồi quay lại bấm Xem cấu trúc", Toast.LENGTH_LONG).show()
        })
        addView(action("Xem cấu trúc đã ghi") { showText("Cấu trúc màn hình", Diagnostics.lastDump(context)) })
        addView(Switch(context).apply {
            text = "Tạm dừng lưu"
            textSize = 15f
            setTextColor(colors.text)
            setPadding(0, dp(8), 0, dp(4))
            isChecked = Settings.isPaused(context)
            setOnCheckedChangeListener { _, paused ->
                Settings.setPaused(context, paused)
                refresh()
            }
        })
    }

    private fun action(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(colors.bubbleOut)
        setPadding(0, dp(10), 0, dp(10))
        setOnClickListener { onClick() }
    }

    private fun togglePanel(open: Boolean) {
        panel.visibility = if (open) View.VISIBLE else View.GONE
    }

    private fun refresh() {
        val notifications = isListenerEnabled()
        val accessibility = isAccessibilityEnabled()
        val rows = store.conversations()
        val paused = Settings.isPaused(this)

        banner.visibility = if (paused || !notifications || !accessibility) View.VISIBLE else View.GONE
        banner.text = when {
            paused -> "Đang tạm dừng lưu. Chạm để mở cài đặt."
            !accessibility -> "Trợ năng đang tắt nên không lưu được tin đã gửi, ảnh và avatar. Máy có thể tự tắt khi dọn app chạy nền. Chạm để bật lại."
            else -> "Chưa bật đọc thông báo nên có thể sót tin nhận. Chạm để bật."
        }
        panelStatus.text = buildString {
            append("Thông báo: ").append(if (notifications) "bật" else "tắt")
            append(" · Trợ năng: ").append(
                when {
                    !accessibility -> "tắt"
                    MessengerAccessibilityService.connected -> "bật"
                    else -> "bật nhưng không chạy, hãy tắt rồi bật lại"
                },
            )
            append(" · ").append(rows.sumOf { it.count }).append(" tin đã lưu")
            val capture = Settings.lastCapture(this@MainActivity)
            append("\nLần đọc màn hình cuối: ")
            append(if (capture == null) "chưa có" else "${stamp(Settings.lastCaptureAt(this@MainActivity))}, $capture")
            val notified = Settings.lastNotificationAt(this@MainActivity)
            append("\nThông báo Messenger cuối: ").append(if (notified == 0L) "chưa có" else stamp(notified))
        }

        val term = search.text.toString().trim()
        adapter.rows = if (term.isEmpty()) {
            rows.map { Row(it.name, it.peer, (if (it.lastOutgoing) "Bạn: " else "") + it.lastBody, it.lastAt) }
        } else {
            store.search(term).map { Row(it.conversation, it.sender, it.body, it.timestamp) }
        }
        adapter.notifyDataSetChanged()
        empty.visibility = if (adapter.rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (term.isEmpty()) "Chưa có tin nào. Mở một đoạn chat trong Messenger để bắt đầu lưu." else "Không tìm thấy tin nào."
    }

    private fun stamp(time: Long) = SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()).format(Date(time))

    private fun isListenerEnabled(): Boolean {
        val enabled = SystemSettings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.contains(ComponentName(this, MessengerNotificationListener::class.java).flattenToString())
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = SystemSettings.Secure.getString(
            contentResolver,
            SystemSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.contains(ComponentName(this, MessengerAccessibilityService::class.java).flattenToString())
    }

    private fun startExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "messenger-archive.json")
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    private fun export(uri: Uri) {
        val message = try {
            val stream = contentResolver.openOutputStream(uri) ?: throw java.io.IOException("không mở được file")
            val count = stream.writer().use { store.exportJson(it) }
            "Đã xuất $count tin"
        } catch (e: java.io.IOException) {
            "Xuất thất bại: ${e.message}"
        } catch (e: RuntimeException) {
            // The chosen location belongs to another app, whose provider can refuse in its own way.
            "Xuất thất bại: ${e.message}"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showText(title: String, text: String?) {
        val body = text ?: "Chưa có dữ liệu."
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("Sao chép") { _, _ ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText(title, body))
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Xoá toàn bộ tin đã lưu?")
            .setMessage("Không thể hoàn tác. Nên xuất JSON trước.")
            .setPositiveButton("Xoá") { _, _ ->
                store.deleteAll()
                MediaStoreFiles.deleteAll(this)
                AvatarStore.deleteAll(this)
                refresh()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun confirmDeleteChat(conversation: String) {
        AlertDialog.Builder(this)
            .setTitle("Xoá đoạn chat \"$conversation\"?")
            .setMessage("Không thể hoàn tác. Mở lại đoạn chat trong Messenger thì app lưu lại những tin còn hiện trên màn hình.")
            .setPositiveButton("Xoá") { _, _ ->
                MediaStoreFiles.delete(this, store.deleteConversation(conversation))
                AvatarStore.delete(this, conversation)
                refresh()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun margins(left: Int, top: Int, right: Int, bottom: Int) =
        LinearLayout.LayoutParams(-1, -2).apply { setMargins(left, top, right, bottom) }

    private data class Row(val conversation: String, val peer: String?, val preview: String, val time: Long)

    private class Holder(val avatar: ImageView, val name: TextView, val preview: TextView, val time: TextView)

    private inner class RowAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val view = convertView ?: createRow()
            val holder = view.tag as Holder
            holder.avatar.setImageBitmap(AvatarStore.load(this@MainActivity, row.conversation, row.peer))
            holder.name.text = row.conversation
            holder.preview.text = row.preview
            holder.time.text = shortTime(row.time)
            return view
        }

        private fun createRow(): View {
            val avatar = avatarView(56)
            val name = TextView(this@MainActivity).apply {
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(colors.text)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val preview = TextView(this@MainActivity).apply {
                textSize = 14f
                setTextColor(colors.muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            val time = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(colors.muted)
                setPadding(dp(8), 0, 0, 0)
            }
            val text = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(name)
                addView(preview)
            }
            return LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(avatar)
                addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                addView(time)
                tag = Holder(avatar, name, preview, time)
            }
        }
    }

    private companion object {
        const val REQUEST_EXPORT = 1
        const val REQUEST_NOTIFICATIONS = 2
        const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
}
