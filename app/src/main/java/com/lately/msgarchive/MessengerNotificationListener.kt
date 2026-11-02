package com.lately.msgarchive

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MessengerNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MESSENGER_PACKAGE || Settings.isPaused(this)) return
        val notification = sbn.notification
        // Summaries repeat their children; ongoing ones are calls and chat heads.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = notification.extras
        val styled = extras.getParcelableArrayCompat(Notification.EXTRA_MESSAGES)
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return

        val store = MessageStore.get(this)
        if (styled.isNullOrEmpty()) {
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
            save(store, conversation, conversation, false, text, sbn.postTime)
            return
        }
        for (item in styled) {
            val bundle = item as? Bundle ?: continue
            val text = bundle.getCharSequence("text")?.toString() ?: continue
            val time = bundle.getLong("time", sbn.postTime)
            val sender = bundle.getCharSequence("sender")?.toString()
                ?: bundle.getPersonCompat("sender_person")?.name?.toString()
            // MessagingStyle leaves the sender empty for the device owner's own replies.
            save(store, conversation, sender, sender == null, text, time)
        }
    }

    private fun save(
        store: MessageStore,
        conversation: String,
        sender: String?,
        outgoing: Boolean,
        text: String,
        time: Long,
    ) {
        val message = Message(conversation, sender, outgoing, text, time, "notification")
        store.insert(message, ChatParser.notificationKey(conversation, sender, time, text))
    }

    @Suppress("DEPRECATION")
    private fun Bundle.getParcelableArrayCompat(key: String): Array<out Parcelable>? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableArray(key, Parcelable::class.java) else getParcelableArray(key)

    @Suppress("DEPRECATION")
    private fun Bundle.getPersonCompat(key: String): Person? =
        if (Build.VERSION.SDK_INT >= 33) getParcelable(key, Person::class.java) else getParcelable(key)
}
