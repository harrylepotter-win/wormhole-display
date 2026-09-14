package io.github.pgodlews.wormhole

import android.content.Context

data class ConnectionEntry(
    val clientName: String,
    val timestamp: Long,
    val durationSeconds: Long
) {
    fun serialize(): String = "$timestamp|$durationSeconds|${escape(clientName)}"

    companion object {
        private fun escape(s: String): String =
            s.replace("%", "%25").replace("|", "%7C").replace("\n", "%0A")

        private fun unescape(s: String): String =
            s.replace("%7C", "|").replace("%0A", "\n").replace("%25", "%")

        fun deserialize(str: String): ConnectionEntry? {
            val parts = str.split("|", limit = 3)
            if (parts.size != 3) return null
            val ts = parts[0].toLongOrNull() ?: return null
            val dur = parts[1].toLongOrNull() ?: return null
            val name = unescape(parts[2])
            return ConnectionEntry(name, ts, dur)
        }
    }
}

class ConnectionHistory(context: Context) {
    private val prefs = context.getSharedPreferences("connection_history", Context.MODE_PRIVATE)

    fun getRecent(limit: Int = 5): List<ConnectionEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split("\n")
            .mapNotNull { ConnectionEntry.deserialize(it) }
            .take(limit)
    }

    fun recordConnection(clientName: String, timestamp: Long, durationSeconds: Long) {
        val current = getRecent().toMutableList()
        current.add(0, ConnectionEntry(clientName, timestamp, durationSeconds))
        val trimmed = current.take(5)
        val serialized = trimmed.joinToString("\n") { it.serialize() }
        prefs.edit().putString(KEY_HISTORY, serialized).apply()
    }

    companion object {
        private const val KEY_HISTORY = "recent_connections"

        fun formatDuration(seconds: Long): String {
            return when {
                seconds < 5 -> "< 5s"
                seconds < 60 -> "${seconds}s"
                else -> {
                    val mins = seconds / 60
                    val secs = seconds % 60
                    if (secs > 0) "${mins}m ${secs}s" else "${mins}m"
                }
            }
        }

        fun formatTimestamp(timestamp: Long, now: Long = System.currentTimeMillis()): String {
            val diffSeconds = (now - timestamp) / 1000
            return when {
                diffSeconds < 60 -> "Just now"
                diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
                diffSeconds < 86400 -> "${diffSeconds / 3600}h ago"
                else -> {
                    val sdf = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                    sdf.format(java.util.Date(timestamp))
                }
            }
        }
    }
}
