package com.camlab.camlink.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * The Wi-Fi link the camera should be reachable on.
 *
 * Samsung phones keep the camera's access point associated even when it has no
 * internet, but send most app traffic over mobile data instead. Every socket we
 * open for the camera is bound to this network explicitly so the camera is
 * reached even while the phone's default route still points at mobile data.
 */
data class WifiLink(
    val network: Network,
    val interfaceName: String,
    val address: String?,
    val prefixLength: Int?,
    val gateway: String?,
    val dns: List<String>,
    val linkAddresses: List<String>
) {
    val subnetLabel: String
        get() {
            val parts = address?.split('.') ?: return "local subnet"
            return if (parts.size == 4) {
                "${parts[0]}.${parts[1]}.${parts[2]}.0/24"
            } else {
                "local subnet"
            }
        }
}

/** Pick the Wi-Fi network that most likely carries the camera. */
fun findWifiLink(context: Context): WifiLink? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val wifiNetworks = cm.allNetworks.filter { network ->
        cm.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    if (wifiNetworks.isEmpty()) return null

    val active = cm.activeNetwork
    val chosen = wifiNetworks.firstOrNull { it == active }
        ?: wifiNetworks.firstOrNull { network ->
            cm.getLinkProperties(network)?.routes?.any { it.isDefaultRoute } == true
        }
        ?: wifiNetworks.firstOrNull { network ->
            cm.getLinkProperties(network)?.linkAddresses
                ?.any { it.address is Inet4Address } == true
        }
        ?: wifiNetworks.first()

    val lp = cm.getLinkProperties(chosen) ?: return null
    val ipv4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
    val gateway = lp.routes
        .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
        ?.gateway
        ?.hostAddress

    return WifiLink(
        network = chosen,
        interfaceName = lp.interfaceName.orEmpty(),
        address = ipv4?.address?.hostAddress,
        prefixLength = ipv4?.prefixLength,
        gateway = gateway,
        dns = lp.dnsServers.mapNotNull { (it as? Inet4Address)?.hostAddress },
        linkAddresses = lp.linkAddresses.map {
            "${it.address.hostAddress}/${it.prefixLength}"
        }
    )
}

/** Socket factory that pins every connection to one Android network. */
internal class WifiBoundSocketFactory(private val network: Network) : SocketFactory() {

    private fun bound(socket: Socket): Socket {
        network.bindSocket(socket)
        return socket
    }

    override fun createSocket(): Socket = bound(Socket())

    override fun createSocket(host: String, port: Int): Socket =
        bound(Socket()).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket = bound(Socket()).apply {
        bind(InetSocketAddress(localHost, localPort))
        connect(InetSocketAddress(host, port))
    }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        bound(Socket()).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = bound(Socket()).apply {
        bind(InetSocketAddress(localAddress, localPort))
        connect(InetSocketAddress(address, port))
    }
}
