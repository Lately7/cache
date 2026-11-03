package com.lately.msgarchive

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MessengerNotificationListener : NotificationListenerService() {

    /** Messages that arrived while the process was dead are still in the shade. */
    override fun onListenerConnected() {
        try {
            activeNotifications?.forEach { onNotificationPosted(it) }
        } catch (e: RuntimeException) {
            Diagnostics.record(this, e)
        }
    }

    /** Vendor cleaners can drop the connection without turning the permission off, and it may not come back alone. */
    override fun onListenerDisconnected() {
        requestRebind(ComponentName(this, MessengerNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != MESSENGER_PACKAGE || Settings.isPaused(this)) return
        // Extras are another app's data; a malformed one must not take the listener down.
        try {
            handle(sbn)
            Settings.noteNotification(this)
        } catch (e: RuntimeException) {
            Diagnostics.record(this, e)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val notification = sbn.notification
        // Summaries repeat their children; ongoing ones are calls and chat heads.
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = notification.extras
        val styled = extras.getParcelableArrayCompat(Notification.EXTRA_MESSAGES)
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return

        val store = MessageStore.get(this)
        val conversation = store.threadForNotification(title, notification.shortcutId)
        saveAvatar(conversation, notification.getLargeIcon())
        val threadId = if (notification.shortcutId == null) "không có mã" else "có mã"
        Diagnostics.trace(this, "Thông báo $title ($threadId): vào $conversation")

        if (styled.isNullOrEmpty()) {
            // Without message data this may be a missed call or an app prompt rather than a chat.
            if (notification.category != Notification.CATEGORY_MESSAGE) return
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
            save(store, conversation, conversation, false, text, sbn.postTime)
            return
        }
        for (item in styled) {
            val bundle = item as? Bundle ?: continue
            val text = bundle.getCharSequence("text")?.toString() ?: continue
            val time = bundle.getLong("time", sbn.postTime)
            val person = bundle.getPersonCompat("sender_person")
            val sender = bundle.getCharSequence("sender")?.toString() ?: person?.name?.toString()
            if (sender != null) saveAvatar(sender, person?.icon)
            // MessagingStyle leaves the sender empty for the device owner's own replies.
            save(store, conversation, sender, sender == null, text, time, bundle)
        }
    }

    private fun saveAvatar(name: String, icon: Icon?) {
        if (icon == null || AvatarStore.has(this, name)) return
        // The icon can point at Messenger's private files; an unreadable one just means no avatar yet.
        val drawable = try {
            icon.loadDrawable(this)
        } catch (_: SecurityException) {
            null
        } ?: return
        AvatarStore.saveDrawable(this, name, drawable)
    }

    private fun save(
        store: MessageStore,
        conversation: String,
        sender: String?,
        outgoing: Boolean,
        text: String,
        time: Long,
        bundle: Bundle? = null,
    ) {
        val type = bundle?.getCharSequence("type")?.toString()
        val uri = bundle?.getUriCompat("uri")
        val kind = when {
            uri == null -> null
            type?.startsWith("video/") == true -> ChatParser.KIND_VIDEO
            type?.startsWith("image/") == true -> ChatParser.KIND_IMAGE
            else -> ChatParser.KIND_FILE
        }
        val message = Message(conversation, sender, outgoing, text, time, "notification", kind)
        val key = ChatParser.notificationKey(conversation, sender, time, text)
        val added = store.insert(message, key)
        if (added && kind == ChatParser.KIND_IMAGE && uri != null) {
            store.idForKey(key)?.let { MediaStoreFiles.copy(this, it, uri) }
        }
    }

    @Suppress("DEPRECATION")
    private fun Bundle.getUriCompat(key: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelable(key, Uri::class.java) else getParcelable(key)

    @Suppress("DEPRECATION")
    private fun Bundle.getParcelableArrayCompat(key: String): Array<out Parcelable>? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableArray(key, Parcelable::class.java) else getParcelableArray(key)

    @Suppress("DEPRECATION")
    private fun Bundle.getPersonCompat(key: String): Person? =
        if (Build.VERSION.SDK_INT >= 33) getParcelable(key, Person::class.java) else getParcelable(key)
}
