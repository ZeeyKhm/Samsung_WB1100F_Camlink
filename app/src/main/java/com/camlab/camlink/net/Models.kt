package com.camlab.camlink.net

/** A UPnP service advertised in the camera's device description. */
data class UpnpService(
    val type: String,
    val controlUrl: String
)

/** The camera as seen through its UPnP device description. */
data class CameraDevice(
    val friendlyName: String,
    val modelName: String,
    val manufacturer: String,
    val ip: String,
    val descriptionUrl: String,
    val services: List<UpnpService>
) {
    val contentDirectoryUrl: String?
        get() = services.firstOrNull {
            it.type.contains("ContentDirectory", ignoreCase = true)
        }?.controlUrl

    val displayName: String
        get() = friendlyName.ifBlank { modelName.ifBlank { ip } }
}

/** One photo or video on the camera's card. */
data class MediaItem(
    val id: String,
    val title: String,
    val url: String,
    val thumbUrl: String?,
    val size: Long,
    val mime: String,
    val date: String?
) {
    val isVideo: Boolean
        get() = mime.startsWith("video/", ignoreCase = true) ||
            title.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    companion object {
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mov", "avi", "mpg", "mpeg", "3gp", "mkv", "m4v", "wmv"
        )
    }
}

data class DidlContainer(val id: String, val title: String)

data class DidlContent(
    val items: List<MediaItem>,
    val containers: List<DidlContainer>
)

data class BrowsePage(
    val content: DidlContent,
    val totalMatches: Int,
    val numberReturned: Int
)
