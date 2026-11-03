package com.lately.msgarchive

/** One chat app. Each keeps its own database and attachments, so a name in one never touches the other. */
enum class Source(
    val packageName: String,
    val label: String,
    val database: String,
    val mediaFolder: String,
    private val avatarPrefix: String,
) {
    MESSENGER("com.facebook.orca", "Messenger", "archive.db", "media", ""),
    ZALO("com.zing.zalo", "Zalo", "archive-zalo.db", "media-zalo", "zalo:");

    /** The same display name in both apps is usually two different people. */
    fun avatar(name: String?) = name?.let { avatarPrefix + it }

    /** Whether an avatar key, as made by [avatar], belongs to this app. */
    fun isOwnAvatar(key: String) = avatarPrefix.isNotEmpty() && key.startsWith(avatarPrefix)

    companion object {
        fun of(packageName: CharSequence?) = entries.firstOrNull { it.packageName == packageName?.toString() }
    }
}
