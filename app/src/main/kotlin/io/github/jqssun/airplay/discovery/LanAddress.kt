package io.github.jqssun.airplay.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

data class LanIpv4Link(val interfaceName: String, val address: String)

/** Active LAN addresses; Internet validation is deliberately not required for local casting. */
fun lanIpv4Links(context: Context): List<LanIpv4Link> {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return emptyList()
    return try {
        connectivity.allNetworks.flatMap { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            val lan = capabilities != null &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            if (!lan) emptyList() else {
                val properties = connectivity.getLinkProperties(network)
                val interfaceName = properties?.interfaceName
                properties?.linkAddresses.orEmpty().mapNotNull { link ->
                    val address = link.address
                    if (address is Inet4Address && !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress && !address.isAnyLocalAddress &&
                        interfaceName != null) address.hostAddress?.let { LanIpv4Link(interfaceName, it) } else null
                }
            }
        }.distinct().sortedBy { it.interfaceName + it.address }
    } catch (_: SecurityException) {
        emptyList()
    }
}

fun lanIpv4Addresses(context: Context): List<String> = lanIpv4Links(context).map { it.address }.distinct()
