package com.camlab.camlink.net

/** File naming rules for saving camera media into the phone's gallery. */
object Naming {

    private val INVALID = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    private val MIME_EXTENSIONS = mapOf(
        "image/jpeg" to ".jpg",
        "image/jpg" to ".jpg",
        "image/png" to ".png",
        "image/gif" to ".gif",
        "image/bmp" to ".bmp",
        "image/webp" to ".webp",
        "video/mp4" to ".mp4",
        "video/mpeg" to ".mpg",
        "video/x-msvideo" to ".avi",
        "video/quicktime" to ".mov",
        "video/3gpp" to ".3gp"
    )

    fun sanitize(raw: String, fallback: String): String {
        val cleaned = INVALID.replace(raw, "_").trim().trim('.')
        return cleaned.ifBlank { fallback }
    }

    fun fileName(item: MediaItem, index: Int = 0): String {
        val base = sanitize(item.title, "camlink_${index + 1}")
        if (hasExtension(base)) return base

        val fromMime = MIME_EXTENSIONS[item.mime.lowercase()]
        if (fromMime != null) return base + fromMime

        val fromUrl = item.url.substringBefore('?').substringAfterLast('/')
        val ext = fromUrl.substringAfterLast('.', "")
        return if (ext.length in 2..5 && ext.all { it.isLetterOrDigit() }) {
            "$base.$ext"
        } else {
            base
        }
    }

    fun hasExtension(name: String): Boolean {
        val tail = name.substringAfterLast('/', name)
        val dot = tail.lastIndexOf('.')
        return dot > 0 && tail.length - dot in 2..6
    }
}
