package com.camlab.camlink.net

import java.net.NetworkInterface

/**
 * The small, read-only introduction the original Samsung SMART CAMERA app sends
 * to a camera in MobileLink mode.
 *
 * In MobileLink mode the WB1100F creates its own access point and its HTTP
 * server answers every DLNA request with `401 Unauthorised` until a client has
 * introduced itself the Samsung way. The app starts with a plain GET such as:
 *
 * ```
 * GET /smp_2_ HTTP/1.0
 * User-Agent: SEC_RVF_ML_<mac>
 * Access-Method: manual
 * ```
 *
 * The camera then shows an "allow this device" prompt. [SamsungLink] only
 * builds headers and paths; every request made with them is a GET, so nothing
 * on the camera can change.
 */
object SamsungLink {

    /** MAC the reference clients send when the real one is unavailable. */
    const val PLACEHOLDER_MAC = "00:00:00:00:00:00"

    /**
     * The phone's Wi-Fi MAC. Android 10+ hides it from apps, so this usually
     * falls back to [PLACEHOLDER_MAC], which the camera's protocol accepts.
     */
    fun phoneMac(): String {
        val fromInterface = runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .filter { it.name.startsWith("wlan") }
                .mapNotNull { it.hardwareAddress }
                .firstOrNull()
                ?.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
        }.getOrNull()
        return fromInterface?.takeIf { it.isNotBlank() } ?: PLACEHOLDER_MAC
    }

    /** Headers for the DLNA retry after the camera has seen the handshake. */
    fun dlnaHeaders(mac: String): Map<String, String> = mapOf(
        "User-Agent" to "SEC_RVF_ML_$mac",
        "Access-Method" to "manual"
    )

    /** Headers for the handshake itself, including the caller's address. */
    fun handshakeHeaders(mac: String, phoneAddress: String?): Map<String, String> =
        buildMap {
            put("User-Agent", "SEC_RVF_ML_$mac")
            put("Access-Method", "manual")
            put("HOST-Mac", mac)
            if (!phoneAddress.isNullOrBlank()) put("HOST-Address", phoneAddress)
        }

    /** Paths the Samsung app asks for, in the order it asks for them. */
    val HANDSHAKE_PATHS = listOf("/smp_2_", "/mode/control")

    /** Ports where Samsung camera HTTP services have been seen. */
    val PORTS = listOf(49152, 7676, 7788, 49153)
}
