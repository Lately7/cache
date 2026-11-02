package com.lately.msgarchive

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    private lateinit var store: MessageStore
    private lateinit var status: TextView
    private lateinit var search: EditText
    private val adapter = RowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = MessageStore.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        status = TextView(this).apply { textSize = 14f }
        root.addView(status)

        root.addView(button("Bật đọc thông báo") {
            startActivity(Intent(SystemSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("Bật Trợ năng") {
            startActivity(Intent(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(Switch(this).apply {
            text = "Tạm dừng lưu"
            isChecked = Settings.isPaused(this@MainActivity)
            setOnCheckedChangeListener { _, paused ->
                Settings.setPaused(this@MainActivity, paused)
                refresh()
            }
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Xuất JSON") { startExport() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Xoá tất cả") { confirmDelete() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)

        search = EditText(this).apply {
            hint = "Tìm trong tin nhắn"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = refresh()
            })
        }
        root.addView(search)

        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                val row = this@MainActivity.adapter.getItem(position)
                startActivity(
                    Intent(this@MainActivity, ConversationActivity::class.java)
                        .putExtra(ConversationActivity.EXTRA_NAME, row.conversation),
                )
            }
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
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

    private fun refresh() {
        val notifications = isListenerEnabled()
        val accessibility = isAccessibilityEnabled()
        val rows = store.conversations()
        status.text = buildString {
            append(if (Settings.isPaused(this@MainActivity)) "Đang tạm dừng" else "Đang lưu")
            append(" · Thông báo: ").append(if (notifications) "bật" else "tắt")
            append(" · Trợ năng: ").append(if (accessibility) "bật" else "tắt")
            append(" · ").append(rows.sumOf { it.count }).append(" tin")
        }
        val term = search.text.toString().trim()
        adapter.rows = if (term.isEmpty()) {
            rows.map { Row(it.name, "${it.count} tin · ${it.lastBody}", it.lastAt) }
        } else {
            store.search(term).map { Row(it.conversation, it.body, it.timestamp) }
        }
        adapter.notifyDataSetChanged()
    }

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
            val count = contentResolver.openOutputStream(uri)!!.writer().use { store.exportJson(it) }
            "Đã xuất $count tin"
        } catch (e: java.io.IOException) {
            "Xuất thất bại: ${e.message}"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Xoá toàn bộ tin đã lưu?")
            .setMessage("Không thể hoàn tác. Nên xuất JSON trước.")
            .setPositiveButton("Xoá") { _, _ ->
                store.deleteAll()
                refresh()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class Row(val conversation: String, val detail: String, val time: Long)

    private inner class RowAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()
        private val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val view = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                addView(TextView(context).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 16f })
                addView(TextView(context).apply { maxLines = 2; textSize = 14f })
            }
            (view.getChildAt(0) as TextView).text = row.conversation
            (view.getChildAt(1) as TextView).text = "${format.format(Date(row.time))} · ${row.detail}"
            return view
        }
    }

    private companion object {
        const val REQUEST_EXPORT = 1
    }
}
