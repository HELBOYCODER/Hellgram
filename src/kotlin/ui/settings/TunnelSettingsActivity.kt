package desu.inugram.ui.settings

import android.view.View
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import desu.inugram.helpers.network.BuiltInTunnelHelper
import org.telegram.messenger.LocaleController
import org.telegram.messenger.R
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class TunnelSettingsActivity : SettingsPageActivity() {
    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuBuiltInTunnel)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_TUNNEL,
                R.string.InuBuiltInTunnel,
                R.string.InuBuiltInTunnelInfo,
                InuConfig.BUILT_IN_TUNNEL.value,
            )
        )
        items.add(UItem.asShadow(statusLine()))

        items.add(UItem.asHeader(LocaleController.getString(R.string.InuTunnelProtocol)))
        for ((index, labelRes) in PROTOCOL_LABELS.withIndex()) {
            val selected = InuConfig.BUILT_IN_TUNNEL_PROTOCOL.value == index
            items.add(
                UItem.asRadio(PROTOCOL_BASE + index, LocaleController.getString(labelRes))
                    .also { it.checked = selected }
            )
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuTunnelProtocolFooter)))
    }

    private fun statusLine(): CharSequence {
        if (!InuConfig.BUILT_IN_TUNNEL.value) return LocaleController.getString(R.string.InuTunnelStatusOff)
        return if (BuiltInTunnelHelper.isActive()) {
            String.format(
                LocaleController.getString(R.string.InuTunnelStatusConnected),
                BuiltInTunnelHelper.statusText(),
            )
        } else {
            LocaleController.getString(R.string.InuTunnelStatusConnecting)
        }
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        when {
            item.id == TOGGLE_TUNNEL -> {
                val new = !InuConfig.BUILT_IN_TUNNEL.value
                BuiltInTunnelHelper.setEnabled(new)
                (view as? NotificationsCheckCell)?.isChecked = new
                listView?.adapter?.update(true)
            }

            item.id in PROTOCOL_BASE until PROTOCOL_BASE + PROTOCOL_LABELS.size -> {
                val protocol = item.id - PROTOCOL_BASE
                if (InuConfig.BUILT_IN_TUNNEL_PROTOCOL.value != protocol) {
                    InuConfig.BUILT_IN_TUNNEL_PROTOCOL.value = protocol
                    BuiltInTunnelHelper.restartIfNeeded()
                    listView?.adapter?.update(true)
                }
            }
        }
    }

    companion object {
        private val TOGGLE_TUNNEL = InuUtils.generateId()
        private val PROTOCOL_BASE = InuUtils.generateId()
        private val PROTOCOL_LABELS = intArrayOf(
            R.string.InuTunnelProtoMasque,
            R.string.InuTunnelProtoWireGuard,
            R.string.InuTunnelProtoGool,
            R.string.InuTunnelProtoAuto,
            R.string.InuTunnelProtoMim,
        )

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "built-in-tunnel",
            titleRes = R.string.InuBuiltInTunnel,
            iconRes = R.drawable.inu_tabler_shield_lock,
            factory = ::TunnelSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("tunnel", R.string.InuBuiltInTunnel, TOGGLE_TUNNEL),
            ),
        )
    }
}
