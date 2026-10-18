package app.local1st.files.core.fs

import java.net.InetAddress

/**
 * True only when the destination belongs to the Wi-Fi interface's actual IP prefix.
 * A zero-length or invalid prefix is not enough to trust an arbitrary LAN.
 */
internal fun isAddressInSubnet(
    target: InetAddress,
    wifiAddress: InetAddress,
    prefixLength: Int,
): Boolean {
    val targetBytes = target.address
    val wifiBytes = wifiAddress.address
    if (targetBytes.size != wifiBytes.size || prefixLength <= 0 ||
        prefixLength > targetBytes.size * 8) return false

    val fullBytes = prefixLength / 8
    for (index in 0 until fullBytes) {
        if (targetBytes[index] != wifiBytes[index]) return false
    }
    val remainingBits = prefixLength % 8
    if (remainingBits == 0) return true
    val mask = 0xff shl (8 - remainingBits)
    return (targetBytes[fullBytes].toInt() and mask) ==
        (wifiBytes[fullBytes].toInt() and mask)
}

/** Return only NAS addresses within at least one real physical Wi-Fi IP prefix. */
internal fun matchingWifiSubnetAddresses(
    nasAddresses: List<InetAddress>,
    wifiAddresses: List<Pair<InetAddress, Int>>,
): List<InetAddress> = nasAddresses.filter { target ->
    wifiAddresses.any { (address, prefix) -> isAddressInSubnet(target, address, prefix) }
}
