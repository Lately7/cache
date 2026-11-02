package com.lately.msgarchive

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class ConversationActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_NAME)
        if (name == null) {
            finish()
            return
        }
        title = name
        val messages = MessageStore.get(this).messages(name)
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val padding = (12 * resources.displayMetrics.density).toInt()

        val list = ListView(this).apply {
            fitsSystemWindows = true
            divider = null
            adapter = object : BaseAdapter() {
                override fun getCount() = messages.size
                override fun getItem(position: Int) = messages[position]
                override fun getItemId(position: Int) = position.toLong()

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val message = messages[position]
                    val view = (convertView as? TextView) ?: TextView(this@ConversationActivity).apply {
                        setPadding(padding, padding / 2, padding, padding / 2)
                        setTextIsSelectable(true)
                    }
                    view.gravity = if (message.outgoing) Gravity.END else Gravity.START
                    val who = if (message.outgoing) "Bạn" else message.sender ?: name
                    view.text = "$who · ${format.format(Date(message.timestamp))}\n${message.body}"
                    return view
                }
            }
            setSelection(messages.size - 1)
        }
        setContentView(list)
    }

    companion object {
        const val EXTRA_NAME = "name"
    }
}
