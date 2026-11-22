package io.github.patevan9.glucose.installer

import android.content.Context
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsDiscoveryState
import kotlinx.coroutines.flow.StateFlow

/**
 * Finds watches on the same Wi-Fi network.
 *
 * When "Wireless debugging" is on, a watch announces itself on Wi-Fi.
 * This only LISTENS for those announcements. It sends nothing to the watch.
 *
 *  - pairDevices    : watches showing the "Pair new device" screen right now
 *  - connectDevices : watches that are ready to connect (already paired)
 *
 * If discovery finds nothing (some routers block it), the user can type the
 * IP address and port shown on the watch instead.
 */
class WatchFinder(context: Context) : AutoCloseable {

    private val mdns = KadbMdnsAndroid(context.applicationContext)

    val state: StateFlow<MdnsDiscoveryState> get() = mdns.state

    fun start() = mdns.start()

    fun stop() = mdns.stop()

    override fun close() = mdns.close()
}
